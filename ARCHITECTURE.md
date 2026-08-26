# SignBridge+ Architecture

## System overview

```text
                         SIGNBRIDGE+
                             │
                             ▼
                         CameraX
                             │
                             ▼
                  Hand Landmark ML Model
                             │
                             ▼
                    Landmark Normalizer
                             │
                             ▼
                      Temporal Buffer
                             │
                  ┌──────────┴──────────┐
                  ▼                     ▼
          Temporal AI Encoder          DTW
                  │                     │
                  ▼                     │
              Embedding                │
                  │                     │
                  └──────────┬──────────┘
                             ▼
                    Few-shot Matcher
                             │
                             ▼
                     Confidence Gate
                       /          \
                      /            \
                 MATCH            UNKNOWN
                   │                │
                   ▼                ▼
                  Text          Retry state
                   │
                   ▼
              Android TTS
```

## Enrollment

```text
Example 1 ──→ encoder ──→ z1
Example 2 ──→ encoder ──→ z2
Example 3 ──→ encoder ──→ z3
                           │
                           ▼
                    prototype = mean(z)
                           │
                           ▼
                     Local storage
```

## Recognition

```text
Live gesture
    ↓
landmarks
    ↓
normalization
    ↓
encoder
    ↓
embedding z
    ↓
compare against prototypes
    ↓
confidence + margin
    ↓
label OR UNKNOWN
```

## Reverse communication (optional future)

```text
Microphone
   ↓
On-device ASR
   ↓
Large text
   ↓
ISL user
```

This is optional and must not delay the core product.

## Why this architecture

- Camera is essential.
- AI is central to perception and gesture representation.
- Few-shot personalization creates the product differentiator.
- Local matching avoids cloud latency/privacy issues.
- DTW gives a simple fallback.
- Components can be swapped independently.
