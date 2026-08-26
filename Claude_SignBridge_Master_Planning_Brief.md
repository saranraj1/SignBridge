# Claude Master Planning Brief — SignBridge+ / iQOO Reskilll Hackathon 2026

## PURPOSE

Create an exhaustive, execution-ready implementation and competition plan for **SignBridge+**, our proposed project for the **iQOO Reskilll Hackathon 2026 City Battle**.

This is not merely a concept document. The objective is a plan that a 3-person team can actually execute before and during the hackathon, under a nominal 30-hour event but only roughly 19–21 usable engineering hours after ceremonies, evaluation, mentor time, meals and other interruptions.

Claude must critically evaluate every assumption, verify current technical facts where needed, challenge weak decisions, and recommend killing or changing the idea if the evidence says it will not work.

---

# 1. COMPETITION CONTEXT

Current understanding:

- iQOO Reskilll Hackathon 2026.
- Phone-first, in-person AI hackathon.
- City Battle nominal duration: 30 hours.
- 3-person teams.
- Tracks currently shown:
  - Smart Education
  - HealthTech
  - Productivity
  - Smart Living
  - Developer Tools
  - Open Innovation
- Red Light / Green Light development periods:
  - Red Light: iQOO phone-only workflow.
  - Green Light: phone + laptop workflow.
- Evaluation rounds occur during the event.
- Supplied iQOO hardware is central to the experience.
- Local/open-source/on-device AI is strategically important.
- Exact current Chennai rules, schedule, rubric, hardware and Red/Green timings must be verified from official sources rather than assumed from earlier editions.

## IMPORTANT BUILD-TIME ASSUMPTION

Do not plan around 30 full coding hours.

The strategic planning assumption is approximately **19–21 genuinely usable engineering hours**. The final plan must derive a more precise usable schedule from the latest official timetable.

Priority order:

1. Working core loop.
2. Early evaluation readiness.
3. Reliability.
4. Measured evidence.
5. Demo polish.
6. Extra features.

---

# 2. TRACK DECISION — OPEN INNOVATION

We currently intend to enter:

## OPEN INNOVATION

The registration page defines it as:

> “Anything outside the tracks, any domain, with a local or open source model at the core.”

### Why Open Innovation?

The core invention is NOT simply:

> “an Indian Sign Language translator.”

It is:

> **An on-device few-shot temporal gesture-learning engine that learns personalized gestures from a few examples and recognizes them in real time.**

Indian Sign Language is the first high-impact application.

This gives a stronger reason for Open Innovation than forcing the idea into HealthTech or another specified domain.

### Critical positioning

Do NOT lead with:

> “We built an AI sign-language translator.”

That risks immediate “I've seen this before” categorization.

Lead with:

> **“We built a phone that can learn a new gesture in seconds and speak it back — completely offline.”**

Then reveal:

> “Indian Sign Language is our first real-world application.”

The hierarchy is:

**Novel mechanism → demonstrated through accessibility use case**

not:

**Accessibility app → forced into Open Innovation**

---

# 3. PROJECT IDENTITY

## Name

**SignBridge+**

## Tagline candidates

> **Teach your phone a gesture. Let it speak for you.**

or:

> **We don't want users to learn the machine. We want the machine to learn the user.**

Do not use generic names such as:
- AI Sign Translator
- ISL Translator
- Gesture Recognition AI

---

# 4. CORE PRODUCT

SignBridge+ is an offline, real-time communication assistant based on an **on-device few-shot temporal gesture-learning engine**.

A user can:

1. Enter Teach Mode.
2. Perform a gesture a few times.
3. Give the gesture a meaning.
4. The phone stores a personalized gesture representation locally.
5. The user performs it again.
6. The system recognizes it.
7. The phone displays the meaning.
8. The phone speaks it using TTS.

The first application is Indian Sign Language communication.

The central innovation is **personalization**, not simply classification.

---

# 5. WINNING DEMO

The judge should ideally become the test subject.

Opening:

> **“Judge, can you teach a phone a new gesture in 30 seconds?”**

The judge performs an arbitrary/simple gesture.

They give it a meaning, e.g. “Emergency.”

The phone records three examples.

After roughly 20–30 seconds:

Judge repeats it.

Phone:

**EMERGENCY**

and speaks:

