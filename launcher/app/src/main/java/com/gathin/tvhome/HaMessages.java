package com.gathin.tvhome;

import org.json.JSONException;
import org.json.JSONObject;

/** Builds and parses the JSON messages of the Home Assistant WebSocket API. Never throws on bad input. */
final class HaMessages {
    static final String EVENT_COMMAND = "glasstv_command";
    static final String EVENT_STATE = "glasstv_state";

    private HaMessages() {}

    /** One command from Home Assistant. {@code minutes} is -1 when absent or not a positive whole number. */
    static final class Command {
        final String name;
        final int minutes;
        final String device;

        Command(String name, int minutes, String device) {
            this.name = name;
            this.minutes = minutes;
            this.device = device;
        }
    }

    /** One message from Home Assistant, reduced to what the launcher acts on. */
    static final class Incoming {
        enum Type { AUTH_REQUIRED, AUTH_OK, AUTH_INVALID, COMMAND, RESULT, PONG, OTHER }

        final Type type;
        final Command command;   // only for COMMAND
        final int id;            // only for RESULT / PONG, else -1
        final boolean success;   // only for RESULT

        Incoming(Type type, Command command, int id, boolean success) {
            this.type = type;
            this.command = command;
            this.id = id;
            this.success = success;
        }
    }

    private static final Incoming OTHER = new Incoming(Incoming.Type.OTHER, null, -1, false);

    static String auth(String token) {
        try {
            return new JSONObject().put("type", "auth").put("access_token", token).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    static String subscribeEvents(int id, String eventType) {
        try {
            return new JSONObject().put("id", id).put("type", "subscribe_events")
                    .put("event_type", eventType).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    static String ping(int id) {
        try {
            return new JSONObject().put("id", id).put("type", "ping").toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    static String stateEvent(int id, String device, boolean active, int minutes, long remainingSeconds) {
        try {
            JSONObject timer = new JSONObject().put("active", active).put("minutes", minutes)
                    .put("remaining_seconds", remainingSeconds);
            JSONObject data = new JSONObject().put("device", device).put("timer", timer);
            return new JSONObject().put("id", id).put("type", "fire_event")
                    .put("event_type", EVENT_STATE).put("event_data", data).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    static Incoming parse(String json) {
        if (json == null || json.isEmpty()) return OTHER;
        try {
            JSONObject o = new JSONObject(json);
            Object t = o.opt("type");
            if (!(t instanceof String)) return OTHER;
            switch ((String) t) {
                case "auth_required": return new Incoming(Incoming.Type.AUTH_REQUIRED, null, -1, false);
                case "auth_ok": return new Incoming(Incoming.Type.AUTH_OK, null, -1, false);
                case "auth_invalid": return new Incoming(Incoming.Type.AUTH_INVALID, null, -1, false);
                case "pong": return new Incoming(Incoming.Type.PONG, null, o.optInt("id", -1), false);
                case "result":
                    return new Incoming(Incoming.Type.RESULT, null, o.optInt("id", -1), o.optBoolean("success", false));
                case "event": return parseEvent(o);
                default: return OTHER;
            }
        } catch (JSONException e) {
            return OTHER;
        }
    }

    private static Incoming parseEvent(JSONObject o) {
        JSONObject ev = o.optJSONObject("event");
        if (ev == null || !EVENT_COMMAND.equals(ev.opt("event_type"))) return OTHER;
        JSONObject data = ev.optJSONObject("data");
        if (data == null) return OTHER;
        Object name = data.opt("command");
        if (!(name instanceof String) || ((String) name).isEmpty()) return OTHER;
        int minutes = -1;
        Object m = data.opt("minutes");
        if (m instanceof Number) {
            // A helper may send 30.0 for 30; accept a whole number however it is written, never 4.5.
            double d = ((Number) m).doubleValue();
            if (d >= 1 && d <= Integer.MAX_VALUE && d == Math.rint(d)) minutes = (int) d;
        }
        Object dev = data.opt("device");
        String device = dev instanceof String ? (String) dev : "";
        return new Incoming(Incoming.Type.COMMAND, new Command((String) name, minutes, device), -1, false);
    }
}
