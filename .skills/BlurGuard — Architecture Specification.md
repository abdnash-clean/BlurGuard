# BlurGuard — Architecture Specification

<aside>
🏗️

Companion to the SRS and Benchmark Study. This document defines the **software architecture** of BlurGuard: module structure, layering rules, the real-time frame pipeline, concurrency model, and the hard invariants that protect the privacy guarantees. It is the source of truth for how the code is organized.

</aside>

## 1. Purpose & Scope

This specification describes *how BlurGuard is built*, not *what it does* (see the SRS for requirements). It targets a native **Android-only** app that detects and anonymizes faces and license plates **in real time, on-device, and offline**, persisting **only** the anonymized video. It is intended to be precise enough that any contributor — human or coding agent — can place new code correctly without weakening the privacy guarantees.

## 2. Architectural Goals & Principles

- **Modern Android Development (MAD):** Kotlin, Jetpack Compose, CameraX, coroutines/Flow, Hilt DI.
- **Strict modularization:** clear `feature/` ↔ `core/` separation; the latency-critical ML concerns live in their own modules.
- **Unidirectional Data Flow (UDF) for UI; a separate fast path for pixels.** UI state is reactive and slow; frame data never flows through `StateFlow`.
- **Privacy by construction:** raw footage is unrepresentable in storage. The architecture — not discipline — prevents it.
- **Swappable implementations:** models, trackers, and runtimes sit behind interfaces so they can be replaced without touching UI.
- **Testability & measurability:** every heavy concern is isolated and benchmarkable (NFR-02).

## 3. High-Level Architecture — Two Data Paths

BlurGuard has **two distinct data paths** that must never be conflated:

1. **UI-state path (slow, reactive):** `Compose screen → ViewModel (UiState as StateFlow) → domain use cases → repositories`. Carries record state, settings, warnings, gallery lists. Standard MVVM/UDF.
2. **Frame path (fast, real-time):** `Camera sensor → SurfaceProcessor (GPU) → {Preview, Encoder}`, with a parallel `ImageAnalysis → detection → tracking → recognition` branch that feeds box/ID/keep-visible metadata into the GPU renderer. This path runs at 24–30 fps and must **never** be routed through the ViewModel or a `StateFlow`.

<aside>
⚠️

The #1 architectural rule: the frame path and the UI-state path are separate. Pushing pixels through UI state will break NFR-02 (real-time performance).

</aside>

## 4. Module Structure

```jsx
BlurGuard/
├── app/                  # entry point, navigation, DI graph, offline-only network policy, app lock
├── core/
│   ├── camera/           # CameraX setup + SurfaceProcessor pipeline (SOLE video writer)
│   ├── blurring/         # GPU blur / pixelate / mask render, watermark, metadata strip
│   ├── ml/               # TFLite/LiteRT model wrappers + delegate management
│   ├── tracking/         # ByteTrack multi-object tracking
│   ├── recognition/      # face embedding match + enrollment (trusted-face gallery)
│   ├── domain/           # frame-pipeline orchestrator + use cases (keep-visible decisions)
│   ├── data/             # settings (Proto DataStore), media store, encrypted embedding store, panic delete
│   ├── model/            # shared immutable domain entities (leaf module)
│   ├── designsystem/     # Compose theme, reusable components, localization
│   └── common/           # dispatchers, Result types, shared utilities
├── feature/
│   ├── camera/           # recording screen + ViewModel, real-time preview, uncertainty warning
│   ├── gallery/          # gallery browse + post-capture manual edit
│   └── settings/         # privacy / anonymization / security / export menus
├── benchmark/            # macrobenchmark guarding frame latency (NFR-02)
└── build.gradle.kts
```

## 5. Dependency Rules

Dependencies point **inward and downward only**. Cycles are forbidden.

| Layer | May depend on | Must NOT depend on |
| --- | --- | --- |
| `app/` | `feature/*`, `core/*` | — |
| `feature/*` | `core/domain`, `core/data`, `core/model`, `core/designsystem`, `core/common` | Another `feature/*`; `core/ml`, `core/tracking`, `core/blurring`, `core/camera` directly |
| `core/domain` | `core/camera`, `core/blurring`, `core/ml`, `core/tracking`, `core/recognition`, `core/data`, `core/model`, `core/common` | `feature/*`, `app/`, `core/designsystem` |
| `core/camera`, `core/blurring`, `core/ml`, `core/tracking`, `core/recognition` | `core/model`, `core/common` | `feature/*`, `core/domain`, each other (except via interfaces in `core/model`) |
| `core/data` | `core/model`, `core/common` | `feature/*`, `core/domain`, ML modules |
| `core/model`, `core/common` | — (leaf) | everything else |
| `core/designsystem` | `core/model` | `feature/*`, business logic modules |
- Features talk to the engine **only** through `core/domain` use cases — never to `core/ml`/`core/camera` directly.
- Cross-module contracts (e.g. `Detector`, `Tracker`, `FaceRecognizer`, `FrameRenderer`) are interfaces declared in `core/model` and implemented in the respective module.

