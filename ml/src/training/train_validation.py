"""AI Model Training and Validation Module.

Phase 3: Waste Classification Model Training
Engineered to achieve >= 0.940 Macro F1-Score on the 17-class waste dataset.
Features:
- Pretrained ReXNet / ConvNeXt / MobileNet vision backbones via timm
- Label Smoothing Cross-Entropy (0.1) for improved generalization
- Discriminative layer-wise learning rates with AdamW optimizer
- Cosine Annealing learning rate schedule with minimum eta
- Gradient clipping & mixed precision (AMP)
- Test-Time Augmentation (TTA) in validation for enhanced F1 precision
"""

import os
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple, Union

import numpy as np
import torch
import torch.nn as nn
from tqdm import tqdm

# Optional dependencies with graceful handling
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


class MulticlassF1Metric:
    """Robust Multiclass Macro-F1 metric supporting torchmetrics or native PyTorch fallback."""

    def __init__(self, num_classes: int, device: torch.device):
        self.num_classes = num_classes
        self.device = device
        if HAS_TORCHMETRICS:
            self.metric = torchmetrics.F1Score(
                task="multiclass", num_classes=num_classes, average="macro"
            ).to(device)
        else:
            self.metric = None
        self.reset()

    def reset(self) -> None:
        if self.metric is not None:
            self.metric.reset()
        self.tp = torch.zeros(self.num_classes, device=self.device)
        self.fp = torch.zeros(self.num_classes, device=self.device)
        self.fn = torch.zeros(self.num_classes, device=self.device)

    def update(self, preds: torch.Tensor, targets: torch.Tensor) -> None:
        if self.metric is not None:
            self.metric.update(preds, targets)
        else:
            pred_labels = torch.argmax(preds, dim=1) if preds.ndim > 1 else preds
            for p, t in zip(pred_labels, targets):
                p_i, t_i = p.item(), t.item()
                if p_i == t_i:
                    self.tp[p_i] += 1
                else:
                    self.fp[p_i] += 1
                    self.fn[t_i] += 1

    def compute(self) -> torch.Tensor:
        if self.metric is not None:
            return self.metric.compute()
        denoms = 2 * self.tp + self.fp + self.fn
        f1_per_class = torch.where(denoms > 0, (2 * self.tp) / denoms, torch.zeros_like(self.tp))
        return f1_per_class.mean()


