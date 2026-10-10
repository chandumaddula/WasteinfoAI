"""Dataset Preparation and Validation Module.

Phase 3: Waste Classification
Inspects raw image datasets, filters corrupted or unreadable images,
normalizes class directories to canonical names, and creates a clean
processed dataset directory.
"""

import argparse
import json
import logging
import os
import shutil
import sys
from pathlib import Path
from typing import Dict, List, Optional, Tuple

from PIL import Image

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    handlers=[logging.StreamHandler(sys.stdout)],
)
logger = logging.getLogger("prepare_dataset")

# Standard class aliases to map various public datasets (e.g. TACO, TrashNet)
# to canonical wasteinfoAI classes.
DEFAULT_CLASS_MAPPING = {
    "battery": "battery",
    "batteries": "battery",
    "e_waste": "e_waste",
    "e-waste": "e_waste",
    "electronic": "e_waste",
    "electronics": "e_waste",
    "glass": "glass",
    "glass_bottles": "glass",
    "metal": "metal",
    "metal_cans": "metal",
    "cans": "metal",
    "organic": "organic",
    "food": "organic",
    "biodegradable": "organic",
    "paper": "paper_cardboard",
    "cardboard": "paper_cardboard",
    "paper_cardboard": "paper_cardboard",
    "plastic": "plastic",
    "plastics": "plastic",
    "plastic_bottles": "plastic",
    "textile": "textile",
    "textiles": "textile",
    "clothes": "textile",
    "other": "other",
    "unknown": "other",
    "trash": "other",
}

SUPPORTED_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}


def load_canonical_classes(labels_path: Path) -> List[str]:
    """Load canonical classes from class_names.json."""
    if not labels_path.exists():
        logger.warning("Labels file not found at %s. Using default 9 classes.", labels_path)
        return [
            "battery", "e_waste", "glass", "metal",
            "organic", "paper_cardboard", "plastic", "textile", "other"
        ]
    with open(labels_path, "r", encoding="utf-8") as f:
        data = json.load(f)
    return [data[str(i)] for i in range(len(data))]


def validate_image(image_path: Path) -> Tuple[bool, Optional[str], Optional[Tuple[int, int]]]:
    """Check if an image can be successfully opened and decoded.

    Returns:
        (is_valid, error_message, (width, height))
    """
    if image_path.stat().st_size == 0:
        return False, "Zero byte file", None

    try:
        with Image.open(image_path) as img:
            img.verify()

        # Re-open for mode/size check as verify() closes or alters some image headers
        with Image.open(image_path) as img:
            size = img.size
            if size[0] < 32 or size[1] < 32:
                return False, f"Image dimensions too small: {size}", None
            return True, None, size
    except Exception as exc:
        return False, str(exc), None


def process_dataset(
    raw_dir: Path,
    processed_dir: Path,
    canonical_classes: List[str],
    verify_only: bool = False,
    convert_rgb: bool = True,
) -> Dict[str, Dict]:
    """Scan raw directory, validate images, and output clean processed images."""
    logger.info("Scanning raw dataset from: %s", raw_dir)
    stats: Dict[str, Dict] = {
        cls: {"valid": 0, "corrupt": 0, "errors": []} for cls in canonical_classes
    }
    stats["unmapped"] = {"valid": 0, "corrupt": 0, "errors": []}

    if not raw_dir.exists():
        logger.error("Raw directory does not exist: %s", raw_dir)
        return stats

    if not verify_only:
        processed_dir.mkdir(parents=True, exist_ok=True)
        for cls in canonical_classes:
            (processed_dir / cls).mkdir(parents=True, exist_ok=True)

    # Build case-insensitive lookup for canonical classes
    canonical_lookup = {c.lower(): c for c in canonical_classes}

    # Walk directory
    for root, _, files in os.walk(raw_dir):
        rel_folder = Path(root).name
        target_class = canonical_lookup.get(rel_folder.lower())
        if not target_class:
            mapped_alias = DEFAULT_CLASS_MAPPING.get(rel_folder.lower())
            target_class = canonical_lookup.get(mapped_alias.lower()) if mapped_alias else None

        if not target_class:
            target_class = "unmapped"

        for file in files:
            file_path = Path(root) / file
            if file_path.suffix.lower() not in SUPPORTED_EXTENSIONS:
                continue

            is_valid, err, size = validate_image(file_path)

            if not is_valid:
                stats[target_class]["corrupt"] += 1
                stats[target_class]["errors"].append(f"{file}: {err}")
                continue

            stats[target_class]["valid"] += 1

            if not verify_only and target_class != "unmapped":
                dest_class_dir = processed_dir / target_class
                dest_file = dest_class_dir / f"{file_path.stem}_{stats[target_class]['valid']}.jpg"

                if convert_rgb:
                    try:
                        with Image.open(file_path) as img:
                            rgb_img = img.convert("RGB")
                            rgb_img.save(dest_file, "JPEG", quality=95)
                    except Exception as e:
                        logger.warning("Failed converting %s: %s", file_path, e)
                        shutil.copy2(file_path, dest_file)
                else:
                    shutil.copy2(file_path, dest_file)

    return stats


def print_summary(stats: Dict[str, Dict], canonical_classes: List[str]) -> None:
    """Print tabular summary of dataset preparation."""
    logger.info("=" * 60)
    logger.info("DATASET PREPARATION SUMMARY")
    logger.info("=" * 60)
    total_valid = 0
    total_corrupt = 0
    for cls in canonical_classes:
        v = stats[cls]["valid"]
        c = stats[cls]["corrupt"]
        total_valid += v
        total_corrupt += c
        logger.info(f"  Class: {cls:18} | Valid: {v:5} | Corrupt/Skipped: {c:3}")

    unmapped = stats["unmapped"]["valid"]
    if unmapped > 0:
        logger.warning(f"  Unmapped folders:           | Valid: {unmapped:5}")

    logger.info("-" * 60)
    logger.info(f"  Total Valid Images:  {total_valid}")
    logger.info(f"  Total Corrupt Files: {total_corrupt}")
    logger.info("=" * 60)


def main() -> None:
    parser = argparse.ArgumentParser(description="WasteinfoAI dataset cleaner and validator")
    parser.add_argument("--raw-dir", type=str, default="data/raw", help="Path to raw images directory")
    parser.add_argument("--processed-dir", type=str, default="data/processed", help="Target processed directory")
    parser.add_argument("--labels", type=str, default="labels/class_names.json", help="Path to class_names.json")
    parser.add_argument("--verify-only", action="store_true", help="Only verify images without writing copies")
    args = parser.parse_args()

    base_dir = Path(__file__).resolve().parents[2]
    raw_path = base_dir / args.raw_dir
    processed_path = base_dir / args.processed_dir
    labels_path = base_dir / args.labels

    canonical = load_canonical_classes(labels_path)
    logger.info("Canonical classes: %s", canonical)

    stats = process_dataset(raw_path, processed_path, canonical, args.verify_only)
    print_summary(stats, canonical)


if __name__ == "__main__":
    main()
