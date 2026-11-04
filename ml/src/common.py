"""Shared data / model / rejection-policy utilities.

train, evaluate, calibrate, export and predict all import from here, so the
split, preprocessing, class order and acceptance rule can never drift apart.
"""
import json
import logging
import math
import random
import sys
from pathlib import Path
from typing import Dict, List, Optional

import numpy as np
import timm
import torch
import torch.nn as nn
import yaml
from PIL import Image
from torch.utils.data import DataLoader, Dataset, WeightedRandomSampler
from torchvision import transforms as T

from src.dataset_index import (IMAGE_EXTS, Taxonomy, create_or_load_split,  # noqa: E402,F401
                               taxonomy_from_manifest)

logger = logging.getLogger("common")


def project_root() -> Path:
    return Path(__file__).resolve().parents[1]


def load_config(path: Path) -> dict:
    with open(path, "r", encoding="utf-8") as f:
        return yaml.safe_load(f)


def seed_everything(seed: int) -> None:
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)
    torch.cuda.manual_seed_all(seed)


def get_device(pref: str = "auto") -> torch.device:
    if pref == "auto":
        return torch.device("cuda" if torch.cuda.is_available() else "cpu")
    return torch.device(pref)


def load_class_names(cfg: dict, root: Path) -> List[str]:
    with open(root / cfg["dataset"]["labels_file"], "r", encoding="utf-8") as f:
        data = json.load(f)
    return [data[str(i)] for i in range(len(data))]


# --------------------------------------------------------------------------- #
# Transforms
# --------------------------------------------------------------------------- #
def build_transforms(cfg: dict):
    ds = cfg["dataset"]
    size = ds["image_size"]
    norm = T.Normalize(ds["normalization"]["mean"], ds["normalization"]["std"])
    train_tf = T.Compose([
        T.RandomResizedCrop(size, scale=(0.6, 1.0), ratio=(0.8, 1.25)),
        T.RandomHorizontalFlip(),
        T.RandomVerticalFlip(p=0.15),
        T.RandomRotation(20),
        T.ColorJitter(0.4, 0.4, 0.4, 0.05),
        T.RandomPerspective(0.2, p=0.3),
        T.RandomApply([T.GaussianBlur(5, sigma=(0.1, 2.0))], p=0.25),
        T.ToTensor(),
        norm,
        T.RandomErasing(p=0.25, scale=(0.02, 0.18), value="random"),
    ])
    # Whole image is kept (no centre crop) so objects near the frame edge are not cut off.
    eval_tf = T.Compose([T.Resize((size, size)), T.ToTensor(), norm])
    return train_tf, eval_tf


# Split / taxonomy logic lives in src/dataset_index.py (leaf classes -> main categories)


class WasteDataset(Dataset):
    def __init__(self, root: Path, paths: List[str], labels: List[int], transform):
        self.root, self.paths, self.labels, self.transform = root, paths, labels, transform

    def __len__(self):
        return len(self.paths)

    def __getitem__(self, i):
        with Image.open(self.root / self.paths[i]) as im:
            im = im.convert("RGB")
        return self.transform(im), self.labels[i]


def make_data(cfg: dict, root: Path, stream_names: List[str]):
    """Returns (loaders, taxonomy). Labels in the loaders are LEAF indices (sub-folder classes)."""
    manifest = create_or_load_split(cfg, root, stream_names)
    tax = taxonomy_from_manifest(manifest)
    train_tf, eval_tf = build_transforms(cfg)
    bs = cfg["training"]["batch_size"]
    nw = 0 if sys.platform == "win32" else cfg["training"].get("num_workers", 2)
    pin = torch.cuda.is_available()
    loaders = {}

    # Class balancing for TRAIN only: sample leaf c with weight count_c ** -power (val/test stay untouched).
    power = float(cfg["training"].get("balance_power", 0.0))
    train_labels = manifest["train"]["labels"]
    sampler = None
    if power > 0 and train_labels:
        counts = np.bincount(train_labels, minlength=tax.num_leaves).astype(np.float64)
        per_class = np.where(counts > 0, counts ** -power, 0.0)
        sampler = WeightedRandomSampler(
            torch.as_tensor(per_class[np.asarray(train_labels)], dtype=torch.double),
            num_samples=len(train_labels), replacement=True,
            generator=torch.Generator().manual_seed(cfg["dataset"]["random_seed"]))
        share = (counts * per_class) / (counts * per_class).sum()
        by_stream = np.zeros(tax.num_streams)
        for leaf, sh in enumerate(share):
            by_stream[tax.leaf_to_stream[leaf]] += sh
        logger.info("Train sampling share per main category (balance_power=%.2f): %s", power,
                    {tax.streams[i]: round(float(by_stream[i]), 3) for i in range(tax.num_streams)})

    for split in ("train", "val", "test"):
        d = manifest[split]
        ds = WasteDataset(root, d["paths"], d["labels"], train_tf if split == "train" else eval_tf)
        use_sampler = split == "train" and sampler is not None
        loaders[split] = DataLoader(
            ds, batch_size=bs, shuffle=(split == "train" and not use_sampler), sampler=sampler if use_sampler else None,
            drop_last=(split == "train"), num_workers=nw, pin_memory=pin, persistent_workers=nw > 0)
    return loaders, tax


