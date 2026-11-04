"""Dataset index: sub-folder classes ("leaves") grouped under the main categories ("streams") the app reports.

    data/waste/Recyclables/plastic/*.jpg   -> leaf "Recyclables/plastic"  -> stream "Recyclables"
    data/waste/hazardous/*.jpg             -> leaf "hazardous"             (images directly in the main folder)
    data/real_world/not_waste/*.jpg        -> leaf "not_waste"             -> stream "not_waste"

The model is TRAINED on the leaves (a plastic bottle and a newspaper are very different pictures, so separate classes
are easier to learn than one "Recyclables" blob). The app is told the STREAM: its probability is the sum of its
leaves' probabilities. No torch import here, so this module can be tested anywhere.
"""
import hashlib
import json
import logging
import random
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Sequence, Tuple

from PIL import Image
from sklearn.model_selection import train_test_split

IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}
MIN_PER_LEAF_FOR_SPLIT = 10  # smaller leaves go entirely to train (cannot be split three ways)
logger = logging.getLogger("dataset_index")


@dataclass(frozen=True)
class Taxonomy:
    streams: List[str]          # main categories, same order as labels/class_names.json
    leaves: List[str]           # trained classes
    leaf_to_stream: List[int]   # for every leaf, the index of its stream

    @property
    def num_leaves(self) -> int:
        return len(self.leaves)

    @property
    def num_streams(self) -> int:
        return len(self.streams)

    def stream_targets(self, leaf_targets: Sequence[int]) -> List[int]:
        return [self.leaf_to_stream[int(i)] for i in leaf_targets]

    def matrix(self) -> List[List[float]]:
        """leaves x streams, one-hot rows: leaf_probs @ matrix = stream_probs."""
        m = [[0.0] * self.num_streams for _ in self.leaves]
        for leaf, stream in enumerate(self.leaf_to_stream):
            m[leaf][stream] = 1.0
        return m

    def leaf_short_name(self, leaf_idx: int) -> str:
        name = self.leaves[leaf_idx]
        return name.split("/", 1)[1] if "/" in name else name


def taxonomy_from_manifest(manifest: dict) -> Taxonomy:
    return Taxonomy(list(manifest["streams"]), list(manifest["classes"]), list(manifest["leaf_to_stream"]))


def _images_in(path: Path, recursive: bool) -> List[Path]:
    it = path.rglob("*") if recursive else path.iterdir()
    return sorted(p for p in it if p.is_file() and p.suffix.lower() in IMAGE_EXTS)


def discover_leaves(roots: Sequence[Path], streams: Sequence[str]) -> Tuple[List[str], List[int], List[List[Path]]]:
    """Folder names are matched to stream names case-insensitively, across all [roots] (waste + real_world...)."""
    by_lower = {s.lower(): s for s in streams}
    stream_dirs: Dict[str, List[Path]] = {s: [] for s in streams}
    for r in roots:
        if r.exists():
            for d in sorted(r.iterdir()):
                if d.is_dir() and d.name.lower() in by_lower:
                    stream_dirs[by_lower[d.name.lower()]].append(d)

    leaves: List[str] = []
    leaf_to_stream: List[int] = []
    files: List[List[Path]] = []
    for s_idx, stream in enumerate(streams):
        dirs = stream_dirs[stream]
        if not dirs:
            raise FileNotFoundError(f"No folder named '{stream}' in {[str(r) for r in roots]}")
        loose: List[Path] = []
        sub_files: Dict[str, List[Path]] = {}
        sub_name: Dict[str, str] = {}
        for d in dirs:
            loose += _images_in(d, recursive=False)
            for sub in sorted(x for x in d.iterdir() if x.is_dir()):
                imgs = _images_in(sub, recursive=True)
                if imgs:
                    key = sub.name.lower()
                    sub_files.setdefault(key, []).extend(imgs)
                    sub_name.setdefault(key, sub.name)
        if sub_files:
            for key in sorted(sub_files):
                leaves.append(f"{stream}/{sub_name[key]}")
                leaf_to_stream.append(s_idx)
                files.append(sorted(sub_files[key]))
            if loose:  # images sitting directly in the main folder next to its sub-folders
                leaves.append(f"{stream}/_other")
                leaf_to_stream.append(s_idx)
                files.append(sorted(loose))
        elif loose:
            leaves.append(stream)
            leaf_to_stream.append(s_idx)
            files.append(sorted(loose))
        else:
            raise FileNotFoundError(f"'{stream}' exists but contains no images: {[str(d) for d in dirs]}")
    return leaves, leaf_to_stream, files


