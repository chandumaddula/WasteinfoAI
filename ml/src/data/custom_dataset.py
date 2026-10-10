"""Custom Dataset and DataLoader Module.

Provides stratified split DataLoaders for waste classification images.
Works on local Windows environments as well as Linux / Kaggle / Colab notebooks.
"""

import os
import sys
import warnings
from glob import glob
from pathlib import Path
from typing import Dict, List, Optional, Tuple, Union

import numpy as np
import torch
from PIL import Image
from sklearn.model_selection import train_test_split
from torch.utils.data import DataLoader, Dataset
from torchvision import transforms as T

warnings.filterwarnings("ignore")

# Set random seed for reproducibility
torch.manual_seed(2025)


class CustomDataset(Dataset):
    """Custom Dataset for loading waste classification images organized by class directories."""

    def __init__(
        self,
        root: Optional[Union[str, Path]] = None,
        transformations: Optional[T.Compose] = None,
        im_paths: Optional[List[str]] = None,
        im_lbls: Optional[List[int]] = None,
        cls_names: Optional[Dict[str, int]] = None,
        im_files: Tuple[str, ...] = (".png", ".jpg", ".jpeg", ".bmp", ".JPG"),
    ):
        self.transformations = transformations
        self.root = root

        if im_paths is not None and im_lbls is not None:
            self.im_paths = im_paths
            self.im_lbls = im_lbls
        else:
            if not root or not os.path.exists(root):
                raise FileNotFoundError(f"Dataset root directory does not exist: {root}")

            # Collect image paths and deduplicate (prevents duplicates on case-insensitive filesystems)
            raw_paths: List[str] = []
            for im_file in im_files:
                raw_paths.extend(glob(os.path.join(str(root), "*", f"*{im_file}")))

            self.im_paths = sorted(list(set(os.path.abspath(p) for p in raw_paths)))

        # Build class names and class counts
        self.cls_counts: Dict[str, int] = {}
        if cls_names is not None:
            self.cls_names = cls_names
        else:
            # Deterministic sorted class naming
            self.cls_names = {}
            count = 0
            for im_path in self.im_paths:
                class_name = self.get_class(im_path)
                if class_name not in self.cls_names:
                    self.cls_names[class_name] = count
                    count += 1

        for im_path in self.im_paths:
            class_name = self.get_class(im_path)
            self.cls_counts[class_name] = self.cls_counts.get(class_name, 0) + 1

        # Populate labels if initialized directly from root
        if im_lbls is None:
            self.im_lbls = [self.cls_names[self.get_class(p)] for p in self.im_paths]

    def get_class(self, path: Union[str, Path]) -> str:
        """Cross-platform extraction of parent folder name (class label).

        Supports both Windows backslashes and Linux forward slashes.
        """
        return os.path.basename(os.path.dirname(os.path.abspath(path)))

    def __len__(self) -> int:
        return len(self.im_paths)

    def __getitem__(self, idx: int) -> Tuple[torch.Tensor, int]:
        im_path = self.im_paths[idx]
        im = Image.open(im_path).convert("RGB")
        gt = self.im_lbls[idx]

        if self.transformations is not None:
            im = self.transformations(im)

        return im, gt

    @classmethod
    def stratified_split_dls(
        cls,
        root: Union[str, Path],
        transformations: Optional[T.Compose] = None,
        train_transformations: Optional[T.Compose] = None,
        val_transformations: Optional[T.Compose] = None,
        test_transformations: Optional[T.Compose] = None,
        bs: int = 32,
        split: List[float] = [0.8, 0.1, 0.1],
        ns: int = 0,
        seed: int = 2025,
    ) -> Tuple[DataLoader, DataLoader, DataLoader, Dict[str, int], List[Dict[str, int]]]:
        """Split dataset into stratified train, val, and test DataLoaders.

        Args:
            root: Root folder containing subdirectories per class.
            transformations: Default transforms for all splits.
            train_transformations: Optional train-specific transforms (with augmentation).
            val_transformations: Optional validation transforms.
            test_transformations: Optional test transforms.
            bs: Batch size.
            split: [train_ratio, val_ratio, test_ratio], summing to 1.0.
            ns: Number of DataLoader workers (defaults to 0 for Windows safety).
            seed: Random seed for stratified splitting reproducibility.

        Returns:
            train_dl, val_dl, test_dl, classes_dict, [train_counts, val_counts, test_counts]
        """
        dataset = cls(root=root, transformations=transformations)

        im_paths = dataset.im_paths
        labels = [dataset.cls_names[dataset.get_class(p)] for p in im_paths]

        # First split: train vs (val + test)
        train_paths, temp_paths, train_lbls, temp_lbls = train_test_split(
            im_paths,
            labels,
            test_size=(split[1] + split[2]),
            stratify=labels,
            random_state=seed,
        )

        # Second split: val vs test
        val_ratio = split[1] / (split[1] + split[2])
        val_paths, test_paths, val_lbls, test_lbls = train_test_split(
            temp_paths,
            temp_lbls,
            test_size=(1 - val_ratio),
            stratify=temp_lbls,
            random_state=seed,
        )

        tr_tf = train_transformations or transformations
        val_tf = val_transformations or transformations
        ts_tf = test_transformations or transformations

        train_ds = cls(
            root=root,
            transformations=tr_tf,
            im_paths=train_paths,
            im_lbls=train_lbls,
            cls_names=dataset.cls_names,
        )
        val_ds = cls(
            root=root,
            transformations=val_tf,
            im_paths=val_paths,
            im_lbls=val_lbls,
            cls_names=dataset.cls_names,
        )
        test_ds = cls(
            root=root,
            transformations=ts_tf,
            im_paths=test_paths,
            im_lbls=test_lbls,
            cls_names=dataset.cls_names,
        )

        train_dl = DataLoader(train_ds, batch_size=bs, shuffle=True, num_workers=ns)
        val_dl = DataLoader(val_ds, batch_size=bs, shuffle=False, num_workers=ns)
        test_dl = DataLoader(test_ds, batch_size=1, shuffle=False, num_workers=ns)

        return (
            train_dl,
            val_dl,
            test_dl,
            dataset.cls_names,
            [train_ds.cls_counts, val_ds.cls_counts, test_ds.cls_counts],
        )


