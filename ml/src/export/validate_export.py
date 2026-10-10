"""Export Validation and Parity Verification Script.

Phase 4: On-Device Vision Deployment
Validates numerical parity, top-1 class agreement, and max absolute difference
between the reference PyTorch model and the exported mobile runtime artifact (.pte).
"""

import argparse
import json
import logging
import sys
import time
from pathlib import Path
from typing import Dict, List, Tuple

import numpy as np
import torch
import torch.nn as nn
from torchvision import models
import yaml

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("validate_export")


def load_config(config_path: Path) -> dict:
    """Load configuration YAML."""
    with open(config_path, "r", encoding="utf-8") as f:
        return yaml.safe_load(f)


def load_pytorch_reference(
    checkpoint_path: Path,
    num_classes: int,
) -> nn.Module:
    """Load PyTorch reference model."""
    model = models.mobilenet_v3_small(weights=None)
    in_features = model.classifier[3].in_features
    model.classifier[3] = nn.Linear(in_features, num_classes)

    if checkpoint_path.exists():
        ckpt = torch.load(checkpoint_path, map_location="cpu")
        state_dict = ckpt.get("model_state_dict", ckpt)
        model.load_state_dict(state_dict)
    model.eval()
    return model


def run_pytorch_inference(model: nn.Module, tensor: torch.Tensor) -> Tuple[np.ndarray, float]:
    """Run inference with reference PyTorch model and record time."""
    start_t = time.perf_counter()
    with torch.no_grad():
        out = model(tensor)
    duration_ms = (time.perf_counter() - start_t) * 1000.0
    return out.numpy(), duration_ms


def run_exported_inference(exported_path: Path, tensor: torch.Tensor) -> Tuple[np.ndarray, float]:
    """Run inference using the exported artifact (.pte / TorchScript / ExecuTorch)."""
    # Try ExecuTorch runtime if available
    try:
        from executorch.extension.pybindings.portable_lib import _load_for_executorch_from_buffer
        with open(exported_path, "rb") as f:
            buf = f.read()
        et_module = _load_for_executorch_from_buffer(buf)
        start_t = time.perf_counter()
        out = et_module.forward((tensor,))
        duration_ms = (time.perf_counter() - start_t) * 1000.0
        return out[0].numpy(), duration_ms
    except (ImportError, Exception):
        pass

    # Fallback to PyTorch JIT / TorchScript loader
    try:
        loaded_model = torch.jit.load(str(exported_path), map_location="cpu")
        loaded_model.eval()
        start_t = time.perf_counter()
        with torch.no_grad():
            out = loaded_model(tensor)
        duration_ms = (time.perf_counter() - start_t) * 1000.0
        return out.numpy(), duration_ms
    except Exception as exc:
        raise RuntimeError(f"Failed to load exported artifact '{exported_path}': {exc}")


def compare_outputs(
    ref_out: np.ndarray,
    exp_out: np.ndarray,
) -> Dict[str, float]:
    """Calculate numerical differences and agreement metrics."""
    diff = np.abs(ref_out - exp_out)
    max_err = float(np.max(diff))
    mean_err = float(np.mean(diff))

    ref_top1 = int(np.argmax(ref_out, axis=1)[0])
    exp_top1 = int(np.argmax(exp_out, axis=1)[0])
    top1_agreement = 1.0 if ref_top1 == exp_top1 else 0.0

    return {
        "max_absolute_error": max_err,
        "mean_absolute_error": mean_err,
        "ref_top1": ref_top1,
        "exp_top1": exp_top1,
        "top1_match": top1_agreement,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Validate numerical parity between reference and exported model")
    parser.add_argument("--config", type=str, default="configs/classifier_config.yaml", help="Path to config")
    parser.add_argument("--checkpoint", type=str, default="checkpoints/best_model.pth", help="PyTorch checkpoint")
    parser.add_argument("--exported", type=str, default="exported/waste_classifier.pte", help="Exported artifact")
    parser.add_argument("--num-samples", type=int, default=20, help="Number of random test samples to evaluate")
    args = parser.parse_args()

    base_dir = Path(__file__).resolve().parents[2]
    config = load_config(base_dir / args.config)

    checkpoint_path = base_dir / args.checkpoint
    exported_path = base_dir / args.exported

    if not exported_path.exists():
        logger.error("Exported model not found at: %s. Run export_model.py first.", exported_path)
        return

    num_classes = config["model"]["num_classes"]
    ref_model = load_pytorch_reference(checkpoint_path, num_classes)

    logger.info("Running export validation across %d representative inputs...", args.num_samples)

    total_top1_matches = 0
    max_errors = []
    ref_latencies = []
    exp_latencies = []

    for i in range(args.num_samples):
        dummy_input = torch.randn(1, 3, 224, 224)

        ref_out, ref_time = run_pytorch_inference(ref_model, dummy_input)
        exp_out, exp_time = run_exported_inference(exported_path, dummy_input)

        stats = compare_outputs(ref_out, exp_out)

        total_top1_matches += int(stats["top1_match"])
        max_errors.append(stats["max_absolute_error"])
        ref_latencies.append(ref_time)
        exp_latencies.append(exp_time)

    top1_rate = (total_top1_matches / args.num_samples) * 100.0
    overall_max_err = max(max_errors)
    avg_ref_lat = sum(ref_latencies) / len(ref_latencies)
    avg_exp_lat = sum(exp_latencies) / len(exp_latencies)

    logger.info("=" * 64)
    logger.info("EXPORT PARITY VALIDATION RESULTS")
    logger.info("=" * 64)
    logger.info("Evaluated Samples:          %d", args.num_samples)
    logger.info("Top-1 Agreement Rate:       %.1f%%", top1_rate)
    logger.info("Max Absolute Logit Error:   %.6f", overall_max_err)
    logger.info("Mean PyTorch Latency:       %.2f ms", avg_ref_lat)
    logger.info("Mean Exported Latency:      %.2f ms", avg_exp_lat)
    logger.info("-" * 64)

    is_passed = (top1_rate == 100.0) and (overall_max_err < 1e-3)
    if is_passed:
        logger.info("ACCEPTANCE GATE: [PASSED] Model matches reference outputs with high parity.")
    else:
        logger.warning("ACCEPTANCE GATE: [WARNING] Discrepancy observed. Inspect quantization/lowering.")
    logger.info("=" * 64)


if __name__ == "__main__":
    main()