class TrainValidation:
    """Trainer and validator optimized to attain >= 0.940 F1-Score."""

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
        use_tta: bool = True,
        label_smoothing: float = 0.1,
        target_f1: float = 0.940,
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
        self.use_tta = use_tta
        self.target_f1 = target_f1
        self.device = torch.device(device) if isinstance(device, str) else device

        # 1. High-capacity vision backbone with dropout regularization
        self.model = self._create_model(model_name, self.num_classes).to(self.device)

        # 2. Label Smoothing Cross-Entropy Loss to prevent overconfidence
        self.loss_fn = nn.CrossEntropyLoss(label_smoothing=label_smoothing)

        # 3. Discriminative layer-wise learning rates with AdamW optimizer
        backbone_params = []
        head_params = []
        for name, param in self.model.named_parameters():
            if not param.requires_grad:
                continue
            if any(k in name.lower() for k in ["head", "fc", "classifier"]):
                head_params.append(param)
            else:
                backbone_params.append(param)

        if backbone_params and head_params:
            self.optimizer = torch.optim.AdamW([
                {"params": backbone_params, "lr": lr * 0.25, "weight_decay": 1e-4},
                {"params": head_params, "lr": lr, "weight_decay": 1e-4},
            ])
        else:
            self.optimizer = torch.optim.AdamW(
                self.model.parameters(), lr=lr, weight_decay=1e-4
            )

        # 4. Cosine Annealing learning rate schedule
        self.scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(
            self.optimizer, T_max=epochs, eta_min=1e-6
        )

        # 5. Multiclass Macro-F1 Metric
        self.f1_metric = MulticlassF1Metric(self.num_classes, self.device)

        # 6. Automatic Mixed Precision (AMP)
        self.use_amp = (self.device.type == "cuda")
        self.scaler = torch.cuda.amp.GradScaler(enabled=self.use_amp)

        os.makedirs(save_dir, exist_ok=True)

        self.best_loss = float("inf")
        self.best_acc = 0.0  # Tracks peak validation Macro-F1
        self.not_improved = 0

        self.tr_losses, self.val_losses = [], []
        self.tr_accs, self.val_accs = [], []
        self.tr_f1s, self.val_f1s = [], []

    def _create_model(self, model_name: str, num_classes: int) -> nn.Module:
        """Create model via timm or fallback to torchvision."""
        if HAS_TIMM:
            try:
                print(f"Creating timm backbone: {model_name} (num_classes={num_classes})")
                return timm.create_model(
                    model_name,
                    pretrained=True,
                    num_classes=num_classes,
                    drop_rate=0.2,
                    drop_path_rate=0.1,
                )
            except Exception as e:
                print(f"timm model creation warning ({e}); checking torchvision...")

        from torchvision import models

        if "mobilenet_v3" in model_name.lower():
            model = models.mobilenet_v3_small(weights=models.MobileNet_V3_Small_Weights.DEFAULT)
            in_features = model.classifier[3].in_features
            model.classifier[2] = nn.Dropout(p=0.2)
            model.classifier[3] = nn.Linear(in_features, num_classes)
            return model
        elif "resnet" in model_name.lower():
            model = models.resnet18(weights=models.ResNet18_Weights.DEFAULT)
            in_features = model.fc.in_features
            model.fc = nn.Sequential(nn.Dropout(p=0.2), nn.Linear(in_features, num_classes))
            return model
        else:
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
            batch_size = gts.shape[0]

            self.optimizer.zero_grad()

            with torch.cuda.amp.autocast(enabled=self.use_amp):
                preds = self.model(ims)
                loss = self.loss_fn(preds, gts)

            # Mixed precision backward pass
            self.scaler.scale(loss).backward()
            # Gradient clipping to prevent gradient explosion
            self.scaler.unscale_(self.optimizer)
            torch.nn.utils.clip_grad_norm_(self.model.parameters(), max_norm=1.0)

            self.scaler.step(self.optimizer)
            self.scaler.update()

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
                batch_size = gts.shape[0]

                with torch.cuda.amp.autocast(enabled=self.use_amp):
                    if self.use_tta:
                        # Test-Time Augmentation (original + horizontal flip)
                        preds_orig = self.model(ims)
                        preds_flip = self.model(torch.flip(ims, dims=[3]))
                        preds = 0.5 * (preds_orig + preds_flip)
                    else:
                        preds = self.model(ims)

                    loss = self.loss_fn(preds, gts)

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
            print(f"Best model saved with F1-Score: {self.best_acc:.3f}")

            if self.best_acc >= self.target_f1:
                print(f"[GOAL REACHED] Target F1-Score of {self.target_f1:.3f} achieved!")

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
        print(f"Target F1-Score: >= {self.target_f1:.3f}")
        print(f"Classes ({self.num_classes}): {list(self.classes.keys()) if isinstance(self.classes, dict) else self.classes}\n")

        for epoch in range(self.epochs):
            if self.dev_mode and epoch == 1:
                break

            print(f"\n{'='*40}\nEpoch {epoch + 1}/{self.epochs} (LR: {self.optimizer.param_groups[-1]['lr']:.6f}):\n{'='*40}")

            train_loss, train_acc, train_f1 = self.train_epoch()
            self.verbose(epoch, train_loss, train_acc, train_f1, process="train")

            val_loss, val_acc, val_f1 = self.validate_epoch()
            self.verbose(epoch, val_loss, val_acc, val_f1, process="validation")

            self.save_best_model(val_f1, val_loss)

            # Step cosine annealing learning rate scheduler
            self.scheduler.step()

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

        # Augmented training transforms for robust feature learning
        mean, std, im_size = [0.485, 0.456, 0.406], [0.229, 0.224, 0.225], 224
        train_tfs = T.Compose([
            T.Resize((im_size + 32, im_size + 32)),
            T.RandomResizedCrop(im_size, scale=(0.85, 1.0)),
            T.RandomHorizontalFlip(p=0.5),
            T.ColorJitter(brightness=0.15, contrast=0.15, saturation=0.15),
            T.ToTensor(),
            T.Normalize(mean=mean, std=std),
        ])

        val_tfs = T.Compose([
            T.Resize((im_size, im_size)),
            T.ToTensor(),
            T.Normalize(mean=mean, std=std),
        ])

        workers = 0 if sys.platform == "win32" else 2
        tr_dl, val_dl, ts_dl, classes, cls_counts = CustomDataset.stratified_split_dls(
            root=balanced_dir,
            train_transformations=train_tfs,
            val_transformations=val_tfs,
            bs=32,
            ns=workers,
        )

        model_name = "rexnet_150"
        save_prefix = "waste"
        save_dir = str(base_dir / "saved_models")
        device = "cuda" if torch.cuda.is_available() else "cpu"

        trainer = TrainValidation(
            model_name=model_name,
            device=device,
            save_prefix=save_prefix,
            save_dir=save_dir,
            classes=classes,
            patience=5,
            lr=3e-4,
            epochs=30,
            tr_dl=tr_dl,
            val_dl=val_dl,
            dev_mode=False,
            target_f1=0.940,
        )
        trainer.run()