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
        int slash = s.indexOf('/');
        String hostPort = slash >= 0 ? s.substring(0, slash) : s;
        String path = slash >= 0 ? s.substring(slash) : "";
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if (hostPort.isEmpty() || hostPort.matches(".*\\s.*")) return null;

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
        }
        if (host.isEmpty()) return null;
        if (port != null) {
            if (!port.matches("\\d{1,5}")) return null;
            int n = Integer.parseInt(port);
            if (n < 1 || n > 65535) return null;
        }
        if (!secure && !isPrivateHost(host)) return null;

        Parsed p = new Parsed();
        p.secure = secure;
        p.host = host.toLowerCase(Locale.ROOT);
        p.hostPort = hostPort.toLowerCase(Locale.ROOT);
        p.path = path;
        return p;
    }
}