def _md5(path: Path) -> str:
    h = hashlib.md5()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def create_or_load_split(cfg: dict, root: Path, stream_names: List[str]) -> dict:
    ds = cfg["dataset"]
    manifest_path = root / ds["splits_dir"] / "split_manifest.json"
    if manifest_path.exists():
        with open(manifest_path, "r", encoding="utf-8") as f:
            manifest = json.load(f)
        if manifest.get("streams") != list(stream_names) or "leaf_to_stream" not in manifest:
            raise RuntimeError(
                f"{manifest_path} was built for a different class layout. Delete it and run again "
                f"(and retrain: an old checkpoint no longer matches).")
        return manifest

    image_dir = root / ds["image_dir"]
    if not image_dir.exists():
        raise FileNotFoundError(f"Dataset folder not found: {image_dir}")
    roots = [image_dir] + [root / d for d in ds.get("extra_image_dirs", []) if (root / d).exists()]
    extra_roots = set(roots[1:])
    leaves, leaf_to_stream, leaf_files = discover_leaves(roots, stream_names)

    paths: List[str] = []
    labels: List[int] = []
    extra_set = set()
    seen = set()
    dupes = bad = 0
    for idx, files in enumerate(leaf_files):
        for p in files:
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
            rel = p.relative_to(root).as_posix()
            paths.append(rel)
            labels.append(idx)
            if any(par in extra_roots for par in p.parents):
                extra_set.add(rel)
    logger.info("Found %d images in %d classes (%d exact duplicates and %d unreadable files skipped)",
                len(paths), len(leaves), dupes, bad)
    for i, name in enumerate(leaves):
        logger.info("  %-34s %5d images  -> %s", name, labels.count(i), stream_names[leaf_to_stream[i]])

    seed = ds["random_seed"]
    hold = ds["val_split"] + ds["test_split"]
    counts = Counter(labels)
    small = {k for k, c in counts.items() if c < MIN_PER_LEAF_FOR_SPLIT}
    if small:
        logger.warning("Classes with < %d images go entirely to train (no val/test): %s",
                       MIN_PER_LEAF_FOR_SPLIT, [leaves[k] for k in sorted(small)])
    big = [i for i, l in enumerate(labels) if l not in small]
    sm = [i for i, l in enumerate(labels) if l in small]
    bp, bl = [paths[i] for i in big], [labels[i] for i in big]
    tr_p, tmp_p, tr_l, tmp_l = train_test_split(bp, bl, test_size=hold, stratify=bl, random_state=seed)
    va_p, te_p, va_l, te_l = train_test_split(
        tmp_p, tmp_l, test_size=ds["test_split"] / hold, stratify=tmp_l, random_state=seed)
    tr_p = list(tr_p) + [paths[i] for i in sm]
    tr_l = list(tr_l) + [labels[i] for i in sm]

    # "not_waste" must not dominate TRAIN (a model that sees far more "not waste" than any waste class starts
    # rejecting real waste). Val/test keep all their not_waste photos so they stay honest.
    nw_leaves = {i for i, s in enumerate(leaf_to_stream) if stream_names[s] == "not_waste"}
    if nw_leaves:
        tc = Counter(tr_l)
        others = sorted(c for k, c in tc.items() if k not in nw_leaves)
        if others:
            cap = int(float(ds.get("not_waste_max_ratio", 2.0)) * others[len(others) // 2])
            nw_paths = [p for p, l in zip(tr_p, tr_l) if l in nw_leaves]
            if len(nw_paths) > cap:
                keep = set(random.Random(seed).sample(nw_paths, cap))
                pairs = [(p, l) for p, l in zip(tr_p, tr_l) if l not in nw_leaves or p in keep]
                tr_p, tr_l = [p for p, _ in pairs], [l for _, l in pairs]
                logger.info("not_waste train images capped at %d (had %d)", cap, len(nw_paths))

    # Real-world photos are few compared with the base dataset: repeat them in TRAIN only
    # (each repeat gets different random augmentation). not_waste is never repeated.
    rep = int(ds.get("extra_repeat", 3))
    if extra_set and rep > 1:
        extra_train = [(p, l) for p, l in zip(tr_p, tr_l) if p in extra_set and l not in nw_leaves]
        tr_p = tr_p + [p for p, _ in extra_train] * (rep - 1)
        tr_l = tr_l + [l for _, l in extra_train] * (rep - 1)
        logger.info("Real-world waste photos: %d in train (x%d)", len(extra_train), rep)

    manifest = {
        "streams": list(stream_names), "classes": leaves, "leaf_to_stream": leaf_to_stream,
        "train": {"paths": tr_p, "labels": tr_l},
        "val": {"paths": list(va_p), "labels": list(va_l)},
        "test": {"paths": list(te_p), "labels": list(te_l)},
    }
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    with open(manifest_path, "w", encoding="utf-8") as f:
        json.dump(manifest, f)
    logger.info("Split: train=%d val=%d test=%d -> %s", len(tr_p), len(va_p), len(te_p), manifest_path)
    return manifest
