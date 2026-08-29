# SignBridge+ M4 Final Engine Notes

This build makes the recognition path internally consistent and removes the known architectural traps discovered during M4/M4.5 debugging.

## Production path

CameraX -> MediaPipe 21 landmarks -> wrist/scale normalization -> EMA hybrid motion segmentation -> 66-D shape + spatial trajectory representation -> banded DTW -> three-shot class consensus -> MATCH / UNKNOWN / AMBIGUOUS.

## Important fixes

- Global wrist movement is retained in recognition; wrist-relative normalization no longer makes spatial gestures invisible.
- Teach and recognition use the same `TemporalSequence.toFeatureVectors66D()` representation.
- Gesture onset keeps a small pre-roll so the first movement is not discarded.
- Velocity is EMA-smoothed to reduce MediaPipe one-frame jitter.
- Gesture completion uses hysteresis and a longer settling window; brief pauses are less likely to truncate a gesture.
- Three demonstrations are scored as a gesture class rather than letting one anomalous prototype define the class.
- DTW uses a conservative warping band to prevent pathological temporal alignments.
- Shape and trajectory contributions are normalized and explicitly weighted.
- Profile persistence uses versioned Android `JSONObject`/`JSONArray` serialization with a temporary-file write path.
- Segmenter and enrollment state mutations are synchronized for camera/UI concurrency.
- `RealSequenceRecorder` is available for debug builds/experiments so physical captures can be collected instead of relying on synthetic benchmarks.

## Verification honesty

Synthetic unit tests validate mathematics, state transitions, serialization and invariants. They are not substitutes for physical-device accuracy. Final M4 acceptance still requires recording real iQOO camera sequences and measuring genuine-vs-unknown distances on the target device.
