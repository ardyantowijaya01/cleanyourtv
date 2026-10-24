package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The sun and moon positions behind the backdrop's discs. */
public class SkyBodyTest {

    // A Jakarta-ish day: sunrise 05:50, sunset 17:55.
    private static final int RISE = 350;
    private static final int SET = 1075;

    @Test
    public void sunIsUpBetweenRiseAndSet() {
        assertFalse(Sky.bodyAt(RISE + 30, RISE, SET).moon);
        assertFalse(Sky.bodyAt(SET - 30, RISE, SET).moon);
    }

    @Test
    public void sunPeaksAtMidday() {
        Sky.Body noon = Sky.bodyAt((RISE + SET) / 2, RISE, SET);
        assertEquals(1f, noon.alt, 0.01f);
        assertEquals(0.5f, noon.x, 0.01f);
    }

    @Test
    public void sunSitsOnTheHorizonAtRiseAndSet() {
        assertEquals(0f, Sky.bodyAt(RISE, RISE, SET).alt, 0.001f);
        assertEquals(0f, Sky.bodyAt(SET, RISE, SET).alt, 0.001f);
        assertEquals(0f, Sky.bodyAt(RISE, RISE, SET).x, 0.001f);
        assertEquals(1f, Sky.bodyAt(SET, RISE, SET).x, 0.001f);
    }

    @Test
    public void moonTakesOverAtNightAndWrapsMidnight() {
        assertTrue(Sky.bodyAt(SET + 60, RISE, SET).moon);
        assertTrue(Sky.bodyAt(30, RISE, SET).moon);
        // Both sides of midnight are one climbing arc, not two.
        float before = Sky.bodyAt(1439, RISE, SET).x;
        float after = Sky.bodyAt(0, RISE, SET).x;
        assertTrue(after > before);
    }

    @Test
    public void anUnusableDayFallsBackToTheReferenceOne() {
        Sky.Body noon = Sky.bodyAt((Sky.REF_SUNRISE + Sky.REF_SUNSET) / 2, 0, 0);
        assertFalse(noon.moon);
        assertEquals(1f, noon.alt, 0.01f);
    }

    @Test
    public void moonPhaseFollowsTheLunarDay() {
        assertEquals(0f, Sky.moonLit(1), 0.01f);
        assertEquals(1f, Sky.moonLit(16), 0.02f);
        assertTrue(Sky.moonWaxing(5));
        assertFalse(Sky.moonWaxing(22));
    }
}
