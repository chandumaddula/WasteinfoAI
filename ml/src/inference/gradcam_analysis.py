"""Inference and AI Model Performance Analysis with GradCAM.

Phase 3–4: Model Explainability & Held-Out Test Evaluation
Features:
- Denormalization & Tensor-to-Image visualization
- Model accuracy & multiclass F1 metrics computation
- GradCAM++ saliency visualizer highlighting image regions driving predictions
- Prediction probability bar chart with color-coded ground truth matching
- Comprehensive Confusion Matrix heatmap
"""

import os
import random
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple, Union

import cv2
import matplotlib.pyplot as plt
import numpy as np
import seaborn as sns
import torch
import torchmetrics
from sklearn.metrics import confusion_matrix
from tqdm import tqdm

# Optional GradCAM import with graceful fallback
try:
    from pytorch_grad_cam import GradCAM, GradCAMPlusPlus
    from pytorch_grad_cam.utils.image import show_cam_on_image
    HAS_GRADCAM = True
except ImportError:
    HAS_GRADCAM = False


class Denormalize:
    """Invert ImageNet channel-wise normalization on PyTorch tensors."""

    def __init__(self, mean: List[float], std: List[float]):
        self.mean = mean
        self.std = std

    def __call__(self, tensor: torch.Tensor) -> torch.Tensor:
        # Clone tensor to avoid mutating original normalized tensors in memory
        t_clone = tensor.clone()
        for t, m, s in zip(t_clone, self.mean, self.std):
            t.mul_(s).add_(m)
        return t_clone


class ModelInferenceVisualizer:
    """Inference evaluator and visualizer with GradCAM++ explainability."""

    def __init__(
        self,
        model: torch.nn.Module,
        device: Union[str, torch.device],
        class_names: Optional[List[str]] = None,
        im_size: int = 224,
        mean: Optional[List[float]] = None,
        std: Optional[List[float]] = None,
    ):
        self.mean = mean or [0.485, 0.456, 0.406]
        self.std = std or [0.229, 0.224, 0.225]
        self.denormalize = Denormalize(self.mean, self.std)

        self.model = model
        self.device = torch.device(device) if isinstance(device, str) else device
        self.class_names = class_names or []
        self.num_classes = len(self.class_names) if self.class_names else 17
        self.im_size = im_size

        self.model.eval()
        self.f1_metric = torchmetrics.F1Score(
            task="multiclass", num_classes=self.num_classes, average="macro"
        ).to(self.device)

    def tensor_to_image(self, tensor: torch.Tensor) -> np.ndarray:
        """Denormalize tensor and convert from CxHxW float to HxWxC uint8 array."""
        tensor = self.denormalize(tensor)
        tensor = tensor.permute(1, 2, 0)
        img_np = np.clip(tensor.cpu().numpy() * 255.0, 0, 255).astype(np.uint8)
        return img_np

    def plot_value_array(self, logits: torch.Tensor, gt: int, class_names: List[str]) -> None:
        """Plot horizontal or vertical probability bar chart for predicted classes."""
        probs = torch.nn.functional.softmax(logits, dim=1)
        pred_class = torch.argmax(probs, dim=1).item()

        plt.grid(visible=True, linestyle="--", alpha=0.5)
        plt.xticks(range(len(class_names)), class_names, rotation="vertical", fontsize=9)
        plt.yticks(np.arange(0.0, 1.1, 0.1), fontsize=9)
        bars = plt.bar(range(len(class_names)), [p.item() for p in probs[0]], color="#777777")
        plt.ylim([0, 1])

        # Color green if prediction matches ground truth, else red
        if pred_class == gt:
            bars[pred_class].set_color("green")
        else:
            bars[pred_class].set_color("red")
            if gt < len(bars):
                bars[gt].set_color("blue")  # Highlight true class in blue

    def _get_target_layers(self) -> List[torch.nn.Module]:
        """Find the appropriate convolutional layer for GradCAM."""
        # 1. ReXNet backbone in timm
        if hasattr(self.model, "features") and len(self.model.features) > 0:
            last_feat = self.model.features[-1]
            if hasattr(last_feat, "conv"):
                return [last_feat.conv]
            return [last_feat]

        # 2. ResNet backbones
        if hasattr(self.model, "layer4"):
            return [self.model.layer4[-1]]

        # 3. MobileNet backbones
        if hasattr(self.model, "features"):
            return [self.model.features[-1]]

        # 4. Fallback: inspect children backwards
        conv_layers = [m for m in self.model.modules() if isinstance(m, torch.nn.Conv2d)]
        if conv_layers:
            return [conv_layers[-1]]

        raise ValueError("Could not automatically locate a convolutional layer for GradCAM.")

    def generate_cam_visualization(self, image_tensor: torch.Tensor) -> np.ndarray:
        """Generate GradCAM++ heatmap mask."""
        if not HAS_GRADCAM:
            # Fallback placeholder if grad-cam package is not installed
            return np.zeros((self.im_size, self.im_size), dtype=np.float32)

        target_layers = self._get_target_layers()
        cam = GradCAMPlusPlus(
            model=self.model,
            target_layers=target_layers,
            use_cuda=(self.device.type == "cuda"),
        )
        input_tensor = image_tensor.unsqueeze(0).to(self.device)
        grayscale_cam = cam(input_tensor=input_tensor)[0, :]
        return grayscale_cam

    def infer_and_visualize(self, test_dl: Any, num_images: int = 5, rows: int = 2) -> None:
        """Run batch inference, print test metrics, and plot GradCAM and Confusion Matrix."""
        preds, images, lbls, logitss = [], [], [], []
        accuracy = 0
        self.f1_metric.reset()

        with torch.no_grad():
            for idx, batch in tqdm(enumerate(test_dl), desc="Inference"):
                ims, gts = batch[0].to(self.device), batch[1].to(self.device)
                logits = self.model(ims)
                pred_class = torch.argmax(logits, dim=1)

                accuracy += (pred_class == gts).sum().item()
                self.f1_metric.update(logits, gts)

                for i in range(ims.size(0)):
                    images.append(ims[i].cpu())
                    logitss.append(logits[i].cpu())
                    preds.append(pred_class[i].item())
                    lbls.append(gts[i].item())

        total_samples = len(images)
        acc_score = accuracy / max(1, total_samples)
        f1_score = self.f1_metric.compute().item()

        print("\n" + "=" * 55)
        print("MODEL TEST EVALUATION METRICS")
        print("=" * 55)
        print(f"Accuracy on test data : {acc_score:.4f} ({acc_score * 100:.2f}%)")
        print(f"Macro-F1 on test data : {f1_score:.4f}")
        print("=" * 55 + "\n")

        # Visualizations
        actual_num_images = min(num_images, total_samples)
        cols_per_row = (actual_num_images * 2 + rows - 1) // rows

        plt.figure(figsize=(22, 10))
        indices = [random.randint(0, total_samples - 1) for _ in range(actual_num_images)]
        count = 1

        for idx, index in enumerate(indices):
            im_np = self.tensor_to_image(images[index].squeeze())
            pred_idx = preds[index]
            gt_idx = lbls[index]

            # 1. Image + GradCAM Overlay subplot
            plt.subplot(rows, cols_per_row, count)
            count += 1

            if HAS_GRADCAM:
                grayscale_cam = self.generate_cam_visualization(images[index])
                visualization = show_cam_on_image(
                    im_np / 255.0, grayscale_cam, image_weight=0.45, use_rgb=True
                )
                vis_resized = cv2.resize(
                    visualization, (self.im_size, self.im_size), interpolation=cv2.INTER_LINEAR
                )
                plt.imshow(vis_resized)
            else:
                plt.imshow(im_np)

            plt.axis("off")

            # 2. Probability distribution subplot
            logits = logitss[index]
            if logits.dim() == 1:
                logits = logits.unsqueeze(0)

            plt.subplot(rows, cols_per_row, count)
            count += 1
            self.plot_value_array(logits=logits, gt=gt_idx, class_names=self.class_names)

            if self.class_names:
                gt_name = self.class_names[gt_idx]
                pred_name = self.class_names[pred_idx]
                color = "green" if gt_name == pred_name else "red"
                plt.title(f"GT -> {gt_name} ; PRED -> {pred_name}", color=color, fontsize=10)

        plt.suptitle("Model Inference with GradCAM++ Saliency Maps", fontsize=15, y=0.98)
        plt.tight_layout()
        plt.show()

        # 3. Full Confusion Matrix
        plt.figure(figsize=(14, 10))
        cm = confusion_matrix(lbls, preds)
        sns.heatmap(
            cm,
            annot=True,
            fmt="d",
            cmap="Blues",
            xticklabels=self.class_names,
            yticklabels=self.class_names,
        )
        plt.title("Confusion Matrix — Held-Out Test Set", fontsize=14, pad=12)
        plt.xlabel("Predicted Class", fontsize=12)
        plt.ylabel("True Class", fontsize=12)
        plt.xticks(rotation=45, ha="right")
        plt.tight_layout()
        plt.show()


