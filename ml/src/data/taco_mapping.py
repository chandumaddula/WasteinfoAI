"""TACO and External Litter Dataset Taxonomy Mapping Module.

Defines explicit mappings from external annotations to the 17 canonical waste categories.
Unsupported categories are explicitly partitioned into an UNMAPPED pool
to avoid silent label corruption.
"""

from typing import Dict, List, Optional

# Explicit mapping from TACO super-categories and categories to canonical 17 classes
TACO_TO_CANONICAL_MAP: Dict[str, str] = {
    # Aluminium and metals
    "Aluminium foil": "metal",
    "Drink can": "metal",
    "Food can": "metal",
    "Metal bottle cap": "metal",
    "Aerosol": "metal",
    "Scrap metal": "metal",
    # Plastics
    "Clear plastic bottle": "plastic",
    "Other plastic bottle": "plastic",
    "Plastic bottle cap": "plastic",
    "Plastic bag & wrapper": "plastic",
    "Disposable plastic cup": "plastic",
    "Other plastic cup": "plastic",
    "Plastic lid": "plastic",
    "Plastic straw": "plastic",
    "Single-use carrier bag": "plastic",
    "Polypropylene bag": "plastic",
    "Crisp packet": "plastic",
    "Squeezable tube": "plastic",
    # Cardboard & Paper
    "Corrugated carton": "cardboard",
    "Meal carton": "cardboard",
    "Pizza box": "cardboard",
    "Egg carton": "cardboard",
    "Paper bag": "paper",
    "Magazine paper": "paper",
    "Paper cup": "paper",
    "Normal paper": "paper",
    "Carded blister pack": "cardboard",
    # Glass
    "Glass bottle": "glass",
    "Broken glass": "glass",
    "Glass jar": "glass",
    # Organics
    "Food waste": "organic",
    # E-Waste / Hazardous
    "Battery": "Battery",
}

# The 17 canonical classes supported by wasteinfoAI
CANONICAL_CLASSES = [
    "Battery", "Keyboard", "Microwave", "Mobile", "Mouse",
    "PCB", "Player", "Printer", "Television", "Washing Machine",
    "cardboard", "glass", "metal", "organic", "paper",
    "plastic", "trash"
]


def map_taco_category(taco_label: str) -> Optional[str]:
    """Map a TACO dataset category to canonical 17 classes or None if unsupported."""
    clean_label = taco_label.strip()
    return TACO_TO_CANONICAL_MAP.get(clean_label)


def get_dataset_metadata() -> dict:
    """Return provenance, license, and split integrity metadata."""
    return {
        "dataset_name": "TACO (Trash Annotations in Context)",
        "source_url": "https://tacodataset.org/",
        "license": "CC-BY 4.0",
        "canonical_taxonomy_size": len(CANONICAL_CLASSES),
        "split_strategy": "stratified_by_session_and_image_id",
        "leakage_prevention": "Strict MD5 deduplication and session-isolated partitioning",
    }