def resolve_dataset_root(candidate_root: Optional[str] = None) -> Path:
    """Resolve dataset path supporting Kaggle, Colab, and local workspace."""
    if candidate_root and os.path.exists(candidate_root):
        return Path(candidate_root)

    # Project workspace search candidates
    base_dir = Path(__file__).resolve().parents[2]
    candidates = [
        Path(candidate_root) if candidate_root else None,
        base_dir / "data" / "balanced_waste_images",
        base_dir / "data" / "raw",
        Path("/kaggle/input/waste-classfication-dataset/balanced_waste_images"),
    ]

    for p in candidates:
        if p and p.exists():
            return p

    raise FileNotFoundError(
        f"Could not find dataset directory. Checked candidates: {[str(c) for c in candidates if c]}"
    )


if __name__ == "__main__":
    # Autodetect root directory (local or Kaggle)
    kaggle_root = "/kaggle/input/waste-classfication-dataset/balanced_waste_images"
    try:
        root_path = resolve_dataset_root(kaggle_root)
        print(f"Using dataset root: {root_path}")

        mean, std, im_size, bs = [0.485, 0.456, 0.406], [0.229, 0.224, 0.225], 224, 32
        tfs = T.Compose([
            T.Resize((im_size, im_size)),
            T.ToTensor(),
            T.Normalize(mean=mean, std=std),
        ])

        # Default ns=0 on Windows to avoid process spawning issues
        workers = 0 if sys.platform == "win32" else 4

        tr_dl, val_dl, ts_dl, classes, cls_counts = CustomDataset.stratified_split_dls(
            root=root_path, transformations=tfs, bs=bs, ns=workers
        )

        print(f"Train batches: {len(tr_dl)}")
        print(f"Val batches:   {len(val_dl)}")
        print(f"Test batches:  {len(ts_dl)}")
        print(f"Classes ({len(classes)}): {classes}")
    except FileNotFoundError as err:
        print(err)
