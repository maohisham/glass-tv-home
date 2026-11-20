package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class SleepTimerTest {

    @Test
    public void formatCountdownBelowOneHour() {
        assertEquals("29:59", SleepTimer.formatMs(29 * 60_000L + 59_000L));
    }

    @Test
    public void formatCountdownAtOneHour() {
        assertEquals("1:00:00", SleepTimer.formatMs(60 * 60_000L));
    }

    @Test
    public void formatCountdownAboveOneHour() {
        assertEquals("1:05:03", SleepTimer.formatMs(65 * 60_000L + 3_000L));
    }

    @Test
    public void formatCountdownAtZero() {
        assertEquals("0:00", SleepTimer.formatMs(0));
    }

    @Test
    public void nextPresetCyclesFrom0() {
        assertEquals(15, SleepTimer.nextPresetMinutes(0));
    }

    @Test
    public void nextPresetCyclesFrom15() {
        assertEquals(30, SleepTimer.nextPresetMinutes(15));
    }

    @Test
    public void nextPresetCyclesFrom30() {
        assertEquals(60, SleepTimer.nextPresetMinutes(30));
    }

    @Test
    public void nextPresetCyclesFrom60() {
        assertEquals(120, SleepTimer.nextPresetMinutes(60));
    }

    @Test
    public void nextPresetWrapsFrom120() {
        assertEquals(0, SleepTimer.nextPresetMinutes(120));
    }

    @Test
    public void nextPresetUnknownValueGoesToNextPresetAbove() {
        assertEquals(120, SleepTimer.nextPresetMinutes(99));
        assertEquals(60, SleepTimer.nextPresetMinutes(45));
        assertEquals(15, SleepTimer.nextPresetMinutes(7));
        assertEquals(15, SleepTimer.nextPresetMinutes(-4));
    }

    @Test
    public void nextPresetAboveLastPresetIsOff() {
        assertEquals(0, SleepTimer.nextPresetMinutes(130));
        assertEquals(0, SleepTimer.nextPresetMinutes(720));
    }

    @Test
    public void validMinutesIsOneTo720() {
        assertFalse(SleepTimer.validMinutes(0));
        assertFalse(SleepTimer.validMinutes(-1));
        assertFalse(SleepTimer.validMinutes(721));
        assertTrue(SleepTimer.validMinutes(1));
        assertTrue(SleepTimer.validMinutes(720));
    }

    @Test
    public void addToIdleTimerStartsOne() {
        assertEquals(900_000L, SleepTimer.addedRemainingMs(false, 0, 15));
    }

    @Test
    public void addToRunningTimerExtendsIt() {
        assertEquals(1_500_000L, SleepTimer.addedRemainingMs(true, 600_000L, 15));
    }

    @Test
    public void addNeverGoesAbove720Minutes() {
        assertEquals(720 * 60_000L, SleepTimer.addedRemainingMs(true, 719 * 60_000L, 30));
    }

    @Test
    public void subtractRemovesTime() {
        assertEquals(300_000L, SleepTimer.subtractedRemainingMs(600_000L, 5));
    }

    @Test
    public void subtractLeavesOneMinute() {
        assertEquals(60_000L, SleepTimer.subtractedRemainingMs(120_000L, 60));
    }

    @Test
    public void subtractNeverExtendsATimerUnderOneMinute() {
        assertEquals(30_000L, SleepTimer.subtractedRemainingMs(30_000L, 5));
    }

    @Test
    public void defaultTimerStartsOnlyOncePerProcess() {
        assertTrue(SleepTimer.shouldApplyDefault(false, false, 30));
        assertFalse(SleepTimer.shouldApplyDefault(true, false, 30));
    }

    @Test
    public void defaultTimerNeverReplacesARunningOne() {
        assertFalse(SleepTimer.shouldApplyDefault(false, true, 30));
    }

    @Test
    public void noDefaultMeansNoTimer() {
        assertFalse(SleepTimer.shouldApplyDefault(false, false, 0));
    }
}