## 6. The Real-Time Frame Pipeline (Critical)

```jsx
Camera sensor
   │  (CameraX)
   ▼
SurfaceProcessor / CameraEffect  ── GPU blur/pixelate/mask (core/blurring)
   │     ▲
   │     │  boxes + track IDs + keep-visible matches  (per detection)
   │     │
   ├────────────────────────►  Preview Surface          (FR-04)
   └────────────────────────►  VideoCapture / MediaCodec (FR-05 — anonymized ONLY)

ImageAnalysis (downsampled, parallel branch)
   → core/ml detection (BlazeFace + YOLO/ShuffleNet)
   → core/tracking (ByteTrack: stable IDs between detections)
   → core/recognition (MobileFaceNet embeddings: re-ID on re-entry)
   → core/domain orchestrator decides blur vs keep-visible
```

- **Detection runs on subsampled `ImageAnalysis` frames** on a background/NPU thread (not every frame).
- **Rendering runs on every frame on the GPU**, using tracker output to interpolate box positions between detections — this is what hits 24–30 fps.
- `core/camera` owns both output surfaces and binds the single CameraX session to the lifecycle.

## 7. Concurrency & Threading Model

| Concern | Thread / dispatcher | Notes |
| --- | --- | --- |
| UI state | Main + `viewModelScope` | StateFlow, UDF |
| Frame rendering | GPU / render thread | Every frame; no allocation in hot loop |
| Detection inference | Dedicated ML dispatcher (background) + NNAPI/GPU delegate | Subsampled cadence; backpressure-aware |
| Tracking / recognition | Same ML/background dispatcher | Lightweight; cosine match on-device |
| Encoding | MediaCodec async callback thread | Consumes processed surface only |

Detection results are passed to the renderer via a lock-free latest-value channel (drop stale frames; never queue-and-lag).

## 8. Module Specifications

| Module | Responsibility | Key contracts / types | Requirements |
| --- | --- | --- | --- |
| `app` | Entry, navigation, Hilt graph, offline-only network policy, app lock host | `Application`, DI modules, `NavHost` | FR-16, NFR-04 |
| `core/camera` | CameraX session + SurfaceProcessor; the ONLY module that writes video | `CameraController`, `FrameSource` | FR-01, FR-04, FR-05 |
| `core/blurring` | GPU anonymization render, watermark, metadata strip on export | `FrameRenderer`, `AnonMode`, `Exporter` | FR-02, FR-03, FR-10, FR-11, FR-13 |
| `core/ml` | TFLite model wrappers + delegate management | `Detector`, `EmbeddingModel` | FR-02, FR-03, FR-18 |
| `core/tracking` | ByteTrack stable IDs across frames | `Tracker`, `TrackId` | FR-06 |
| `core/recognition` | Embedding match + enrollment; trusted-face gallery | `FaceRecognizer`, `FaceEnrollment` | FR-18 |
| `core/domain` | Frame-pipeline orchestrator; keep-visible decision use cases | `AnonymizationPipeline`, use cases | FR-06, FR-08, FR-18 |
| `core/data` | Settings (Proto DataStore), media store, encrypted embedding store, panic delete | `SettingsRepository`, `MediaRepository`, `EmbeddingStore` | FR-07, FR-15, FR-16, FR-17 |
| `core/model` | Shared immutable entities + interfaces (leaf) | `DetectionBox`, `FaceEmbedding`, `AnonMode` | — |
| `core/designsystem` | Compose theme, components, AR/EN localization | `BlurGuardTheme`, components | NFR-06, NFR-09 |
| `core/common` | Dispatchers, Result, utilities | `DispatcherProvider` | — |
| `feature/camera` | Recording screen + ViewModel; preview; uncertainty warning | `CameraViewModel`, `CameraUiState` | FR-01, FR-04, FR-08 |
| `feature/gallery` | Gallery browse + post-capture manual edit | `GalleryViewModel` | FR-09 |
| `feature/settings` | Privacy / anonymization / security / export menus | `SettingsViewModel` | FR-17, NFR-04 |
| `benchmark` | Macrobenchmark guarding frame latency | — | NFR-02 |

## 9. Critical Architectural Invariants

These are non-negotiable and should be enforced by code review and (where possible) lint/tests:

