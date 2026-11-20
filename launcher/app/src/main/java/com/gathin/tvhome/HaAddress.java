package com.gathin.tvhome;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the Home Assistant address the owner typed into a WebSocket or HTTP URL, and refuses
 * plain (unencrypted) connections to hosts outside the home network. Pure Java: no Android classes.
 */
final class HaAddress {
    private static final String WS_PATH = "/api/websocket";
    private static final int DEFAULT_PORT = 8123;
    private static final Pattern SCHEME = Pattern.compile("^([a-zA-Z][a-zA-Z0-9+.-]*)://(.*)$");
    private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");

    private HaAddress() {}

    /** A parsed address: {@code secure} is true for https/wss. */
    private static final class Parsed {
        boolean secure;
        String host;      // without brackets, lower case
        String hostPort;  // as it goes in a URL, host lower-cased
        String path;      // no trailing slash, may be empty
    }

    /** {@code ws(s)://host[:port]/api/websocket}, or null when invalid or a public host over plain http. */
    static String toWebSocketUrl(String input) {
        Parsed p = parse(input);
        if (p == null) return null;
        String path = p.path.endsWith(WS_PATH) ? p.path : p.path + WS_PATH;
        return (p.secure ? "wss://" : "ws://") + p.hostPort + path;
    }

    /** {@code http(s)://host[:port]} with no path, or null when invalid or not allowed. */
    static String toHttpBase(String input) {
        Parsed p = parse(input);
        if (p == null) return null;
        return (p.secure ? "https://" : "http://") + p.hostPort;
    }

    static boolean isPrivateHost(String host) {
        if (host == null) return false;
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        if (h.isEmpty()) return false;
        Matcher m = IPV4.matcher(h);
        if (m.matches()) {
            for (int i = 1; i <= 4; i++) {
                if (Integer.parseInt(m.group(i)) > 255) return false;
            }
            int a = Integer.parseInt(m.group(1)), b = Integer.parseInt(m.group(2));
            return a == 10 || a == 127 || (a == 169 && b == 254)
                    || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168);
        }
        if (h.indexOf(':') >= 0) {
            return h.equals("::1") || h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd");
        }
        return h.equals("localhost") || h.indexOf('.') < 0
                || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa");
    }

    private static Parsed parse(String input) {
        if (input == null) return null;
        String s = input.trim();
        if (s.isEmpty()) return null;
        String scheme = "http";
        Matcher sm = SCHEME.matcher(s);
        if (sm.matches()) {
            scheme = sm.group(1).toLowerCase(Locale.ROOT);
            s = sm.group(2);
        }
        boolean secure;
        switch (scheme) {
            case "http": case "ws": secure = false; break;
            case "https": case "wss": secure = true; break;
            default: return null;
        }
        // The host part ends at the first / ? or #; a pasted browser address loses its page, query and fragment.
        int end = s.length();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '/' || ch == '?' || ch == '#') { end = i; break; }
        }
        String hostPort = s.substring(0, end);
        String path = "";
        if (end < s.length() && s.charAt(end) == '/') {
            int stop = s.length();
            for (int i = end; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (ch == '?' || ch == '#') { stop = i; break; }
            }
            path = s.substring(end, stop);
            while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            if (!path.endsWith(WS_PATH)) path = "";
        }
        if (hostPort.isEmpty()) return null;

        String host = hostPort, port = null;
        if (hostPort.startsWith("[")) {
            int close = hostPort.indexOf(']');
            if (close < 0) return null;
            host = hostPort.substring(1, close);
            String rest = hostPort.substring(close + 1);
            if (!rest.isEmpty()) {
                if (!rest.startsWith(":")) return null;
                port = rest.substring(1);
            }
        } else {
            int colon = hostPort.lastIndexOf(':');
            if (colon >= 0) {
                host = hostPort.substring(0, colon);
                port = hostPort.substring(colon + 1);
            }
            if (!host.matches("[A-Za-z0-9.-]+")) return null;
        }
        if (host.isEmpty()) return null;
        if (port != null) {
            if (!port.matches("\\d{1,5}")) return null;
            int n = Integer.parseInt(port);
            if (n < 1 || n > 65535) return null;
        }
        if (!secure && !isPrivateHost(host)) return null;
        // Home Assistant listens on 8123; a plain address typed without a port means that.
        if (port == null && !secure) hostPort = hostPort + ":" + DEFAULT_PORT;

        Parsed p = new Parsed();
        p.secure = secure;
        p.host = host.toLowerCase(Locale.ROOT);
        p.hostPort = hostPort.toLowerCase(Locale.ROOT);
        p.path = path;
        return p;
    }
}
