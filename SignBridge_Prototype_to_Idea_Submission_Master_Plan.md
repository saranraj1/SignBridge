# SignBridge+ — Prototype-to-Idea-Submission Master Plan

## Objective

Build a **small, genuinely working prototype** of SignBridge+ before the iQOO Reskilll idea-submission/screening stage, assuming the event rules permit the relevant pre-event prototype/model work.

The prototype is **not the final hackathon product**.

Its job is to prove the single strongest claim:

> **A phone can learn a gesture from only a few examples and recognize it locally in real time.**

The first application is Indian Sign Language (ISL) communication.

The prototype should make a reviewer think:

> **“This is technically real, the mechanism is different, and I want to see what this team can build in the full hackathon.”**

---

# 1. What We Are Actually Building

## Prototype name

**SignBridge+**

## Core promise

> **Teach your phone a gesture. Let it speak for you.**

## Prototype definition

SignBridge+ Prototype is an **on-device few-shot temporal gesture-learning system** that:

1. Captures a gesture through the phone camera.
2. Extracts hand/pose landmarks locally.
3. Converts a short temporal gesture sequence into a representation using a pretrained lightweight temporal AI model.
4. Learns a personalized prototype from approximately three examples.
5. Recognizes a later occurrence of that gesture.
6. Applies a confidence/unknown gate.
7. Displays the recognized meaning.
8. Speaks the result locally using TTS.
9. Continues functioning with network connectivity disabled.

---

# 2. What the Prototype Must Prove

The prototype must demonstrate these five things:

### P1 — Few-shot learning

A new gesture can be taught from only a few examples.

### P2 — Personalization

The system can associate that gesture with a user-selected meaning.

### P3 — On-device inference

The recognition path runs locally on the phone.

### P4 — Real-time behavior

Recognition happens fast enough to feel immediate.

### P5 — Offline operation

The core pipeline does not require cloud connectivity.

These five claims are more important than the number of signs supported.

---

# 3. What It Does NOT Need to Prove

The prototype does **not** attempt to prove:

- universal ISL translation
- continuous sentence-level ISL
- facial grammar interpretation
- thousands of signs
- production-scale deployment
- perfect signer independence
- clinical accessibility certification
- complete two-way conversation
- large language understanding
- a giant neural model

We explicitly scope those out.

This prevents the reviewer from judging a 30-hour hackathon prototype against a research-grade continuous sign-language translation system.

---

# 4. Architecture

## Primary architecture

```text
                    PHONE CAMERA
                         │
                         ▼
              Landmark Detection Model
             (MediaPipe / AI Edge option)
                         │
                         ▼
                Landmark Normalization
                         │
                         ▼
                 Temporal Window
                         │
                         ▼
          Pretrained Temporal AI Encoder
                         │
                         ▼
                  Gesture Embedding
                         │
              ┌──────────┴──────────┐
              │                     │
       Prototype Library          DTW
              │                     │
              └──────────┬──────────┘
                         ▼
                Similarity Scoring
                         │
                         ▼
                  Confidence Gate
                   /          \
                  /            \
             CONFIDENT        UNKNOWN
                │                │
                ▼                ▼
             Gesture        "Please repeat"
                │
                ▼
              TEXT
                │
                ▼
               TTS
                │
                ▼
             SPEECH
```

## Optional future path

```text
Recognized gesture tokens
          ↓
Optional local language model
          ↓
Natural sentence
```

This is **not part of the prototype critical path**.

---

# 5. AI Architecture

AI is central to the prototype.

There are potentially three ML components:

### A. Landmark perception

A trained computer-vision model detects hand/pose landmarks.

### B. Temporal gesture encoder

A lightweight pretrained model converts a sequence of normalized landmarks into an embedding.

Example:

```text
[T × landmarks × features]
              ↓
        Temporal encoder
              ↓
       64/128/256-D vector
```

The exact dimension must be determined experimentally.

### C. Few-shot personalization

The encoder is not retrained when a user teaches a gesture.

Instead:

```text
Example 1 → embedding 1
Example 2 → embedding 2
Example 3 → embedding 3
                    ↓
               prototype
```

A new gesture is encoded and compared against stored prototypes.

This is the core few-shot mechanism.

---

# 6. Model Strategy

We should investigate four options.

## Option A — Pretrained temporal encoder + prototype matching

**Primary target.**

Advantages:
- strong AI story
- few-shot compatible
- no event-time training
- compact inference
- easy personalization

## Option B — Pretrained encoder + DTW

Potentially stronger for temporal alignment.

## Option C — DTW-only baseline