> “Emergency.”

Then:

> **“Now let's remove the internet.”**

Disable connectivity / airplane mode.

Judge repeats it.

Phone still recognizes and speaks it.

Then demonstrate actual ISL communication.

Desired judge mental sequence:

1. “Another AI project.”
2. “Wait, what is it doing?”
3. “It learned my gesture?”
4. “Without retraining?”
5. “It works offline?”
6. “How did they implement that?”
7. “And they're using it for ISL?”
8. “This is memorable.”

The WOW is NOT “sign language recognition.”

The WOW is:

> **The phone learned MY gesture in seconds.**

---

# 6. PRODUCT SCOPE

## MUST HAVE

- Android app.
- Camera input.
- On-device landmark extraction.
- Landmark normalization.
- Temporal representation.
- Few-shot enrollment.
- Personalized local gesture storage.
- Recognition.
- Confidence threshold.
- Text output.
- TTS.
- Offline core operation.
- Small curated ISL vocabulary.
- Basic performance metrics.
- Graceful failure.

## SHOULD HAVE

- Two-way communication.
- On-device ASR → large text.
- Phrase chaining.
- Temporal smoothing.
- Better signer robustness.
- Benchmark screen.

## COULD HAVE

- Local LLM phrase smoothing.
- Tamil output.
- Conversation history.
- Rich accessibility UX.
- Larger gesture libraries.

## MUST NOT BUILD

- Full open-vocabulary ISL translation.
- Thousands of signs.
- Full continuous unrestricted ISL.
- Facial-grammar research.
- Avatar generation.
- Cloud backend.
- Accounts/authentication.
- Analytics dashboards.
- Large model training from scratch.
- Features that do not improve the core demo.

---

# 7. ARCHITECTURE

Preferred critical path:

```text
Camera
   ↓
Hand / optional upper-body landmarks
   ↓
Landmark normalization
   ↓
Temporal window
   ↓
Few-shot prototype representation
   ↓
DTW / temporal similarity matching
   ↓
Confidence gate
   ↓
Recognized gesture
   ↓
Text
   ↓
Android TTS
```

Optional reverse channel:

```text
Microphone
   ↓
On-device ASR
   ↓
Large text
```

Optional future/local-model layer:

```text
Recognized tokens
   ↓
Optional local LLM
   ↓
Natural sentence / phrase smoothing
```

The LLM must NOT be assumed to be the critical recognition mechanism.

---

# 8. GOOGLE AI EDGE / AI EDGE GALLERY

AI Edge Gallery should be treated as an **evaluated tooling option**, not a hard dependency.

Investigate:

- Google AI Edge / MediaPipe.
- LiteRT/TFLite.
- AI Edge Gallery.
- Custom LiteRT models.
- Android integration.
- On-device benchmarking.
- Snapdragon/NPU acceleration.

Do not force an LLM/VLM into the gesture path merely because the hackathon mentions AI.

Preferred first implementation:

**Landmarks + temporal matching**

rather than:

**camera → VLM → LLM → speech**

The final plan must determine whether AI Edge Gallery adds real value or should remain outside the MVP.

---

# 9. LANDMARK STRATEGY

Start with **hands only**.

Each hand has approximately 21 landmarks.

Potential representation:

- x
- y
- z

For two hands:
- left hand
- right hand

Only add pose/upper-body landmarks if hands-only proves insufficient.

Do not begin with maximum complexity.

---

# 10. NORMALIZATION

Raw coordinates vary due to:

- distance
- hand size
- position
- rotation
- signer
- handedness

Normalize:

1. Translation.
2. Scale.
3. Rotation where practical.
4. Handedness.

The implementation plan must define the exact mathematical transformations and tests.

---

# 11. TEMPORAL REPRESENTATION

A gesture is a movement over time, not just one image.

Capture:

```text
t1 → t2 → t3 → ... → tn
```

Test candidate approaches:

- DTW
- soft-DTW
- nearest-prototype matching
- learned embeddings
- lightweight 1D CNN
- LSTM/GRU
- small transformer

Selection criteria:

- development time
- accuracy
- robustness
- latency
- memory
- explainability
- personalization
- Red-Light feasibility
- Android integration

Do not assume DTW is automatically best.

