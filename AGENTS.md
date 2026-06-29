---
name: blurguard-architecture
description: Enforce BlurGuard's modular Android architecture and privacy invariants. Apply to ALL code changes in this repository.
always_apply: true
---

# SKILL: BlurGuard Architecture Guardrails

You are working on BlurGuard, a native Android (Kotlin/Compose/CameraX) app that detects and anonymizes faces and license plates in real time, on-device, and offline, and saves ONLY the anonymized video. Follow these rules strictly. If a request conflicts with them, STOP and flag the conflict instead of silently violating the architecture.

## 1. Module map (where code MUST live)
- app/                 entry point, navigation, Hilt graph, offline-only network policy, app lock host
- core/camera/         CameraX session + SurfaceProcessor pipeline. The ONLY module allowed to write video.
- core/blurring/     GPU blur/pixelate/mask rendering, watermark, metadata strip on export
- core/ml/             TFLite/LiteRT model wrappers + delegate management (detection, embeddings)
- core/tracking/       ByteTrack multi-object tracking
- core/recognition/    face embedding matching + enrollment (trusted-face gallery)
- core/domain/         frame-pipeline orchestrator + use cases (blur-vs-keep-visible decisions)
- core/data/           settings (Proto DataStore), media store, encrypted embedding store, panic delete
- core/model/          shared immutable entities + cross-module interfaces (LEAF module)
- core/designsystem/   Compose theme, reusable components, AR/EN localization
- core/common/         dispatchers, Result types, utilities
- feature/camera/      recording screen + ViewModel, preview, uncertainty warning
- feature/gallery/     gallery browse + post-capture manual edit
- feature/settings/    privacy / anonymization / security / export menus
- benchmark/           macrobenchmark guarding frame latency

Before adding code, decide which module owns it using the list above. Never put logic in the wrong layer for convenience.

## 2. Dependency direction (MUST NOT create cycles)
- app -> feature/*, core/*
- feature/* -> core/domain, core/data, core/model, core/designsystem, core/common ONLY.
- feature/* MUST NOT depend on another feature/*, nor directly on core/ml, core/tracking, core/blurring, core/camera, core/recognition.
- Features access the engine ONLY through core/domain use cases.
- core/domain may orchestrate camera/blurring/ml/tracking/recognition/data.
- Engine modules (camera, blurring, ml, tracking, recognition) depend only on core/model and core/common, and talk to each other ONLY via interfaces declared in core/model.
- core/model and core/common are leaves; they depend on nothing internal.
- core/designsystem holds no business logic.

## 3. The two data paths (NEVER conflate)
- UI-STATE PATH (slow): Compose -> ViewModel(UiState as StateFlow) -> core/domain use cases -> repositories. For record state, settings, warnings, gallery lists.
- FRAME PATH (fast, 24-30 fps): Camera -> SurfaceProcessor(GPU) -> {Preview, Encoder}, with a parallel ImageAnalysis -> ml -> tracking -> recognition branch feeding boxes/IDs/keep-visible flags to the renderer.
- RULE: Pixel/frame data MUST NEVER flow through a ViewModel or StateFlow. Never collect camera frames in UI state.

## 4. Hard privacy & safety invariants (NON-NEGOTIABLE)
1. SINGLE VIDEO WRITER: only core/camera writes video, and only the processed (anonymized) SurfaceProcessor output. The raw camera Surface stays private to core/camera and is never exposed or returned.
2. NO RAW PERSISTENCE: un-anonymized frames must NEVER reach disk, cache, MediaStore, logs, crash reports, or analytics — not even temporarily. Encoder intermediates must be app-internal and encrypted.
3. ON-DEVICE ONLY: no inference, embedding, or footage may leave the device. Do not add network calls in the recording/blurring path.
4. OFFLINE-ONLY enforced centrally in app/, not per-feature.
5. METADATA STRIP (GPS/device/timestamp) is a mandatory, default-on export stage in core/blurring.
6. Trusted-face embeddings live in an encrypted on-device store and are wiped by panic delete; never exported.

If a change could violate any invariant above, refuse and explain. Do not weaken an invariant to make a test pass.

## 5. Concurrency rules
- Detection runs on a background/ML dispatcher with NNAPI/GPU delegate, on SUBSAMPLED ImageAnalysis frames (not every frame).
- Rendering runs on the GPU every frame; use tracker output to interpolate between detections.
- No allocations or blocking calls in the per-frame hot loop.
- Pass detection results to the renderer via a latest-value (conflated) channel; drop stale frames, never queue-and-lag.
- All dispatchers come from core/common DispatcherProvider; do not hardcode Dispatchers in modules.

## 6. Conventions
- Cross-module contracts are interfaces in core/model (Detector, Tracker, FaceRecognizer, FrameRenderer, repositories). Implement them in the owning module; inject via Hilt.
- UI is Compose + MVVM + UDF: one immutable UiState per screen, events up, state down.
- Settings via Proto DataStore in core/data. Saved files use names like BlurGuard_YYYY-MM-DD_HH-mm.mp4.
- Keep models/runtimes swappable behind interfaces; no direct TFLite calls outside core/ml.

## 7. Decision guide ("where does this go?")
- New screen/UI -> feature/* (+ core/designsystem for shared components)
- New camera/recording behavior -> core/camera (capture) and/or core/blurring (pixels)
- New ML model or delegate -> core/ml behind an interface in core/model
- New tracking logic -> core/tracking; new re-ID/enrollment -> core/recognition
- Orchestration / business decision spanning ml+tracking+recognition+blurring -> core/domain use case
- Persistence / settings / deletion -> core/data
- Shared data type or interface -> core/model

## 8. Pre-change review checklist (apply to every diff)
- [ ] Code is in the correct module per section 1.
- [ ] No forbidden dependency or cycle introduced (section 2).
- [ ] No frame/pixel data routed through ViewModel/StateFlow (section 3).
- [ ] No raw footage written anywhere; only core/camera writes video (invariants 1-2).
- [ ] No new network access in record/process paths; offline-only respected (invariants 3-4).
- [ ] Metadata strip preserved on export (invariant 5).
- [ ] Embeddings stay encrypted/on-device and panic-deletable (invariant 6).
- [ ] Cross-module access uses interfaces from core/model, injected via Hilt.
- [ ] Per-frame hot loop has no allocations/blocking.
- [ ] Feature does not import another feature or an engine module directly.

## 9. Anti-patterns to reject
- Recording with CameraX VideoCapture bound directly to the raw camera stream (saves un-anonymized video).
- Collecting camera frames into a StateFlow / LiveData.
- A feature module importing core/ml or core/camera directly.
- Saving a temporary raw clip "just to process it later."
- Adding cloud inference, telemetry, or crash uploads that could carry frame data.
- God-classes in feature ViewModels that embed detection/tracking logic.