Fallback.

Advantages:
- extremely fast to implement
- deterministic
- no training
- explainable

## Option D — Small custom temporal model

Only if pre-event experiments demonstrate a clear benefit.

### Decision rule

Do not select the most sophisticated architecture.

Select the architecture that gives the best combination of:

**unseen-gesture recognition + cross-person robustness + latency + Android feasibility + implementation risk.**

---

# 7. The Most Important Pre-Prototype Experiment

Before building the UI, prove this:

```text
Person A
   ↓
3 examples
   ↓
prototype
   ↓
Person B performs gesture
   ↓
recognized?
```

This is more important than supporting 20 ISL signs.

If it works, we have a real foundation.

If it fails, investigate whether the failure is:

- landmark representation
- normalization
- temporal window
- encoder
- prototype construction
- distance metric
- threshold
- gesture selection

---

# 8. Dataset Strategy

We need two different datasets.

## Dataset A — Encoder validation

Purpose:

Determine whether the pretrained temporal model creates useful embeddings.

Requirements:

- multiple gesture classes
- multiple people
- different repetitions
- variation in speed
- variation in orientation where possible

## Dataset B — Few-shot evaluation

Purpose:

Simulate the actual product.

Protocol:

```text
Known training/representation classes
                 ↓
        hold out entire gesture classes
                 ↓
       3-shot enrollment
                 ↓
      test new examples of class
```

This is much more meaningful than random frame-level train/test splitting.

---

# 9. Avoid Data Leakage

Do not allow:

- the same video in train and test
- nearly identical repetitions across splits
- frames from one recording appearing in multiple splits
- test gesture examples influencing prototype construction

The most important validation should use **unseen gesture classes and unseen people** wherever practical.

---

# 10. Prototype Gesture Set

Do not start with 30–40 signs.

Start with approximately:

### Generic demonstration gestures
1–3 custom gestures.

### ISL application
Approximately 5–10 validated signs/phrases.

Candidate semantic categories:

- Hello
- Yes
- No
- Thank you
- Help
- Water
- Food
- Emergency
- Wait
- Call someone

The exact ISL gestures must be verified from credible sources.

Do not invent ISL signs from English translations.

---

# 11. Teach Mode

## Screen 1

### Teach New Gesture

```text
Teach your phone a new gesture.

[ START ]
```

## Screen 2

### Record Example 1

```text
Perform the gesture.

Example 1 / 3
████░░░░░░
```

## Screen 3

Example 2.

## Screen 4

Example 3.

## Screen 5

### Name it

```text
What does this gesture mean?

[ Emergency             ]

[ SAVE ]
```

## Screen 6

### Learned

```text
✓ Gesture learned

Emergency

Try it now →

[ TEST ]
```

The entire interaction should ideally take under 30 seconds.

---

# 12. Recognition Mode

Minimal UI:

```text
┌──────────────────────────┐
│       SIGNBRIDGE+        │
│                          │
│       CAMERA VIEW        │
│                          │
│          🤟              │
│                          │
│      EMERGENCY           │
│                          │
│ Confidence: 94%          │
│ Latency: 128 ms          │
│                          │
│       🔊 SPEAKING        │
└──────────────────────────┘
```

Do not turn the prototype into a dashboard.

Metrics are evidence, not the product.

---

# 13. Confidence System

The system must have an explicit unknown state.

```text
Prediction
    ↓
Confidence
   / \
 HIGH LOW
  ↓    ↓
 SPEAK RETRY
```

Example:

### High confidence

> Emergency — 94%

### Low confidence

> I'm not sure. Please repeat.

This prevents embarrassing false predictions during the demo.

---

# 14. Temporal Controls

The implementation must define experimentally:

- frame rate
- temporal window
- stride
- minimum gesture duration
- stable prediction duration
- cooldown
- repeated-trigger prevention

A single prediction should not cause:

> “Emergency Emergency Emergency Emergency…”

The system should speak once per recognized gesture.

---

# 15. Offline Architecture

The prototype should ideally support:

```text
Camera
 ↓
Landmarks
 ↓
Temporal encoder
 ↓
Few-shot matching
 ↓
Confidence
 ↓
Text
 ↓
TTS
```

with:

**Wi-Fi OFF**
**Mobile Data OFF**
**Airplane Mode**

No cloud dependency in the critical path.

---

# 16. Google AI Edge / LiteRT Strategy

Investigate:

- MediaPipe
- Google AI Edge
- LiteRT
- AI Edge Gallery
- Android deployment
- hardware acceleration

But do not force AI Edge Gallery into the product.

