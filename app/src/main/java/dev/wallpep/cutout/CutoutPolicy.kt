package dev.wallpep.cutout

internal enum class CacheDecision { USE_CACHED, CREATE, SKIP }

/** Completed failures are not retried; a missing/interrupted result can be attempted once. */
internal fun cacheDecision(photoExists: Boolean, cutoutExists: Boolean, failureExists: Boolean): CacheDecision = when {
    !photoExists -> CacheDecision.SKIP
    cutoutExists -> CacheDecision.USE_CACHED
    failureExists -> CacheDecision.SKIP
    else -> CacheDecision.CREATE
}
