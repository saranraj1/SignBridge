# SignBridge+

> **Teach your phone a gesture. Let it speak for you.**

SignBridge+ is an on-device, few-shot temporal gesture-learning system. The core product idea is simple: instead of forcing users to work with a fixed gesture vocabulary, the phone can learn a personalized gesture from a few examples and recognize it locally in real time.

The first high-impact application is Indian Sign Language (ISL) communication.

## Core demo

1. User enters **Teach Mode**.
2. User performs a new gesture three times.
3. User assigns a meaning, e.g. `Emergency`.
4. SignBridge+ creates a personalized local gesture prototype.
5. User repeats the gesture.
6. The phone recognizes it.
7. The meaning appears as text and is spoken with TTS.
8. The core path continues working with connectivity disabled.

## Core architecture

```text
Camera
  ↓
MediaPipe / hand-landmark ML
  ↓
Landmark normalization
  ↓
Temporal window
  ↓
Pretrained/lightweight temporal gesture encoder
  ↓
Gesture embedding
  ↓
Few-shot prototype matching
  ↓
Confidence / unknown gate
  ↓
Text
  ↓
Android TTS
```

A **DTW baseline/fallback** is maintained throughout development. The learned encoder should only become the primary recognition mechanism if experiments show a meaningful advantage.

## What makes the demo memorable

The judge is the test subject:

> "Can you teach our phone a new gesture in 30 seconds?"

The judge creates the gesture, teaches it, repeats it, and sees the phone recognize and speak it. The offline proof follows.

## Scope

### MVP
- Android app
- camera input
- hand landmarks
- temporal gesture representation
- 3-shot enrollment
- local prototype storage
- recognition
- confidence/unknown state
- text output
- TTS
- offline critical path

### Explicitly out of scope
- universal continuous ISL translation
- thousands of signs
- facial grammar
- cloud recognition
- giant VLM/LLM in the recognition loop
- production-scale backend

## Development principle

**Build the smallest thing that proves the biggest claim.**

The prototype is not the final product. It exists to prove that few-shot gesture learning works reliably enough to justify the full hackathon build.
