package com.smithware.orderradar.domain

import com.smithware.orderradar.data.VisionCorrection
import kotlin.math.pow

data class VisionBias(val ratio: Double, val confidenceMultiplier: Double, val sampleCount: Int)

// Turns past user corrections on AI shelf counts into a per-product adjustment, so guesses get
// better with use instead of leaning on fixed thresholds. Recent corrections are weighted more
// heavily than older ones (0.8^index), and a product whose corrections have been wildly
// inconsistent gets a confidence penalty since it's historically hard to count from photos --
// a product that's always confirmed close to the estimate gets a small confidence boost instead.
object VisionLearningEngine {
    private const val MAX_SAMPLES = 8
    private const val MIN_RATIO = 0.4
    private const val MAX_RATIO = 2.5

    fun biasFor(corrections: List<VisionCorrection>, productId: Long): VisionBias {
        val recent = corrections
            .filter { it.productId == productId && it.aiEstimatedQuantity > 0.0 }
            .sortedByDescending { it.createdAt }
            .take(MAX_SAMPLES)
        if (recent.isEmpty()) return VisionBias(ratio = 1.0, confidenceMultiplier = 1.0, sampleCount = 0)

        val ratios = recent.map { (it.confirmedQuantity / it.aiEstimatedQuantity).coerceIn(MIN_RATIO, MAX_RATIO) }
        var weightedSum = 0.0
        var weightTotal = 0.0
        ratios.forEachIndexed { index, ratio ->
            val weight = 0.8.pow(index)
            weightedSum += ratio * weight
            weightTotal += weight
        }
        val avgRatio = (weightedSum / weightTotal).coerceIn(MIN_RATIO, MAX_RATIO)

        val mean = ratios.average()
        val variance = ratios.sumOf { (it - mean) * (it - mean) } / ratios.size
        val consistency = 1.0 / (1.0 + variance * 4.0)
        val confidenceMultiplier = (0.75 + consistency * 0.4).coerceIn(0.75, 1.15)

        return VisionBias(ratio = avgRatio, confidenceMultiplier = confidenceMultiplier, sampleCount = recent.size)
    }

    // Corrections only ever measure quantity accuracy (confirmedQuantity vs aiEstimatedQuantity),
    // so this should be applied to countConfidence, not identificationConfidence -- there's no
    // signal here about whether the AI named the right product.
    fun applyBias(rawQuantity: Double, rawConfidencePercent: Int, bias: VisionBias): Pair<Double, Int> {
        if (bias.sampleCount == 0) return rawQuantity to rawConfidencePercent
        val adjustedQuantity = rawQuantity * bias.ratio
        val adjustedConfidence = (rawConfidencePercent * bias.confidenceMultiplier).toInt().coerceIn(1, 99)
        return adjustedQuantity to adjustedConfidence
    }
}
