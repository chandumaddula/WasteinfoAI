"""Evaluation Script for Held-Out Test Set.

Phase 3: Waste Classification Model
Measures accuracy, macro-F1, per-class precision/recall,
confusion matrix, and low-confidence unfamiliar-object behavior.
Outputs structured metrics to reports/evaluation_summary.json.
"""

import argparse
import datetime
import json
import logging
import sys
from pathlib import Path
from typing import Dict, List, Tuple

import numpy as np
import torch
import torch.nn as nn
from torch.utils.data import DataLoader
from torchvision import datasets, models, transforms
import yaml

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    handlers=[logging.StreamHandler(sys.stdout)],
)
logger = logging.getLogger("evaluate_classifier")


def load_config(config_path: Path) -> dict:
    """Load configuration YAML."""
    with open(config_path, "r", encoding="utf-8") as f:
        return yaml.safe_load(f)


def get_test_transform(config: dict) -> transforms.Compose:
    """Get standardized evaluation transform."""
    mean = config["dataset"]["normalization"]["mean"]
    std = config["dataset"]["normalization"]["std"]
    img_size = config["dataset"]["image_size"]
    crop_padding = config["dataset"].get("crop_padding", 32)

    return transforms.Compose([
        transforms.Resize(img_size + crop_padding),
        transforms.CenterCrop(img_size),
        transforms.ToTensor(),
        transforms.Normalize(mean=mean, std=std),
    ])


def load_checkpoint_model(
    checkpoint_path: Path,
    num_classes: int,
    device: torch.device,
) -> Tuple[nn.Module, dict]:
    """Load MobileNetV3-Small model and trained weights."""
    model = models.mobilenet_v3_small(weights=None)
    in_features = model.classifier[3].in_features
    model.classifier[3] = nn.Linear(in_features, num_classes)

    if not checkpoint_path.exists():
        raise FileNotFoundError(f"Checkpoint not found at: {checkpoint_path}")

    checkpoint = torch.load(checkpoint_path, map_location=device)
    model.load_state_dict(checkpoint["model_state_dict"])
    model = model.to(device)
    model.eval()

    return model, checkpoint


def evaluate_test_set(
    model: nn.Module,
    dataloader: DataLoader,
    device: torch.device,
    classes: List[str],
    confidence_threshold: float = 0.60,
) -> Dict:
    """Evaluate model on held-out test data and calculate metrics."""
    criterion = nn.CrossEntropyLoss()
    total_loss = 0.0
    all_preds: List[int] = []
    all_targets: List[int] = []
    all_probs: List[float] = []

    num_classes = len(classes)
    confusion = np.zeros((num_classes, num_classes), dtype=int)

    with torch.no_grad():
        for images, labels in dataloader:
            images = images.to(device)
            labels = labels.to(device)

            outputs = model(images)
            loss = criterion(outputs, labels)
            total_loss += loss.item() * images.size(0)

            probs = torch.softmax(outputs, dim=1)
            max_probs, preds = torch.max(probs, dim=1)

            for p, t, prob in zip(preds.cpu().numpy(), labels.cpu().numpy(), max_probs.cpu().numpy()):
                all_preds.append(int(p))
                all_targets.append(int(t))
                all_probs.append(float(prob))
                confusion[int(t), int(p)] += 1

    total_samples = len(all_targets)
    avg_loss = total_loss / max(1, total_samples)

    # Compute overall accuracy
    correct = sum(p == t for p, t in zip(all_preds, all_targets))
    accuracy = correct / max(1, total_samples)

    # Per-class metrics
    per_class = {}
    f1_list = []
    precision_list = []
    recall_list = []

    for i, cls_name in enumerate(classes):
        tp = confusion[i, i]
        fp = confusion[:, i].sum() - tp
        fn = confusion[i, :].sum() - tp
        support = int(confusion[i, :].sum())

        precision = tp / (tp + fp) if (tp + fp) > 0 else 0.0
        recall = tp / (tp + fn) if (tp + fn) > 0 else 0.0
        f1 = (2 * precision * recall) / (precision + recall) if (precision + recall) > 0 else 0.0

        per_class[cls_name] = {
            "precision": round(float(precision), 4),
            "recall": round(float(recall), 4),
            "f1_score": round(float(f1), 4),
            "support": support,
        }
        f1_list.append(f1)
        precision_list.append(precision)
        recall_list.append(recall)

    macro_f1 = sum(f1_list) / max(1, num_classes)
    macro_precision = sum(precision_list) / max(1, num_classes)
    macro_recall = sum(recall_list) / max(1, num_classes)

    # Confidence analysis & unfamiliar/low-confidence handling
    high_conf_indices = [idx for idx, prob in enumerate(all_probs) if prob >= confidence_threshold]
    low_conf_count = total_samples - len(high_conf_indices)
    high_conf_correct = sum(
        all_preds[idx] == all_targets[idx] for idx in high_conf_indices
    )
    high_conf_accuracy = high_conf_correct / max(1, len(high_conf_indices))

    return {
        "overall_metrics": {
            "test_loss": round(float(avg_loss), 4),
            "accuracy": round(float(accuracy), 4),
            "macro_f1": round(float(macro_f1), 4),
            "macro_precision": round(float(macro_precision), 4),
            "macro_recall": round(float(macro_recall), 4),
            "total_samples": total_samples,
        },
        "confidence_analysis": {
            "threshold": confidence_threshold,
            "high_confidence_count": len(high_conf_indices),
            "high_confidence_accuracy": round(float(high_conf_accuracy), 4),
            "low_confidence_rejection_count": low_conf_count,
            "abstention_rate": round(float(low_conf_count / max(1, total_samples)), 4),
            "uncertain_guidance_triggered": True,
        },
        "per_class_metrics": per_class,
        "confusion_matrix": confusion.tolist(),
    }


