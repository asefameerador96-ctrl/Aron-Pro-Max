package com.aktcl.aron.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** docs/design/tokens.md s6: 3 columns on a 360 dp phone, 2 at font scale 1.5 or more, never fewer than 2 or more than 4. */
class TileColumnsTest {
    @Test fun threeOnA360PhoneAtNormalScale() = assertEquals(3, autoTileColumns(360f, 1.0f))
    @Test fun threeAtFontScaleOnePointThree() = assertEquals(3, autoTileColumns(360f, 1.3f))
    @Test fun twoFromFontScaleOnePointFive() { assertEquals(2, autoTileColumns(360f, 1.5f)); assertEquals(2, autoTileColumns(360f, 2.0f)) }
    @Test fun twoOnANarrowPhone() = assertEquals(2, autoTileColumns(320f, 1.0f))
    @Test fun fourOnALargeScreen() { assertEquals(4, autoTileColumns(480f, 1.0f)); assertEquals(4, autoTileColumns(800f, 1.0f)) }
}
