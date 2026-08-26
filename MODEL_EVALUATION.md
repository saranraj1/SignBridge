# SignBridge+ Model Evaluation Protocol

## Goal

Select the smallest model that provides a meaningful improvement over DTW.

## Candidates

- DTW baseline
- pretrained gesture embedding model
- small temporal neural encoder
- hybrid encoder + DTW

## Dataset split

### Development
Used for threshold/model tuning.

### Validation
Used for architecture selection.

### Final test
Locked until the model choice is made.

Prefer:
- unseen gesture classes
- unseen people

## Metrics

1. few-shot accuracy
2. cross-person accuracy
3. unknown rejection
4. median latency
5. p95 latency
6. model size
7. memory

## Selection rule

Choose the model that maximizes practical value:

```text
Recognition benefit
+
robustness
+
mobile feasibility
-
complexity
-
latency
-
integration risk
```

## Important

Do not claim that the learned encoder is better until it has actually been measured.