Use it only if it materially improves:

- model deployment
- experimentation
- benchmarking
- local inference
- NPU acceleration

The product architecture must remain understandable without AI Edge Gallery.

---

# 17. Prototype Development Phases

## Phase 1 — Research Spike

Goal:

Select:
- landmark pipeline
- encoder
- embedding dimension
- similarity metric
- DTW baseline

Output:

**Architecture decision document.**

---

## Phase 2 — Offline ML Prototype

Build outside Android first if faster:

```text
landmark sequence
      ↓
encoder
      ↓
embedding
      ↓
prototype
      ↓
recognition
```

Output:

A script/notebook that proves few-shot recognition.

---

## Phase 3 — Android Inference

Move the critical model path to Android.

Goal:

```text
camera
→ landmarks
→ encoder
→ prototype
```

Output:

Live recognition on phone.

---

## Phase 4 — Teach Mode

Add:

- 3-shot capture
- prototype creation
- local storage
- labeling

---

## Phase 5 — TTS

Recognition:

```text
gesture
→ meaning
→ speech
```

---

## Phase 6 — Offline Validation

Disable all network access.

Repeat the complete flow.

---

## Phase 7 — Prototype Hardening

Fix:

1. recognition failures
2. tracking failures
3. false positives
4. latency
5. UI confusion

Only fix the highest-impact issues.

---

# 18. Team Allocation

## Person 1 — AI/CV

### Deliverables

- landmark pipeline
- normalization
- encoder integration
- embedding generation
- prototype matching
- DTW baseline
- confidence scoring
- benchmarks

### Acceptance criterion

A script can:

```text
3 examples
→ prototype
→ unseen example
→ correct prediction
```

---

## Person 2 — Android

### Deliverables

- CameraX
- live preview
- landmark integration
- Teach UI
- Recognition UI
- local storage
- TTS
- offline verification

### Acceptance criterion

A user can complete:

```text
Teach
→ recognize
→ hear speech
```

without a laptop.

---

## Person 3 — Product/Data/Demo

### Deliverables

- gesture dataset
- ISL references
- labeling
- testing
- UX copy
- demo flow
- benchmark sheet
- video
- submission assets

### Acceptance criterion

A reviewer can understand the product in under 15 seconds.

---

# 19. Parallel Execution

Do not wait for one person to finish everything.

Example:

```text
P1:
Encoder experiments
       │
       ├──────→ model artifact
       │
P2:
Android camera/UI
       │
       ├──────→ integration
       │
P3:
Dataset/demo/UX
       │
       └──────→ test cases
```

Integrate at every meaningful milestone.

---

# 20. Prototype Acceptance Criteria

The prototype is considered **minimum acceptable** when:

- camera works
- landmarks work
- 3-shot enrollment works
- personalized prototype is created
- unseen repetition is recognized
- text appears
- TTS works
- core path works offline
- no obvious repeated-trigger bug

---

# 21. Strong Prototype

A strong prototype additionally has:

- cross-person testing
- unseen gesture testing
- measured latency
- confidence threshold
- unknown rejection
- 5+ useful gestures
- at least a few validated ISL examples
- clean Teach/Recognize UX
- airplane-mode demo

---

# 22. Exceptional Prototype

An exceptional screening prototype can demonstrate:

> Judge invents a gesture → teaches it → phone learns → judge repeats → phone speaks → internet is disabled → it still works.

This should be the target.

---

# 23. Benchmark Table

Create a real benchmark sheet.

| Metric | Target | Measured |
|---|---:|---:|
| Enrollment time | ≤30 sec | TBD |
| Recognition latency | <300 ms target | TBD |
| FPS | ≥25 target | TBD |
| Few-shot accuracy | Measure | TBD |
| Cross-person accuracy | Measure | TBD |
| Unknown rejection | Measure | TBD |
| Offline success | 100% target | TBD |
| Memory | Measure | TBD |

Never replace TBD with an invented number.

---

# 24. Benchmark Comparison

Do not claim:

> “We have better accuracy than all existing ISL systems.”

Instead compare:

### SignBridge+
- personalization time
- offline operation
- on-device latency
- unseen-user adaptation

against the capabilities of relevant alternatives.

The benchmark claim should be:

> **“Our product differentiator is adaptation speed and local operation, not a claim of state-of-the-art ISL accuracy.”**

---

# 25. Prototype Video

Target duration:

**30–60 seconds.**

## Shot 1 — Hook

Text:

> “Can a phone learn a new gesture in 30 seconds?”

## Shot 2 — Teach

