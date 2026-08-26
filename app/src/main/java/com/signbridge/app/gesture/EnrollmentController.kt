package com.signbridge.app.gesture

/**
 * Controller orchestrating the 3-shot Teach Mode gesture enrollment pipeline.
 */
class EnrollmentController {

    var state: EnrollmentState = EnrollmentState.IDLE
        private set

    private val capturedSequences = mutableListOf<TemporalSequence>()

    val capturedCount: Int
        get() = capturedSequences.size

    val targetCount: Int = 3

    val isTeaching: Boolean
        get() = state != EnrollmentState.IDLE

    /**
     * Initiates the Teach Mode workflow from IDLE state.
     */
    fun startTeaching() {
        capturedSequences.clear()
        state = EnrollmentState.TEACH_INTRO
    }

    /**
     * Begins capturing the next demonstration sample.
     */
    fun startRecordingCurrentSample() {
        state = when (state) {
            EnrollmentState.TEACH_INTRO -> EnrollmentState.RECORDING_1
            EnrollmentState.CAPTURED_1 -> EnrollmentState.RECORDING_2
            EnrollmentState.CAPTURED_2 -> EnrollmentState.RECORDING_3
            else -> state
        }
    }

    /**
     * Evaluates an incoming temporal buffer snapshot during an active recording state.
     *
     * @param hasHands Whether at least one hand is currently tracked
     * @param bufferSnapshot Snapshot of the 30-frame normalized temporal sequence
     * @return True if a sample was successfully validated and captured
     */
    fun processFrame(hasHands: Boolean, bufferSnapshot: TemporalSequence): Boolean {
        if (!hasHands || !bufferSnapshot.isReady || bufferSnapshot.frameCount < 5) {
            return false
        }

        // Quality check: ensure all frames contain finite, non-NaN values
        val isValid = bufferSnapshot.frames.all { frame ->
            frame.landmarks.size == 21 && frame.landmarks.all { p ->
                p.x.isFinite() && p.y.isFinite() && p.z.isFinite()
            }
        }

        if (!isValid) {
            return false
        }

        return when (state) {
            EnrollmentState.RECORDING_1 -> {
                capturedSequences.add(bufferSnapshot)
                state = EnrollmentState.CAPTURED_1
                true
            }
            EnrollmentState.RECORDING_2 -> {
                capturedSequences.add(bufferSnapshot)
                state = EnrollmentState.CAPTURED_2
                true
            }
            EnrollmentState.RECORDING_3 -> {
                capturedSequences.add(bufferSnapshot)
                state = EnrollmentState.LABELING
                true
            }
            else -> false
        }
    }

    /**
     * Manually registers a captured sequence (primarily for unit tests and simulation).
     */
    fun recordSampleDirectly(sequence: TemporalSequence): Boolean {
        if (capturedSequences.size >= targetCount) return false
        capturedSequences.add(sequence)
        state = when (capturedSequences.size) {
            1 -> EnrollmentState.CAPTURED_1
            2 -> EnrollmentState.CAPTURED_2
            3 -> EnrollmentState.LABELING
            else -> state
        }
        return true
    }

    /**
     * Finalizes and saves the enrolled gesture profile into the store and updates the matcher.
     *
     * @param label Human-readable name for the gesture (e.g. "HELP", "YES")
     * @param store In-memory [PersonalGestureStore]
     * @param matcher Live [PrototypeMatcher] used for DTW recognition
     * @return [Result] containing the created [GestureProfile] or an exception on failure
     */
    fun saveGesture(
        label: String,
        store: PersonalGestureStore,
        matcher: PrototypeMatcher
    ): Result<GestureProfile> {
        val trimmed = label.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Gesture label cannot be empty"))
        }

        if (trimmed.length > 32) {
            return Result.failure(IllegalArgumentException("Gesture label must be 32 characters or less"))
        }

        if (capturedSequences.size < targetCount) {
            return Result.failure(IllegalStateException("Cannot save gesture: captured ${capturedSequences.size}/$targetCount samples"))
        }

        return try {
            val profile = store.createProfile(trimmed, ArrayList(capturedSequences))

            // Synchronize all enrolled prototypes into the live matcher
            matcher.clearPrototypes()
            for (proto in store.getAllPrototypes()) {
                matcher.addPrototype(proto)
            }

            state = EnrollmentState.SAVED
            capturedSequences.clear()
            // Return to IDLE mode ready for recognition
            state = EnrollmentState.IDLE

            Result.success(profile)
        } catch (e: Exception) {
            state = EnrollmentState.ERROR
            Result.failure(e)
        }
    }

    /**
     * Aborts Teach Mode, discards all collected samples, and resets to IDLE.
     */
    fun cancel() {
        capturedSequences.clear()
        state = EnrollmentState.IDLE
    }

    /**
     * Returns an immutable copy of currently captured sequences.
     */
    fun getCapturedSequences(): List<TemporalSequence> {
        return ArrayList(capturedSequences)
    }
}
