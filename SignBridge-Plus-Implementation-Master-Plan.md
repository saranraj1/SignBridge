# SignBridge+ — Implementation Master Plan
### iQOO Hackathon 2026, Chennai City Battle, 12–13 Sept · Open Innovation track

This plan supersedes the earlier idea-selection playbook for the *specific* project — that document still holds for general competition intelligence (rubric, Red/Green mechanics, prize structure). This one is execution-focused on SignBridge+ as scoped in your brief, resolved against the DTW-vs-learned-embedding question.

**Reconciling the two build-time assumptions**: your brief plans for ~19–21 usable engineering hours (after ceremonies, meals, mentor time, evaluation rounds); the official published structure is a 30-hour event, 55% Red Light / 45% Green Light. These aren't in conflict — treat it as **~20 hours build + 5 hours dedicated validation/demo prep = 25 active hours, inside the 30-hour event, with ~5 hours absorbed by the event's own ceremonies/meals/evaluation rounds you don't control.** All timelines below use this.

---

## A. Competition Strategy

**Verification checklist — confirm at Chennai check-in, don't assume:**
- Exact Red Light / Green Light clock windows (not published in advance)
- Exact mechanism for coding *during* Red Light (mobile IDE/Termux-style tool vs. something else)
- Whether the "working prototype" the registration flow references genuinely earns shortlisting weight, or is purely optional framing
- Current on-site rubric matches the published 30/20/15/15/10/10 split

**Track**: Open Innovation. Justification, stated in your own first sentence to the jury: *"our core contribution is a reusable on-device few-shot gesture-learning engine — ISL is the first application, not the whole product."* This is a stronger novelty claim than any of the 6 named tracks would let you make, and it's honest, not a framing trick — the few-shot mechanism genuinely is the differentiator, not the ISL vocabulary.

**Scoring exploitation**: the hybrid architecture (if the kill-gate passes) attacks four of six rubric lines simultaneously — technical depth (15%, jury) via the learned encoder; creative phone use (15%, device data) via camera+voice as the entire interaction; novelty/impact (20%, jury) via "the phone learns you" instead of "another ISL app"; demo/presentation (10%, jury) via the judge-teaches-a-gesture moment. Office Kit (10%, device data) is scored during Green Light model work, not the demo.

**Prototype advantage**: build the minimum pre-hackathon spike (Section E) regardless of whether the platform formally rewards it — its real value is de-risking the one true unknown (does the recognition mechanism work at all) before you've committed 3 people and 30 hours to it.

**Evaluation-round strategy**: if the event has a mid-way check-in/evaluation, show the *working core loop* even if ugly (raw text output, no TTS polish, no full vocabulary) rather than a prettier but non-functional UI — evaluators at this stage are almost certainly screening for "is this real," not judging final polish.

---

## B. Product

- **Problem**: everyday communication between Deaf/hard-of-hearing people and hearing people who don't know ISL has no low-friction bridge — interpreters are scarce, and every existing app assumes one side already knows a fixed, large ISL vocabulary.
- **User**: a Deaf/hard-of-hearing person and the hearing person they're interacting with, in an ordinary moment (not replacing professional interpretation).
- **Job to be done**: "let me communicate a need right now, without either of us having to already know the same system."
- **MVP**: fixed curated ISL vocabulary (25–40 signs) recognized via the pipeline in Section C, PLUS a live "teach mode" where any new gesture (not necessarily ISL) can be enrolled from 3 examples and recognized thereafter — this second piece is what makes the demo yours, not "another ISL project."
- **Non-goals** (explicit, say these out loud in the pitch before a judge asks): continuous/sentence-level ISL, facial grammar, thousands of signs, cloud backend, accounts, avatar generation, training the encoder live.
- **UX**: two modes — *Teach* (record 3 examples → name it → done) and *Communicate* (camera live, recognized text + spoken output, confidence-gated).
- **Demo**: Section K.

---

## C. Technical Architecture (resolved)

