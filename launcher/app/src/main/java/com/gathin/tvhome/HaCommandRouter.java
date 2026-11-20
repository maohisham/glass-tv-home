package com.gathin.tvhome;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Sends each Home Assistant command to the handler registered for the part of its name before the first
 * dot ("timer" for "timer.start"). A new feature, such as the camera pop-up, registers its own prefix.
 */
final class HaCommandRouter {
    interface Handler {
        void handle(HaMessages.Command command);
    }

    private final Map<String, Handler> handlers = new HashMap<>();

    void register(String prefix, Handler handler) {
        handlers.put(prefix, handler);
    }

    /**
     * Returns whether a handler took the command. A command addressed to another device name (compared
     * ignoring case) is ignored; an empty device means every TV.
     */
    boolean dispatch(HaMessages.Command command, String ownDevice) {
        if (command == null) return false;
        String device = command.device == null ? "" : command.device;
        if (!device.isEmpty() && !device.toLowerCase(Locale.ROOT).equals(
                ownDevice == null ? "" : ownDevice.toLowerCase(Locale.ROOT))) {
            return false;
        }
        int dot = command.name.indexOf('.');
        if (dot <= 0) return false;
        Handler h = handlers.get(command.name.substring(0, dot));
        if (h == null) return false;
        h.handle(command);
        return true;
    }
}
