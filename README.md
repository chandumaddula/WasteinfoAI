# wasteinfoAI
### Offline AI-Powered Waste Identification, Recycling & Smart Sanitation Platform

**Bharat AI-SoC Challenge 2026–27 | Theme: Environment & Climate**

wasteinfoAI is an AI-powered waste management platform designed to help individuals, institutions, industries, communities, and municipal sanitation teams identify waste, understand proper disposal methods, learn about recycling, and coordinate cleanup activities.

The platform combines computer vision, an on-device Llama language model deployed using ExecuTorch, text-to-speech assistance, and geospatial waste reporting to promote responsible waste management.

Its primary goal is to make waste-related information accessible, practical, and available offline while helping communities identify waste accumulation hotspots and organize effective sanitation activities.

---

## Table of Contents

- [Project Overview](#project-overview)
- [Problem Statement](#problem-statement)
- [Project Objectives](#project-objectives)
- [Target Audience](#target-audience)
- [Key Features](#key-features)
- [System Architecture](#system-architecture)
- [AI Technology Stack](#ai-technology-stack)
- [Waste Identification Workflow](#waste-identification-workflow)
- [AI Waste Assistant](#ai-waste-assistant)
- [Audio Assistance](#audio-assistance)
- [Waste Hotspot Mapping](#waste-hotspot-mapping)
- [Role-Based Dashboards](#role-based-dashboards)
- [Offline-First Design](#offline-first-design)
- [Technology Stack](#technology-stack)
- [Installation and Setup](#installation-and-setup)
- [Project Structure](#project-structure)
- [Security and Responsible AI](#security-and-responsible-ai)
- [Testing and Evaluation](#testing-and-evaluation)
- [Expected Impact](#expected-impact)
- [Future Enhancements](#future-enhancements)
- [Competition Requirements](#competition-requirements)
- [Contributing](#contributing)
- [License](#license)

---

## Project Overview

Waste segregation and disposal are important environmental challenges. People may struggle to identify waste materials, understand recycling requirements, and locate suitable disposal facilities. Meanwhile, communities and sanitation teams need effective ways to report waste accumulation and coordinate cleanup activities.

wasteinfoAI addresses these challenges through a unified AI-powered application.

Users can capture or upload an image of waste and receive information about its predicted category, likely material, possible recycling options, recommended disposal methods, and environmental impact.

An AI-powered conversational assistant allows users to ask follow-up questions and receive understandable explanations. Text-to-speech makes the information accessible through audio.

The platform also enables users to report waste accumulation locations on a map. Authorized sanitation teams can review reports, prioritize cleanup, navigate to assigned locations, and record completion evidence.

**Core concept:**

Identify waste → Understand it → Receive guidance → Report waste hotspots → Coordinate cleanup → Track progress.

## Problem Statement

**Theme: Environment & Climate**

Develop an AI-powered assistant that identifies waste materials, recommends appropriate disposal methods, explains recycling options, and promotes sustainable waste management using Llama and ExecuTorch on Arm-based devices.

The project extends this concept with community waste reporting, hotspot visualization, and sanitation coordination.

### Challenges Addressed

- Incorrect waste segregation at the source.
- Limited access to reliable disposal information.
- Difficulty understanding recycling and material-reuse options.
- Limited accessibility for users who prefer audio or local-language explanations.
- Waste accumulation in public spaces and communities.
- Difficulty reporting, prioritizing, and tracking cleanup activities.
- Dependence on internet connectivity for digital AI assistance.

---

## Project Objectives

1. Develop an AI-powered waste identification system using computer vision.
2. Provide understandable waste information and actionable disposal guidance.
3. Integrate a Llama-family model through ExecuTorch for on-device advisory and reasoning.
4. Support offline waste scanning and AI assistance on a compatible Arm-based device.
5. Provide text and audio explanations.
6. Enable contextual questions about scanned waste.
7. Create a map-based waste reporting and hotspot visualization system.
8. Support sanitation task assignment, navigation, and cleanup tracking.
9. Introduce role-based dashboards for different user groups.
10. Encourage environmental awareness, recycling, and responsible waste management.

---

## Target Audience

### 1. Households and Citizens
- Identify household waste.
- Learn how to segregate and dispose of materials.
- Ask questions about recycling and reuse.
- Report waste accumulation in public spaces.

### 2. Students and Educational Institutions
- Learn about waste categories and environmental impacts.
- Conduct campus waste audits.
- Report waste-related issues.
- Support cleanliness and sustainability campaigns.

### 3. Industries and Businesses
- Identify common packaging and recyclable materials.
- Maintain waste segregation records.
- Monitor waste-management issues within their facilities.

### 4. Municipal Workers and Sanitation Teams
- View reported waste hotspots.
- Receive and manage cleanup assignments.
- Navigate to reported locations.
- Submit cleanup evidence and update report status.

### 5. Local Communities and NGOs
- Report littered locations.
- Organize community cleanup initiatives.
- Track local sanitation progress.
- Promote environmental awareness.

### 6. Municipal Administrators
- Monitor waste reports and hotspots.
- Assign tasks to authorized teams.
- Analyze cleanup performance.
- Generate area-wise waste-management reports.

---

## Key Features

### 1. AI-Powered Waste Scanner

Capture an image using the device camera or upload an existing image.

The vision model predicts the waste category and, where supported, the object or likely material.

**Potential categories:**
- Plastic
- Paper
- Cardboard
- Glass
- Metal
- Organic waste
- Textile waste
- Electronic waste
- Hazardous waste
- Other or uncertain waste

**Information displayed:**
- Predicted object and category.
- Likely material.
- Model confidence.
- Recycling or composting eligibility, where established.
- Preparation and segregation instructions.
- Recommended disposal route.
- Safety precautions.
- Environmental impact and reuse information.

The system should identify uncertain results and request user confirmation when the image does not provide enough evidence.

### 2. Detailed Waste Information

Present the scan result in a structured, user-friendly format.

Example:

**Detected item:** Plastic bottle

**Possible material:** PET plastic

**Recommended actions:**
1. Empty the bottle.
2. Follow local cleaning and preparation instructions.
3. Check whether caps and labels require separate handling.
4. Use the designated collection stream if the material is accepted locally.

The application should use verified local disposal rules rather than assuming every material is recyclable everywhere.

### 3. On-Device AI Waste Assistant

Integrate a Llama-family model using ExecuTorch.

The assistant can answer contextual questions such as:

- What type of waste did I scan?
- How should I dispose of it?
- Can this material be recycled or reused?
- Why is proper waste segregation important?
- What precautions should I follow?
- How can I report a waste hotspot?

The assistant should use scan results and a trusted local waste-information database as context.

### 4. Text-to-Speech Assistance

Convert waste explanations into spoken responses.

Features may include:
- Read-aloud assistance.
- Playback controls.
- Supported language selection.
- Short and simplified explanations.
- Optional offline speech recognition and voice input.

Language availability depends on the selected speech engine and installed resources.

### 5. Waste Hotspot Reporting

Allow users to report waste accumulation.

A report may contain:
- Photograph of the waste.
- GPS coordinates or manually selected location.
- Waste category.
- Description and estimated severity.
- Report timestamp.
- Current status.

Reports should support validation, duplicate detection, and authorized review.

### 6. Interactive Waste Hotspot Map

Display reported waste locations on a map.

**Map capabilities:**
- Waste report markers.
- Location and category filters.
- Clustering of nearby reports.
- Status and priority indicators.
- Selected-location details.
- Directions to assigned cleanup locations.
- Resolved and unresolved report tracking.

The map identifies reported or verified hotspots, not necessarily every location where waste exists.

### 7. Sanitation Task Management

Support the process from reporting to cleanup.

1. Citizen submits a report.
2. The report is validated.
3. An authorized administrator reviews the location.
4. A sanitation task is assigned.
5. The worker navigates to the location.
6. The worker performs the cleanup.
7. Completion evidence is submitted.
8. The report is marked resolved after appropriate verification.

### 8. Role-Based Dashboards

Provide separate views for citizens, students, organizations, sanitation workers, and administrators.

Each role receives the functions relevant to its responsibilities, enforced through secure authorization.

### 9. Scan and Report History

Store previous scans and submitted reports locally or on the backend, according to the selected storage and synchronization policy.

Users can review past results and monitor report progress.

### 10. Environmental Analytics

Provide useful statistics, such as:
- Number of waste reports submitted.
- Distribution of reported waste categories.
- Number of hotspots verified.
- Cleanup tasks completed.
- Average time to resolve reports.
- Recurring waste locations.

Metrics should be calculated from actual application records rather than simulated statistics.

---

## System Architecture

```text
                   wasteinfoAI
                        |
            +-----------+-----------+
            |                       |
            v                       v
      Waste Scanner           Hotspot Reporting
            |                       |
            v                       v
      Vision Model            Photo + Location
            |                       |
            v                       v
     Waste Classification     Report Validation
            |
            v
      Local Waste Rules
            |
            v
    Llama + ExecuTorch
            |
       +----+-----+
       |          |
       v          v
   Text Output  Audio Output
       |
       v
  Contextual AI Agent


  Validated Waste Reports
            |
            v
     Hotspot Map
            |
            v
    Sanitation Dashboard
            |
            v
    Task Assignment
            |
            v
     Navigation
            |
            v
   Cleanup Verification
```

### Architecture Principles

- Run supported AI inference locally.
- Keep waste identification and language generation as separate pipelines.
- Use verified rules for safety-sensitive disposal recommendations.
- Store user and waste-report data according to defined permissions.
- Synchronize reports when connectivity is available.
- Clearly distinguish locally available features from connectivity-dependent features.

---

## AI Technology Stack

### Computer Vision

The vision component identifies waste from camera images.

Possible development options include:
- PyTorch for model training and experimentation.
- OpenCV for image preprocessing.
- Lightweight classification or object-detection architectures.
- Quantization and mobile optimization for deployment.

A separate vision model is required for the initial design when using a text-only Llama model.

### Llama Language Model

Use a compatible Llama-family instruction model for:
- Conversational assistance.
- Waste-related explanations.
- Contextual question answering.
- Simplifying disposal guidance.
- Generating user-friendly responses from verified information.

A small model, such as Llama 3.2 1B Instruct, is a reasonable starting point for device-level testing. A quantized variant may reduce memory requirements.

### ExecuTorch

ExecuTorch provides the runtime for executing the exported Llama model on the target device.

The deployment workflow includes:
1. Selecting a compatible Llama checkpoint.
2. Exporting the model through a supported workflow.
3. Selecting a compatible execution backend.
4. Packaging the model and required tokenizer assets.
5. Integrating the runtime into the application.
6. Testing inference, memory usage, latency, and offline behavior.

The final model, export configuration, and runtime must be verified on the selected Arm-based device.

### Local Knowledge Base

Maintain structured, verified information about:
- Waste categories.
- Material-specific handling.
- Local collection rules.
- Recycling eligibility.
- Safety precautions.
- Approved disposal channels.

Use this information as context for the language model rather than relying on generated answers alone.

---

## Waste Identification Workflow

1. The user captures or uploads an image.
2. The application checks image quality.
3. The vision model predicts the waste category.
4. The application evaluates confidence and uncertainty.
5. The predicted result is matched with relevant local waste rules.
6. The information is passed to the Llama assistant when explanation is required.
7. The response is displayed as text.
8. Optional text-to-speech reads the response aloud.
9. The user can ask follow-up questions or report a waste location.

---

## AI Waste Assistant

The AI assistant uses the current scan result and verified knowledge to answer waste-related questions.

**Example input:**

```text
Waste category: Plastic
Detected item: Bottle
User question: How should I dispose of this?
```

**Expected response:**

```text
This appears to be a plastic bottle.

Empty the bottle and follow the preparation
instructions for your local recycling system.

If the material is accepted, place it in the
designated recyclable-plastics collection.
```

The assistant must not invent local rules, guarantee uncertain material identification, or provide unsupported hazardous-waste instructions.

---

## Audio Assistance

The audio subsystem converts displayed explanations into speech.

Possible components:
- Android TextToSpeech APIs.
- Offline text-to-speech engines where supported.
- Optional speech recognition for voice input.
- Language-specific voice resources.

The application should remain usable through text if a language or speech resource is unavailable.

---

## Waste Hotspot Mapping

The mapping system helps communities and sanitation teams coordinate waste cleanup.

### Report Data

A waste report may contain:

```text
Report ID
Reporter ID
Photo Reference
Latitude
Longitude
Waste Category
Description
Priority
Created At
Status
Assigned Worker
Resolution Evidence
Resolved At
```

### Report Lifecycle

```text
Reported
   |
   v
Under Review
   |
   v
Verified
   |
   v
Assigned
   |
   v
In Progress
   |
   v
Resolved
```

Additional states may be used for rejected, duplicate, or reopened reports.

### Hotspot Identification

Use validated report coordinates, report frequency, age, and severity to highlight potential waste hotspots.

The system should avoid counting duplicate reports as separate waste accumulations and should allow authorized staff to review hotspot classifications.

### Navigation

Provide directions to assigned locations using a supported mapping or navigation service.

Offline access to assigned coordinates and task details should be supported. Full offline navigation requires appropriate locally stored map data and routing capabilities.

---

## Role-Based Dashboards

| Role | Main capabilities |
|---|---|
| Citizen | Scan, ask AI questions, listen to explanations, report waste |
| Student / Institution | Learn, scan, report campus waste, view statistics |
| Industry | Record common waste categories, monitor facility issues |
| Sanitation Worker | View assignments, navigate, update cleanup status |
| Administrator | Review reports, assign tasks, monitor hotspots and analytics |

Role-specific permissions must be enforced by the backend when server-side resources are involved.

---

## Offline-First Design

Offline operation is a major design objective.

### Features intended to work offline
- Image capture and local waste classification.
- Llama inference through ExecuTorch.
- Local waste guidance already stored on the device.
- Text-to-speech when suitable local voice resources are installed.
- Local scan history.
- Draft waste reports and cached assigned tasks.

### Features that may require connectivity
- Synchronizing reports across users.
- Shared hotspot maps.
- Server-side authentication and task assignment.
- Live traffic-aware routing.
- Retrieving newly published local waste rules.
- Centralized administrative analytics.

The application should clearly communicate synchronization status and avoid implying that a report has reached municipal authorities until successful submission is confirmed.

---

## Technology Stack

The final stack should be selected based on the target Arm device and the competition's deployment requirements.

| Component | Candidate technology |
|---|---|
| Android application | Kotlin + Jetpack Compose |
| Computer vision | PyTorch + MobileNetV3-Small, OpenCV, lightweight vision model |
| Language model | Compatible Llama-family model |
| On-device LLM runtime | ExecuTorch |
| CPU execution backend | XNNPACK, where supported |
| Local storage | SQLite |
| Backend API | FastAPI |
| Server database | PostgreSQL  |
| Mapping | Compatible map SDK and routing service |
| Audio output | Android TextToSpeech or a supported offline engine |
| Version control | Git and GitHub |

**Important:** The backend is optional for local AI inference, but it is useful for shared reports, task allocation, synchronization, and administration.

---

## Installation and Setup

The project is under development. The steps below describe the intended setup process; exact commands and configuration will be documented after the initial implementation is established.

### Prerequisites

- Git.
- Android Studio or the chosen Android development environment.
- A compatible Arm-based Android device.
- Python and PyTorch for vision-model development, if applicable.
- A compatible Llama checkpoint.
- ExecuTorch and the required Android runtime integration.
- A vision model and its preprocessing configuration.
- Optional backend dependencies and mapping credentials.

### Setup Workflow

**1. Clone the repository**

```bash
git clone https://github.com/chandumaddula/WasteinfoAI.git
cd wasteinfoAI
```

**2. Configure the application**

Set up the Android project and required dependencies according to the selected framework.

**3. Prepare the AI models**

Export a compatible Llama model using the supported ExecuTorch workflow. Prepare and validate the vision model separately.

**4. Configure local data**

Add the required waste-category definitions, verified disposal guidance, and any permitted offline resources.

**5. Configure optional backend services**

Set up authentication, database connections, report synchronization, and mapping integrations if those components are enabled.

**6. Run and test**

Build the application, install it on the target device, and verify that scanning and Llama inference work without an internet connection.

Detailed build commands, model download instructions, environment variables, and backend startup procedures will be added after the corresponding modules are implemented.

---

## Project Structure

A proposed repository structure is:

```text
wasteinfoAI/
│
├── README.md
├── .gitignore
├── .env.example
├── docker-compose.yml
│
├── android/                           # PHASES 1–6, 9–10
│   ├── settings.gradle.kts
│   ├── build.gradle.kts
│   ├── gradle.properties
│   └── app/
│       ├── build.gradle.kts
│       ├── proguard-rules.pro
│       └── src/
│           ├── main/
│           │   ├── AndroidManifest.xml
│           │   │
│           │   ├── assets/
│           │   │   ├── vision/
│           │   │   │   └── waste_classifier.pte
│           │   │   ├── llm/
│           │   │   └── rules/
│           │   │       └── disposal_rules.json
│           │   │
│           │   ├── java/com/wasteinfoai/
│           │   │   ├── MainActivity.kt
│           │   │   ├── WasteInfoAIApp.kt
│           │   │   │
│           │   │   ├── core/
│           │   │   │   ├── common/
│           │   │   │   ├── constants/
│           │   │   │   ├── permissions/
│           │   │   │   ├── navigation/
│           │   │   │   └── util/
│           │   │   │
│           │   │   ├── data/
│           │   │   │   ├── local/
│           │   │   │   │   ├── WasteDatabase.kt
│           │   │   │   │   ├── dao/
│           │   │   │   │   └── entity/
│           │   │   │   ├── remote/
│           │   │   │   │   ├── ApiService.kt
│           │   │   │   │   └── ApiClient.kt
│           │   │   │   └── repository/
│           │   │   │
│           │   │   ├── ai/
│           │   │   │   ├── vision/
│           │   │   │   │   ├── WasteClassifier.kt
│           │   │   │   │   ├── ImagePreprocessor.kt
│           │   │   │   │   └── ClassificationResult.kt
│           │   │   │   ├── llm/
│           │   │   │   │   ├── LlamaEngine.kt
│           │   │   │   │   ├── PromptBuilder.kt
│           │   │   │   │   └── AssistantService.kt
│           │   │   │   └── rules/
│           │   │   │       ├── DisposalRuleEngine.kt
│           │   │   │       └── SafetyGuard.kt
│           │   │   │
│           │   │   ├── camera/
│           │   │   │   ├── CameraScreen.kt
│           │   │   │   ├── CameraViewModel.kt
│           │   │   │   └── ImagePicker.kt
│           │   │   │
│           │   │   ├── audio/
│           │   │   │   └── TextToSpeechManager.kt
│           │   │   │
│           │   │   ├── features/
│           │   │   │   ├── auth/
│           │   │   │   ├── home/
│           │   │   │   ├── scan/
│           │   │   │   ├── history/
│           │   │   │   ├── assistant/
│           │   │   │   ├── reports/
│           │   │   │   ├── map/
│           │   │   │   ├── tasks/
│           │   │   │   └── dashboards/
│           │   │   │
│           │   │   ├── sync/
│           │   │   │   ├── SyncWorker.kt
│           │   │   │   └── SyncScheduler.kt
│           │   │   │
│           │   │   └── ui/
│           │   │       ├── theme/
│           │   │       ├── components/
│           │   │       └── screens/
│           │   │
│           │   ├── res/
│           │   └── test/
│           │
│           └── androidTest/
│
├── ml/                                # PHASES 3–4
│   ├── README.md
│   ├── requirements.txt
│   ├── configs/
│   │   └── classifier_config.yaml
│   ├── data/
│   │   ├── raw/
│   │   ├── processed/
│   │   └── splits/
│   ├── notebooks/
│   │   ├── 01_dataset_exploration.ipynb
│   │   └── 02_model_evaluation.ipynb
│   ├── src/
│   │   ├── data/
│   │   │   ├── prepare_dataset.py
│   │   │   └── split_dataset.py
│   │   ├── training/
│   │   │   ├── train_classifier.py
│   │   │   └── evaluate_classifier.py
│   │   ├── inference/
│   │   │   └── predict.py
│   │   └── export/
│   │       ├── export_model.py
│   │       └── validate_export.py
│   ├── labels/
│   │   └── class_names.json
│   ├── checkpoints/                  # Generated locally; do not commit
│   ├── exported/                     # Generated locally; do not commit
│   └── reports/
│       └── evaluation_summary.json
│
├── backend/                           # PHASES 7–8
│   ├── requirements.txt
│   ├── alembic.ini
│   ├── Dockerfile
│   ├── alembic/
│   │   ├── env.py
│   │   └── versions/
│   │
│   ├── app/
│   │   ├── main.py
│   │   ├── api/
│   │   │   └── v1/
│   │   │       ├── auth.py
│   │   │       ├── users.py
│   │   │       ├── reports.py
│   │   │       ├── hotspots.py
│   │   │       ├── tasks.py
│   │   │       └── rules.py
│   │   │
│   │   ├── core/
│   │   │   ├── config.py
│   │   │   ├── security.py
│   │   │   └── permissions.py
│   │   │
│   │   ├── database/
│   │   │   ├── session.py
│   │   │   └── base.py
│   │   │
│   │   ├── models/
│   │   │   ├── user.py
│   │   │   ├── report.py
│   │   │   ├── task.py
│   │   │   ├── evidence.py
│   │   │   └── audit_log.py
│   │   │
│   │   ├── schemas/
│   │   │   ├── auth.py
│   │   │   ├── user.py
│   │   │   ├── report.py
│   │   │   └── task.py
│   │   │
│   │   ├── services/
│   │   │   ├── report_service.py
│   │   │   ├── hotspot_service.py
│   │   │   ├── task_service.py
│   │   │   └── sync_service.py
│   │   │
│   │   └── storage/
│   │       └── media_storage.py
│   │
│   └── tests/
│       ├── test_auth.py
│       ├── test_reports.py
│       ├── test_tasks.py
│       └── test_sync.py
│
├── database/                          # PHASE 7
│   ├── init/
│   └── seeds/
│       └── development_rules.sql
│
├── docs/                              # PHASES 1–12
│   ├── architecture.md
│   ├── system_workflow.md
│   ├── api_documentation.md
│   ├── database_schema.md
│   ├── ai_model_card.md
│   ├── offline_strategy.md
│   ├── security_and_privacy.md
│   ├── test_plan.md
│   ├── technical_report.md
│   └── demo_script.md
│
├── scripts/
│   ├── setup_dev.ps1
│   ├── train_model.ps1
│   └── run_backend.ps1
│
└── .github/
    └── workflows/
        └── tests.yml
```

This is a proposed structure and can be adapted to the actual repository implementation.

Do not commit large model files, private credentials, personal data, or restricted datasets to source control.

---

## Security and Responsible AI

- Request camera and location permissions only when needed.
- Protect account information and private report data.
- Restrict administrative actions and task assignment to authorized users.
- Validate image uploads and API inputs.
- Obtain appropriate consent before sharing location or photographs.
- Avoid exposing precise reporter locations unnecessarily.
- Treat AI classifications as predictions, not guaranteed facts.
- Provide safe fallback guidance when confidence is low.
- Use verified sources for hazardous-waste recommendations.
- Provide a mechanism to correct inaccurate classifications and report information.

---

## Testing and Evaluation

Evaluate the system using measurable criteria.

### Computer Vision
- Classification accuracy and per-class precision/recall.
- Performance on real-world images.
- Confidence calibration and uncertainty handling.
- Robustness to lighting, background, and occlusion.

### Llama and ExecuTorch
- Successful on-device inference.
- Offline question-answering functionality.
- Response latency.
- Memory consumption and power usage.
- Correct handling of contextual waste questions.

### Disposal Guidance
- Agreement with verified local rules.
- Safety and factual accuracy.
- Quality of fallback behavior when information is missing.

### Audio
- Speech clarity.
- Language coverage.
- Successful playback without connectivity where supported.

### Hotspot Management
- Accuracy of reported coordinates.
- Duplicate detection.
- Map usability.
- Correct task assignment and status transitions.
- Evidence-based cleanup verification.

### Offline Reliability
- Operation with network access disabled.
- Persistence of local scans and drafts.
- Correct synchronization after connectivity returns.

---

## Expected Impact

wasteinfoAI aims to:

- Improve household and community waste segregation.
- Increase access to practical recycling information.
- Promote environmental awareness.
- Support accessibility through spoken guidance.
- Enable communities to report waste accumulation.
- Help sanitation teams prioritize cleanup work.
- Improve visibility into waste-report resolution.
- Demonstrate practical on-device AI on Arm-based hardware.

These are intended outcomes. Actual environmental improvements must be evaluated through real-world usage and measured results.

---

## Future Enhancements

- Multilingual voice interaction.
- Improved waste material and object detection.
- More detailed local recycling-rule databases.
- Offline map tiles and routing where feasible.
- Waste hotspot density and recurrence analysis.
- Community cleanup event coordination.
- Integration with authorized municipal reporting systems.
- Additional analytics for institutions and industries.
- Improved on-device model optimization and battery efficiency.

---

## Bharat AI-SoC Challenge Requirements

The project is designed around the Environment & Climate problem statement.

| Requirement | Planned implementation |
|---|---|
| Waste classification | Local computer vision model |
| Disposal recommendations | Verified waste rules + Llama-generated explanations |
| Recycling education | Contextual AI assistant |
| Llama-family model | Compatible Llama instruction model |
| ExecuTorch | On-device Llama inference |
| Arm-based target | Compatible Android smartphone |
| Offline operation | Local models, local knowledge, and cached data |
| Application | Android interface |
| Demonstration | Offline scan, AI explanation, audio, and hotspot workflow |
| Technical report | Architecture, model choices, measurements, and limitations |

The final submission should demonstrate the actual Llama + ExecuTorch pipeline on the target device and document which features operate fully offline and which require connectivity.

---

## Contributing

Contributions are welcome from developers working on:
- Computer vision and model optimization.
- Android application development.
- Llama and ExecuTorch integration.
- Audio and multilingual accessibility.
- Mapping and geospatial analysis.
- Waste-management datasets and verified local guidance.
- Testing and documentation.

Before contributing, document the affected feature, test the changes, and ensure that sensitive data and credentials are not committed.

---

## License

Choose an appropriate open-source license before publishing the repository. Ensure that all model weights, datasets, libraries, and other assets used by the project permit the intended use and redistribution.

---

## Project Vision

**wasteinfoAI — Identify waste. Understand its impact. Find hotspots. Enable cleaner communities.**

An offline-first environmental assistant combining computer vision, Llama, ExecuTorch, and community-driven sanitation coordination to support smarter and more responsible waste management.
