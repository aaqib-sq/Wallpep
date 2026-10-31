package dev.wallpep.storage

import dev.wallpep.model.isSafeBackgroundName

/** Sidecars can only be derived from a validated, app-generated background name. */
internal fun cutoutFileName(background: String): String? =
    background.takeIf(::isSafeBackgroundName)?.let { "cutout-$it.png" }

internal fun cutoutFailureFileName(background: String): String? =
    background.takeIf(::isSafeBackgroundName)?.let { "cutout-$it.failed" }

/** Recognize only our own foreground, failure and in-progress files during cleanup. */
internal fun cutoutBackgroundName(fileName: String): String? {
    val candidate = when {
        fileName.startsWith(".cutout-") && fileName.endsWith(".png.tmp") ->
            fileName.removePrefix(".cutout-").removeSuffix(".png.tmp")
        fileName.startsWith("cutout-") && fileName.endsWith(".png") ->
            fileName.removePrefix("cutout-").removeSuffix(".png")
        fileName.startsWith("cutout-") && fileName.endsWith(".failed") ->
            fileName.removePrefix("cutout-").removeSuffix(".failed")
        else -> return null
    }
    return candidate.takeIf(::isSafeBackgroundName)
}
