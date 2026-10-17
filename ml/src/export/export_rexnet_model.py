"""Export verified ReXNet-150 waste classifier checkpoint to ExecuTorch .pte artifact."""
import json
import logging
import shutil
from pathlib import Path

import timm
import torch
from torch.export import export
from executorch.exir import to_edge, EdgeCompileConfig

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("export_rexnet")


def main():
    root_dir = Path(__file__).resolve().parents[3]
    checkpoint_path = root_dir / "ml" / "saved_models" / "waste_best_model.pth"
    labels_path = root_dir / "ml" / "labels" / "class_names.json"
    exported_dir = root_dir / "ml" / "exported"
    exported_dir.mkdir(parents=True, exist_ok=True)
    out_pte_path = exported_dir / "waste_classifier.pte"
    out_meta_path = exported_dir / "model_metadata.json"

    android_vision_dir = root_dir / "android" / "app" / "src" / "main" / "assets" / "vision"
    android_vision_dir.mkdir(parents=True, exist_ok=True)

    logger.info("Loading class labels from: %s", labels_path)
    with open(labels_path, "r", encoding="utf-8") as f:
        labels_map = json.load(f)
    num_classes = len(labels_map)
    classes_list = [labels_map[str(i)] for i in range(num_classes)]
    logger.info("Verified %d classes: %s", num_classes, classes_list)

    logger.info("Building ReXNet-150 model...")
    model = timm.create_model("rexnet_150", pretrained=False, num_classes=num_classes)

    logger.info("Loading checkpoint weights from: %s", checkpoint_path)
    state_dict = torch.load(checkpoint_path, map_location="cpu")
    if "model_state_dict" in state_dict:
        state_dict = state_dict["model_state_dict"]
    model.load_state_dict(state_dict)
    model.eval()

    example_input = (torch.randn(1, 3, 224, 224),)

    logger.info("Running PyTorch 2 torch.export...")
    exported_prog = export(model, example_input)

    logger.info("Converting to Edge IR...")
    edge_prog = to_edge(exported_prog, compile_config=EdgeCompileConfig())

    logger.info("Converting to ExecuTorch program...")
    exec_prog = edge_prog.to_executorch()

    logger.info("Writing ExecuTorch artifact to: %s", out_pte_path)
    with open(out_pte_path, "wb") as f:
        f.write(exec_prog.buffer)
    logger.info("Successfully exported .pte file! Size: %d bytes (%.2f MB)", len(exec_prog.buffer), len(exec_prog.buffer) / (1024 * 1024))

    metadata = {
        "model_name": "wasteinfoAI-rexnet150",
        "architecture": "rexnet_150",
        "runtime": "executorch",
        "input_shape": [1, 3, 224, 224],
        "normalization": {
            "mean": [0.485, 0.456, 0.406],
            "std": [0.229, 0.224, 0.225]
        },
        "num_classes": num_classes,
        "classes": classes_list,
        "size_bytes": len(exec_prog.buffer)
    }

    with open(out_meta_path, "w", encoding="utf-8") as f:
        json.dump(metadata, f, indent=2)

    # Sync to Android assets
    android_pte = android_vision_dir / "waste_classifier.pte"
    shutil.copy2(out_pte_path, android_pte)
    logger.info("Copied .pte model to Android assets: %s", android_pte)

    android_labels = android_vision_dir / "class_names.json"
    shutil.copy2(labels_path, android_labels)
    logger.info("Copied class_names.json to Android assets: %s", android_labels)

    android_meta = android_vision_dir / "model_metadata.json"
    shutil.copy2(out_meta_path, android_meta)
    logger.info("Copied model_metadata.json to Android assets: %s", android_meta)


if __name__ == "__main__":
    main()
