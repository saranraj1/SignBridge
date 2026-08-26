# SignBridge+ Risk Register

| Risk | Severity | Mitigation | Fallback |
|---|---|---|---|
| Learned encoder underperforms DTW | High | Benchmark early | DTW |
| Cross-person failure | High | Personalization + testing | Increase examples / simplify gestures |
| Landmark instability | High | Early device testing | Hands-only |
| Two-hand occlusion | Medium | Curated vocabulary | One-hand gestures |
| Lighting variation | Medium | Normalize + test venue | Curated demo conditions |
| False positives | High | Confidence + UNKNOWN | Higher threshold |
| Android integration delay | High | Integrate early | Simpler runtime |
| Model too slow | High | Smaller model / lower inference rate | DTW |
| TTS unavailable offline | Medium | Test device TTS | Local text-only fallback |
| Prototype looks like generic ISL app | High | Lead with judge-teach mechanism | Reframe around gesture engine |
| Overbuilding | High | Hard scope freeze | Remove features |
| Rule ambiguity | High | Verify official T&C | Ask organizer |
| Dataset/license issue | High | Audit sources | Replace dataset |
| Judge refuses demo | Medium | Trained teammate fallback | Backup video |
