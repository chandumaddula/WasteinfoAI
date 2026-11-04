"""Fit temperature scaling and pick the accept/reject thresholds from REAL model outputs.

* Thresholds are tuned on the validation split (never on test).
* "Unknown" inputs = synthetic OOD (noise, flat colour, smooth texture, heavily blurred
  waste photos) + any real non-waste photos you place in `data/ood/`.
  Real photos (hands, rooms, people, pets) give a far more trustworthy false-accept rate.
* Final numbers are reported once on the test split.
"""
import argparse
import json
import logging
import sys
from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as F
import torchvision.transforms.functional as TF
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from src.common import (IMAGE_EXTS, accept_mask, build_transforms, check_checkpoint_matches,  # noqa: E402
                        collect_logits, get_device, load_checkpoint, load_class_names, load_config,
                        logits_for_tensor, make_data, project_root, stream_probs)

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("calibrate_rejection")


def fit_temperature(leaf_logits, stream_targets, tax) -> float:
    """Temperature that minimises the loss of the MAIN-category probabilities (the ones the app thresholds)."""
    log_t = torch.zeros(1, requires_grad=True)
    opt = torch.optim.LBFGS([log_t], lr=0.1, max_iter=100)
    m = torch.as_tensor(tax.matrix(), dtype=leaf_logits.dtype)
    rows = torch.arange(len(stream_targets))

    def closure():
        opt.zero_grad()
        p = torch.softmax(leaf_logits / log_t.exp(), dim=1) @ m
        loss = -torch.log(p[rows, stream_targets].clamp_min(1e-9)).mean()
        loss.backward()
        return loss

    opt.step(closure)
    return float(log_t.exp().detach())


def build_ood_logits(model, val_loader, cfg, root, device, tta, n=150):
    size = cfg["dataset"]["image_size"]
    mean = torch.tensor(cfg["dataset"]["normalization"]["mean"]).view(1, 3, 1, 1)
    std = torch.tensor(cfg["dataset"]["normalization"]["std"]).view(1, 3, 1, 1)
    g = torch.Generator().manual_seed(0)
    norm = lambda x: (x - mean) / std  # noqa: E731

    sets = {
        "noise": norm(torch.rand(n, 3, size, size, generator=g)),
        "flat_color": norm(torch.rand(n, 3, 1, 1, generator=g).expand(n, 3, size, size)),
        "smooth_texture": norm(F.interpolate(torch.rand(n, 3, 6, 6, generator=g), size=(size, size),
                                             mode="bicubic", align_corners=False).clamp(0, 1)),
    }
    imgs = []
    for x, _ in val_loader:
        imgs.append(x)
        if sum(len(i) for i in imgs) >= n:
            break
    sets["blurred_waste"] = TF.gaussian_blur(torch.cat(imgs)[:n], kernel_size=51, sigma=15.0)

    ood_dir = root / cfg["evaluation"]["ood_dir"]
    if ood_dir.exists():
        _, eval_tf = build_transforms(cfg)
        files = [p for p in ood_dir.rglob("*") if p.suffix.lower() in IMAGE_EXTS]
        if files:
            tensors = [eval_tf(Image.open(p).convert("RGB")) for p in files]
            sets["real_ood"] = torch.stack(tensors)
            logger.info("Using %d real OOD images from %s", len(files), ood_dir)
    else:
        logger.warning("No %s folder: FAR is measured on synthetic inputs only (optimistic).", ood_dir)

    out = {}
    for name, x in sets.items():
        out[name] = torch.cat([logits_for_tensor(model, x[i:i + 64], device, tta) for i in range(0, len(x), 64)])
    return out


