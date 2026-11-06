package dev.wallpep.render

/**
 * Per-layer parallax composition (#46, #47, #88, #89).
 *
 * A layer moves with the device tilt only when the global "Clock, Date & Text" parallax (#141)
 * and the layer's own switch are both on; the layer's strength is a percentage of the global
 * travel (#142), so the shipped defaults — on, 100 % — reproduce the pre-per-layer behaviour
 * exactly and old saved documents keep rendering unchanged. Pure Kotlin for unit tests; the
 * scene multiplies the result by tilt × screen size × its parallax fractions.
 */
internal fun layerParallaxScale(
    globalEnabled: Boolean,
    globalStrengthPercent: Int,
    layerEnabled: Boolean,
    layerStrengthPercent: Int,
): Float {
    if (!globalEnabled || !layerEnabled) return 0f
    val global = globalStrengthPercent.coerceIn(0, 100) / 100f
    val layer = layerStrengthPercent.coerceIn(0, 100) / 100f
    return global * layer
}
