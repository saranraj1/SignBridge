# Product Requirements Document — SignBridge+

## 1. Product summary

SignBridge+ is a phone-first accessibility and gesture-learning product that lets a user teach the phone a new gesture from a few examples and use that personalized gesture vocabulary in real time.

The first application is ISL communication.

## 2. Problem

Fixed-vocabulary gesture/sign recognition systems require the system to know a gesture beforehand. Real users vary in signing style, movement, speed, camera position and environment.

The product hypothesis is:

> Personalization from a few examples can make on-device gesture interaction more adaptable and memorable.

## 3. Target users

### Primary
- Deaf or hard-of-hearing users using ISL
- People communicating with ISL users who do not know ISL

### Hackathon demonstration user
- The judge

The judge is intentionally used as the live test subject to demonstrate personalization.

## 4. Jobs to be done

### JTBD 1
"When I need to communicate a concept using a gesture the system does not already know, I want to teach it quickly."

### JTBD 2
"When I perform a taught gesture, I want the phone to recognize it without requiring internet access."

### JTBD 3
"When recognition is uncertain, I want the system to admit uncertainty rather than speak a wrong answer."

## 5. Product principles

- Local first
- Few-shot first
- Reliability over vocabulary size
- Honest uncertainty
- Minimal UI
- Judge can experience the mechanism
- Accessibility over spectacle

## 6. Core user flow

```text
Open app
→ Teach New Gesture
→ Record 3 examples
→ Name gesture
→ Save
→ Test
→ Recognize
→ Speak
```

## 7. Functional requirements

### FR-01 Camera
The app shall capture live camera frames.

### FR-02 Landmark extraction
The app shall extract hand landmarks locally.

### FR-03 Enrollment
The app shall record approximately three gesture examples.

### FR-04 Personalization
The app shall generate a local prototype/representation for the enrolled gesture.

### FR-05 Recognition
The app shall compare a live gesture against stored prototypes.

### FR-06 Unknown rejection
The app shall be capable of returning UNKNOWN below a confidence threshold.

### FR-07 Output
The app shall display the recognized label.

### FR-08 Speech
The app shall speak the recognized label using local Android TTS where available.

### FR-09 Offline
The critical recognition path shall operate with network connectivity disabled.

### FR-10 Persistence
Enrolled gestures shall persist locally for the prototype session.

## 8. Non-functional requirements

- Target responsive real-time interaction.
- Measure median and p95 recognition latency where practical.
- Avoid repeated speech triggers.
- Avoid crashes when tracking is temporarily lost.
- Provide retry states.
- Keep model memory within practical device limits.

## 9. Success metrics

Primary:
- personalization time
- few-shot recognition performance
- cross-person performance
- recognition latency
- unknown rejection
- offline success

Secondary:
- FPS
- memory
- thermal behavior
- enrollment failure rate

## 10. Prototype success definition

Minimum:
- one new gesture can be taught in three examples and recognized.

Strong:
- works across multiple people and offline with measured latency.

Exceptional:
- a judge invents a gesture, teaches it, repeats it, and the phone recognizes and speaks it offline.

## 11. Out of scope

- universal ISL
- continuous sentence translation
- facial grammar
- cloud backend
- full conversational AI
- large language model in critical recognition path