DTW is currently the preferred first prototype because it is:
- few-shot friendly
- deterministic
- speed-tolerant
- easy to explain
- potentially fast for a small vocabulary

But it must be experimentally validated.

---

# 12. FEW-SHOT ENROLLMENT

Flow:

```text
Create Gesture
      ↓
Name
      ↓
Record Example 1
      ↓
Record Example 2
      ↓
Record Example 3
      ↓
Normalize sequences
      ↓
Create prototype
      ↓
Store locally
      ↓
Ready
```

Potential storage object:

```text
Gesture {
    id
    label
    prototype
    examples
    confidenceThreshold
}
```

Prefer storing derived landmarks rather than unnecessary raw video.

All personalization data should remain local.

---

# 13. RECOGNITION

Live input:

```text
Camera
 ↓
Landmarks
 ↓
Normalization
 ↓
Sliding temporal window
 ↓
Compare against enrolled gestures
 ↓
Score
 ↓
Confidence gate
```

Need to define and test:

- window length
- overlap
- smoothing
- threshold calibration
- unknown gesture detection
- debounce
- cooldown
- repeated-trigger prevention

---

# 14. CONFIDENCE / UNKNOWN STATE

Never always return the highest score.

If confidence is low:

> **“I'm not sure. Please repeat.”**

This is a product feature, not a failure.

The system must prevent:
- repeated speech every frame
- random false predictions
- low-confidence spoken output

---

# 15. ISL VOCABULARY

Start small.

Candidate categories:

### Basic
- Hello
- Yes
- No
- Thank you

### Needs
- Water
- Food
- Help

### Emergency
- Help me
- Emergency
- Call someone

### Communication
- I need help
- I don't understand
- Please wait
- Where?

Exact ISL signs must be sourced from credible references and validated.

Do not invent signs from English translations.

The plan must specify:
- data source
- attribution/licensing
- sign selection
- data collection
- phrase mapping

---

# 16. DATA STRATEGY

Do not build a huge dataset.

Separate:

### Predefined ISL vocabulary
Used for demonstrating the application.

### User-defined gestures
Used to demonstrate the few-shot mechanism.

The user-defined gesture is the strongest demo feature because the judge can create it live.

---

# 17. PRE-HACKATHON 4-HOUR FEASIBILITY SPIKE

This is the most important technical experiment.

### Hour 1
Camera → landmarks on target Android hardware.

### Hour 2
Capture temporal gesture sequences.

### Hour 3
Implement matching.

### Hour 4
Person A enrolls.
Person B performs.

Measure:
- recognition
- latency
- false positives
- enrollment time

Decision:

### Continue
If the mechanism is working reliably enough to improve.

### Kill/Pivot
If the core mechanism is fundamentally unstable or threatens hackathon feasibility.

Do not spend weeks polishing an unproven mechanism.

---

# 18. WORKING PROTOTYPE STRATEGY

IMPORTANT APPLICATION FACT:

The hackathon/application asks for a **working prototype** as an optional/additional-strength item. It is not strictly mandatory for the basic idea submission, but a working prototype can provide **additional points / shortlist advantage**.

Therefore:

## We SHOULD build a pre-hackathon feasibility prototype if the official rules permit it.

Target:

```text
Camera
 ↓
Landmarks
 ↓
3-shot enrollment
 ↓
Personalized prototype
 ↓
Recognition
 ↓
Text/TTS
```

It does NOT need:
- 40 ISL signs
- polished UI
- full two-way communication
- final production robustness

The prototype is valuable because it:
1. Demonstrates feasibility.
2. Strengthens idea screening.
3. Reveals technical failure modes.
4. Reduces hackathon risk.
5. Gives the team implementation familiarity.
6. Potentially earns prototype-related points.

## RULE COMPLIANCE

This must be handled carefully.

Before the event, distinguish:

### Preparation that is permitted
- research
- architecture
- technical experiments
- learning
- idea development
- permitted prototype work

### Final event-window implementation
- final submission code
- final integration
- final features
- final polish

Verify the latest official T&C before carrying any code/assets into the event.

Never violate the build-window rules for the sake of a prototype.

A working but ugly prototype is better than a beautiful fake prototype.

---

# 19. 3-PERSON TEAM

## P1 — AI / Computer Vision

Own:
- landmarks
- normalization
- temporal representation
- matching
- confidence
- latency
- benchmark