if __name__ == "__main__":
    import timm
    from torchvision import transforms as T

    base_dir = Path(__file__).resolve().parents[2]
    balanced_dir = base_dir / "data" / "balanced_waste_images"
    save_dir = base_dir / "saved_models"
    save_prefix = "waste"

    mean = [0.485, 0.456, 0.406]
    std = [0.229, 0.224, 0.225]
    im_size = 224

    if balanced_dir.exists():
        sys.path.insert(0, str(base_dir))
        from src.data.custom_dataset import CustomDataset

        tfs = T.Compose([
            T.Resize((im_size, im_size)),
            T.ToTensor(),
            T.Normalize(mean=mean, std=std),
        ])

        workers = 0 if sys.platform == "win32" else 2
        tr_dl, val_dl, ts_dl, classes, cls_counts = CustomDataset.stratified_split_dls(
            root=balanced_dir,
            transformations=tfs,
            bs=32,
            ns=workers,
        )

        class_names = list(classes.keys())
        device = "cuda" if torch.cuda.is_available() else "cpu"
        model_name = "rexnet_150"

        # Initialize model
        model = timm.create_model(
            model_name=model_name, pretrained=True, num_classes=len(class_names)
        ).to(device)

        # Load trained checkpoint if available
        ckpt_path = save_dir / f"{save_prefix}_best_model.pth"
        if ckpt_path.exists():
            print(f"Loading weights from: {ckpt_path}")
            model.load_state_dict(torch.load(ckpt_path, map_location=device))
        else:
            print(f"Checkpoint not found at {ckpt_path}. Evaluating using model baseline weights.")

        visualizer = ModelInferenceVisualizer(
            model=model,
            device=device,
            class_names=class_names,
            im_size=im_size,
            mean=mean,
            std=std,
        )

        visualizer.infer_and_visualize(ts_dl, num_images=10, rows=4)
