# Technical Requirements Document — SignBridge+

## 1. Technical objective

Build a replaceable, benchmarkable on-device pipeline for few-shot temporal gesture recognition.

## 2. Runtime pipeline

```text
CameraX
 ↓
Landmark detector
 ↓
Normalization
 ↓
Temporal buffer
 ↓
Temporal encoder
 ↓
Embedding
 ↓
Prototype store
 ↓
Distance/similarity
 ↓
Confidence gate
 ↓
Output controller
 ↓
Android TTS
```

## 3. Landmark representation

Initial representation:
- 21 landmarks per hand
- x/y/z where available
- support one or two hands
- timestamps/frame indices

Start with hands only. Add pose only if experiments demonstrate that it is required.

## 4. Normalization

Candidate operations:
1. translation normalization
2. scale normalization
3. rotation normalization where stable
4. handedness handling

Each operation must be tested independently.

## 5. Temporal representation

Represent a gesture as a sequence:

```text
X = [x1, x2, ..., xT]
```

where each xt is the normalized landmark feature vector at time t.

The system must support variable gesture duration.

## 6. Model strategy

### Primary candidate
Pretrained/lightweight temporal encoder producing an embedding.

### Baseline
DTW over normalized temporal landmarks.

### Selection rule
Use measured unseen-class and cross-person results.

Do not optimize only random split accuracy.

## 7. Few-shot prototype

For label c with examples e1...ek:

```text
z_i = encoder(e_i)

prototype_c = mean(z_1, ..., z_k)
```

Distance can initially be cosine or Euclidean, then benchmark.

For DTW, use sequence-level distance instead of embedding distance.

## 8. Confidence

Recognition:

```text
best_score = min(distance)
second_score = next best

accept only if:
- best_score passes threshold
- margin from second best is sufficient
- temporal stability condition passes
```

Exact thresholds must be calibrated from validation data.

## 9. Unknown state

If no prototype is sufficiently close:

```text
UNKNOWN
```

UI:
> "I'm not sure. Please repeat."

## 10. Debounce

The system shall not speak on every frame.

Use:
- stable prediction duration
- cooldown
- prediction change detection

## 11. Storage

Store:
- gesture ID
- label
- prototype or enrollment representation
- threshold/calibration metadata
- model/version identifier

Avoid storing raw video unless required for debugging and consented to.

## 12. Offline boundary

No cloud dependency for:
- camera
- landmark extraction
- gesture encoder
- matching
- confidence
- text
- TTS where device TTS is available offline

If any dependency requires network access, document it.

## 13. Model deployment

Evaluate:
- LiteRT/TFLite
- MediaPipe Tasks
- Qualcomm AI Runtime where applicable
- GPU/NPU acceleration only after measurement

AI Edge Gallery is an optional experimentation/deployment aid, not a mandatory dependency.

## 14. Android structure

Suggested modules:

```text
app/
  camera/
  vision/
  preprocessing/
  gesture/
  enrollment/
  recognition/
  storage/
  speech/
  ui/
  benchmark/
```

Use interfaces around model inference so the encoder can be swapped with DTW.

## 15. Performance instrumentation

Measure timestamps at:

```text
frame received
→ landmarks complete
→ preprocessing complete
→ encoder complete
→ matching complete
→ UI prediction
→ speech request
```

Report:
- median
- p95
- FPS

## 16. Failure handling

Cases:
- camera unavailable
- tracking lost
- insufficient examples
- inconsistent enrollment
- unknown gesture
- model load failure
- TTS unavailable
- low confidence

Every failure needs a recoverable user state.

## 17. Security/privacy

- Keep enrollment data local.
- Do not upload video.
- Avoid unnecessary permissions.
- Clearly document any data retained.

## 18. Technical acceptance criteria

The prototype passes when:
- end-to-end local recognition works
- enrollment works
- unknown state works
- TTS works
- offline test passes
- latency is measured
- a baseline comparison exists
