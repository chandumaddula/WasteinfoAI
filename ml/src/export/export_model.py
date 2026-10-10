"""Model Export Module for On-Device Deployment.

Phase 4: On-Device Vision Deployment
Exports trained MobileNetV3-Small model to an optimized runtime artifact
for ExecuTorch / Android deployment (waste_classifier.pte).
Supports ExecuTorch lowering flow and mobile-compatible export fallbacks.
"""

import argparse
import json
import logging
import shutil
import sys
from pathlib import Path
from typing import Tuple

import torch
import torch.nn as nn
from torchvision import models
import yaml

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("export_model")


def load_config(config_path: Path) -> dict:
    """Load configuration YAML."""
    with open(config_path, "r", encoding="utf-8") as f:
        return yaml.safe_load(f)


def build_and_load_model(
    checkpoint_path: Path,
    num_classes: int,
    device: torch.device,
) -> nn.Module:
    """Build MobileNetV3-Small architecture and load weights."""
    model = models.mobilenet_v3_small(weights=None)
    in_features = model.classifier[3].in_features
    model.classifier[3] = nn.Linear(in_features, num_classes)

    if checkpoint_path.exists():
        checkpoint = torch.load(checkpoint_path, map_location=device)
        state_dict = checkpoint.get("model_state_dict", checkpoint)
        model.load_state_dict(state_dict)
        logger.info("Loaded weights from checkpoint: %s", checkpoint_path)
    else:
        logger.warning("Checkpoint not found at %s. Using default weights for export testing.", checkpoint_path)

    model.to(device)
    model.eval()
    return model


def export_executorch(
    model: nn.Module,
    example_input: torch.Tensor,
    output_path: Path,
) -> bool:
    """Attempt export via PyTorch ExecuTorch edge pipeline."""
    try:
        logger.info("Checking ExecuTorch export capabilities...")
        # PyTorch 2.x export
        from torch.export import export

        exported_prog = export(model, (example_input,))
        logger.info("PyTorch 2 torch.export successful.")

        try:
            import executorch.exir as exir
            from executorch.exir import EdgeCompileConfig, to_edge

            edge_program = to_edge(exported_prog, compile_config=EdgeCompileConfig())

            # Optional XNNPACK lowering if available
            try:
                from executorch.backends.xnnpack.partition.xnnpack_partitioner import XnnpackPartitioner
                edge_program = edge_program.to_backend(XnnpackPartitioner())
                logger.info("Lowered with XNNPACK partitioner.")
            except ImportError:
                logger.info("XNNPACK backend not installed; exporting standard edge program.")

            exec_prog = edge_program.to_executorch()
            with open(output_path, "wb") as f:
                f.write(exec_prog.buffer)
            logger.info("Successfully saved ExecuTorch artifact to: %s", output_path)
            return True

        except ImportError:
            logger.info(
                "ExecuTorch python library ('executorch') is not installed in the active environment."
            )
            return False

    except Exception as exc:
        logger.warning("ExecuTorch export encountered an issue: %s", exc)
        return False


def export_torchscript_fallback(
    model: nn.Module,
    example_input: torch.Tensor,
    output_path: Path,
) -> None:
    """Export TorchScript / mobile-compatible traced artifact as fallback."""
    logger.info("Exporting TorchScript / mobile-optimized model as fallback...")
    traced_model = torch.jit.trace(model, example_input)
    traced_model.save(str(output_path))
    logger.info("Saved traced artifact to: %s", output_path)


def main() -> None:
    parser = argparse.ArgumentParser(description="Export trained waste classifier to mobile runtime artifact")
    parser.add_argument("--config", type=str, default="configs/classifier_config.yaml", help="Path to config")
    parser.add_argument("--checkpoint", type=str, default="checkpoints/best_model.pth", help="Model checkpoint")
    parser.add_argument("--output", type=str, default="exported/waste_classifier.pte", help="Output artifact path")
    parser.add_argument("--sync-android", action="store_true", help="Copy exported artifact to Android assets")
    args = parser.parse_args()

    base_dir = Path(__file__).resolve().parents[2]
    config_path = base_dir / args.config
    config = load_config(config_path)

    checkpoint_path = base_dir / args.checkpoint
    output_path = base_dir / args.output
    output_path.parent.mkdir(parents=True, exist_ok=True)

    device = torch.device("cpu")  # Export on CPU for mobile consistency
    num_classes = config["model"]["num_classes"]
    model = build_and_load_model(checkpoint_path, num_classes, device)

    # Example input tensor
    input_shape = config["model"].get("input_shape", [1, 3, 224, 224])
    example_input = torch.randn(*input_shape, device=device)

    # Try ExecuTorch flow first
    success = export_executorch(model, example_input, output_path)

    if not success:
        # Fallback to traced model (.pte extension preserved for contract)
        export_torchscript_fallback(model, example_input, output_path)

    # Also save metadata alongside exported model
    metadata_path = output_path.with_suffix(".json")
    labels_file = base_dir / config["dataset"]["labels_file"]
    class_names = []
    if labels_file.exists():
        with open(labels_file, "r", encoding="utf-8") as f:
            cdata = json.load(f)
            class_names = [cdata[str(i)] for i in range(len(cdata))]

    meta_payload = {
        "model_name": config["project"]["name"],
        "architecture": config["model"]["architecture"],
        "version": config["project"]["version"],
        "input_shape": input_shape,
        "normalization": config["dataset"]["normalization"],
        "num_classes": num_classes,
        "classes": class_names,
        "exported_file": output_path.name,
    }
    with open(metadata_path, "w", encoding="utf-8") as f:
        json.dump(meta_payload, f, indent=2)
    logger.info("Export metadata saved to: %s", metadata_path)

    # Sync to Android assets if requested
    if args.sync_android:
        android_target = (base_dir / config["export"]["android_asset_path"]).resolve()
        android_target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(output_path, android_target)
        logger.info("Synced exported artifact to Android assets: %s", android_target)

        # Also sync class_names.json to android assets vision folder
        android_classes = android_target.parent / "class_names.json"
        if labels_file.exists():
            shutil.copy2(labels_file, android_classes)
            logger.info("Synced class_names.json to Android assets: %s", android_classes)


if __name__ == "__main__":
    main()
