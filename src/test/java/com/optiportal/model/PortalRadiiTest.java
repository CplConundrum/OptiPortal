package com.optiportal.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the neutral radius-resolution helpers (deslopify qd-001 + qd-003):
 * the per-axis family, the uniform family, and the strategy-aware family
 * (WARM -&gt; default warm radius, otherwise predictive radius).
 */
class PortalRadiiTest {

    private PortalEntry entry() {
        PortalEntry e = new PortalEntry();
        e.setId("t1");
        e.setWorld("world");
        return e;
    }

    @Test
    void resolveRadiusX_explicitAxisWinsOverEverything() {
        PortalEntry e = entry();
        e.setWarmRadiusX(5);
        e.setWarmRadius(7);
        e.setStrategy(WarmStrategy.WARM);
        assertEquals(5, PortalRadii.resolveRadiusX(e, 3));
        assertEquals(5, PortalRadii.resolveRadiusXStrategy(e, 3, 8));
    }

    @Test
    void resolveRadius_uniformOverridesDefault() {
        PortalEntry e = entry();
        e.setWarmRadius(7);
        assertEquals(7, PortalRadii.resolveRadiusX(e, 3));
        assertEquals(7, PortalRadii.resolveRadiusZ(e, 3));
    }

    @Test
    void resolveRadiusX_fallsBackToCallerDefault() {
        assertEquals(3, PortalRadii.resolveRadiusX(entry(), 3));
    }

    @Test
    void resolveRadiusZ_explicitZWinsOverUniform() {
        PortalEntry e = entry();
        e.setWarmRadiusZ(6);
        e.setWarmRadius(7);
        assertEquals(6, PortalRadii.resolveRadiusZ(e, 3));
    }

    @Test
    void resolveRadius_uniformOverridesFallback() {
        PortalEntry e = entry();
        e.setWarmRadius(4);
        assertEquals(4, PortalRadii.resolveRadius(e, 9));
    }

    @Test
    void resolveRadius_zeroUniformFallsBack() {
        assertEquals(9, PortalRadii.resolveRadius(entry(), 9));
    }

    @Test
    void strategyVariant_warmStrategyUsesDefaultWarmRadius() {
        PortalEntry e = entry();
        e.setStrategy(WarmStrategy.WARM);
        assertEquals(3, PortalRadii.resolveRadiusXStrategy(e, 3, 8));
        assertEquals(3, PortalRadii.resolveRadiusZStrategy(e, 3, 8));
    }

    @Test
    void strategyVariant_predictiveStrategyUsesPredictiveRadius() {
        PortalEntry e = entry();
        e.setStrategy(WarmStrategy.PREDICTIVE);
        assertEquals(8, PortalRadii.resolveRadiusXStrategy(e, 3, 8));
        assertEquals(8, PortalRadii.resolveRadiusZStrategy(e, 3, 8));
    }

    @Test
    void strategyVariant_nullStrategyTreatedAsPredictive() {
        PortalEntry e = entry();
        e.setStrategy(null);
        assertEquals(8, PortalRadii.resolveRadiusXStrategy(e, 3, 8));
    }

    @Test
    void defaultEntryStrategyIsPredictive() {
        assertEquals(WarmStrategy.PREDICTIVE, entry().getStrategy());
    }
}
