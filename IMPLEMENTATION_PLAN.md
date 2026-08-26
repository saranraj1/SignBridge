# SignBridge+ Implementation Plan

## Strategy

Work from uncertainty to certainty.

The first objective is not UI. It is proving that the recognition mechanism works.

## Phase 0 — Rule and environment verification

Before development:
- verify current hackathon rules
- confirm pre-trained models are allowed
- confirm prototype submission requirements
- confirm permitted pre-event code/assets
- select target Android device
- record Android/SDK/toolchain versions

## Phase 1 — 4-hour feasibility spike

### Hour 0–1
Camera → hand landmarks.

### Hour 1–2
Record normalized temporal sequences.

### Hour 2–3
Implement DTW few-shot baseline.

### Hour 3–4
Teach three examples → recognize new example.

### Gate
If baseline works, proceed.
If not, diagnose landmarks/representation before touching UI.

## Phase 2 — Learned encoder experiment

- identify candidate pretrained temporal encoders
- create embeddings
- build prototype matcher
- evaluate unseen gesture classes
- evaluate unseen people
- compare against DTW

### Gate
Use learned encoder only if it provides a meaningful practical benefit.

## Phase 3 — Android core

- CameraX
- landmark inference
- preprocessing
- encoder runtime
- matching
- local storage

Target:
live prediction on phone.

## Phase 4 — Teach Mode

- label input
- three-example capture
- quality checks
- prototype generation
- save
- test

## Phase 5 — Recognition UX

- camera view
- prediction
- confidence
- unknown state
- debounce
- TTS

## Phase 6 — Offline

Test:
- Wi-Fi off
- mobile data off
- airplane mode

## Phase 7 — ISL application

Add only a small, verified set of ISL examples.

Do not allow ISL vocabulary growth to destabilize the core demo.

## Phase 8 — External testing

Recruit at least five non-team testers if possible.

Test:
- signer variation
- speed
- distance
- lighting
- left/right hand
- background
- unknown gestures

## Phase 9 — Benchmark

Record:
- enrollment time
- median/p95 latency
- FPS
- few-shot accuracy
- cross-person accuracy
- unknown rejection
- offline success
- memory
- thermal observations

## Phase 10 — Submission assets

Prepare:
- 30–60s prototype video
- screenshots
- architecture diagram
- benchmark table
- short description
- novelty statement
- limitations
- team details

## Phase 11 — Final freeze

Freeze features.

Only:
- bug fixes
- reliability fixes
- demo fixes
- submission fixes

## 3-person parallel plan

### Person 1 — AI/CV
Landmarks, normalization, encoder, DTW, embeddings, confidence, benchmarks.

### Person 2 — Android
CameraX, Android pipeline, UI, storage, TTS, offline testing.

### Person 3 — Product/Data/Demo
Dataset, ISL validation, UX, testing, demo, video, submission.

## Integration checkpoints

Integrate at:
- landmarks
- DTW
- encoder
- enrollment
- recognition
- TTS
- offline
- stranger testing

Never postpone integration until the end.