Secondary:
- technical Q&A

## P2 — Android / Device

Own:
- CameraX
- Android architecture
- enrollment UX
- recognition UI
- TTS
- ASR
- local storage
- device optimization
- Red/Green workflow

## P3 — Product / Data / Demo

Own:
- ISL vocabulary
- data curation
- testing
- failure matrix
- UX
- demo
- pitch
- backup video
- submission

Person 3 must not become merely “the presentation person.”

All three participate in architecture and integration.

---

# 20. INTEGRATION RULE

Integrate every 3–4 hours.

Example checkpoints:

1. Camera → landmarks
2. Landmarks → matching
3. Matching → text
4. Text → speech
5. Teach mode
6. Offline
7. Stranger testing

Never big-bang integrate at Hour 20.

---

# 21. REALISTIC HACKATHON PLAN

Plan for roughly 19–21 usable engineering hours.

## 0–2h
- scope lock
- architecture lock
- project scaffold
- camera
- landmark pipeline
- demo script

## 2–5h
- normalization
- temporal capture
- matcher
- first recognition
- first complete core loop

Target:
**first gesture → text/speech**

## 5–8h
- enrollment UX
- 3-shot teaching
- confidence
- TTS
- evaluation-ready demo

## 8–12h
- robustness
- smoothing
- gesture library
- selected ISL vocabulary
- phrase chaining

## 12–16h
- offline hardening
- ASR if core is stable
- latency instrumentation
- Red-Light-compatible work

## 16–19h
- stranger testing
- fix top two failures
- UX hardening

## 19–21h
- feature freeze
- bug fixing
- benchmark
- README
- submission
- rehearsal

Preserve the priority order even if the official timetable shifts.

---

# 22. EVALUATION STRATEGY

First evaluation:
- working core loop
- few-shot teaching
- recognition
- early offline proof if stable
- clear next-hardening steps

Second evaluation:
- reliability
- measured metrics
- stronger demo
- stranger testing
- polished UX
- benchmark evidence

The demo is the product.

---

# 23. FINAL 5-HOUR VALIDATION

### 90 min
Stranger testing.

### 60 min
Fix only the top two failure classes.

### 60 min
Demo rehearsal ×5.

### 45 min
Backup demo video.

### 45 min
Q&A drill.

Questions to rehearse:
- Why not CNN?
- Why not a large classifier?
- Why DTW?
- Why not cloud?
- How does few-shot learning work?
- How do you reject unknown gestures?
- What about continuous ISL?
- What about facial grammar?
- How does it scale?
- Why Open Innovation?
- What exactly runs locally?
- Where does AI Edge Gallery fit?
- Does it use the NPU?
- Why an LLM?
- What happens when wrong?
- What is your benchmark?
- What if I invent a new gesture?
- Is this really AI?
- What existed before the hackathon?
- What is your biggest limitation?

---

# 24. BENCHMARKS

Define exact measurement procedures for:

### Personalization time
From first enrollment example to first successful recognition.

### Latency
Camera/frame → landmarks → processing → match → output.

Report:
- median
- p95 if possible

### FPS
Live processing rate.

### Recognition
Measure:
- known gesture success
- unseen-user success
- false positives
- unknown rejection

### Offline
Run with connectivity disabled.

### Memory
Measure runtime memory.

### Battery/thermal
Observe during sustained demo workload.

Never claim a target as an achieved metric until measured.

---

# 25. TEST MATRIX

Test:

## Signer
- 3 teammates
- ≥5 strangers if possible

## Lighting
- bright
- indoor
- dim

## Background
- plain
- busy

## Distance
- near
- normal
- far

## Speed
- slow
- normal
- fast

## Handedness
- left
- right

## Gesture
- one hand
- selected two-hand gestures

## Connectivity
- connected
- Wi-Fi off
- airplane mode

## Failure
- unknown gesture
- ambiguous gesture
- partial occlusion
- lost tracking

---

# 26. DEMO

## 30-second version
Hook → teach → recognize → speech.

## 60-second version
Hook → teach → recognize → offline.

## 90-second version
0–10s: hook
10–30s: judge teaches
30–40s: label
40–50s: judge repeats
50–60s: speech
60–70s: airplane mode
70–85s: ISL communication
85–90s: closing line

