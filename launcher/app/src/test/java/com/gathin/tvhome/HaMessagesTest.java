package com.gathin.tvhome;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class HaMessagesTest {

    private static String commandEvent(String data) {
        return "{\"type\":\"event\",\"event\":{\"event_type\":\"glasstv_command\",\"data\":" + data + "}}";
    }

    @Test
    public void authMessage() throws Exception {
        JSONObject o = new JSONObject(HaMessages.auth("abc"));
        assertEquals("auth", o.getString("type"));
        assertEquals("abc", o.getString("access_token"));
    }

    @Test
    public void subscribeMessage() throws Exception {
        JSONObject o = new JSONObject(HaMessages.subscribeEvents(2, "glasstv_command"));
        assertEquals("subscribe_events", o.getString("type"));
        assertEquals("glasstv_command", o.getString("event_type"));
        assertEquals(2, o.getInt("id"));
    }

    @Test
    public void pingMessage() throws Exception {
        JSONObject o = new JSONObject(HaMessages.ping(9));
        assertEquals("ping", o.getString("type"));
        assertEquals(9, o.getInt("id"));
    }

    @Test
    public void stateEventMessage() throws Exception {
        JSONObject o = new JSONObject(HaMessages.stateEvent(5, "tv", true, 45, 2310));
        assertEquals("fire_event", o.getString("type"));
        assertEquals("glasstv_state", o.getString("event_type"));
        assertEquals(5, o.getInt("id"));
        JSONObject d = o.getJSONObject("event_data");
        assertEquals("tv", d.getString("device"));
        JSONObject t = d.getJSONObject("timer");
        assertTrue(t.getBoolean("active"));
        assertEquals(45, t.getInt("minutes"));
        assertEquals(2310, t.getLong("remaining_seconds"));
    }

    @Test
    public void authAndPongTypes() {
        assertEquals(HaMessages.Incoming.Type.AUTH_REQUIRED, HaMessages.parse("{\"type\":\"auth_required\"}").type);
        assertEquals(HaMessages.Incoming.Type.AUTH_OK, HaMessages.parse("{\"type\":\"auth_ok\"}").type);
        assertEquals(HaMessages.Incoming.Type.AUTH_INVALID, HaMessages.parse("{\"type\":\"auth_invalid\"}").type);
        assertEquals(HaMessages.Incoming.Type.PONG, HaMessages.parse("{\"id\":9,\"type\":\"pong\"}").type);
    }

    @Test
    public void commandEventParses() {
        HaMessages.Incoming in = HaMessages.parse(commandEvent("{\"command\":\"timer.start\",\"minutes\":45}"));
        assertEquals(HaMessages.Incoming.Type.COMMAND, in.type);
        assertEquals("timer.start", in.command.name);
        assertEquals(45, in.command.minutes);
        assertEquals("", in.command.device);
    }

    @Test
    public void commandDeviceIsKept() {
        HaMessages.Incoming in = HaMessages.parse(commandEvent("{\"command\":\"timer.cancel\",\"device\":\"Bedroom\"}"));
        assertEquals("Bedroom", in.command.device);
    }

    @Test
    public void badMinutesBecomeMinusOne() {
        for (String m : new String[]{"\"45\"", "4.5", "null", "-3", "0", "true", "[]"}) {
            HaMessages.Incoming in = HaMessages.parse(commandEvent("{\"command\":\"timer.start\",\"minutes\":" + m + "}"));
            assertEquals(m, HaMessages.Incoming.Type.COMMAND, in.type);
            assertEquals(m, -1, in.command.minutes);
        }
        HaMessages.Incoming none = HaMessages.parse(commandEvent("{\"command\":\"timer.start\"}"));
        assertEquals(-1, none.command.minutes);
    }

    @Test
    public void garbageIsOther() {
        assertEquals(HaMessages.Incoming.Type.OTHER, HaMessages.parse("not json").type);
        assertEquals(HaMessages.Incoming.Type.OTHER, HaMessages.parse("").type);
        assertEquals(HaMessages.Incoming.Type.OTHER, HaMessages.parse(null).type);
        assertEquals(HaMessages.Incoming.Type.OTHER, HaMessages.parse("[]").type);
    }

    @Test
    public void otherEventTypeIsOther() {
        String s = "{\"type\":\"event\",\"event\":{\"event_type\":\"state_changed\",\"data\":{}}}";
        assertEquals(HaMessages.Incoming.Type.OTHER, HaMessages.parse(s).type);
    }

    @Test
    public void commandWithoutNameIsOther() {
        assertEquals(HaMessages.Incoming.Type.OTHER, HaMessages.parse(commandEvent("{\"minutes\":5}")).type);
        assertEquals(HaMessages.Incoming.Type.OTHER, HaMessages.parse(commandEvent("{\"command\":5}")).type);
    }

    @Test
    public void resultMessage() {
        HaMessages.Incoming in = HaMessages.parse("{\"id\":7,\"type\":\"result\",\"success\":false}");
        assertEquals(HaMessages.Incoming.Type.RESULT, in.type);
        assertEquals(7, in.id);
        assertFalse(in.success);
        assertTrue(HaMessages.parse("{\"id\":8,\"type\":\"result\",\"success\":true}").success);
    }

    @Test
    public void wholeNumberSentAsDecimalIsAccepted() {
        HaMessages.Incoming in = HaMessages.parse(commandEvent("{\"command\":\"timer.start\",\"minutes\":30.0}"));
        assertEquals(30, in.command.minutes);
    }
}
