"""Shared data / model / rejection-policy utilities.

train, evaluate, calibrate, export and predict all import from here, so the
split, preprocessing, class order and acceptance rule can never drift apart.
"""
import hashlib
import json
import logging
import math
import random
import sys
from pathlib import Path
from typing import Dict, List

import numpy as np
import timm
import torch
import torch.nn as nn
import yaml
from PIL import Image
from sklearn.model_selection import train_test_split
from torch.utils.data import DataLoader, Dataset
from torchvision import transforms as T

IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}
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


# --------------------------------------------------------------------------- #
# Split (created once, then reused by every script)
# --------------------------------------------------------------------------- #
def _md5(path: Path) -> str:
    h = hashlib.md5()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def create_or_load_split(cfg: dict, root: Path, class_names: List[str]) -> dict:
    ds = cfg["dataset"]
    manifest_path = root / ds["splits_dir"] / "split_manifest.json"
    if manifest_path.exists():
        with open(manifest_path, "r", encoding="utf-8") as f:
            manifest = json.load(f)
        if manifest["classes"] != class_names:
            raise RuntimeError(f"{manifest_path} was built for different classes; delete it to rebuild.")
        return manifest

    image_dir = root / ds["image_dir"]
    if not image_dir.exists():
        raise FileNotFoundError(f"Dataset folder not found: {image_dir}")
    folders = {p.name.lower(): p for p in image_dir.iterdir() if p.is_dir()}

    paths, labels, seen, dupes, bad = [], [], set(), 0, 0
    for idx, name in enumerate(class_names):
        folder = folders.get(name.lower())
        if folder is None:
            raise FileNotFoundError(f"No folder for class '{name}' in {image_dir}. Found: {sorted(folders)}")
        for p in sorted(folder.rglob("*")):
            if p.suffix.lower() not in IMAGE_EXTS:
                continue
            try:
                with Image.open(p) as im:
                    im.verify()
            except Exception:
                bad += 1
                continue
            digest = _md5(p)
            if digest in seen:      # exact duplicates would leak between train and test
                dupes += 1
                continue
            seen.add(digest)
            paths.append(p.relative_to(root).as_posix())
            labels.append(idx)
    logger.info("Found %d images (%d exact duplicates and %d unreadable files skipped)", len(paths), dupes, bad)

    seed = ds["random_seed"]
    hold = ds["val_split"] + ds["test_split"]
    tr_p, tmp_p, tr_l, tmp_l = train_test_split(paths, labels, test_size=hold, stratify=labels, random_state=seed)
    va_p, te_p, va_l, te_l = train_test_split(
        tmp_p, tmp_l, test_size=ds["test_split"] / hold, stratify=tmp_l, random_state=seed)

    manifest = {
        "classes": class_names,
        "train": {"paths": tr_p, "labels": tr_l},
        "val": {"paths": va_p, "labels": va_l},
        "test": {"paths": te_p, "labels": te_l},
    }
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    with open(manifest_path, "w", encoding="utf-8") as f:
        json.dump(manifest, f)
    logger.info("Split: train=%d val=%d test=%d -> %s", len(tr_p), len(va_p), len(te_p), manifest_path)
    return manifest


class WasteDataset(Dataset):
    def __init__(self, root: Path, paths: List[str], labels: List[int], transform):
        self.root, self.paths, self.labels, self.transform = root, paths, labels, transform

    def __len__(self):
        return len(self.paths)

    def __getitem__(self, i):
        with Image.open(self.root / self.paths[i]) as im:
            im = im.convert("RGB")
        return self.transform(im), self.labels[i]


def make_loaders(cfg: dict, root: Path, class_names: List[str]) -> Dict[str, DataLoader]:
    manifest = create_or_load_split(cfg, root, class_names)
    train_tf, eval_tf = build_transforms(cfg)
    bs = cfg["training"]["batch_size"]
    nw = 0 if sys.platform == "win32" else cfg["training"].get("num_workers", 2)
    pin = torch.cuda.is_available()
    loaders = {}
    for split in ("train", "val", "test"):
        d = manifest[split]
        ds = WasteDataset(root, d["paths"], d["labels"], train_tf if split == "train" else eval_tf)
        loaders[split] = DataLoader(
            ds, batch_size=bs, shuffle=(split == "train"), drop_last=(split == "train"),
            num_workers=nw, pin_memory=pin, persistent_workers=nw > 0)
    return loaders


# --------------------------------------------------------------------------- #
# Model
# --------------------------------------------------------------------------- #
def build_model(arch: str, num_classes: int, pretrained: bool, dropout: float) -> nn.Module:
    return timm.create_model(arch, pretrained=pretrained, num_classes=num_classes, drop_rate=dropout)


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


def confidence_stats(probs: torch.Tensor):
    top2 = probs.topk(2, dim=1).values
    entropy = -(probs * torch.log(probs.clamp_min(1e-9))).sum(1) / math.log(probs.shape[1])
    return top2[:, 0], top2[:, 0] - top2[:, 1], entropy


def accept_mask(probs: torch.Tensor, policy: dict) -> torch.Tensor:
    top1, margin, ent = confidence_stats(probs)
    return ((top1 >= policy["confidence_threshold"])
            & (margin >= policy["min_margin"])
            & (ent <= policy["max_norm_entropy"]))
