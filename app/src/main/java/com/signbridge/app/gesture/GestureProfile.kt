package com.signbridge.app.gesture

/**
 * Represents an enrolled personalized gesture profile created via Teach Mode.
 *
 * A gesture profile contains exactly 3 demonstrations (prototypes) of the same gesture,
 * capturing natural speed and timing variations across performances.
 *
 * @property id Unique identifier for this gesture profile (e.g. "profile_help_1724683000")
 * @property label Human-assigned label/meaning (e.g. "HELP", "EMERGENCY", "YES")
 * @property prototypes List of 3 enrolled [GesturePrototype] instances representing the demonstrations
 * @property createdAtMs Timestamp when this profile was enrolled
 */
data class GestureProfile(
    val id: String,
    val label: String,
    val prototypes: List<GesturePrototype>,
    val createdAtMs: Long = System.currentTimeMillis()
) {
    init {
        require(label.isNotBlank()) { "Gesture profile label must not be blank" }
        require(prototypes.isNotEmpty()) { "Gesture profile must contain at least one prototype" }
    }

    /**
     * Computes the pairwise DTW distances between the enrolled prototypes (P1 vs P2, P1 vs P3, P2 vs P3).
     * This provides a measure of consistency across the user's demonstrations during enrollment.
     */
    fun computePairwiseIntraDistances(): List<Double> {
        val distances = mutableListOf<Double>()
        for (i in 0 until prototypes.size) {
            for (j in i + 1 until prototypes.size) {
                val dtwResult = DTW.computeDistance(prototypes[i].sequence, prototypes[j].sequence)
                if (dtwResult.isValid) {
                    distances.add(dtwResult.normalizedDistance)
                }
            }
        }
        return distances
    }

    /**
     * Returns the mean pairwise intra-gesture distance across demonstrations.
     */
    fun meanIntraDistance(): Double {
        val pairwise = computePairwiseIntraDistances()
        return if (pairwise.isNotEmpty()) pairwise.average() else 0.0
    }
}