1. **Single video writer.** Only `core/camera` may write video, and it writes **only** the processed SurfaceProcessor output. The raw camera `Surface` is private to `core/camera` and never exposed.
2. **No raw persistence.** Un-anonymized frames must never reach disk, cache, MediaStore, or logs — not even temporarily. Any encoder intermediate is app-internal and encrypted. (FR-05, NFR-01)
3. **Frame path off the UI thread & off StateFlow.** Pixels never traverse the ViewModel. (NFR-02)
4. **On-device only.** No model inference, embedding, or footage leaves the device. (NFR-01, NFR-05)
5. **Features never reach into engine modules.** All access via `core/domain` use cases.
6. **Offline-only is enforced centrally** in `app/`, not per-feature. (FR-16)
7. **Metadata strip is a mandatory export stage**, on by default. (FR-11)

## 10. Data & Storage

- **Settings:** Proto DataStore (`CameraAppSettings`-style typed config) in `core/data`.
- **Media:** anonymized videos written via MediaStore with clear filenames (`BlurGuard_YYYY-MM-DD_HH-mm.mp4`).
- **Trusted-face embeddings:** stored in an **encrypted** on-device store (e.g. SQLCipher / Jetpack Security), never exported; cleared by panic delete.
- **Temp files:** app-internal only, auto-deleted per policy (Immediately / 1h / 24h); panic delete wipes them.

## 11. Cross-Cutting Concerns

| Concern | Owner | Requirement |
| --- | --- | --- |
| Offline-only network policy | `app/` | FR-16, NFR-05 |
| Panic delete / auto-delete temp | `core/data` | FR-15 |
| Metadata removal on export | `core/blurring` | FR-11 |
| App lock / screenshot protection | `app/`  • `feature/settings` | NFR-04 |
| Localization (AR/EN) | `core/designsystem`  • resources | NFR-09 |
| Uncertainty signal (confidence/luminance) | `core/domain` → `feature/camera` | FR-08 |

## 12. Technology Stack

- **Language/UI:** Kotlin, Jetpack Compose.
- **Camera:** CameraX (`Preview`, `VideoCapture`, `ImageAnalysis`, `CameraEffect`/`SurfaceProcessor`).
- **Inference:** TensorFlow Lite (LiteRT) + NNAPI/GPU delegates (ONNX Runtime / NCNN as alternatives).
- **Models:** BlazeFace (faces), small YOLO / ShuffleNetv2 (plates), ByteTrack (tracking), MobileFaceNet/ArcFace (recognition).
- **DI:** Hilt. **Async:** Coroutines + Flow. **Storage:** Proto DataStore, MediaStore, encrypted DB.
- **Rendering:** OpenGL ES / RenderEffect for GPU anonymization.

## 13. Requirements Traceability (Module ↔ Requirement)

| Requirement | Primary module(s) |
| --- | --- |
| FR-01 Real-time recording | `core/camera`, `feature/camera` |
| FR-02 Face detection & anonymization | `core/ml`, `core/blurring` |
| FR-03 Plate detection & anonymization | `core/ml`, `core/blurring` |
| FR-04 Real-time anonymized preview | `core/camera`, `core/blurring` |
| FR-05 Save only anonymized video | `core/camera` (invariant) |
| FR-06 Keep selected face visible | `core/tracking`, `core/domain` |
| FR-07 Gallery export w/ filename | `core/data`, `core/blurring` |
| FR-08 Uncertainty warning | `core/domain`, `feature/camera` |
| FR-09 Manual edit mode | `feature/gallery`, `core/blurring` |
| FR-10 Multiple anonymization modes | `core/blurring` |
| FR-11 Metadata removal | `core/blurring` |
| FR-12 Emergency quick record | `app/`, `feature/camera` |
| FR-13 Watermark | `core/blurring` |
| FR-15 Panic delete | `core/data` |
| FR-16 Offline-only mode | `app/` |
| FR-17 Settings management | `feature/settings`, `core/data` |
| FR-18 Face recognition (re-ID) | `core/recognition`, `core/ml`, `core/domain` |
| NFR-01 Privacy / on-device | all (invariants #2, #4) |
| NFR-02 Real-time performance | frame path, `benchmark` |
| NFR-04 Security | `app/`, `feature/settings` |
| NFR-05 Offline availability | `app/` |
| NFR-06 Usability | `core/designsystem`, features |
| NFR-09 Localization | `core/designsystem` |

## 14. Testing & Performance Strategy

- **Unit tests** per `core/*` module against interfaces (fake `Detector`, `Tracker`, `FaceRecognizer`).
- **Pipeline tests** in `core/domain` verifying blur-vs-keep decisions and FR-08 signaling.
- **Macrobenchmark** (`benchmark/`) asserting sustained ≥24 fps and ≤~100 ms per-frame latency on target devices (NFR-02).
- **Privacy regression tests:** assert no file is written outside `core/camera`'s processed output; assert no network calls during recording.