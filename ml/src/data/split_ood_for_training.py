"""One-off helper: turn part of data/ood into a TRAINING class called `not_waste`.

Moves 70% of the images in data/ood to data/real_world/not_waste (the new 18th class)
and leaves 30% in data/ood so threshold calibration still uses photos the model never trained on.

Place this file in ml/src/data/ and run from ml/:   python src/data/split_ood_for_training.py
"""
import random
import shutil
from pathlib import Path

EXTS = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}
root = Path(__file__).resolve().parents[2]          # ml/
ood = root / "data" / "ood"
dst = root / "data" / "real_world" / "not_waste"

if not ood.exists():
    raise SystemExit(f"Not found: {ood}")
dst.mkdir(parents=True, exist_ok=True)
if any(dst.iterdir()):
    raise SystemExit(f"{dst} already has files: refusing to split again.")

files = sorted(p for p in ood.rglob("*") if p.suffix.lower() in EXTS)
random.Random(42).shuffle(files)
n_train = int(len(files) * 0.7)
for i, p in enumerate(files[:n_train]):
    shutil.move(str(p), dst / f"{i:05d}_{p.name}")
print(f"Moved {n_train} images to {dst}; kept {len(files) - n_train} in {ood} for calibration.")