Fallback:
- trained team member demonstrates if judge refuses.

Never make the whole demo dependent on judge participation.

---

# 27. PITCH

## 20-second opening

> “Judge, can you teach a phone a new gesture in 30 seconds?”

## Problem

> “Communication breaks down when two people don't share a spoken or signed language.”

## Insight

> “Instead of forcing users into a fixed classifier, we make the phone adapt to the user.”

## Technology

> “We use on-device temporal gesture representations and few-shot personalized matching.”

## Benchmark

Measure:
- personalization time
- latency
- offline operation
- unseen-user performance

## Why Open Innovation

> “Our core innovation is a reusable on-device few-shot gesture engine; ISL is our first high-impact application.”

## Closing

> “We don't want users to learn the machine. We want the machine to learn the user.”

---

# 28. DECK

Keep it short.

### Slide 1
**What if your phone could learn a new gesture in 30 seconds?**

### Slide 2
Problem.

### Slide 3
Few-shot innovation.

### Slide 4
Existing vs SignBridge+.

### Slide 5
Judge live demo.

### Slide 6
Architecture.

### Slide 7
Benchmark / results.

### Slide 8
Team / roadmap.

Do not overload slides with text.

---

# 29. Q&A

Prepare at least 30 difficult questions.

Core answers must cover:

- novelty
- few-shot learning
- DTW vs neural model
- signer independence
- continuous ISL limitations
- facial grammar
- unknown gesture
- false positives
- privacy
- offline
- scalability
- benchmark
- iQOO relevance
- NPU
- AI Edge Gallery
- LLM role
- Open Innovation
- prototype vs hackathon code
- limitations
- future work

Never exaggerate.

---

# 30. RISK REGISTER

## Cliché ISL project
Mitigation:
Lead with few-shot mechanism.

## Cross-person failure
Mitigation:
Personalization + stranger testing.

## Two-hand occlusion
Mitigation:
Curated vocabulary + early testing.

## Lighting
Mitigation:
Normalization + venue testing.

## False positives
Mitigation:
Confidence gate + unknown state.

## Latency
Mitigation:
Compact landmarks instead of raw-video model.

## Android failure
Mitigation:
Prove camera → landmarks first.

## Red Light
Mitigation:
Schedule laptop-dependent work during Green Light.

## Overbuilding
Mitigation:
Hard feature freeze.

## Prototype failure
Mitigation:
Four-hour feasibility kill test.

---

# 31. WHAT WE MUST NOT DO

10 DOs:

1. Lead with few-shot learning.
2. Make the judge a user.
3. Keep core recognition offline.
4. Measure real metrics.
5. Test strangers.
6. Use confidence thresholds.
7. Integrate early.
8. Freeze scope.
9. Explain Open Innovation clearly.
10. Prioritize reliability.

10 DON'Ts:

1. Don't pitch “another ISL translator.”
2. Don't claim universal ISL translation.
3. Don't fake benchmarks.
4. Don't force an LLM into the core.
5. Don't depend on cloud.
6. Don't build thousands of signs.
7. Don't spend hours on UI before the core works.
8. Don't depend entirely on judge participation.
9. Don't delay integration.
10. Don't sacrifice reliability for novelty.

---

# 32. PROBABILITY ESTIMATES

These are strategic planning estimates, NOT statistical predictions.

### Average execution
Top 10: 15–25%
Top 6: 8–15%
City win: 2–4%

### Good idea, imperfect execution
Top 10: 30–45%
Top 6: 18–30%
City win: 5–10%

### Reliable SignBridge+ + good demo
Top 10: 50–65%
Top 6: 35–50%
City win: 12–20%

### Full few-shot + judge WOW + offline + measured benchmark
Top 10: 65–80%
Top 6: 50–65%
City win: 20–30%

### Exceptional execution
Top 10: 80–90%
Top 6: 65–80%
City win: 30–45%

Current working estimate if the complete target system is genuinely reliable:

- Top 6: ~60%
- City win: ~30%
- National win: ~8–15%

Revise these after:
- feasibility test
- prototype performance
- official rubric verification
- actual competitor information

---

# 33. JUDGE COMPARISON SCENARIO

If a judge has already seen excellent projects in every specified track, a simple ISL translator may trigger:

