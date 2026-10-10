"""Model Training Script for Waste Classifier.

Phase 3: Waste Classification Model
Trains a pretrained MobileNetV3-Small classifier on waste dataset splits,
evaluates validation performance (Loss, Accuracy, Macro-F1),
and saves the best checkpoint for on-device export.
"""

import argparse
import json
import logging
import os
import sys
from pathlib import Path
from typing import Dict, Tuple

import torch
import torch.nn as nn
from torch.optim import AdamW
from torch.optim.lr_scheduler import CosineAnnealingLR
from torch.utils.data import DataLoader
from torchvision import datasets, models, transforms
import yaml

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    handlers=[logging.StreamHandler(sys.stdout)],
)
logger = logging.getLogger("train_classifier")


def load_config(config_path: Path) -> dict:
    """Load configuration YAML file."""
    with open(config_path, "r", encoding="utf-8") as f:
        return yaml.safe_load(f)


def get_data_transforms(config: dict) -> Tuple[transforms.Compose, transforms.Compose]:
    """Define data augmentation and normalization transforms."""
    mean = config["dataset"]["normalization"]["mean"]
    std = config["dataset"]["normalization"]["std"]
    img_size = config["dataset"]["image_size"]

    train_transform = transforms.Compose([
        transforms.RandomResizedCrop(img_size, scale=(0.8, 1.0)),
        transforms.RandomHorizontalFlip(p=0.5),
        transforms.ColorJitter(brightness=0.2, contrast=0.2, saturation=0.2),
        transforms.ToTensor(),
        transforms.Normalize(mean=mean, std=std),
    ])

    val_transform = transforms.Compose([
        transforms.Resize(img_size + config["dataset"].get("crop_padding", 32)),
        transforms.CenterCrop(img_size),
        transforms.ToTensor(),
        transforms.Normalize(mean=mean, std=std),
    ])

    return train_transform, val_transform


def build_model(num_classes: int, dropout: float = 0.2) -> nn.Module:
    """Build MobileNetV3-Small model with custom classification head."""
    logger.info("Initializing MobileNetV3-Small with pretrained weights...")
    weights = models.MobileNet_V3_Small_Weights.DEFAULT
    model = models.mobilenet_v3_small(weights=weights)

    # Replace classifier head (layer index 3 in mobilenet_v3 classifier)
    in_features = model.classifier[3].in_features
    model.classifier[2] = nn.Dropout(p=dropout)
    model.classifier[3] = nn.Linear(in_features, num_classes)

    return model


def freeze_backbone(model: nn.Module, freeze: bool = True) -> None:
    """Freeze or unfreeze backbone feature extractor layers."""
    for param in model.features.parameters():
        param.requires_grad = not freeze
    state = "Frozen" if freeze else "Unfrozen"
    logger.info("Backbone feature extractor parameters: %s", state)


def evaluate_epoch(
    model: nn.Module,
    dataloader: DataLoader,
    criterion: nn.Module,
    device: torch.device,
    num_classes: int,
) -> Tuple[float, float, float]:
    """Evaluate model on a dataset partition (validation).

    Returns:
        (avg_loss, accuracy, macro_f1)
    """
    model.eval()
    running_loss = 0.0
    correct = 0
    total = 0

    # For Macro-F1 computation
    tp = [0] * num_classes
    fp = [0] * num_classes
    fn = [0] * num_classes

    with torch.no_grad():
        for images, labels in dataloader:
            images = images.to(device)
            labels = labels.to(device)

            outputs = model(images)
            loss = criterion(outputs, labels)
            running_loss += loss.item() * images.size(0)

            _, preds = torch.max(outputs, 1)
            correct += (preds == labels).sum().item()
            total += labels.size(0)

            for p, t in zip(preds.view(-1).cpu(), labels.view(-1).cpu()):
                p_i, t_i = p.item(), t.item()
                if p_i == t_i:
                    tp[p_i] += 1
                else:
                    fp[p_i] += 1
                    fn[t_i] += 1

    avg_loss = running_loss / max(1, total)
    accuracy = correct / max(1, total)

    # Compute Macro-F1
    f1_scores = []
    for i in range(num_classes):
        denom = (2 * tp[i] + fp[i] + fn[i])
        f1 = (2 * tp[i] / denom) if denom > 0 else 0.0
        f1_scores.append(f1)
    macro_f1 = sum(f1_scores) / max(1, num_classes)

    return avg_loss, accuracy, macro_f1


