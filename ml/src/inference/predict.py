"""Classify user images with calibration, TTA and the rejection policy.

Usage: python src/classifier/predict.py photo1.jpg photo2.png some_folder/
"""
import argparse
import json
import sys
from pathlib import Path

import torch
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from src.common import (IMAGE_EXTS, accept_mask, build_transforms, calibrated_probs,  # noqa: E402
                        get_device, load_checkpoint, load_config, logits_for_tensor, project_root)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("images", nargs="+")
    ap.add_argument("--config", default="configs/classifier_config.yaml")
    ap.add_argument("--checkpoint", default="checkpoints/best_model.pth")
    ap.add_argument("--no-tta", action="store_true")
    args = ap.parse_args()

    root = project_root()
    cfg = load_config(root / args.config)
    device = get_device(cfg["training"]["device"])
    model, ckpt = load_checkpoint(root / args.checkpoint, device)
    policy = json.loads((root / cfg["evaluation"]["policy_file"]).read_text())
    _, eval_tf = build_transforms(cfg)

    files = []
    for a in args.images:
        p = Path(a)
        files += [f for f in sorted(p.rglob("*")) if f.suffix.lower() in IMAGE_EXTS] if p.is_dir() else [p]

    for f in files:
        x = eval_tf(Image.open(f).convert("RGB")).unsqueeze(0)
        probs = calibrated_probs(logits_for_tensor(model, x, device, not args.no_tta), policy["temperature"])
        conf, idx = probs.topk(3, dim=1)
        top = ", ".join(f"{ckpt['classes'][i]} {c:.2f}" for c, i in zip(conf[0].tolist(), idx[0].tolist()))
        if accept_mask(probs, policy).item():
            print(f"{f.name}: {ckpt['classes'][idx[0, 0]]}  ({conf[0, 0]:.1%})   top3: {top}")
        else:
            print(f"{f.name}: UNCERTAIN - retake the photo (single object, good light)   top3: {top}")


if __name__ == "__main__":
    main()
