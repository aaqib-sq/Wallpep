package dev.wallpep.render

import org.junit.Assert.assertEquals
import org.junit.Test

class LayerParallaxTest {
    @Test fun aLayerMovesOnlyWhenTheGlobalAndItsOwnSwitchAreOn() {
        assertEquals(0f, layerParallaxScale(false, 100, true, 100), 1e-6f)
        assertEquals(0f, layerParallaxScale(true, 40, false, 100), 1e-6f)
        assertEquals(0.4f, layerParallaxScale(true, 40, true, 100), 1e-6f)
    }

    @Test fun theLayerStrengthIsAPercentageOfTheGlobalTravel() {
        assertEquals(0.2f, layerParallaxScale(true, 40, true, 50), 1e-6f)
        assertEquals(0f, layerParallaxScale(true, 40, true, 0), 1e-6f)
        assertEquals(0.4f, layerParallaxScale(true, 40, true, 100), 1e-6f)
    }

    @Test fun shippedDefaultsReproduceThePrePerLayerBehaviour() {
        // Before the per-layer keys existed, a layer moved at exactly the global strength.
        assertEquals(1f, layerParallaxScale(true, 100, true, 100), 1e-6f)
        assertEquals(0.55f, layerParallaxScale(true, 55, true, 100), 1e-6f)
    }

    @Test fun outOfRangeStrengthsAreClamped() {
        assertEquals(1f, layerParallaxScale(true, 900, true, 900), 1e-6f)
        assertEquals(0f, layerParallaxScale(true, -20, true, -5), 1e-6f)
    }
}