def make_loaders(cfg: dict, root: Path, class_names: List[str]) -> Dict[str, DataLoader]:
    return make_data(cfg, root, class_names)[0]


# --------------------------------------------------------------------------- #
# Model
# --------------------------------------------------------------------------- #
def build_model(arch: str, num_classes: int, pretrained: bool, dropout: float) -> nn.Module:
    return timm.create_model(arch, pretrained=pretrained, num_classes=num_classes, drop_rate=dropout)


def taxonomy_from_checkpoint(ckpt: dict) -> Taxonomy:
    if "streams" not in ckpt or "leaf_to_stream" not in ckpt:
        raise RuntimeError("This checkpoint was trained before the main-folder / sub-folder layout. Retrain: "
                           "python src/training/train_classifier.py")
    return Taxonomy(list(ckpt["streams"]), list(ckpt["classes"]), list(ckpt["leaf_to_stream"]))


def check_checkpoint_matches(ckpt: dict, tax: Taxonomy) -> None:
    """Fail loudly instead of 'mapping' an old model onto new classes (its numbers would be meaningless)."""
    if taxonomy_from_checkpoint(ckpt) != tax:
        raise RuntimeError(
            f"Checkpoint classes ({len(ckpt['classes'])}) do not match the current data layout ({tax.num_leaves}). "
            "Delete data/splits/split_manifest.json and retrain with train_classifier.py.")


def load_checkpoint(path: Path, device: torch.device):
    ckpt = torch.load(path, map_location=device, weights_only=False)
    model = build_model(ckpt["arch"], len(ckpt["classes"]), False, 0.0)
    model.load_state_dict(ckpt["model_state_dict"])
    return model.to(device).eval(), ckpt


@torch.no_grad()
def logits_for_tensor(model, x: torch.Tensor, device, tta: bool = False) -> torch.Tensor:
    x = x.to(device, non_blocking=True)
    out = model(x)
    if tta:
        out = (out + model(torch.flip(x, dims=[3]))) / 2
    return out.float().cpu()


@torch.no_grad()
def collect_logits(model, loader, device, tta: bool = False):
    model.eval()
    outs, tgts = [], []
    for x, y in loader:
        outs.append(logits_for_tensor(model, x, device, tta))
        tgts.append(torch.as_tensor(y))
    return torch.cat(outs), torch.cat(tgts)


# --------------------------------------------------------------------------- #
# Rejection policy: one definition used by calibrate / predict / export metadata
# --------------------------------------------------------------------------- #
def calibrated_probs(logits: torch.Tensor, temperature: float) -> torch.Tensor:
    return torch.softmax(logits / temperature, dim=1)


def stream_probs(leaf_logits: torch.Tensor, tax: Taxonomy, temperature: float = 1.0) -> torch.Tensor:
    """Probability of each MAIN category = sum of the probabilities of its sub-folder classes."""
    p = torch.softmax(leaf_logits / temperature, dim=1)
    m = torch.as_tensor(tax.matrix(), dtype=p.dtype, device=p.device)
    return p @ m


def confidence_stats(probs: torch.Tensor):
    top2 = probs.topk(2, dim=1).values
    entropy = -(probs * torch.log(probs.clamp_min(1e-9))).sum(1) / math.log(probs.shape[1])
    return top2[:, 0], top2[:, 0] - top2[:, 1], entropy


def accept_mask(probs: torch.Tensor, policy: dict, reject_idx: Optional[int] = None) -> torch.Tensor:
    """True where a prediction is trusted. `reject_idx` = index of the explicit "not_waste" class:
    a confident "not_waste" is a correct REJECTION, never an accepted waste item."""
    top1, margin, ent = confidence_stats(probs)
    mask = ((top1 >= policy["confidence_threshold"])
            & (margin >= policy["min_margin"])
            & (ent <= policy["max_norm_entropy"]))
    if reject_idx is not None:
        mask = mask & (probs.argmax(dim=1) != reject_idx)
    return mask