> “I've seen this.”

But a judge-teaches-the-phone demo can trigger:

> “Wait — it learned mine?”

Then:

> “It works offline?”

Then:

> “And this solves ISL communication?”

The project should aim to be the **most memorable technically credible project**, not merely the most emotionally appealing.

WOW does not compensate for unreliable software.

---

# 34. PRE-COMMITMENT GATE

Before fully committing, answer YES/NO:

1. Stable landmarks?
2. Temporal examples?
3. Few-shot recognition?
4. Cross-person recognition?
5. Acceptable latency?
6. Offline core?
7. Unknown gesture rejection?
8. Repeatable demo?
9. Stranger testing?
10. Defensible benchmark?
11. Clear Open Innovation rationale?
12. MVP fits actual usable event time?
13. Full rule compliance?
14. Working prototype possible under rules?
15. Still compelling if “ISL” is removed and only the few-shot mechanism remains?

If several are NO:
**pivot.**

Do not allow sunk-cost bias.

---

# 35. REQUIRED CLAUDE OUTPUT

Create an exhaustive **SignBridge+ Implementation Master Plan** containing:

## A. Competition
- current event assumptions
- official-rule verification checklist
- track strategy
- scoring strategy
- prototype advantage
- Red/Green Light strategy
- evaluation-round strategy

## B. Product
- problem
- users
- JTBD
- MVP
- non-goals
- UX
- user journeys
- demo

## C. Technical Architecture
- exact stack
- Android architecture
- camera pipeline
- landmark extraction
- normalization math
- temporal representation
- matching
- confidence
- storage
- TTS
- ASR
- optional LLM
- offline architecture
- AI Edge Gallery role
- LiteRT role
- Snapdragon/NPU role
- privacy/security

## D. Research
- existing ISL systems
- academic benchmarks
- few-shot gesture research
- Google AI Edge capabilities
- Qualcomm/Snapdragon capabilities
- competing apps
- honest benchmark comparison
- claims we can/cannot make

## E. Prototype
- minimum pre-hackathon prototype
- exact prototype architecture
- prototype test plan
- prototype video
- rules compliance
- how it can strengthen screening

## F. Implementation
- milestones
- tasks
- dependencies
- acceptance criteria
- technical spikes
- fallback paths

## G. Team
For each of 3 people:
- responsibilities
- deliverables
- dependencies
- parallelization
- integration checkpoints
- backup ownership

## H. Pre-Hackathon
Create a realistic preparation schedule.

## I. Hackathon
Create an hour-by-hour plan for the nominal 30 hours while explicitly accounting for the fact that only ~19–21 hours may be usable for engineering.

## J. Final Validation
Exact 5-hour test plan.

## K. Demo
30s / 60s / 90s / fallback.

## L. Pitch
20s / 60s / 3min / slide deck / speaker allocation.

## M. Q&A
At least 30 difficult questions with strong, technically honest answers.

## N. Testing
Unit, integration, device, performance, UX, robustness, stranger, offline, battery, thermal, failure recovery.

## O. Metrics
Exact formulas and measurement procedures.

## P. Risks
Technical, competition, demo, and rule-compliance risks.

## Q. Documentation
README, architecture, benchmarks, limitations, attribution, licenses, video, deck, submission checklist.

## R. Decision Framework
At each milestone:
- continue
- modify
- simplify
- pivot
- kill

---

# 36. CRITICAL INSTRUCTION

Be a critical engineering partner, not a cheerleader.

If:
- AI Edge Gallery is unnecessary, say so.
- DTW is inferior, replace it.
- ISL creates too much risk, challenge it.
- Another architecture is better, recommend it.
- A feature is too risky, remove it.
- The project fails feasibility, recommend killing it.

The objective is:

> **WIN WITH A WORKING PRODUCT**

not:

> preserve the original idea.

---

# 37. FINAL THESIS

The project should ultimately communicate:

> **“What if your phone could learn a new physical language in seconds?”**

The judge experiences the answer personally.

The phone:
- watches
- learns
- recognizes
- speaks

Then the internet is removed.

It still works.

Then the mechanism is connected to ISL communication.

Final message:

> **“We don't want users to learn the machine. We want the machine to learn the user.”**

Build the mechanism first.
Prove it.
Measure it.
Then build the product around it.
