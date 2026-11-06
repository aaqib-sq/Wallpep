package dev.wallpep.wallpaper

/**
 * Surface gating (#145 "Show content on lockscreen", #146 "Show content on homescreen").
 *
 * ## What the platform actually offers
 *
 * A live wallpaper is drawn into one surface per engine instance. Which screen that surface
 * belongs to is only knowable through public APIs in two situations:
 *
 * 1. **API 34+ — [android.service.wallpaper.WallpaperService.Engine.getWallpaperFlags].**
 *    The engine reports `FLAG_SYSTEM` (home), `FLAG_LOCK` (lock) or both. The value is the
 *    wallpaper data's `which` flag, handed to the engine when the system attaches it. When the
 *    platform runs the "independent lock screen live wallpaper" feature (Android 14 QPR1 and
 *    later on some builds) and the user picks one screen only, the lock screen and the home
 *    screen get **separate engines**, so each engine knows its own surface exactly.
 *
 * 2. **[android.app.KeyguardManager.isKeyguardLocked]** — no permission, public since API 16.
 *    This says whether the keyguard is showing *right now*, which is what distinguishes the
 *    two surfaces for an engine that serves **both** of them.
 *
 * ## The honest limit
 *
 * When the user picks "Home screen and lock screen" — the default, and the only option on
 * Android 13 and earlier — **one engine and one surface serve both screens**. The same pixels
 * are composited onto both, so no public API can tell the engine "this frame is going to the
 * lock screen". The only signal left is the keyguard state, which names the surface that is
 * on screen at that moment. Because the home screen is only visible while the device is
 * unlocked and the lock screen only while it is locked, that signal is sufficient to gate the
 * content layers correctly; it is a *surface* decision, not a per-frame one.
 *
 * Nothing here fakes a mechanism: the engine asks the platform what it is and the platform
 * answers, or the answer is "shared" and the keyguard state decides.
 */

/** Which screen(s) an engine instance is drawing for. */
enum class SurfaceTarget {
    /** The engine draws for the home screen only. */
    HOME,

    /** The engine draws for the lock screen only. */
    LOCK,

    /** One engine serves both screens; the keyguard state names the visible one. */
    SHARED,
}

/**
 * Reads the engine's surface target from the platform's wallpaper flags.
 *
 * `FLAG_SYSTEM` alone is [SurfaceTarget.HOME], `FLAG_LOCK` alone is [SurfaceTarget.LOCK], and
 * both together (or neither) is [SurfaceTarget.SHARED]. The flags exist from API 34; on older
 * releases there is only one live wallpaper surface, so the target is [SurfaceTarget.SHARED].
 */
fun surfaceTargetFromFlags(flags: Int, supported: Boolean = true): SurfaceTarget {
    if (!supported) return SurfaceTarget.SHARED
    val system = flags and WALLPAPER_FLAG_SYSTEM != 0
    val lock = flags and WALLPAPER_FLAG_LOCK != 0
    return when {
        system && !lock -> SurfaceTarget.HOME
        lock && !system -> SurfaceTarget.LOCK
        else -> SurfaceTarget.SHARED
    }
}

/** [android.app.WallpaperManager.FLAG_SYSTEM], copied so the policy stays Android-free. */
const val WALLPAPER_FLAG_SYSTEM = 1

/** [android.app.WallpaperManager.FLAG_LOCK], copied so the policy stays Android-free. */
const val WALLPAPER_FLAG_LOCK = 2

/**
 * Whether the clock, the date and the text layer should be drawn for the surface that is
 * currently on screen.
 *
 * - A home-only engine honours [showOnHomescreen]; the lock screen never shows this engine,
 *   so the lock switch cannot apply to it.
 * - A lock-only engine honours [showOnLockscreen]; the home screen never shows this engine.
 * - A shared engine honours both: the keyguard state says which of the two surfaces is on
 *   screen, and the matching switch decides.
 *
 * The wallpaper image itself is never gated — only the content layers are, exactly as the
 * reference app's wording says ("clock/date/text hide on the lock screen only").
 */
fun contentVisibleFor(
    target: SurfaceTarget,
    showOnLockscreen: Boolean,
    showOnHomescreen: Boolean,
    keyguardLocked: Boolean,
): Boolean = when (target) {
    SurfaceTarget.HOME -> showOnHomescreen
    SurfaceTarget.LOCK -> showOnLockscreen
    // Shared: the keyguard state names the surface the user is looking at right now.
    SurfaceTarget.SHARED -> if (keyguardLocked) showOnLockscreen else showOnHomescreen
}
