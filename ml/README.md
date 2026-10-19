# wasteinfoAI — Machine Learning Pipeline

### PHASES 3–4: Waste Classification & On-Device Vision Deployment

This directory contains the complete computer vision pipeline for **wasteinfoAI**, focusing on on-device waste identification using a lightweight **MobileNetV3-Small** architecture, dataset auditing, training, held-out evaluation, and export to mobile runtime artifacts (`.pte`) via PyTorch and ExecuTorch.

---

## 1. Directory Structure

```text
ml/
├── README.md                          # Machine learning documentation and instructions
├── requirements.txt                   # ML dependencies (PyTorch, TorchVision, etc.)
├── configs/
│   └── classifier_config.yaml         # Training, dataset, and export configuration
├── data/
│   ├── raw/                           # Raw image datasets (keep large files outside Git)
│   ├── processed/                     # Cleaned, validated, and normalized images
│   └── splits/                        # Stratified train/, val/, and test/ splits
├── notebooks/
│   ├── 01_dataset_exploration.ipynb   # Dataset inspection, EDA, and class balance
│   └── 02_model_evaluation.ipynb      # Metrics, confusion matrix, threshold tuning
├── src/
│   ├── data/
│   │   ├── prepare_dataset.py         # Image validation, corruption removal, mapping
│   │   └── split_dataset.py           # Stratified train/val/test dataset splitting
│   ├── training/
│   │   ├── train_classifier.py        # MobileNetV3-Small baseline training routine
│   │   └── evaluate_classifier.py     # Held-out test evaluation & metrics computation
│   ├── inference/
│   │   └── predict.py                 # Single-image and batch inference CLI/module
│   └── export/
│       ├── export_model.py            # Export to mobile artifact (.pte) & ExecuTorch
│       └── validate_export.py         # Numerical parity and latency comparison
├── labels/
│   └── class_names.json               # 17 canonical project class labels
├── checkpoints/                       # Model weights (.pth); generated locally
├── exported/                          # Exported runtime models (.pte); generated locally
└── reports/
    └── evaluation_summary.json        # Test set metrics, confusion matrix, and gate summary
```

---

## 2. Waste Categories (Balanced Dataset)

The classification model recognizes **17 waste classes** present in `ml/data/balanced_waste_images`:

| Class ID | Name | Description |
|---|---|---|
| `0` | `Battery` | Household and rechargeable batteries. Hazardous handling. |
| `1` | `Keyboard` | Computer keyboards and electronic input peripherals. |
| `2` | `Microwave` | Small kitchen electronics and microwave ovens. |
| `3` | `Mobile` | Mobile phones, smartphones, portable communication devices. |
| `4` | `Mouse` | Computer pointing devices and accessories. |
| `5` | `PCB` | Printed circuit boards and electronic components. |
| `6` | `Player` | Audio/video media players, portable electronics. |
| `7` | `Printer` | Desktop printers, scanners, copiers. |
| `8` | `Television` | CRT, LCD, and LED monitors and television displays. |
| `9` | `Washing Machine` | Large home appliances and white goods. |
| `10` | `cardboard` | Corrugated cardboard boxes, packaging cartons. |
| `11` | `glass` | Glass bottles, containers, jars, and glassware. |
| `12` | `metal` | Aluminum cans, metal scrap, bottle caps, tins. |
| `13` | `organic` | Food scraps, compostable waste, plant matter. |
| `14` | `paper` | Printed paper, office paper, newspapers, magazines. |
| `15` | `plastic` | Bottles, containers, plastic bags, packaging polymers. |
| `16` | `trash` | Non-recyclable mixed refuse and inert waste. |

---

## 3. Environment Setup

Python **3.10** or **3.11** is recommended.

### Windows (PowerShell)
```powershell
# From repository root or ml/ directory:
py -3.10 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
python -m pip install -r ml/requirements.txt
```

For GPU acceleration (NVIDIA CUDA):
```powershell
pip install torch torchvision --index-url https://download.pytorch.org/whl/cu121
```

---

## 4. End-to-End ML Workflow

### Step 1: Prepare and Clean Raw Data
Place downloaded or curated images into `data/raw/<category>/`. Run the cleaner to filter corrupted images and convert formats:
```bash
python ml/src/data/prepare_dataset.py --raw-dir data/raw --processed-dir data/processed
```

### Step 2: Create Stratified Splits
Generate reproducible train (70%), validation (15%), and held-out test (15%) splits:
```bash
python ml/src/data/split_dataset.py --processed-dir data/processed --splits-dir data/splits --seed 42
```
> **Important:** The `test` split is strictly reserved and held out for final evaluation.

### Step 3: Train MobileNetV3-Small Classifier
Train the model with transfer learning:
```bash
python ml/src/training/train_classifier.py --config configs/classifier_config.yaml
```
- Pretrained backbone weights loaded with ImageNet initialization.
- Feature extractor frozen for initial epochs, followed by end-to-end fine-tuning.
- Checkpoints automatically saved to `ml/checkpoints/best_model.pth`.

### Step 4: Evaluate on Held-Out Test Data
Compute Accuracy, Macro-F1, per-class Precision/Recall, and confusion matrix:
```bash
python ml/src/training/evaluate_classifier.py --checkpoint checkpoints/best_model.pth
```
Results are saved to `ml/reports/evaluation_summary.json`.

### Step 5: Test Python Inference
Run test inference on an image:
```bash
python ml/src/inference/predict.py --image path/to/sample.jpg --threshold 0.60
```

### Step 6: Export for On-Device Deployment
Export model to `.pte` runtime artifact:
```bash
python ml/src/export/export_model.py --checkpoint checkpoints/best_model.pth --sync-android
```
The `--sync-android` flag automatically copies the exported `waste_classifier.pte` and `class_names.json` to `android/app/src/main/assets/vision/`.

### Step 7: Validate Export Parity
Verify numerical consistency between PyTorch and the exported runtime:
```bash
python ml/src/export/validate_export.py --num-samples 20
```

---

## 5. Acceptance Gates (Phases 3 & 4)

- **Phase 3 Gate:** Held-out test metrics and exact class mapping documented (`class_names.json`, `evaluation_summary.json`).
- **Phase 4 Gate:** On-device model exported (`waste_classifier.pte`), input preprocessing contract verified (224x224 RGB, ImageNet normalization), and top-1 agreement verified.
- **Safety Rule:** Confidence below threshold (0.60) triggers conservative uncertainty fallback; batteries and e-waste route to special handling guidance regardless of score.
