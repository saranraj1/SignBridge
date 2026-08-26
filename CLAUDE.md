# CLAUDE.md — SignBridge+ Engineering Instructions

## Mission

Build SignBridge+ as a reliable, phone-first, on-device few-shot gesture-learning product for the iQOO Reskilll hackathon.

The objective is **winning with a working product**, not maximizing feature count.

## Product thesis

The key differentiator is:

> **The phone adapts to the user instead of forcing the user into a predefined gesture vocabulary.**

ISL is the first application. Do not reduce the project to "another ISL translator."

## Architecture priority

Preferred critical path:

```text
Camera
→ landmark ML
→ normalization
→ temporal representation
→ learned gesture encoder
→ few-shot prototype
→ confidence gate
→ text
→ TTS
```

DTW is the required baseline/fallback.

A VLM or LLM is optional and must never be inserted into the critical recognition path merely to make the project look more AI-heavy.

## Model policy

- Pretrained models are allowed.
- Prefer compact, mobile-deployable models.
- Benchmark before committing.
- Do not assume a complex neural encoder beats DTW.
- A model is selected only after measured evaluation.
- Do not claim NPU acceleration until measured on target hardware.

## Coding principles

1. Keep the critical path local.
2. Prefer deterministic, testable components.
3. Avoid cloud dependencies.
4. Keep model interfaces replaceable.
5. Instrument latency from the beginning.
6. Add an explicit UNKNOWN state.
7. Debounce repeated predictions.
8. Store only the data needed for personalization.
9. Keep UI simple until recognition is reliable.
10. Integrate frequently; never leave integration to the end.

## Hard non-goals

Do not implement without explicit approval:
- continuous unrestricted ISL
- full facial grammar
- thousands of classes
- cloud inference
- authentication/backend
- unnecessary dashboards
- large LLM/VLM features
- speculative "future" features that do not strengthen the demo

## Kill gates

If the learned encoder:
- does not beat or materially improve over DTW,
- is too slow,
- is too difficult to deploy,
- or introduces unacceptable instability,

use DTW or a simpler model.

If ISL data becomes the main source of instability, preserve the generic few-shot gesture demo and reduce the ISL vocabulary.

## Demo rule

The first 30–60 seconds should prove:

**judge teaches → phone learns → judge repeats → phone recognizes → phone speaks → offline proof.**

Do not begin with architecture slides.

## Claims

Never invent:
- accuracy
- latency
- FPS
- NPU use
- state-of-the-art status
- "first" claims
- universal ISL capability

Label numbers as TARGET until measured.

## Review standard

Before considering a feature complete, ask:

1. Does it improve the core user experience?
2. Does it improve reliability?
3. Is it measurable?
4. Can it run locally?
5. Does it fit the event-time budget?
6. Does it survive a skeptical judge?

If not, defer it.
