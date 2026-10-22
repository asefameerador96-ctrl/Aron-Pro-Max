package com.aktcl.aron.core.geo

import org.junit.Test
import org.junit.Assert.assertEquals

class MdmSamplingTest {
    private val cfg = BreadcrumbSettings(enabled = true, intervalMin = 30)

    @Test fun offStopsBreadcrumbsWhateverTheConfigSays() = assertEquals(false, cfg.withMdmSampling("off", null).enabled)

    @Test fun intervalTurnsThemOnAtThePolicyIntervalWithinTheConfigClamp() {
        val off = BreadcrumbSettings(enabled = false)
        assertEquals(BreadcrumbSettings(enabled = true, intervalMin = 15), off.withMdmSampling("interval", 15))
        assertEquals(5, off.withMdmSampling("interval", 1).intervalMin)
        assertEquals(120, off.withMdmSampling("interval", 500).intervalMin)
        assertEquals(30, off.withMdmSampling("interval", null).intervalMin)
    }

    @Test fun onDemandAndUnknownModesLeaveTheConfigAlone() {
        assertEquals(cfg, cfg.withMdmSampling("on_demand", null))
        assertEquals(cfg, cfg.withMdmSampling(null, null))
        assertEquals(cfg, cfg.withMdmSampling("something_new", 5))
    }
}
