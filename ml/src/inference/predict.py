"""Inference Module for Waste Classification.

Phase 3–4: Waste Classification & On-Device Parity Testing
Performs image preprocessing, single-image or batch prediction,
formats alternatives, confidence threshold checking, and inference timing.
Matches the WastePrediction contract expected by the Android application.
"""

import argparse
import json
import logging
import time
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Dict, List, Optional, Tuple, Union

from PIL import Image
import torch
import torch.nn as nn
import timm
from torchvision import models, transforms
import yaml

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("predict")


@dataclass
class WastePrediction:
    class_id: int
    label: str
    confidence: float
    alternatives: List[Dict[str, Union[str, float]]]
    is_low_confidence: bool
    inference_ms: float
    model_version: str


class WastePredictor:
    """Predictor for classifying waste images with MobileNetV3-Small."""

    def __init__(
        self,
        checkpoint_path: Optional[Union[str, Path]] = None,
        config_path: Optional[Union[str, Path]] = None,
        labels_path: Optional[Union[str, Path]] = None,
        confidence_threshold: float = 0.60,
        device: Optional[str] = None,
    ):
        base_dir = Path(__file__).resolve().parents[2]

        # Resolve config
        cfg_file = Path(config_path) if config_path else base_dir / "configs/classifier_config.yaml"
        if cfg_file.exists():
            with open(cfg_file, "r", encoding="utf-8") as f:
                self.config = yaml.safe_load(f)
        else:
            self.config = {
                "dataset": {
                    "image_size": 224,
                    "crop_padding": 32,
                    "normalization": {
                        "mean": [0.485, 0.456, 0.406],
                        "std": [0.229, 0.224, 0.225],
                    },
                },
                "model": {"num_classes": 9},
            }

        self.threshold = confidence_threshold or self.config.get("evaluation", {}).get(
            "confidence_threshold", 0.60
        )

        # Resolve device
        if device:
            self.device = torch.device(device)
        else:
            self.device = torch.device("cuda" if torch.cuda.is_available() else "cpu")

        # Resolve class names
        lbl_file = Path(labels_path) if labels_path else base_dir / "labels/class_names.json"
        self.class_names = self._load_classes(lbl_file)

        # Preprocessing transform
        mean = self.config["dataset"]["normalization"]["mean"]
        std = self.config["dataset"]["normalization"]["std"]
        img_size = self.config["dataset"]["image_size"]
        crop_padding = self.config["dataset"].get("crop_padding", 32)

        self.transform = transforms.Compose([
            transforms.Resize(img_size + crop_padding),
            transforms.CenterCrop(img_size),
            transforms.ToTensor(),
            transforms.Normalize(mean=mean, std=std),
        ])

        # Load model
        num_classes = len(self.class_names)
        self.model = self._build_model(num_classes)
        self.model_version = self.config.get("project", {}).get("version", "1.0.0")

        if checkpoint_path and Path(checkpoint_path).exists():
            ckpt = torch.load(checkpoint_path, map_location=self.device)
            state_dict = ckpt.get("model_state_dict", ckpt)
            self.model.load_state_dict(state_dict)
            logger.info("Loaded trained weights from: %s", checkpoint_path)
        else:
            logger.warning("No checkpoint provided or found; using initialized model weights.")

        self.model.to(self.device)
        self.model.eval()

    def _load_classes(self, labels_path: Path) -> List[str]:
        if labels_path.exists():
            with open(labels_path, "r", encoding="utf-8") as f:
                data = json.load(f)
            return [data[str(i)] for i in range(len(data))]
        return [
            "battery", "e_waste", "glass", "metal",
            "organic", "paper_cardboard", "plastic", "textile", "other"
        ]

    def _build_model(self, num_classes: int) -> nn.Module:
        arch = self.config.get("model", {}).get("architecture", "rexnet_150")
        if "rexnet" in arch.lower():
            return timm.create_model("rexnet_150", pretrained=False, num_classes=num_classes)
        model = models.mobilenet_v3_small(weights=None)
        in_features = model.classifier[3].in_features
        model.classifier[3] = nn.Linear(in_features, num_classes)
        return model

    def predict(self, image_input: Union[str, Path, Image.Image]) -> WastePrediction:
        """Run classification on a single image.

        Args:
            image_input: File path or PIL Image instance.

        Returns:
            WastePrediction object with predicted label, confidence, alternatives.
        """
        if isinstance(image_input, (str, Path)):
            pil_img = Image.open(image_input).convert("RGB")
        else:
            pil_img = image_input.convert("RGB")

        tensor = self.transform(pil_img).unsqueeze(0).to(self.device)

        start_t = time.perf_counter()
        with torch.no_grad():
            logits = self.model(tensor)
            probs = torch.softmax(logits, dim=1).squeeze(0)
        inference_ms = (time.perf_counter() - start_t) * 1000.0

        top_indices = torch.argsort(probs, descending=True).cpu().tolist()
        top_idx = top_indices[0]
        confidence = float(probs[top_idx].item())
        label = self.class_names[top_idx]

        # Alternatives (indices 1..3)
        alternatives = []
        for idx in top_indices[1:4]:
            alternatives.append({
                "class_id": idx,
                "label": self.class_names[idx],
                "confidence": round(float(probs[idx].item()), 4),
            })

        is_low_confidence = confidence < self.threshold

        return WastePrediction(
            class_id=top_idx,
            label=label,
            confidence=round(confidence, 4),
            alternatives=alternatives,
            is_low_confidence=is_low_confidence,
            inference_ms=round(inference_ms, 2),
            model_version=self.model_version,
        )


def main() -> None:
    parser = argparse.ArgumentParser(description="Predict waste class from image")
    parser.add_argument("--image", type=str, required=True, help="Path to input image")
    parser.add_argument("--checkpoint", type=str, default="checkpoints/best_model.pth", help="Model checkpoint")
    parser.add_argument("--threshold", type=float, default=0.60, help="Confidence threshold")
    parser.add_argument("--json", action="store_true", help="Output raw JSON")
    args = parser.parse_args()

    base_dir = Path(__file__).resolve().parents[2]
    ckpt_path = base_dir / args.checkpoint

    predictor = WastePredictor(
        checkpoint_path=ckpt_path if ckpt_path.exists() else None,
        confidence_threshold=args.threshold,
    )

    pred = predictor.predict(args.image)

    if args.json:
        print(json.dumps(asdict(pred), indent=2))
    else:
        logger.info("=" * 50)
        logger.info("WASTE CLASSIFICATION RESULT")
        logger.info("=" * 50)
        logger.info("Predicted Class:  %s (ID: %d)", pred.label.upper(), pred.class_id)
        logger.info("Confidence:       %.2f%%", pred.confidence * 100)
        logger.info("Inference Time:   %.2f ms", pred.inference_ms)
        logger.info("Low Confidence?   %s", "YES (Uncertain)" if pred.is_low_confidence else "NO")
        logger.info("Top Alternatives:")
        for alt in pred.alternatives:
            logger.info("  - %s: %.2f%%", alt["label"], alt["confidence"] * 100)
        logger.info("=" * 50)


if __name__ == "__main__":
    main()
