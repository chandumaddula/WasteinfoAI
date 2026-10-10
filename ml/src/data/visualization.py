"""Data Visualization and Analysis Module.

Phase 3: Waste Dataset Exploration & Imbalance Analysis
Provides tensor-to-numpy image conversion, batch grid visualization,
class distribution bar charts, and pie chart analysis for train/val/test splits.
"""

from typing import Any, Dict, List, Optional, Union

import matplotlib.pyplot as plt
import numpy as np
import torch
from torchvision import transforms as T


class Visualization:
    """Utility for visualizing dataset splits and analyzing class distributions."""

    def __init__(
        self,
        vis_datas: List[Any],
        n_ims: int = 20,
        rows: int = 4,
        cmap: Optional[str] = "rgb",
        cls_names: Optional[Union[List[str], Dict[Union[int, str], Any]]] = None,
        cls_counts: Optional[Union[List[Dict[str, int]], Dict[str, int]]] = None,
        t_type: str = "rgb",
    ):
        self.n_ims = n_ims
        self.rows = rows
        self.t_type = t_type
        self.cmap = cmap

        # Normalize cls_names to a list of class name strings
        if isinstance(cls_names, dict):
            # If dict is {name: id}, invert to {id: name}
            if all(isinstance(v, int) for v in cls_names.values()):
                inv_map = {v: k for k, v in cls_names.items()}
                self.cls_names = [inv_map.get(i, str(i)) for i in range(len(cls_names))]
            else:
                self.cls_names = [cls_names.get(i, str(i)) for i in range(len(cls_names))]
        else:
            self.cls_names = cls_names

        data_names = ["train", "val", "test"]
        self.colors = ["darkorange", "seagreen", "salmon"]

        self.vis_datas = {data_names[i]: vis_datas[i] for i in range(min(len(vis_datas), len(data_names)))}

        if isinstance(cls_counts, list):
            self.analysis_datas = {
                data_names[i]: cls_counts[i]
                for i in range(min(len(cls_counts), len(data_names)))
            }
        elif cls_counts is not None:
            self.analysis_datas = {"all": cls_counts}
        else:
            self.analysis_datas = {}

    def tn2np(self, t: torch.Tensor) -> np.ndarray:
        """Convert a normalized PyTorch tensor into a uint8 NumPy image."""
        t_clone = t.clone().detach().cpu()

        gray_tfs = T.Compose([
            T.Normalize(mean=[0.0], std=[1 / 0.5]),
            T.Normalize(mean=[-0.5], std=[1.0]),
        ])
        rgb_tfs = T.Compose([
            T.Normalize(mean=[0.0, 0.0, 0.0], std=[1 / 0.229, 1 / 0.224, 1 / 0.225]),
            T.Normalize(mean=[-0.485, -0.456, -0.406], std=[1.0, 1.0, 1.0]),
        ])

        inv_trans = gray_tfs if self.t_type == "gray" else rgb_tfs

        if self.t_type == "gray":
            arr = inv_trans(t_clone) * 255.0
            arr = arr.squeeze().permute(1, 2, 0) if arr.ndim == 3 else arr
            return np.clip(arr.numpy(), 0, 255).astype(np.uint8)
        else:
            arr = inv_trans(t_clone) * 255.0
            return np.clip(arr.permute(1, 2, 0).numpy(), 0, 255).astype(np.uint8)

    def plot(self, rows: int, cols: int, count: int, im: torch.Tensor, title: str = "Original Image") -> int:
        """Plot a single image subplot."""
        plt.subplot(rows, cols, count)
        plt.imshow(self.tn2np(im))
        plt.axis("off")
        plt.title(title)
        return count + 1

    def vis(self, data: Any, save_name: str = "train") -> None:
        """Visualize a grid of random samples from a dataset."""
        print(f"{save_name.upper()} Data Visualization is in process...\n")
        assert self.cmap in ["rgb", "gray"], "Please choose 'rgb' or 'gray' cmap"
        cols = self.n_ims // self.rows

        plt.figure(figsize=(20, 16))
        total_items = len(data)
        indices = [np.random.randint(low=0, high=total_items) for _ in range(self.n_ims)]

        for idx, index in enumerate(indices):
            image, label = data[index]
            plt.subplot(self.rows, cols, idx + 1)

            im_np = self.tn2np(image)
            if self.t_type == "gray" or (self.cmap == "gray" and im_np.ndim == 2):
                plt.imshow(im_np, cmap="gray")
            else:
                plt.imshow(im_np)

            plt.axis("off")
            if self.cls_names is not None:
                lbl_idx = int(label)
                name = self.cls_names[lbl_idx] if lbl_idx < len(self.cls_names) else str(label)
                plt.title(f"GT -> {name}", fontsize=11)
            else:
                plt.title(f"GT -> {label}", fontsize=11)

        plt.suptitle(f"{save_name.upper()} Dataset Sample Grid", fontsize=15, y=0.98)
        plt.tight_layout()
        plt.show()

    def data_analysis(self, cls_counts: Dict[str, int], save_name: str = "all", color: str = "darkorange") -> None:
        """Plot bar chart showing class sample counts and imbalance."""
        print(f"Data analysis for {save_name.upper()} is in process...\n")
        width = 0.7
        cls_names = list(cls_counts.keys())
        counts = list(cls_counts.values())

        _, ax = plt.subplots(figsize=(16, 7))
        indices = np.arange(len(counts))
        ax.bar(indices, counts, width, color=color, edgecolor="black", alpha=0.85)
        ax.set_xlabel("Class Names", color="black", fontsize=12)
        ax.set_ylabel("Data Counts", color="black", fontsize=12)
        ax.set_title(f"Dataset Class Imbalance Analysis — {save_name.upper()}", fontsize=14, pad=10)
        ax.set_xticks(indices)
        ax.set_xticklabels(cls_names, rotation=45, ha="right", fontsize=10)
        ax.grid(axis="y", linestyle="--", alpha=0.5)

        max_c = max(counts) if counts else 100
        text_height = max_c * 0.02

        for i, v in enumerate(counts):
            ax.text(i, v + text_height, str(v), color="royalblue", ha="center", fontweight="bold", fontsize=9)

        plt.tight_layout()
        plt.show()

    def plot_pie_chart(self, cls_counts: Dict[str, int], save_name: str = "all") -> None:
        """Plot a pie chart showing relative class distribution percentages."""
        print(f"Generating pie chart for {save_name.upper()}...\n")
        labels = list(cls_counts.keys())
        sizes = list(cls_counts.values())
        explode = [0.05] * len(labels)

        plt.figure(figsize=(9, 9))
        cmap = plt.cm.tab20.colors if len(labels) <= 20 else None
        plt.pie(
            sizes,
            explode=explode,
            labels=labels,
            autopct="%1.1f%%",
            startangle=140,
            colors=cmap,
            textprops={"fontsize": 9},
        )
        plt.title(f"Class Distribution Percentage — {save_name.upper()}", fontsize=13, pad=12)
        plt.axis("equal")
        plt.tight_layout()
        plt.show()

    def visualization(self) -> None:
        """Visualize sample image grids across all available splits."""
        for save_name, data in self.vis_datas.items():
            dataset = getattr(data, "dataset", data)
            self.vis(dataset, save_name)

    def analysis(self) -> None:
        """Generate class distribution bar charts across all splits."""
        for (save_name, data), color in zip(self.analysis_datas.items(), self.colors):
            self.data_analysis(data, save_name, color)

    def pie_chart(self) -> None:
        """Generate pie charts across all splits."""
        for save_name, data in self.analysis_datas.items():
            self.plot_pie_chart(data, save_name)


if __name__ == "__main__":
    from pathlib import Path
    import sys

    # Quick demo if run directly
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

        tr_dl, val_dl, ts_dl, classes, cls_counts = CustomDataset.stratified_split_dls(
            root=balanced_dir, transformations=tfs, bs=32, ns=0
        )

        vis = Visualization(
            vis_datas=[tr_dl, val_dl, ts_dl],
            n_ims=16,
            rows=4,
            cmap="rgb",
            cls_names=list(classes.keys()),
            cls_counts=cls_counts,
        )

        print("Running class imbalance analysis bar chart...")
        vis.analysis()
