"""Evaluate the best checkpoint once on the held-out test split."""
import argparse
import datetime
import json
import logging
import sys
from pathlib import Path

import torch
import torch.nn.functional as F
from sklearn.metrics import classification_report, confusion_matrix

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from src.common import (calibrated_probs, collect_logits, get_device, load_checkpoint,  # noqa: E402
                        load_class_names, load_config, make_loaders, project_root)

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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", default="configs/classifier_config.yaml")
    ap.add_argument("--checkpoint", default="checkpoints/best_model.pth")
    args = ap.parse_args()

    root = project_root()
    cfg = load_config(root / args.config)
    device = get_device(cfg["training"]["device"])
    classes = load_class_names(cfg, root)
    model, ckpt = load_checkpoint(root / args.checkpoint, device)
    if ckpt["classes"] != classes:
        raise RuntimeError("Checkpoint class order differs from class_names.json")
    test_loader = make_loaders(cfg, root, classes)["test"]

    logits, y = collect_logits(model, test_loader, device, tta=cfg["evaluation"]["tta"])
    probs = torch.softmax(logits, 1)
    pred = probs.argmax(1)
    K = len(classes)

    top3 = (probs.topk(3, dim=1).indices == y[:, None]).any(1).float().mean().item()
    report = classification_report(y, pred, labels=list(range(K)), target_names=classes,
                                   output_dict=True, zero_division=0)
    cm = confusion_matrix(y, pred, labels=list(range(K)))
    confused = sorted(((int(cm[i, j]), classes[i], classes[j]) for i in range(K) for j in range(K) if i != j),
                      reverse=True)[:8]

    summary = {
        "metadata": {"arch": ckpt["arch"], "checkpoint": args.checkpoint, "tta": cfg["evaluation"]["tta"],
                     "evaluated_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                     "classes": classes},
        "overall": {
            "test_loss": round(F.cross_entropy(logits, y).item(), 4),
            "accuracy": round((pred == y).float().mean().item(), 4),
            "top3_accuracy": round(top3, 4),
            "macro_f1": round(report["macro avg"]["f1-score"], 4),
            "ece_uncalibrated": round(expected_calibration_error(probs, y), 4),
            "samples": len(y),
        },
        "per_class": {c: {k: round(v, 4) if isinstance(v, float) else v for k, v in report[c].items()}
                      for c in classes},
        "most_confused": [{"count": n, "true": a, "predicted": b} for n, a, b in confused if n > 0],
        "confusion_matrix": cm.tolist(),
    }
    out = root / cfg["evaluation"]["summary_file"]
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(summary, indent=2), encoding="utf-8")

    o = summary["overall"]
    logger.info("acc %.4f | top3 %.4f | macroF1 %.4f | ECE %.4f", o["accuracy"], o["top3_accuracy"],
                o["macro_f1"], o["ece_uncalibrated"])
    for item in summary["most_confused"]:
        logger.info("confused: %s -> %s x%d", item["true"], item["predicted"], item["count"])
    logger.info("Saved %s", out)


if __name__ == "__main__":
    main()