def evaluate_policy(id_probs, id_y, ood_probs, pol, reject_idx=None) -> dict:
    if reject_idx is not None:
        # Held-out "not_waste" photos are non-waste: score them as unknowns (false accept if accepted),
        # and keep them out of the waste coverage / accuracy numbers.
        nw = id_y == reject_idx
        extra = id_probs[nw]
        id_probs, id_y = id_probs[~nw], id_y[~nw]
        ood_probs = extra if ood_probs is None else torch.cat([ood_probs, extra])
    mask = accept_mask(id_probs, pol, reject_idx)
    preds = id_probs.argmax(1)
    cov = mask.float().mean().item()
    acc = (preds[mask] == id_y[mask]).float().mean().item() if mask.any() else 0.0
    far = (accept_mask(ood_probs, pol, reject_idx).float().mean().item()
           if ood_probs is not None and len(ood_probs) else 0.0)
    return {"coverage": round(cov, 4), "false_rejection_rate": round(1 - cov, 4),
            "accuracy_on_accepted": round(acc, 4), "ood_false_accept_rate": round(far, 4)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", default="configs/classifier_config.yaml")
    ap.add_argument("--checkpoint", default="checkpoints/best_model.pth")
    args = ap.parse_args()

    root = project_root()
    cfg = load_config(root / args.config)
    device = get_device(cfg["training"]["device"])
    loaders, tax = make_data(cfg, root, load_class_names(cfg, root))
    model, ckpt = load_checkpoint(root / args.checkpoint, device)
    check_checkpoint_matches(ckpt, tax)
    tta, ev = cfg["evaluation"]["tta"], cfg["evaluation"]
    leaf_to_stream = torch.as_tensor(tax.leaf_to_stream)
    reject_idx = tax.streams.index("not_waste") if "not_waste" in tax.streams else None

    # Everything below works on MAIN-category probabilities (sum of the sub-folder probabilities).
    val_logits, val_leaf_y = collect_logits(model, loaders["val"], device, tta)
    val_y = leaf_to_stream[val_leaf_y]
    temperature = fit_temperature(val_logits, val_y, tax)
    logger.info("Fitted temperature: %.3f", temperature)
    val_probs = stream_probs(val_logits, tax, temperature)

    ood = build_ood_logits(model, loaders["val"], cfg, root, device, tta)
    ood_probs = stream_probs(torch.cat(list(ood.values())), tax, temperature)
    logger.info("OOD sets: %s", {k: len(v) for k, v in ood.items()})

    best, best_key, feasible_any = None, None, False
    for c in np.arange(0.30, 0.96, 0.05):
        for mg in (0.0, 0.05, 0.10, 0.15, 0.20, 0.30):
            for ent in (0.5, 0.6, 0.7, 0.8, 1.0):
                pol = {"confidence_threshold": round(float(c), 2), "min_margin": mg, "max_norm_entropy": ent}
                r = evaluate_policy(val_probs, val_y, ood_probs, pol, reject_idx)
                ok = (r["accuracy_on_accepted"] >= ev["target_accuracy_on_accepted"]
                      and r["ood_false_accept_rate"] <= ev["max_false_accept_rate"])
                key = (ok, r["coverage"]) if ok else (False, r["accuracy_on_accepted"] - r["ood_false_accept_rate"])
                if best_key is None or key > best_key:
                    best, best_key, feasible_any = (pol, r), key, feasible_any or ok
    if not feasible_any:
        logger.warning("No policy met the targets; using best trade-off. Consider more data / real OOD images.")

    pol, val_metrics = best
    test_logits, test_leaf_y = collect_logits(model, loaders["test"], device, tta)
    test_metrics = evaluate_policy(stream_probs(test_logits, tax, temperature), leaf_to_stream[test_leaf_y], None, pol, reject_idx)
    logger.info("Policy %s", pol)
    logger.info("VAL  %s", val_metrics)
    logger.info("TEST %s (test false-accept rate = held-out not_waste photos only)", test_metrics)

    out = root / ev["policy_file"]
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps({
        "version": "3.0.0-calibrated", "temperature": temperature, **pol, "streams": tax.streams,
        "validation_metrics": val_metrics, "test_metrics": test_metrics,
        "ood_sets": {k: len(v) for k, v in ood.items()},
    }, indent=2), encoding="utf-8")
    logger.info("Saved %s", out)


if __name__ == "__main__":
    main()
