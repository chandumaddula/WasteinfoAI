"""Validate numerical parity and top-1 agreement between PyTorch ReXNet-150 reference and exported .pte."""
import json
import logging
from pathlib import Path
import numpy as np
import timm
import torch
from executorch.runtime import Runtime

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("validate_rexnet")


def main():
    root_dir = Path(__file__).resolve().parents[3]
    ckpt_path = root_dir / "ml" / "saved_models" / "waste_best_model.pth"
    pte_path = root_dir / "android" / "app" / "src" / "main" / "assets" / "vision" / "waste_classifier.pte"
    labels_path = root_dir / "ml" / "labels" / "class_names.json"

    with open(labels_path, "r", encoding="utf-8") as f:
        labels_map = json.load(f)
    num_classes = len(labels_map)

    logger.info("Loading PyTorch reference ReXNet-150 model...")
    pt_model = timm.create_model("rexnet_150", pretrained=False, num_classes=num_classes)
    sd = torch.load(ckpt_path, map_location="cpu")
    if "model_state_dict" in sd:
        sd = sd["model_state_dict"]
    pt_model.load_state_dict(sd)
    pt_model.eval()

    logger.info("Loading ExecuTorch runtime for: %s", pte_path)
    runtime = Runtime.get()
    program = runtime.load_program(str(pte_path))
    method = program.load_method("forward")

    # Run 10 parity tests
    top1_matches = 0
    max_abs_errors = []

    torch.manual_seed(42)
    for i in range(10):
        test_input = torch.randn(1, 3, 224, 224)

        # PyTorch reference forward
        with torch.no_grad():
            pt_out = pt_model(test_input).numpy()

        # ExecuTorch forward
        et_out = method.execute([test_input])[0].numpy()

        diff = np.abs(pt_out - et_out)
        max_diff = float(np.max(diff))
        max_abs_errors.append(max_diff)

        pt_top1 = int(np.argmax(pt_out))
        et_top1 = int(np.argmax(et_out))

        if pt_top1 == et_top1:
            top1_matches += 1

        logger.info(
            "Test %2d: PT top1=%s (%d), ET top1=%s (%d), max_diff=%.6f",
            i + 1,
            labels_map[str(pt_top1)],
            pt_top1,
            labels_map[str(et_top1)],
            et_top1,
            max_diff,
        )

    logger.info("=== Parity Summary ===")
    logger.info("Top-1 Agreement: %d / 10 (%.1f%%)", top1_matches, (top1_matches / 10.0) * 100)
    logger.info("Average Max Absolute Error: %.7f", float(np.mean(max_abs_errors)))
    logger.info("Peak Max Absolute Error: %.7f", float(np.max(max_abs_errors)))


if __name__ == "__main__":
    main()
