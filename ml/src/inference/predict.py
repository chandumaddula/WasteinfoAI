"""Classify user images: reports the MAIN category (and which sub-folder type it looks like).

Usage: python src/inference/predict.py photo1.jpg photo2.png some_folder/
"""
import argparse
import json
import sys
from pathlib import Path

import torch
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from src.common import (IMAGE_EXTS, accept_mask, build_transforms, get_device, load_checkpoint,  # noqa: E402
                        load_config, logits_for_tensor, project_root, taxonomy_from_checkpoint)


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
    tax = taxonomy_from_checkpoint(ckpt)
    policy = json.loads((root / cfg["evaluation"]["policy_file"]).read_text())
    _, eval_tf = build_transforms(cfg)
    agg = torch.as_tensor(tax.matrix())
    l2s = torch.as_tensor(tax.leaf_to_stream)
    reject_idx = tax.streams.index("not_waste") if "not_waste" in tax.streams else None

    files = []
    for a in args.images:
        p = Path(a.strip().strip('"'))
        files += [f for f in sorted(p.rglob("*")) if f.suffix.lower() in IMAGE_EXTS] if p.is_dir() else [p]

    for f in files:
        x = eval_tf(Image.open(f).convert("RGB")).unsqueeze(0)
        leaf_p = torch.softmax(logits_for_tensor(model, x, device, not args.no_tta) / policy["temperature"], dim=1)
        sp = leaf_p @ agg
        conf, idx = sp.topk(min(3, tax.num_streams), dim=1)
        top = ", ".join(f"{tax.streams[i]} {c:.2f}" for c, i in zip(conf[0].tolist(), idx[0].tolist()))
        s = idx[0, 0].item()
        # best sub-folder type INSIDE the predicted main category
        in_stream = leaf_p[0] * (l2s == s)
        leaf = int(in_stream.argmax())
        kind = f" - looks like: {tax.leaf_short_name(leaf)} ({in_stream[leaf] / sp[0, s]:.0%})" if tax.leaves[leaf] != tax.streams[s] else ""
        if reject_idx is not None and s == reject_idx:
            print(f"{f.name}: NOT WASTE (hand / clothing / furniture / background)   top3: {top}")
        elif accept_mask(sp, policy, reject_idx).item():
            print(f"{f.name}: {tax.streams[s]}  ({conf[0, 0]:.1%}){kind}   top3: {top}")
        else:
            print(f"{f.name}: UNCERTAIN - retake the photo (single object, good light)   top3: {top}")


if __name__ == "__main__":
    main()
