package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class HaBackoffTest {

    @Test
    public void startsAtTwoSecondsAndDoubles() {
        assertEquals(2_000L, HaBackoff.delayMs(0));
        assertEquals(4_000L, HaBackoff.delayMs(1));
        assertEquals(8_000L, HaBackoff.delayMs(2));
        assertEquals(16_000L, HaBackoff.delayMs(3));
        assertEquals(32_000L, HaBackoff.delayMs(4));
    }

    @Test
    public void capsAtOneMinute() {
        assertEquals(60_000L, HaBackoff.delayMs(5));
        assertEquals(60_000L, HaBackoff.delayMs(50));
        assertEquals(60_000L, HaBackoff.delayMs(Integer.MAX_VALUE));
    }

    @Test
    public void negativeAttemptIsTheFirst() {
        assertEquals(2_000L, HaBackoff.delayMs(-1));
    }
}