```
CAMERA (30fps)
     │
     ▼
MediaPipe Hand (+ optional Pose) Landmarker — on-device, real ML
     │
     ▼
Landmark normalization (translation, scale, rotation, handedness)
     │
     ▼
Sliding temporal window
     │
     ▼
        ┌─────────────────────────────┐
        │   PRIMARY MATCHER            │
        │   (decided by Section E spike)│
        │                               │
        │  Path A — Frozen pretrained  │
        │  temporal encoder (tiny GRU  │
        │  or 1D-CNN, trained via      │
        │  prototypical-network-style  │
        │  metric learning, PRE-EVENT  │
        │  only) → embedding →         │
        │  nearest-prototype (cosine)  │
        │                               │
        │  Path B — Normalized-        │
        │  landmark DTW against stored │
        │  example sequences           │
        └───────────────┬───────────────┘
                        ▼
              Confidence gate
          (below threshold → "not recognized",
           never guesses)
                        ▼
                   Recognized text
                   ↙            ↘
           Android TTS      (stretch: tiny local
           (offline)         LLM phrase-smoothing
                              via LiteRT, NOT AI
                              Edge Gallery's chat UI)

Reverse channel (should-have): Mic → on-device ASR → text display
```

**Why this is the right call, precisely:**
- MediaPipe's landmark model is itself a real trained neural network — this is true "AI at the core" even before you get to the matcher, and it's a fact you can state confidently if a judge tries to dismiss the sensing layer as "just CV."
- The encoder (Path A) is only shipped if it beats DTW on a **held-out gesture class** in the pre-event spike — i.e., a class not in its pretraining set, tested the way it'll actually be used live. This is the correct test; testing it only on pretraining-set classes would be a false positive.
- DTW (Path B) is not the fallback you apologize for — it's the technique the ML literature itself uses when there's no training data (which is your exact situation for the live "teach a new gesture" case if the encoder doesn't generalize). Either path gives you an honest, technically literate answer to "where's the AI."
- **AI Edge Gallery is explicitly out of the critical path** — it's a general-purpose LLM/GenAI showcase (Gemma via LiteRT-LM), not a tool for training or deploying a small custom metric-learning model. If you want the "AI Edge" name legitimately in your architecture, it's the **LiteRT runtime** (which both Gallery and your custom model would sit on) doing NPU/GPU-accelerated on-device inference — that's a real, checkable claim distinct from having used the Gallery app itself.
- **Confidence gate is a product feature, not an error state** — never speak a low-confidence guess. This single design choice is what separates "engineered" from "demoed" in a judge's eyes.

**Privacy**: all enrollment data (landmark sequences, not raw video) stored locally only. Say this proactively in the pitch — it's a real advantage of the on-device architecture, not an afterthought.

---

## D. Research — What Already Exists (verified, not assumed)

- **Prototypical Networks** (Snell et al., NeurIPS 2017) is the correct, established technique for exactly this problem — metric-learning an embedding space pre-trained on diverse classes, then doing nearest-prototype classification on unseen classes from a few examples. Applied precedent exists for skeleton-based one-shot gesture recognition, EMG-based few-shot gesture adaptation, and on-device human-activity-recognition personalization via the same architecture — this is not a novel research risk, it's an application of a known-working method.
- **Isolated ISL sign recognition** (MediaPipe landmarks + LSTM/similar) hits 92–99% on curated vocabularies of 11–50 signs in academic settings — this is your accuracy ceiling to be realistic against, not a target to claim you beat.
- **Continuous/sentence-level ISL and fingerspelling remain genuinely unsolved** (a 2025 dataset paper reports ~83% character error rate even after fine-tuning) — this validates your MUST-NOT-BUILD list, don't second-guess it under pressure at the event.
- **No mainstream consumer app combines**: real-time, fully offline, on-device recognition-to-speech, AND live few-shot personalization of arbitrary new gestures. Existing ISL apps found in the literature (DEF-ISL App, SignAble) are lookup/dictionary tools, not recognizers.
- **Claims you CAN make**: on-device, fully offline, sub-second sign-to-speech latency; genuine few-shot personalization of novel gestures (if the encoder passes its kill-gate) or transparent DTW-based few-shot matching (if it doesn't); zero cloud dependency, zero per-use cost, local-only data.
- **Claims you CANNOT make**: state-of-the-art ISL recognition accuracy; general/continuous ISL translation; signer-independent robustness without testing it (test it — Section N).

---

## E. Pre-Hackathon Prototype (the single most important artifact before 12 Sept)

**Extended 4-hour feasibility spike** (your brief's Section 17, with the encoder kill-test folded in):

| Hour | Task |
|---|---|
| 1 | Camera → MediaPipe landmarks running live on the actual target Android hardware |
| 2 | Capture temporal gesture sequences from 2+ team members; normalize |
| 3a | Implement DTW matcher (baseline) | 
| 3b | *(parallel, different laptop)* Begin encoder pretraining data collection: 15–20 team members' varied gestures across sessions |
| 4 | **The actual decision test**: Person A enrolls a brand-new gesture never used in training data; Person B (a stranger to the model) attempts it. Measure DTW accuracy vs. (once trained) encoder accuracy on this *held-out* class. |

**Decision rule**: if the encoder — once it exists — beats DTW on the held-out class by a clear margin, commit to the hybrid. If not, ship DTW-only and be honest about why in the pitch (Section D gives you the exact honest framing).

**Encoder pretraining, if pursued** (days before the event, not hackathon hours):
- Collect 30–50 *varied* arbitrary gestures across team members (not just ISL signs — diversity is what makes the embedding space generalize)
- Augment with synthetic time-warp/rotation/scale perturbations to multiply effective examples
- Train with episodic prototypical-network loss (small GRU or 1D-CNN, tens of thousands of parameters — this trains in hours on a laptop GPU or free-tier Colab, not days)
- Export to TFLite/LiteRT, verify it runs on-device before the event, not for the first time at hour 2

**Rule compliance**: pretraining and research happen before the event (permitted preparation per your own brief's Section 18 distinction); final integration, UI, vocabulary selection, and submission code happen inside the event window. Verify this split against the actual T&Cs before carrying trained weights into the venue — if the rules require the model itself to be trained at the event, the hybrid is off the table and you ship DTW, full stop, no exceptions.

---

## F. Implementation Milestones

| Milestone | Acceptance criteria |
|---|---|
| M1 — Sensing works | Camera → landmarks, stable at ≥15fps on target hardware |
| M2 — First core loop | One gesture → recognized → spoken, even ugly |
| M3 — Teach mode | 3-example enrollment → recognition of that exact gesture works |
| M4 — Confidence gating | System says "not recognized" on genuinely unknown input, never guesses |
| M5 — Offline proven | Airplane mode, full loop still works |
| M6 — Stranger-tested | A non-team-member's gesture is correctly enrolled and recognized |
| M7 — Demo-frozen | No new features; only bug fixes from this point |

**Fallback paths**: if M3 (few-shot enrollment) proves unreliable by hour 10, cut to a fixed curated-vocabulary-only demo (no live teaching) — still a complete, honest, working product, just a smaller novelty claim. If the whole CV pipeline proves unstable by hour 4 (M1 fails), pivot per the earlier playbook's backup recommendation (TrafficEar — same architecture philosophy, lower CV risk).

---

## G. Team Allocation

| Person | Owns | Also does |
|---|---|---|
| **P1 — AI/CV** | Landmarks, normalization, temporal representation, matcher (DTW and/or encoder), confidence gating, latency, benchmarking | Leads all technical Q&A prep; owns the encoder pretraining work in the pre-hackathon week |
| **P2 — Android/Device** | CameraX pipeline, app architecture, enrollment UX, recognition UI, TTS, ASR, local storage, Red/Green workflow, Office Kit | Builds the "record a gesture" tool *first* so P1 isn't blocked waiting on UI |
| **P3 — Product/Data/Demo** | ISL vocabulary sourcing (from credible, attributed references — never invented from English), data curation, continuous testing from hour 5 onward, failure-mode tracking, UX polish, demo script, pitch, submission | Owns stranger-testing recruitment (mentors, other teams, volunteers) throughout, not just in the final validation window |

**Integration checkpoints** (every 3–4 hours, all three present, full pipeline run end-to-end): camera→landmarks; landmarks→matching; matching→text; text→speech; teach mode; offline; stranger test. Never big-bang integrate late.

---

## H. Pre-Hackathon Schedule (the week before 12 Sept)

- **Now–T minus 10 days**: source ISL vocabulary from a credible, attributable reference (not invented); confirm licensing/attribution; run the extended 4-hour feasibility spike (Section E) and make the DTW-vs-hybrid call.
- **T minus 9 to T minus 5 days** (only if hybrid was chosen): collect diverse pretraining gesture data, augment, train the encoder, validate on a genuinely held-out class, export to TFLite/LiteRT, confirm on-device inference works.
- **T minus 4 to T minus 2 days**: Office Kit installed and rehearsed by all three (screen mirror, clipboard, file transfer, remote control); repo/environment scaffolded and confirmed working on the actual phone model if accessible, or the closest available Android device.
- **T minus 1 day**: full team dry-run of the demo script end-to-end on whatever prototype exists; pack chargers, confirm devices, sleep.

---

## I. Hackathon Hour-by-Hour (≈20 build hours; exact Red/Green windows TBD on-site — every hour lists what's phone-only-safe vs. needs the laptop, so the team can pivot the instant the light changes)

| Hours | Red-Light-safe | Green-Light-needed | Milestone |
|---|---|---|---|
| 0–2 | Vocabulary/scope lock, demo script draft, gesture recording (all 3 members) | Repo scaffold, dependency install, app shell | Scope frozen |
| 2–5 | Continue gesture recording (multiple angles), UI sketching | Normalization code, matcher implementation, TTS integration | M1 |
| 5–8 | Manual QA of recordings, enrollment UX build, on-device testing of matcher | Confidence-gate tuning, TTS/text pipeline wiring | M2 |
| 8–12 | Continuous testing by P3, robustness passes, curated vocabulary expansion | Smoothing/debounce logic, ASR integration if stable | M3, M4 |
| 12–16 | Offline hardening tests, Red-Light-compatible polish | Latency instrumentation, Office Kit session sync | M5 |
| 16–19 | Stranger testing begins in earnest, fix top 2 failure modes only | Final model swap-in if applicable, final build compile | M6 |
| 19–20 | Feature freeze declared | Submission packaging | M7 |

---

## J. Final 5-Hour Validation (hours 20–25)

| Block | Task |
|---|---|
| 90 min | Stranger testing — as many non-team testers as you can recruit (mentors, other teams) |
| 60 min | Fix only the top two recurring failure classes — no new features |
| 60 min | Demo rehearsal ×5, different "judges" each time, timed |
| 45 min | Record backup demo video (insurance against live failure) |
| 45 min | Q&A drill (Section M) |

---

## K. Demo Scripts

**30s**: hook → judge teaches a gesture → phone recognizes it → speaks it.

**60s**: adds → Wi-Fi disabled → still works.

**90s** (primary target):
| Time | Beat |
|---|---|
| 0–10s | "Can you teach my phone a gesture in 30 seconds?" |
| 10–30s | Judge performs an arbitrary gesture 3x, names it |
| 30–40s | System confirms enrollment |
| 40–50s | Judge repeats it → recognized, spoken |
| 50–60s | Wi-Fi/airplane mode disabled, visibly |
| 60–70s | Judge repeats again → still works, offline |
| 70–85s | Quick ISL communication example (a curated sign → spoken phrase) |
| 85–90s | "We don't want users to learn the machine. We want the machine to learn the user." |

**Fallback**: if a judge declines to participate, a trained team member demonstrates the identical flow — never let the whole demo depend on judge cooperation.

---

## L. Pitch

- **20s open**: "Can you teach a phone a new gesture in 30 seconds?" (then hand over the phone)
- **60s**: problem → insight ("we make the phone adapt to the user, not the other way around") → live demo
- **3min**: adds architecture (landmarks → few-shot matcher → confidence gate → speech, entirely on-device), the honest benchmark (latency, offline, not raw accuracy), ISL as the first application, Open Innovation framing, roadmap
- **Speaker allocation**: P3 leads narration; P1 fields any technical question directly (rehearsed hot-seat); P2 handles the physical device handoff to the judge smoothly — this should be rehearsed as a choreographed motion, not improvised.

---

## M. Q&A — Prepared Answers

| Question | Honest answer |
|---|---|
| Why not a big CNN/transformer? | Three live examples can't train one; we'd be pretending. Our matcher is either a pre-trained-and-frozen encoder used purely for inference, or DTW — both are honest for a zero-shot-at-runtime regime. |
| Where's the actual AI? | MediaPipe's landmark model is a trained neural network — that's real. [If hybrid shipped:] our temporal encoder was trained pre-event via prototypical-network-style metric learning on a diverse gesture set, then frozen; at runtime we only compute embeddings and average prototypes, we never retrain live. |
| Why DTW at all? | It's the historically correct technique for matching with zero training data available at test time — this predates and coexists with neural approaches for exactly this reason. |
| Does this scale to full ISL? | No, and we're not claiming it does — continuous/sentence-level ISL remains an open research problem (cite the ~83% CER result honestly if pressed). We scoped to isolated signs deliberately. |
| What about unseen signers? | We tested it — [state your actual measured cross-signer accuracy, honestly, whatever it is]. |
| What happens on a wrong/unknown gesture? | Confidence gate rejects it and asks for a repeat — never a false spoken output. |
| Why local, not cloud? | Privacy (you're filming someone's hands/face), zero latency, zero connectivity dependency, zero per-use cost. |
| Why Open Innovation and not HealthTech/Smart Living? | Our core contribution is the few-shot gesture-learning mechanism; ISL is our first application of it, not the whole product. |
| Does it use the NPU? | [State your actual measured delegate/backend, honestly — don't claim NPU acceleration you haven't verified.] |
| What existed before the hackathon? | The pre-event feasibility spike and [if applicable] encoder pretraining — disclose this proactively, it's good engineering practice, not something to hide. |
| Biggest limitation right now? | [Name the real one — likely cross-signer generalization or vocabulary size — never claim there isn't one.] |

*(Extend this table with the remaining ~20 questions from your brief's Section 23 using the same honesty standard — never exaggerate a number you haven't measured.)*

---

## N. Testing Matrix

Signer (3 teammates + ≥5 strangers) × lighting (bright/indoor/dim) × background (plain/busy) × distance × speed × handedness × connectivity (online/Wi-Fi off/airplane mode) × failure modes (unknown gesture, occlusion, lost tracking). Run this continuously from hour 5, not only in the final validation window — a failure found at hour 8 is fixable; one found at hour 24 is not.

---

## O. Metrics — Exact Measurement, Never Claimed Without Measuring

- **Personalization time**: stopwatch, first enrollment example → first successful recognition
- **Latency**: instrument camera-frame timestamp → spoken-output timestamp; report median and p95 if you have the samples
- **Recognition accuracy**: known-signer and unseen-signer, separately reported
- **False-positive rate** and **unknown-rejection rate**, separately
- **Offline**: binary pass/fail with connectivity disabled, demonstrated live
- Never present a target number as an achieved one in the pitch.

---

## P. Risks

| Risk | Mitigation |
|---|---|
| Cliché "another ISL app" read | Lead with the few-shot mechanism, reveal ISL second |
| Cross-signer failure | Personalization design + stranger testing from hour 5 |
| Encoder doesn't generalize | Kill-gate in Section E — ship DTW, no shame in it |
| Two-hand occlusion | Curated vocabulary avoids the worst offenders; test early |
| Latency | Landmark-based, not raw-video-based, matching keeps compute light |
| Red Light stalls the team | Dual task queues (Section I) so any hour has safe work available |
| Overbuilding | Hard feature freeze at hour 19–20 |
| Rules non-compliance on pre-trained weights | Verify explicitly before the event; DTW-only is the compliant fallback |

---

## Q. Documentation Checklist

README (what it does, what it doesn't, how to run), architecture diagram, measured benchmark numbers, honest limitations section, ISL vocabulary source/attribution, licenses for any dataset/model used, backup demo video, pitch deck (8 slides max, per your own outline), submission checklist confirmed against the actual portal requirements.

---

## R. Decision Framework at Each Milestone

At every checkpoint in Section F: **continue** if on track; **modify** if a component needs rework but the architecture holds; **simplify** if scope is the problem (cut vocabulary, cut teach-mode, cut ASR reverse-path — in that order); **pivot** if the CV pipeline itself is unstable by hour 4–5 (see backup idea in the earlier playbook); **kill** only if M1 (basic sensing) is unachievable on the actual hardware — at that point no amount of remaining time fixes it, and switching ideas entirely is the correct call, not a failure.

**The line that should govern every judgment call this week**: would this still be a compelling, honest project if you removed the word "ISL" and only the few-shot gesture-learning mechanism remained? If yes, you're building the right thing.
