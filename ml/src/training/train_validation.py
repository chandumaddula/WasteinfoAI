"""AI Model Training and Validation Module.

Phase 3: Waste Classification Model Training
Implements the TrainValidation class for training vision backbones (timm/torchvision),
computing multiclass accuracy and F1 metrics, saving best checkpoints, and early stopping.
"""

import os
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple, Union

import torch
import torch.nn as nn
from tqdm import tqdm

# Optional dependencies with graceful fallbacks
try:
    import timm
    HAS_TIMM = True
except ImportError:
    HAS_TIMM = False

try:
    import torchmetrics
    HAS_TORCHMETRICS = True
except ImportError:
    HAS_TORCHMETRICS = False


class FallbackF1Score:
    """Lightweight multiclass Macro-F1 metric fallback if torchmetrics is unavailable."""

    def __init__(self, num_classes: int, device: torch.device):
        self.num_classes = num_classes
        self.device = device
        self.reset()

    def reset(self) -> None:
        self.tp = torch.zeros(self.num_classes, device=self.device)
        self.fp = torch.zeros(self.num_classes, device=self.device)
        self.fn = torch.zeros(self.num_classes, device=self.device)

    def update(self, preds: torch.Tensor, targets: torch.Tensor) -> None:
        pred_labels = torch.argmax(preds, dim=1) if preds.ndim > 1 else preds
        for p, t in zip(pred_labels, targets):
            p_val, t_val = p.item(), t.item()
            if p_val == t_val:
                self.tp[p_val] += 1
            else:
                self.fp[p_val] += 1
                self.fn[t_val] += 1

    def compute(self) -> torch.Tensor:
        denoms = 2 * self.tp + self.fp + self.fn
        f1_per_class = torch.where(denoms > 0, (2 * self.tp) / denoms, torch.zeros_like(self.tp))
        return f1_per_class.mean()

    def to(self, device: torch.device) -> "FallbackF1Score":
        self.device = device
        self.tp = self.tp.to(device)
        self.fp = self.fp.to(device)
        self.fn = self.fn.to(device)
        return self


