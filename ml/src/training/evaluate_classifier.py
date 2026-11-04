"""Evaluate the best checkpoint once on the held-out test split: main categories (what the app shows) + sub-folders."""
import argparse
import datetime
import json
import logging
import sys
from pathlib import Path

import torch
from sklearn.metrics import classification_report, confusion_matrix

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from src.common import (check_checkpoint_matches, collect_logits, get_device, load_checkpoint,  # noqa: E402
                        load_class_names, load_config, make_data, project_root, stream_probs)

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("evaluate_classifier")


def expected_calibration_error(probs, targets, bins=15):
    conf, pred = probs.max(1)
    correct = (pred == targets).float()
    edges, ece = torch.linspace(0, 1, bins + 1), 0.0
    for lo, hi in zip(edges[:-1], edges[1:]):
        mask = (conf > lo) & (conf <= hi)
        if mask.any():
            ece += mask.float().mean().item() * abs(correct[mask].mean().item() - conf[mask].mean().item())
    return ece


def most_confused(cm, names, top=8):
    k = len(names)
    rows = sorted(((int(cm[i, j]), names[i], names[j]) for i in range(k) for j in range(k) if i != j), reverse=True)
    return [{"count": n, "true": a, "predicted": b} for n, a, b in rows[:top] if n > 0]


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
    # An old checkpoint is NOT mapped onto the new classes: it was trained on different data, so its test score
    # would be meaningless (and test images may have been in its training set). Retrain instead.
    check_checkpoint_matches(ckpt, tax)

    logits, y_leaf = collect_logits(model, loaders["test"], device, tta=cfg["evaluation"]["tta"])
    leaf_to_stream = torch.as_tensor(tax.leaf_to_stream)
    y = leaf_to_stream[y_leaf]
    probs = stream_probs(logits, tax)
    pred = probs.argmax(1)
    leaf_pred = logits.argmax(1)
    K, L = tax.num_streams, tax.num_leaves

    nll = -torch.log(probs[torch.arange(len(y)), y].clamp_min(1e-7)).mean().item()
    top3 = (probs.topk(min(3, K), dim=1).indices == y[:, None]).any(1).float().mean().item()
    report = classification_report(y, pred, labels=list(range(K)), target_names=tax.streams,
                                   output_dict=True, zero_division=0)
    cm = confusion_matrix(y, pred, labels=list(range(K)))
    leaf_report = classification_report(y_leaf, leaf_pred, labels=list(range(L)), target_names=tax.leaves,
                                        output_dict=True, zero_division=0)
    leaf_cm = confusion_matrix(y_leaf, leaf_pred, labels=list(range(L)))

    rnd = lambda d: {k: round(v, 4) if isinstance(v, float) else v for k, v in d.items()}  # noqa: E731
    summary = {
        "metadata": {"arch": ckpt["arch"], "checkpoint": args.checkpoint, "tta": cfg["evaluation"]["tta"],
                     "evaluated_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                     "classes": tax.streams, "trained_classes": tax.leaves},
        "overall": {  # MAIN categories: what the app reports
            "test_loss": round(nll, 4),
            "accuracy": round((pred == y).float().mean().item(), 4),
            "top3_accuracy": round(top3, 4),
            "macro_f1": round(report["macro avg"]["f1-score"], 4),
            "ece_uncalibrated": round(expected_calibration_error(probs, y), 4),
            "samples": len(y),
        },
        "per_class": {c: rnd(report[c]) for c in tax.streams},
        "most_confused": most_confused(cm, tax.streams),
        "confusion_matrix": cm.tolist(),
        "leaf_level": {  # sub-folder classes the model was trained on
            "accuracy": round((leaf_pred == y_leaf).float().mean().item(), 4),
            "macro_f1": round(leaf_report["macro avg"]["f1-score"], 4),
            "per_class": {c: rnd(leaf_report[c]) for c in tax.leaves},
            "most_confused": most_confused(leaf_cm, tax.leaves, top=12),
        },
    }
    out = root / cfg["evaluation"]["summary_file"]
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(summary, indent=2), encoding="utf-8")

    o = summary["overall"]
    logger.info("MAIN categories: acc %.4f | top3 %.4f | macroF1 %.4f | ECE %.4f", o["accuracy"], o["top3_accuracy"],
                o["macro_f1"], o["ece_uncalibrated"])
    for c in tax.streams:
        r = report[c]
        logger.info("  %-14s precision %.3f recall %.3f f1 %.3f (n=%d)", c, r["precision"], r["recall"], r["f1-score"], r["support"])
    logger.info("Sub-folders: acc %.4f | macroF1 %.4f", summary["leaf_level"]["accuracy"], summary["leaf_level"]["macro_f1"])
    for item in summary["most_confused"]:
        logger.info("confused categories: %s -> %s x%d", item["true"], item["predicted"], item["count"])
    for item in summary["leaf_level"]["most_confused"][:6]:
        logger.info("confused sub-folders: %s -> %s x%d", item["true"], item["predicted"], item["count"])
    logger.info("Saved %s", out)


if __name__ == "__main__":
    main()