Show three examples.

## Shot 3 — Learned

Show:

> Gesture learned ✓

## Shot 4 — Recognition

Repeat.

Phone displays:

> EMERGENCY

## Shot 5 — Speech

TTS:

> Emergency.

## Shot 6 — Offline

Airplane mode.

Repeat.

## Shot 7 — Closing

> **SignBridge+ — Teach your phone a gesture. Let it speak for you.**

No long architecture narration.

The reviewer should see the mechanism working.

---

# 26. Idea Submission Description

## Short version

> **SignBridge+ is an on-device few-shot gesture-learning system that learns a user's gesture from just a few examples and recognizes it in real time. Instead of relying on a fixed vocabulary, it builds a personalized gesture library locally on the phone. We demonstrate the mechanism through Indian Sign Language communication, converting recognized gestures into speech and text without requiring the cloud.**

---

# 27. Stronger Screening Version

> **What if your phone could learn a new gesture in 30 seconds? SignBridge+ is an on-device few-shot gesture-learning system that learns personalized gestures from a few examples, recognizes them in real time, and converts them into speech. Unlike fixed-vocabulary sign-language classifiers, the phone adapts to the user instead of requiring the user to adapt to a predefined model. We demonstrate this through Indian Sign Language communication, with the critical recognition pipeline running locally and offline.**

---

# 28. Why Open Innovation

Use:

> **“Our core innovation is a reusable on-device few-shot gesture-learning engine. Indian Sign Language is the first high-impact application of that mechanism, rather than the entire technical scope of the project.”**

This makes the track choice defensible.

---

# 29. Prototype Screening Judge Journey

The reviewer sees:

### Name
SignBridge+

### Description
“Phone learns gesture from a few examples.”

### Video
Phone actually learns gesture.

### Prototype
Working APK/demo.

### Mental state

```text
Generic AI project
       ↓
Interesting
       ↓
Wait...
       ↓
It learns?
       ↓
Few-shot?
       ↓
Offline?
       ↓
ISL application?
       ↓
Shortlist
```

---

# 30. Red-Team Test

Before submission, ask five people who know nothing about the project:

### Question 1
“What do you think this does?”

If they answer:

> “AI sign language translator”

the mechanism isn't obvious enough.

If they answer:

> “It learns gestures from you”

we are positioned correctly.

### Question 2
“What is unusual about it?”

Desired:

> “It learns a new gesture.”

### Question 3
“What would you want to test?”

Desired:

> “I want to teach it my own gesture.”

If users don't naturally want to test it, improve the demo.

---

# 31. Kill Gates

## Kill/Pivot Gate 1

If landmarks cannot run reliably on target hardware:

**simplify landmark pipeline.**

## Kill/Pivot Gate 2

If temporal encoder is unstable:

**DTW baseline.**

## Kill/Pivot Gate 3

If encoder does not outperform DTW meaningfully:

**use DTW.**

## Kill/Pivot Gate 4

If Android inference is too slow:

**smaller model / lower frequency / DTW.**

## Kill/Pivot Gate 5

If cross-person recognition is poor:

**increase personalization examples or simplify gesture set.**

## Kill/Pivot Gate 6

If ISL signs introduce instability:

**retain generic gesture learning demo and use only validated ISL examples.**

Do not allow sunk-cost bias.

---

# 32. Prototype Schedule

## Week 1 — Technical Feasibility

### Goal
Prove few-shot recognition.

Deliver:
- landmark extraction
- encoder comparison
- DTW baseline
- prototype matching
- first metrics

---

## Week 2 — Android

### Goal
Move core inference onto phone.

Deliver:
- CameraX
- landmarks
- encoder
- matching
- live recognition

---

## Week 3 — Teach Mode

### Goal
User can create a new gesture.

Deliver:
- 3-shot enrollment
- local storage
- label
- recognition

---

## Week 4 — Productization

### Goal
Make the prototype understandable.

Deliver:
- UI
- TTS
- confidence
- unknown state
- offline mode

---

## Week 5 — Testing

### Goal
Find real failures.

Deliver:
- stranger testing
- unseen gesture testing
- latency
- FPS
- false positives
- failure matrix

---

## Week 6 — Submission

### Goal
Create evidence.

Deliver:
- prototype video
- deck
- architecture diagram
- benchmark table
- description
- screenshots
- final APK/demo

The exact number of weeks can be compressed or expanded based on the actual deadline.

---

# 33. Submission Package

Prepare:

### 1. Project name
SignBridge+

### 2. One-line pitch
> Teach your phone a gesture. Let it speak for you.

