# SignBridge+ Test Plan

## 1. Unit tests

Test:
- normalization
- feature dimensions
- embedding dimensions
- prototype averaging
- distance calculations
- threshold logic
- debounce
- serialization/deserialization

## 2. Integration tests

Test:
- camera → landmarks
- landmarks → preprocessing
- preprocessing → encoder
- encoder → matcher
- matcher → UI
- UI → TTS
- storage persistence

## 3. Recognition experiments

### Experiment A — Same signer
Enroll 3 examples; test repeated examples.

### Experiment B — Unseen signer
Person A enrolls; Person B tests.

### Experiment C — Unseen gesture class
Hold out entire gesture classes from encoder validation.

### Experiment D — Unknown gesture
Test gestures that have no prototype.

## 4. Environment matrix

| Factor | Conditions |
|---|---|
| Lighting | bright / normal / dim |
| Background | plain / busy |
| Distance | near / normal / far |
| Speed | slow / normal / fast |
| Hand | left / right |
| Occlusion | none / partial |
| Connectivity | online / offline |

## 5. Metrics

### Enrollment time
Time from first enrollment capture to successful test state.

### Recognition latency
Timestamp from usable input window to final prediction.

### Accuracy
correct predictions / total test examples.

### Unknown rejection
unknown inputs correctly rejected / total unknown inputs.

### Cross-person accuracy
correct predictions from people not used for enrollment.

## 6. Prototype acceptance

Minimum:
- stable same-person recognition
- successful three-shot enrollment
- TTS
- offline critical path

Strong:
- cross-person recognition
- unknown rejection
- measured latency
- stranger testing

## 7. Regression testing

Every model or preprocessing change must rerun:
- baseline test set
- unseen-person test
- unknown test
- latency test

Do not optimize one metric while silently breaking another.