def print_evaluation_report(results: Dict, classes: List[str]) -> None:
    """Print readable evaluation metrics table."""
    logger.info("=" * 72)
    logger.info("HELD-OUT TEST SET EVALUATION REPORT")
    logger.info("=" * 72)
    om = results["overall_metrics"]
    logger.info("Overall Accuracy: %.4f | Macro-F1: %.4f | Test Loss: %.4f", om["accuracy"], om["macro_f1"], om["test_loss"])
    logger.info("Total Test Samples: %d", om["total_samples"])

    ca = results["confidence_analysis"]
    logger.info("Confidence Threshold: %.2f | High-Conf Accuracy: %.4f | Abstention: %.2f%%",
                ca["threshold"], ca["high_confidence_accuracy"], ca["abstention_rate"] * 100)

    logger.info("-" * 72)
    logger.info(f"{'Class':<20} | {'Precision':>9} | {'Recall':>9} | {'F1-Score':>9} | {'Support':>7}")
    logger.info("-" * 72)
    for cls in classes:
        m = results["per_class_metrics"].get(cls, {})
        logger.info(f"{cls:<20} | {m.get('precision', 0.0):>9.4f} | {m.get('recall', 0.0):>9.4f} | {m.get('f1_score', 0.0):>9.4f} | {m.get('support', 0):>7}")
    logger.info("=" * 72)


def main() -> None:
    parser = argparse.ArgumentParser(description="Evaluate trained classifier on held-out test data")
    parser.add_argument("--config", type=str, default="configs/classifier_config.yaml", help="Path to config")
    parser.add_argument("--checkpoint", type=str, default="checkpoints/best_model.pth", help="Checkpoint file")
    parser.add_argument("--test-dir", type=str, default="data/splits/test", help="Held-out test set directory")
    args = parser.parse_args()

    base_dir = Path(__file__).resolve().parents[2]
    config_path = base_dir / args.config
    config = load_config(config_path)

    checkpoint_path = base_dir / args.checkpoint
    test_path = base_dir / args.test_dir

    balanced_dir = base_dir / "data" / "balanced_waste_images"
    test_transform = get_test_transform(config)

    if test_path.exists() and any(test_path.iterdir()):
        test_dataset = datasets.ImageFolder(str(test_path), transform=test_transform)
        test_loader = DataLoader(test_dataset, batch_size=config["training"]["batch_size"], shuffle=False)
        test_classes = test_dataset.classes
    elif balanced_dir.exists():
        sys.path.insert(0, str(base_dir))
        from src.data.custom_dataset import CustomDataset
        logger.info("Test split directory not found. Using CustomDataset test split from: %s", balanced_dir)
        _, _, test_loader, cls_names, _ = CustomDataset.stratified_split_dls(
            root=balanced_dir,
            test_transformations=test_transform,
            bs=config["training"]["batch_size"],
            ns=0 if sys.platform == "win32" else 2,
        )
        test_classes = list(cls_names.keys())
    else:
        logger.error("Neither test directory (%s) nor balanced_waste_images (%s) found.", test_path, balanced_dir)
        return

    threshold = config["evaluation"].get("confidence_threshold", 0.60)
    eval_results = evaluate_test_set(model, test_loader, device, test_classes, threshold)

    print_evaluation_report(eval_results, test_classes)

    # Save summary JSON
    report_file = base_dir / config["evaluation"]["summary_file"]
    report_file.parent.mkdir(parents=True, exist_ok=True)

    report_payload = {
        "metadata": {
            "model_architecture": config["model"]["architecture"],
            "checkpoint": str(args.checkpoint),
            "evaluated_at": datetime.datetime.utcnow().isoformat() + "Z",
            "num_classes": num_classes,
            "classes": test_dataset.classes,
        },
        **eval_results,
    }

    with open(report_file, "w", encoding="utf-8") as f:
        json.dump(report_payload, f, indent=2)
    logger.info("Evaluation report saved to: %s", report_file)


if __name__ == "__main__":
    main()