def train_model(
    config: dict,
    base_dir: Path,
    override_epochs: int = None,
) -> None:
    """Main training routine."""
    # Set seed
    seed = config["dataset"].get("random_seed", 42)
    torch.manual_seed(seed)
    if torch.cuda.is_available():
        torch.cuda.manual_seed_all(seed)

    # Device selection
    dev_str = config["training"].get("device", "auto")
    if dev_str == "auto":
        device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    else:
        device = torch.device(dev_str)
    logger.info("Using device: %s", device)

    # Data loaders
    splits_dir = base_dir / config["dataset"]["splits_dir"]
    train_dir = splits_dir / "train"
    val_dir = splits_dir / "val"
    balanced_dir = base_dir / "data" / "balanced_waste_images"

    train_tf, val_tf = get_data_transforms(config)
    batch_size = config["training"]["batch_size"]
    num_workers = config["training"].get("num_workers", 0 if sys.platform == "win32" else 2)

    if train_dir.exists() and any(train_dir.iterdir()):
        train_dataset = datasets.ImageFolder(str(train_dir), transform=train_tf)
        val_dataset = datasets.ImageFolder(str(val_dir), transform=val_tf)

        train_loader = DataLoader(
            train_dataset, batch_size=batch_size, shuffle=True,
            num_workers=num_workers, pin_memory=(device.type == "cuda")
        )
        val_loader = DataLoader(
            val_dataset, batch_size=batch_size, shuffle=False,
            num_workers=num_workers, pin_memory=(device.type == "cuda")
        )
        classes = train_dataset.classes
        class_to_idx = train_dataset.class_to_idx
    elif balanced_dir.exists():
        sys.path.insert(0, str(base_dir))
        from src.data.custom_dataset import CustomDataset

        logger.info("Using CustomDataset with stratified split on: %s", balanced_dir)
        train_loader, val_loader, _, cls_names, _ = CustomDataset.stratified_split_dls(
            root=balanced_dir,
            train_transformations=train_tf,
            val_transformations=val_tf,
            bs=batch_size,
            ns=num_workers,
        )
        classes = list(cls_names.keys())
        class_to_idx = cls_names
    else:
        logger.error(
            "Neither splits directory (%s) nor balanced_waste_images (%s) found.",
            splits_dir, balanced_dir
        )
        return

    num_classes = config["model"]["num_classes"]
    logger.info(
        "Loaded datasets: %d train batches, %d val batches across %d classes.",
        len(train_loader), len(val_loader), len(classes)
    )

    # Build model
    model = build_model(num_classes, dropout=config["model"].get("dropout", 0.2))
    model = model.to(device)

    # Setup training hyperparameters
    epochs = override_epochs or config["training"]["epochs"]
    freeze_epochs = config["training"].get("freeze_backbone_epochs", 0)
    lr = config["training"]["learning_rate"]
    weight_decay = config["training"]["weight_decay"]

    criterion = nn.CrossEntropyLoss()
    optimizer = AdamW(model.parameters(), lr=lr, weight_decay=weight_decay)
    scheduler = CosineAnnealingLR(
        optimizer, T_max=epochs, eta_min=config["training"].get("min_lr", 1e-5)
    )

    checkpoint_dir = base_dir / config["training"]["checkpoint_dir"]
    checkpoint_dir.mkdir(parents=True, exist_ok=True)

    best_val_f1 = 0.0
    patience = config["training"].get("early_stopping_patience", 5)
    patience_counter = 0

    if freeze_epochs > 0:
        freeze_backbone(model, freeze=True)

    logger.info("Beginning training for %d epochs...", epochs)

    for epoch in range(1, epochs + 1):
        if epoch == freeze_epochs + 1:
            freeze_backbone(model, freeze=False)
            # Re-initialize optimizer with smaller lr for fine-tuning
            optimizer = AdamW(model.parameters(), lr=lr * 0.5, weight_decay=weight_decay)

        model.train()
        running_train_loss = 0.0
        train_correct = 0
        train_total = 0

        for images, labels in train_loader:
            images = images.to(device)
            labels = labels.to(device)

            optimizer.zero_grad()
            outputs = model(images)
            loss = criterion(outputs, labels)
            loss.backward()
            optimizer.step()

            running_train_loss += loss.item() * images.size(0)
            _, preds = torch.max(outputs, 1)
            train_correct += (preds == labels).sum().item()
            train_total += labels.size(0)

        scheduler.step()

        train_loss = running_train_loss / max(1, train_total)
        train_acc = train_correct / max(1, train_total)

        val_loss, val_acc, val_f1 = evaluate_epoch(
            model, val_loader, criterion, device, num_classes
        )

        logger.info(
            "Epoch [%02d/%02d] | Train Loss: %.4f | Train Acc: %.4f | "
            "Val Loss: %.4f | Val Acc: %.4f | Val Macro-F1: %.4f",
            epoch, epochs, train_loss, train_acc, val_loss, val_acc, val_f1
        )

        # Save checkpoint metadata
        checkpoint_data = {
            "epoch": epoch,
            "model_state_dict": model.state_dict(),
            "optimizer_state_dict": optimizer.state_dict(),
            "val_f1": val_f1,
            "val_acc": val_acc,
            "classes": train_dataset.classes,
            "class_to_idx": train_dataset.class_to_idx,
            "config": config,
        }

        # Latest checkpoint
        torch.save(checkpoint_data, checkpoint_dir / "latest_checkpoint.pth")

        # Best checkpoint
        if val_f1 > best_val_f1:
            best_val_f1 = val_f1
            patience_counter = 0
            torch.save(checkpoint_data, checkpoint_dir / "best_model.pth")
            logger.info("Saved new best checkpoint (Macro-F1: %.4f)", best_val_f1)
        else:
            patience_counter += 1
            if patience_counter >= patience and epoch > freeze_epochs:
                logger.info("Early stopping triggered after %d epochs without improvement.", patience)
                break

    logger.info("Training complete. Best Validation Macro-F1: %.4f", best_val_f1)


def main() -> None:
    parser = argparse.ArgumentParser(description="Train MobileNetV3-Small waste classifier")
    parser.add_argument("--config", type=str, default="configs/classifier_config.yaml", help="Path to config")
    parser.add_argument("--epochs", type=int, default=None, help="Override number of training epochs")
    args = parser.parse_args()

    base_dir = Path(__file__).resolve().parents[2]
    config_path = base_dir / args.config
    config = load_config(config_path)

    train_model(config, base_dir, args.epochs)


if __name__ == "__main__":
    main()
