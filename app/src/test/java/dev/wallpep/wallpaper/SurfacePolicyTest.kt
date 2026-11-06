package dev.wallpep.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The surface-gating rules for #145 (Show content on lockscreen) and #146 (Show content on
 * homescreen).
 *
 * The rules are stated from the platform's own answers: the engine's wallpaper flags say which
 * screen an exclusive engine serves, and the keyguard state names the visible screen for an
 * engine that serves both.
 */
class SurfacePolicyTest {

    @Test fun exclusiveWallpaperFlagsNameTheirOwnSurface() {
        // FLAG_SYSTEM alone: the home screen.
        assertEquals(SurfaceTarget.HOME, surfaceTargetFromFlags(WALLPAPER_FLAG_SYSTEM))
        // FLAG_LOCK alone: the lock screen.
        assertEquals(SurfaceTarget.LOCK, surfaceTargetFromFlags(WALLPAPER_FLAG_LOCK))
    }

    @Test fun bothFlagsTogetherMeanOneEngineServesBothScreens() {
        assertEquals(SurfaceTarget.SHARED, surfaceTargetFromFlags(WALLPAPER_FLAG_SYSTEM or WALLPAPER_FLAG_LOCK))
    }

    @Test fun anUnknownOrEmptyFlagSetIsTreatedAsShared() {
        assertEquals(SurfaceTarget.SHARED, surfaceTargetFromFlags(0))
    }

    @Test fun releasesWithoutWallpaperFlagsCannotNameTheirSurface() {
        // API 33 has no getWallpaperFlags(); the engine cannot know, so it is shared.
        assertEquals(SurfaceTarget.SHARED, surfaceTargetFromFlags(WALLPAPER_FLAG_SYSTEM, supported = false))
        assertEquals(SurfaceTarget.SHARED, surfaceTargetFromFlags(WALLPAPER_FLAG_LOCK, supported = false))
    }

    @Test fun aHomeOnlyEngineFollowsTheHomeSwitch() {
        assertTrue(contentVisibleFor(SurfaceTarget.HOME, showOnLockscreen = false, showOnHomescreen = true, keyguardLocked = false))
        assertFalse(contentVisibleFor(SurfaceTarget.HOME, showOnLockscreen = false, showOnHomescreen = false, keyguardLocked = false))
        // The lock screen never shows this engine, so the lock switch cannot apply to it.
        assertTrue(contentVisibleFor(SurfaceTarget.HOME, showOnLockscreen = false, showOnHomescreen = true, keyguardLocked = true))
    }

    @Test fun aLockOnlyEngineFollowsTheLockSwitch() {
        assertTrue(contentVisibleFor(SurfaceTarget.LOCK, showOnLockscreen = true, showOnHomescreen = false, keyguardLocked = true))
        assertFalse(contentVisibleFor(SurfaceTarget.LOCK, showOnLockscreen = false, showOnHomescreen = true, keyguardLocked = true))
        // The home screen never shows this engine, so the home switch cannot apply to it.
        assertTrue(contentVisibleFor(SurfaceTarget.LOCK, showOnLockscreen = true, showOnHomescreen = false, keyguardLocked = false))
    }

    @Test fun aSharedEngineUsesTheKeyguardStateToNameTheVisibleScreen() {
        // Locked: the lock screen is on top, so the lock switch decides.
        assertTrue(contentVisibleFor(SurfaceTarget.SHARED, showOnLockscreen = true, showOnHomescreen = false, keyguardLocked = true))
        assertFalse(contentVisibleFor(SurfaceTarget.SHARED, showOnLockscreen = false, showOnHomescreen = true, keyguardLocked = true))
        // Unlocked: the home screen is on top, so the home switch decides.
        assertTrue(contentVisibleFor(SurfaceTarget.SHARED, showOnLockscreen = false, showOnHomescreen = true, keyguardLocked = false))
        assertFalse(contentVisibleFor(SurfaceTarget.SHARED, showOnLockscreen = true, showOnHomescreen = false, keyguardLocked = false))
    }

    @Test fun bothSwitchesOnAlwaysShowContentOnEverySurface() {
        for (target in SurfaceTarget.entries) {
            for (locked in listOf(true, false)) {
                assertTrue(
                    "$target locked=$locked",
                    contentVisibleFor(target, showOnLockscreen = true, showOnHomescreen = true, keyguardLocked = locked),
                )
            }
        }
    }

    @Test fun bothSwitchesOffAlwaysHideContentOnEverySurface() {
        for (target in SurfaceTarget.entries) {
            for (locked in listOf(true, false)) {
                assertFalse(
                    "$target locked=$locked",
                    contentVisibleFor(target, showOnLockscreen = false, showOnHomescreen = false, keyguardLocked = locked),
                )
            }
        }
    }
}
