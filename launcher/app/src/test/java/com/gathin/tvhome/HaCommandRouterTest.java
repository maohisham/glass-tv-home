package com.gathin.tvhome;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class HaCommandRouterTest {

    private static HaMessages.Command cmd(String name, String device) {
        return new HaMessages.Command(name, -1, device);
    }

    @Test
    public void prefixHandlerReceivesItsCommands() {
        HaCommandRouter r = new HaCommandRouter();
        List<String> got = new ArrayList<>();
        r.register("timer", c -> got.add(c.name));
        assertTrue(r.dispatch(cmd("timer.start", ""), "tv"));
        assertEquals(1, got.size());
        assertEquals("timer.start", got.get(0));
    }

    @Test
    public void unknownPrefixIsNotHandled() {
        HaCommandRouter r = new HaCommandRouter();
        r.register("timer", c -> fail("must not be called"));
        assertFalse(r.dispatch(cmd("camera.show", ""), "tv"));
    }

    @Test
    public void prefixMustEndAtADot() {
        HaCommandRouter r = new HaCommandRouter();
        r.register("timer", c -> fail("must not be called"));
        assertFalse(r.dispatch(cmd("timerx.start", ""), "tv"));
        assertFalse(r.dispatch(cmd("timer", ""), "tv"));
    }

    @Test
    public void deviceNameMatchIgnoresCase() {
        HaCommandRouter r = new HaCommandRouter();
        List<String> got = new ArrayList<>();
        r.register("timer", c -> got.add(c.name));
        assertTrue(r.dispatch(cmd("timer.cancel", "Living-Room"), "living-room"));
        assertEquals(1, got.size());
    }

    @Test
    public void otherDeviceIsIgnored() {
        HaCommandRouter r = new HaCommandRouter();
        r.register("timer", c -> fail("must not be called"));
        assertFalse(r.dispatch(cmd("timer.cancel", "bedroom"), "living-room"));
    }

    @Test
    public void emptyDeviceMeansEveryTv() {
        HaCommandRouter r = new HaCommandRouter();
        List<String> got = new ArrayList<>();
        r.register("timer", c -> got.add(c.name));
        assertTrue(r.dispatch(cmd("timer.cancel", ""), "bedroom"));
        assertEquals(1, got.size());
    }
}
