# SignBridge+ Benchmark Plan

## Purpose

Benchmark the mechanism honestly. The goal is not to claim state-of-the-art ISL accuracy.

## Primary metrics

### 1. Personalization time

Measure:
first enrollment capture → successful recognition-ready state.

Target:
≤30 seconds.

Report measured value.

### 2. Recognition latency

Measure:
camera frame/window → final prediction.

Report:
median and p95.

### 3. Few-shot recognition

Use:
3 enrollment examples.

Test:
new examples.

### 4. Cross-person recognition

Person A enrolls.
Person B tests.

### 5. Unknown rejection

Test gestures not represented in the local library.

### 6. Offline operation

Repeat the full pipeline with connectivity disabled.

## Secondary metrics

- FPS
- memory
- model size
- battery/thermal observation
- enrollment failure rate

## Baseline comparison

At minimum compare:

```text
DTW
vs
learned temporal encoder
```

Use the same evaluation protocol.

## Reporting rules

Always distinguish:

**TARGET** — desired engineering objective.

**MEASURED** — actual experimental result.

Never convert a target into a marketing claim.

## Suggested results table

| System | Few-shot accuracy | Cross-person | Median latency | Offline |
|---|---:|---:|---:|---|
| DTW | TBD | TBD | TBD | Yes/No |
| Learned encoder | TBD | TBD | TBD | Yes/No |

## Interpretation

A model is preferred only if its practical improvement justifies:
- complexity
- runtime
- memory
- integration risk
