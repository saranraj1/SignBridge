package com.signbridge.app.gesture

/**
 * Explicit finite state machine representing the Teach Mode gesture enrollment lifecycle.
 */
enum class EnrollmentState {
    /** Standard operating mode (live recognition active). */
    IDLE,

    /** Teach Mode opened; waiting for user readiness before recording sample 1. */
    TEACH_INTRO,

    /** Actively capturing demonstration 1/3 from the live camera stream. */
    RECORDING_1,

    /** Demonstration 1 captured successfully; ready for demonstration 2. */
    CAPTURED_1,

    /** Actively capturing demonstration 2/3. */
    RECORDING_2,

    /** Demonstration 2 captured successfully; ready for demonstration 3. */
    CAPTURED_2,

    /** Actively capturing demonstration 3/3. */
    RECORDING_3,

    /** Demonstration 3 captured successfully; all 3 samples collected. */
    CAPTURED_3,

    /** Awaiting user to input a name/label for the new gesture. */
    LABELING,

    /** Gesture profile created, prototypes stored, and matcher updated. */
    SAVED,

    /** Enrollment failed due to invalid sample, hand loss, or validation error. */
    ERROR
}