### 3. Short description

### 4. Problem

### 5. Solution

### 6. Novelty

### 7. Technology

### 8. Prototype video

### 9. Prototype link/APK if permitted

### 10. Architecture diagram

### 11. Benchmark results

### 12. Team information

### 13. Limitations

### 14. Future roadmap

---

# 34. What We Should Reveal

Reveal:

- few-shot learning
- on-device AI
- personalization
- offline operation
- measurable latency
- ISL application
- judge interaction
- prototype evidence

Do not reveal unnecessary implementation details that make the concept harder to understand.

The reviewer needs to understand:

> **What is different?**

before:

> **How every tensor works.**

---

# 35. What We Should NOT Claim

Never claim without evidence:

- “first”
- “best”
- “state-of-the-art”
- “100% accurate”
- “universal ISL”
- “under 300ms” before measuring
- “runs on NPU” before validating
- “works for everyone”
- “solves sign language”

Use:

> target

until measured.

Use:

> prototype demonstrates

rather than:

> production-ready.

---

# 36. Prototype vs Final Hackathon Product

## Prototype

Proves:

> **few-shot gesture learning works.**

## Hackathon product

Adds:

- stronger UX
- validated ISL vocabulary
- robustness
- two-way communication if time permits
- performance optimization
- benchmark evidence
- polished demo
- final product story

The prototype is the **technical foundation**, not the final scope.

---

# 37. Final Prototype Demo Script

### Opening

> “Judge, can you teach a phone a gesture in 30 seconds?”

### Teach

> “Perform it three times.”

### Label

> “What should we call it?”

### Learn

> “The phone now has a personalized representation of your gesture.”

### Test

Judge repeats.

### Result

> **EMERGENCY**

### Speech

> “Emergency.”

### Offline

> “Now the internet is gone.”

Airplane mode.

Repeat.

### Close

> **“The phone doesn't need to know your gesture beforehand. You teach it.”**

Then:

> **“That's the mechanism we're applying to Indian Sign Language.”**

---

# 38. Final Decision

## Should we submit a working prototype?

**YES — if it meets the minimum acceptance criteria and the event rules permit the relevant pre-event work.**

A broken prototype is worse than no prototype.

A simple working prototype is extremely valuable.

## Biggest technical risk

Cross-person few-shot recognition.

## Biggest competition risk

Being perceived as another sign-language translator.

## Biggest mitigation

Make the custom judge-taught gesture the FIRST thing the reviewer sees.

## Biggest strategic advantage

The judge personally experiences the novelty.

## Biggest technical fallback

DTW.

## Primary target

Pretrained lightweight temporal AI encoder + few-shot prototype matching.

## Secondary/fallback target

Landmark + DTW.

---

# 39. Final North Star

Do not ask:

> “How many ISL signs can we support?”

Ask:

> **“Can we make a judge believe, within 60 seconds, that their phone just learned something it didn't know before?”**

If YES:

The prototype has done its job.

If NO:

Do not add features.

Fix the core mechanism.

---

# 40. Final Pre-Submission Checklist

## Core
- [ ] Camera works
- [ ] Landmarks work
- [ ] Temporal sequence works
- [ ] Encoder works
- [ ] DTW baseline exists
- [ ] Few-shot prototype works
- [ ] Unknown state works
- [ ] Confidence works
- [ ] Text works
- [ ] TTS works
- [ ] Offline works

## Validation
- [ ] Cross-person test
- [ ] Unseen gesture test
- [ ] Latency measured
- [ ] FPS measured
- [ ] False positives measured
- [ ] Enrollment time measured
- [ ] 5+ external testers if possible

## Product
- [ ] Teach Mode
- [ ] Recognition Mode
- [ ] Clear error states
- [ ] Simple UI
- [ ] Demo mode

## Evidence
- [ ] Benchmark table
- [ ] Architecture diagram
- [ ] Prototype screenshots
- [ ] Working demo
- [ ] 30–60s video

## Submission
- [ ] Name
- [ ] One-line pitch
- [ ] Description
- [ ] Problem
- [ ] Solution
- [ ] Novelty
- [ ] Technology
- [ ] Prototype link
- [ ] Video
- [ ] Deck
- [ ] Team details
- [ ] Limitations

---

# Final Principle

**Do not build a large prototype. Build a convincing prototype.**

The entire screening strategy rests on one moment:

> **A person teaches the phone a gesture it has never been explicitly assigned before, and the phone recognizes it seconds later — locally and offline.**

If we can make that moment reliable, the rest of SignBridge+ becomes much easier to sell.
