"""Train the waste classifier on the SUB-FOLDER classes; report and select by the MAIN category the app shows."""
import argparse
import logging
import sys
from pathlib import Path

import torch
import torch.nn as nn
import torch.nn.functional as F
from sklearn.metrics import f1_score
from torch.optim import AdamW
from torch.optim.lr_scheduler import CosineAnnealingLR, LinearLR, SequentialLR

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from src.common import (build_model, collect_logits, get_device, load_class_names,  # noqa: E402
                        load_config, make_data, project_root, seed_everything, stream_probs)

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("train_classifier")


def set_frozen(model, head_ids, frozen: bool):
    for p in model.parameters():
        p.requires_grad = (not frozen) or id(p) in head_ids


def train_one_epoch(model, loader, criterion, optimizer, scaler, device, use_amp, frozen):
    model.train()
    if frozen:  # keep BatchNorm statistics of the pretrained backbone untouched
        for m in model.modules():
            if isinstance(m, nn.modules.batchnorm._BatchNorm):
                m.eval()
    loss_sum, correct, n = 0.0, 0, 0
    for x, y in loader:
        x, y = x.to(device, non_blocking=True), y.to(device, non_blocking=True)
        optimizer.zero_grad(set_to_none=True)
        with torch.autocast(device_type=device.type, enabled=use_amp):
            out = model(x)
            loss = criterion(out, y)
        scaler.scale(loss).backward()
        scaler.unscale_(optimizer)
        nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        scaler.step(optimizer)
        scaler.update()
        loss_sum += loss.item() * y.size(0)
        correct += (out.argmax(1) == y).sum().item()
        n += y.size(0)
    return loss_sum / n, correct / n


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", default="configs/classifier_config.yaml")
    ap.add_argument("--epochs", type=int, default=None)
    args = ap.parse_args()

    root = project_root()
    cfg = load_config(root / args.config)
    seed_everything(cfg["dataset"]["random_seed"])
    device = get_device(cfg["training"]["device"])
    use_amp = device.type == "cuda"
    loaders, tax = make_data(cfg, root, load_class_names(cfg, root))
    leaf_to_stream = torch.as_tensor(tax.leaf_to_stream)

    t, m = cfg["training"], cfg["model"]
    epochs = args.epochs or t["epochs"]
    model = build_model(m["architecture"], tax.num_leaves, m["pretrained"], m["dropout"]).to(device)

    head_ids = {id(p) for p in model.get_classifier().parameters()}
    head = [p for p in model.parameters() if id(p) in head_ids]
    backbone = [p for p in model.parameters() if id(p) not in head_ids]
    optimizer = AdamW([
        {"params": backbone, "lr": t["learning_rate"] * t["backbone_lr_mult"]},
        {"params": head, "lr": t["learning_rate"]},
    ], weight_decay=t["weight_decay"])
    # ONE optimizer + ONE scheduler for the whole run
    scheduler = SequentialLR(
        optimizer,
        [LinearLR(optimizer, start_factor=0.1, total_iters=1),
         CosineAnnealingLR(optimizer, T_max=max(1, epochs - 1), eta_min=t["min_lr"])],
        milestones=[1])
    criterion = nn.CrossEntropyLoss(label_smoothing=t["label_smoothing"])
    scaler = torch.amp.GradScaler("cuda", enabled=use_amp)

    ckpt_dir = root / t["checkpoint_dir"]
    ckpt_dir.mkdir(parents=True, exist_ok=True)
    freeze_epochs, patience = t["freeze_backbone_epochs"], t["early_stopping_patience"]
    best_score, bad = (-1.0, -1.0), 0
    logger.info("Device=%s arch=%s | %d trained classes (sub-folders) -> %d main categories %s | epochs=%d",
                device, m["architecture"], tax.num_leaves, tax.num_streams, tax.streams, epochs)

    for epoch in range(1, epochs + 1):
        frozen = epoch <= freeze_epochs
        if epoch == 1 or epoch == freeze_epochs + 1:
            set_frozen(model, head_ids, frozen)
            logger.info("Backbone %s", "frozen" if frozen else "unfrozen")

        tr_loss, tr_acc = train_one_epoch(model, loaders["train"], criterion, optimizer,
                                          scaler, device, use_amp, frozen)
        scheduler.step()

        logits, targets = collect_logits(model, loaders["val"], device)
        leaf_pred = logits.argmax(1)
        s_pred = stream_probs(logits, tax).argmax(1)
        s_true = leaf_to_stream[targets]
        val_loss = F.cross_entropy(logits, targets).item()
        leaf_acc = (leaf_pred == targets).float().mean().item()
        stream_acc = (s_pred == s_true).float().mean().item()
        leaf_f1 = f1_score(targets.numpy(), leaf_pred.numpy(), average="macro", zero_division=0)
        stream_f1 = f1_score(s_true.numpy(), s_pred.numpy(), average="macro", zero_division=0)
        logger.info("Epoch %02d/%02d | train loss %.4f acc %.4f | val loss %.4f | main-category acc %.4f macroF1 %.4f"
                    " | sub-folder acc %.4f macroF1 %.4f",
                    epoch, epochs, tr_loss, tr_acc, val_loss, stream_acc, stream_f1, leaf_acc, leaf_f1)

        score = (round(stream_f1, 4), leaf_f1)  # what the user sees first, sub-folder F1 breaks ties
        if score > best_score:
            best_score, bad = score, 0
            torch.save({
                "model_state_dict": model.state_dict(), "arch": m["architecture"],
                "classes": tax.leaves, "streams": tax.streams, "leaf_to_stream": tax.leaf_to_stream,
                "image_size": cfg["dataset"]["image_size"], "norm": cfg["dataset"]["normalization"],
                "epoch": epoch, "val_f1": stream_f1, "val_acc": stream_acc, "val_leaf_f1": leaf_f1,
            }, ckpt_dir / "best_model.pth")
            logger.info("Saved best checkpoint (main-category macro-F1 %.4f, sub-folder macro-F1 %.4f)", stream_f1, leaf_f1)
        elif not frozen:
            bad += 1
            if bad >= patience:
                logger.info("Early stopping.")
                break
    logger.info("Done. Best main-category macro-F1: %.4f", best_score[0])


if __name__ == "__main__":
    main()
