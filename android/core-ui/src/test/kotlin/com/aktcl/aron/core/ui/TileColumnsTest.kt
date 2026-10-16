package com.aktcl.aron.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * docs/design/tokens.md s6 (`fontScale.tiles2` 1.3, `fontScale.tiles1` 1.9): 3 columns on a 360 dp phone, 2 from font
 * scale 1.3, 1 from 1.9, never fewer than 2 or more than 4 below 1.3.
 */
class TileColumnsTest {
    @Test fun threeOnA360PhoneAtNormalScale() = assertEquals(3, autoTileColumns(360f, 1.0f))
    @Test fun threeJustBelowFontScaleOnePointThree() = assertEquals(3, autoTileColumns(360f, 1.29f))
    @Test fun twoFromFontScaleOnePointThree() { assertEquals(2, autoTileColumns(360f, 1.3f)); assertEquals(2, autoTileColumns(360f, 1.5f)); assertEquals(2, autoTileColumns(800f, 1.5f)) }
    @Test fun oneFromFontScaleOnePointNine() { assertEquals(1, autoTileColumns(360f, 1.9f)); assertEquals(1, autoTileColumns(360f, 2.0f)) }
    @Test fun twoOnANarrowPhone() = assertEquals(2, autoTileColumns(320f, 1.0f))
    @Test fun fourOnALargeScreen() { assertEquals(4, autoTileColumns(480f, 1.0f)); assertEquals(4, autoTileColumns(800f, 1.0f)) }
}
