"""Dataset Splitting Module.

Phase 3: Waste Classification
Splits processed dataset into stratified train, validation, and held-out test sets.
Ensures zero data leakage and preserves test set untouched until final evaluation.
"""

import argparse
import json
import logging
import random
import shutil
import sys
from pathlib import Path
from typing import Dict, List, Tuple

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    handlers=[logging.StreamHandler(sys.stdout)],
)
logger = logging.getLogger("split_dataset")

IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}


def get_class_images(processed_dir: Path) -> Dict[str, List[Path]]:
    """Scan processed directory and collect image files per class."""
    class_images: Dict[str, List[Path]] = {}
    if not processed_dir.exists():
        logger.error("Processed directory not found: %s", processed_dir)
        return class_images

    for sub in sorted(processed_dir.iterdir()):
        if sub.is_dir():
            imgs = [
                f for f in sub.iterdir()
                if f.is_file() and f.suffix.lower() in IMAGE_EXTENSIONS
            ]
            class_images[sub.name] = sorted(imgs)

    return class_images


def split_list(
    items: List[Path],
    train_ratio: float,
    val_ratio: float,
    test_ratio: float,
    seed: int,
) -> Tuple[List[Path], List[Path], List[Path]]:
    """Shuffle and split list into train, val, test subsets."""
    rng = random.Random(seed)
    shuffled = list(items)
    rng.shuffle(shuffled)

    n = len(shuffled)
    n_train = int(n * train_ratio)
    n_val = int(n * val_ratio)

    train_items = shuffled[:n_train]
    val_items = shuffled[n_train : n_train + n_val]
    test_items = shuffled[n_train + n_val :]

    return train_items, val_items, test_items


def create_splits(
    processed_dir: Path,
    splits_dir: Path,
    train_ratio: float = 0.70,
    val_ratio: float = 0.15,
    test_ratio: float = 0.15,
    seed: int = 42,
    copy_files: bool = True,
) -> Dict[str, Dict[str, int]]:
    """Create stratified train/val/test splits."""
    class_images = get_class_images(processed_dir)
    if not class_images:
        logger.warning("No class directories found in %s", processed_dir)
        return {}

    summary_counts: Dict[str, Dict[str, int]] = {}

    for split in ["train", "val", "test"]:
        (splits_dir / split).mkdir(parents=True, exist_ok=True)

    for class_name, img_paths in class_images.items():
        if not img_paths:
            logger.warning("Class '%s' has 0 images. Skipping.", class_name)
            continue

        train_imgs, val_imgs, test_imgs = split_list(
            img_paths, train_ratio, val_ratio, test_ratio, seed
        )

        summary_counts[class_name] = {
            "total": len(img_paths),
            "train": len(train_imgs),
            "val": len(val_imgs),
            "test": len(test_imgs),
        }

        split_dict = {
            "train": train_imgs,
            "val": val_imgs,
            "test": test_imgs,
        }

        for split_name, subset in split_dict.items():
            split_class_dir = splits_dir / split_name / class_name
            split_class_dir.mkdir(parents=True, exist_ok=True)

            if copy_files:
                for img_path in subset:
                    dest = split_class_dir / img_path.name
                    shutil.copy2(img_path, dest)

    return summary_counts


def print_split_table(counts: Dict[str, Dict[str, int]]) -> None:
    """Print tabular split statistics."""
    logger.info("=" * 68)
    logger.info(f"{'Class Name':<20} | {'Total':>7} | {'Train':>7} | {'Val':>7} | {'Test':>7}")
    logger.info("=" * 68)

    t_total, t_train, t_val, t_test = 0, 0, 0, 0
    for cls, c in counts.items():
        logger.info(f"{cls:<20} | {c['total']:>7} | {c['train']:>7} | {c['val']:>7} | {c['test']:>7}")
        t_total += c["total"]
        t_train += c["train"]
        t_val += c["val"]
        t_test += c["test"]

    logger.info("-" * 68)
    logger.info(f"{'TOTAL':<20} | {t_total:>7} | {t_train:>7} | {t_val:>7} | {t_test:>7}")
    logger.info("=" * 68)


def main() -> None:
    parser = argparse.ArgumentParser(description="Split waste dataset into train/val/test")
    parser.add_argument("--processed-dir", type=str, default="data/processed", help="Path to processed data")
    parser.add_argument("--splits-dir", type=str, default="data/splits", help="Path to output splits")
    parser.add_argument("--train-ratio", type=float, default=0.70, help="Train ratio (default: 0.70)")
    parser.add_argument("--val-ratio", type=float, default=0.15, help="Val ratio (default: 0.15)")
    parser.add_argument("--test-ratio", type=float, default=0.15, help="Test ratio (default: 0.15)")
    parser.add_argument("--seed", type=int, default=42, help="Random seed for reproducibility")
    args = parser.parse_args()

    base_dir = Path(__file__).resolve().parents[2]
    processed_path = base_dir / args.processed_dir
    splits_path = base_dir / args.splits_dir

    logger.info(
        "Creating splits (train=%.2f, val=%.2f, test=%.2f, seed=%d)",
        args.train_ratio, args.val_ratio, args.test_ratio, args.seed
    )

    counts = create_splits(
        processed_dir=processed_path,
        splits_dir=splits_path,
        train_ratio=args.train_ratio,
        val_ratio=args.val_ratio,
        test_ratio=args.test_ratio,
        seed=args.seed,
    )

    if counts:
        print_split_table(counts)
        # Save split manifest
        manifest_path = splits_path / "splits_manifest.json"
        with open(manifest_path, "w", encoding="utf-8") as f:
            json.dump({"seed": args.seed, "counts": counts}, f, indent=2)
        logger.info("Saved split manifest to: %s", manifest_path)


if __name__ == "__main__":
    main()