class TrainValidation:
    """Trainer and validator for deep learning vision models."""

    def __init__(
        self,
        model_name: str,
        classes: Union[List[str], Dict[str, int]],
        tr_dl: Any,
        val_dl: Any,
        device: Union[str, torch.device],
        save_dir: str = "saved_models",
        save_prefix: str = "model",
        lr: float = 3e-4,
        epochs: int = 50,
        patience: int = 5,
        threshold: float = 0.001,
        dev_mode: bool = False,
    ):
        self.model_name = model_name
        self.classes = classes
        self.num_classes = len(classes)
        self.tr_dl = tr_dl
        self.val_dl = val_dl
        self.save_dir = save_dir
        self.save_prefix = save_prefix
        self.lr = lr
        self.epochs = epochs
        self.patience = patience
        self.threshold = threshold
        self.dev_mode = dev_mode
        self.device = torch.device(device) if isinstance(device, str) else device

        # Initialize Model
        self.model = self._create_model(model_name, self.num_classes).to(self.device)

        # Loss function & optimizer
        self.loss_fn = nn.CrossEntropyLoss()
        self.optimizer = torch.optim.Adam(self.model.parameters(), lr=lr)

        # Multiclass F1 metric
        if HAS_TORCHMETRICS:
            self.f1_metric = torchmetrics.F1Score(
                task="multiclass", num_classes=self.num_classes
            ).to(self.device)
        else:
            self.f1_metric = FallbackF1Score(
                num_classes=self.num_classes, device=self.device
            )

        os.makedirs(save_dir, exist_ok=True)

        self.best_acc = 0.0  # Tracks best validation F1 score
        self.not_improved = 0

        self.tr_losses: List[float] = []
        self.val_losses: List[float] = []
        self.tr_accs: List[float] = []
        self.val_accs: List[float] = []
        self.tr_f1s: List[float] = []
        self.val_f1s: List[float] = []

    def _create_model(self, model_name: str, num_classes: int) -> nn.Module:
        """Create model using timm if available, or fallback to torchvision."""
        if HAS_TIMM:
            try:
                print(f"Creating model '{model_name}' via timm...")
                return timm.create_model(model_name, pretrained=True, num_classes=num_classes)
            except Exception as e:
                print(f"timm model creation failed ({e}); checking torchvision...")

        # Fallback to torchvision architectures
        from torchvision import models

        if "mobilenet_v3" in model_name.lower():
            model = models.mobilenet_v3_small(weights=models.MobileNet_V3_Small_Weights.DEFAULT)
            in_features = model.classifier[3].in_features
            model.classifier[3] = nn.Linear(in_features, num_classes)
            return model
        elif "resnet" in model_name.lower():
            model = models.resnet18(weights=models.ResNet18_Weights.DEFAULT)
            in_features = model.fc.in_features
            model.fc = nn.Linear(in_features, num_classes)
            return model
        else:
            # Default lightweight baseline
            model = models.mobilenet_v3_small(weights=models.MobileNet_V3_Small_Weights.DEFAULT)
            in_features = model.classifier[3].in_features
            model.classifier[3] = nn.Linear(in_features, num_classes)
            return model

    @staticmethod
    def to_device(batch: Tuple[torch.Tensor, torch.Tensor], device: torch.device) -> Tuple[torch.Tensor, torch.Tensor]:
        ims, gts = batch
        return ims.to(device), gts.to(device)

    def train_epoch(self) -> Tuple[float, float, float]:
        self.model.train()
        train_loss, train_acc = 0.0, 0.0
        self.f1_metric.reset()

        total_samples = 0
        for idx, batch in tqdm(enumerate(self.tr_dl), desc="Training", leave=False):
            if self.dev_mode and idx == 1:
                break

            ims, gts = TrainValidation.to_device(batch=batch, device=self.device)

            # Forward pass
            preds = self.model(ims)
            loss = self.loss_fn(preds, gts)

            # Backward pass
            self.optimizer.zero_grad()
            loss.backward()
            self.optimizer.step()

            # Update metrics
            batch_size = gts.shape[0]
            train_loss += loss.item() * batch_size
            train_acc += (torch.argmax(preds, dim=1) == gts).sum().item()
            total_samples += batch_size
            self.f1_metric.update(preds, gts)

        train_loss /= max(1, total_samples)
        train_acc /= max(1, total_samples)
        train_f1 = float(self.f1_metric.compute().item())

        self.tr_losses.append(train_loss)
        self.tr_accs.append(train_acc)
        self.tr_f1s.append(train_f1)

        return train_loss, train_acc, train_f1

    def validate_epoch(self) -> Tuple[float, float, float]:
        self.model.eval()
        val_loss, val_acc = 0.0, 0.0
        self.f1_metric.reset()

        total_samples = 0
        with torch.no_grad():
            for idx, batch in tqdm(enumerate(self.val_dl), desc="Validation", leave=False):
                if self.dev_mode and idx == 1:
                    break

                ims, gts = TrainValidation.to_device(batch=batch, device=self.device)
                preds = self.model(ims)
                loss = self.loss_fn(preds, gts)

                # Update metrics
                batch_size = gts.shape[0]
                val_loss += loss.item() * batch_size
                val_acc += (torch.argmax(preds, dim=1) == gts).sum().item()
                total_samples += batch_size
                self.f1_metric.update(preds, gts)

        val_loss /= max(1, total_samples)
        val_acc /= max(1, total_samples)
        val_f1 = float(self.f1_metric.compute().item())

        self.val_losses.append(val_loss)
        self.val_accs.append(val_acc)
        self.val_f1s.append(val_f1)

        return val_loss, val_acc, val_f1

    def save_best_model(self, val_f1: float, val_loss: float) -> None:
        """Save best checkpoint when validation F1 improves."""
        if val_f1 > (self.best_acc + self.threshold):
            self.best_acc = val_f1
            save_path = os.path.join(self.save_dir, f"{self.save_prefix}_best_model.pth")
            torch.save(self.model.state_dict(), save_path)
            print(f"--> Best model saved with F1-Score: {self.best_acc:.3f} to {save_path}")
            self.not_improved = 0
        else:
            self.not_improved += 1
            print(f"No improvement for {self.not_improved} epoch(s). (Current best F1: {self.best_acc:.3f})")

    def verbose(self, epoch: int, metric1: float, metric2: float, metric3: float, process: str = "train") -> None:
        print(f"\n{epoch + 1}-epoch {process} process is completed!")
        print(f"{epoch + 1}-epoch {process} loss          -> {metric1:.3f}")
        print(f"{epoch + 1}-epoch {process} accuracy      -> {metric2:.3f}")
        print(f"{epoch + 1}-epoch {process} f1-score      -> {metric3:.3f}")

    def run(self) -> None:
        print(f"Start training '{self.model_name}' on device '{self.device}'...")
        print(f"Classes ({self.num_classes}): {list(self.classes.keys()) if isinstance(self.classes, dict) else self.classes}\n")

        for epoch in range(self.epochs):
            if self.dev_mode and epoch == 1:
                break

            print(f"\n{'='*40}\nEpoch {epoch + 1}/{self.epochs}:\n{'='*40}")

            train_loss, train_acc, train_f1 = self.train_epoch()
            self.verbose(epoch, train_loss, train_acc, train_f1, process="train")

            val_loss, val_acc, val_f1 = self.validate_epoch()
            self.verbose(epoch, val_loss, val_acc, val_f1, process="validation")

            self.save_best_model(val_f1, val_loss)

            if self.not_improved >= self.patience:
                print(f"\nEarly stopping triggered after {self.patience} epochs without improvement.")
                break

        print(f"\nTraining session finished. Best Validation F1: {self.best_acc:.3f}")


if __name__ == "__main__":
    from torchvision import transforms as T

    base_dir = Path(__file__).resolve().parents[2]
    balanced_dir = base_dir / "data" / "balanced_waste_images"

    if balanced_dir.exists():
        sys.path.insert(0, str(base_dir))
        from src.data.custom_dataset import CustomDataset

        mean, std, im_size = [0.485, 0.456, 0.406], [0.229, 0.224, 0.225], 224
        tfs = T.Compose([
            T.Resize((im_size, im_size)),
            T.ToTensor(),
            T.Normalize(mean=mean, std=std),
        ])

        workers = 0 if sys.platform == "win32" else 2
        tr_dl, val_dl, ts_dl, classes, cls_counts = CustomDataset.stratified_split_dls(
            root=balanced_dir, transformations=tfs, bs=16, ns=workers
        )

        model_name = "rexnet_150"
        save_prefix = "waste"
        save_dir = str(base_dir / "saved_models")
        device = "cuda" if torch.cuda.is_available() else "cpu"

        trainer = TrainValidation(
            model_name=model_name,
            classes=classes,
            tr_dl=tr_dl,
            val_dl=val_dl,
            device=device,
            save_dir=save_dir,
            save_prefix=save_prefix,
            patience=3,
            dev_mode=True,  # Set False for complete training
        )
        trainer.run()
