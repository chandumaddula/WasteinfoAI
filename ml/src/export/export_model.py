"""Export the calibrated model (softmax + temperature baked in) for ExecuTorch / Android."""
import argparse
import json
import logging
import shutil
import sys
from pathlib import Path

import torch
import torch.nn as nn

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from src.common import load_checkpoint, load_config, project_root  # noqa: E402

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("export_model")


class CalibratedModel(nn.Module):
    """Outputs calibrated probabilities so the app applies thresholds directly."""

    def __init__(self, model, temperature: float):
        super().__init__()
        self.model = model
        self.register_buffer("t", torch.tensor(float(temperature)))

    def forward(self, x):
        return torch.softmax(self.model(x) / self.t, dim=1)


def export_executorch(module, example, out_path: Path) -> bool:
    try:
        from torch.export import export
        from executorch.exir import EdgeCompileConfig, to_edge
    except ImportError as exc:
        logger.warning("ExecuTorch not available (%s).", exc)
        return False
    try:
        program = export(module, (example,))
        with torch.no_grad():  # parity check: exported graph must match eager model
            diff = (program.module()(example) - module(example)).abs().max().item()
        logger.info("torch.export parity max|diff| = %.2e", diff)
        edge = to_edge(program, compile_config=EdgeCompileConfig())
        try:
            from executorch.backends.xnnpack.partition.xnnpack_partitioner import XnnpackPartitioner
            edge = edge.to_backend(XnnpackPartitioner())
            logger.info("Lowered with XNNPACK.")
        except ImportError:
            logger.info("XNNPACK partitioner not installed; exporting portable program.")
        out_path.write_bytes(edge.to_executorch().buffer)
        return True
    except Exception as exc:
        logger.warning("ExecuTorch export failed: %s", exc)
        return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", default="configs/classifier_config.yaml")
    ap.add_argument("--checkpoint", default="checkpoints/best_model.pth")
    ap.add_argument("--sync-android", action="store_true")
    args = ap.parse_args()

    root = project_root()
    cfg = load_config(root / args.config)
    out_dir = root / cfg["export"]["exported_dir"]
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / cfg["export"]["output_model_name"]

    model, ckpt = load_checkpoint(root / args.checkpoint, torch.device("cpu"))
    policy_path = root / cfg["evaluation"]["policy_file"]
    policy = json.loads(policy_path.read_text()) if policy_path.exists() else {}
    if not policy:
        logger.warning("No rejection policy found; run calibrate_rejection_policy.py first. Using T=1.")
    module = CalibratedModel(model, policy.get("temperature", 1.0)).eval()
    example = torch.randn(*cfg["model"]["input_shape"])

    if not export_executorch(module, example, out_path):
        # A TorchScript file is NOT loadable by ExecuTorch, so do not give it a .pte name.
        out_path = out_path.with_suffix(".pt")
        torch.jit.trace(module, example).save(str(out_path))
        logger.warning("Saved TorchScript fallback %s (use PyTorch Mobile, not ExecuTorch).", out_path)

    meta = {
        "architecture": ckpt["arch"], "classes": ckpt["classes"], "input_shape": cfg["model"]["input_shape"],
        "preprocess": {"resize": [ckpt["image_size"], ckpt["image_size"]], "center_crop": False,
                       "normalization": ckpt["norm"]},
        "output": "calibrated softmax probabilities",
        "rejection_policy": {k: policy[k] for k in ("confidence_threshold", "min_margin", "max_norm_entropy")
                             if k in policy},
        "exported_file": out_path.name,
    }
    out_path.with_suffix(".json").write_text(json.dumps(meta, indent=2), encoding="utf-8")

    if args.sync_android:
        target = (root / cfg["export"]["android_asset_path"]).resolve()
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(out_path, target.with_name(out_path.name))
        shutil.copy2(out_path.with_suffix(".json"), target.parent / "model_meta.json")
        shutil.copy2(root / cfg["dataset"]["labels_file"], target.parent / "class_names.json")
        logger.info("Synced to %s", target.parent)


if __name__ == "__main__":
    main()
