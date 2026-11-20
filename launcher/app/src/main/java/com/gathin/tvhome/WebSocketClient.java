package com.gathin.tvhome;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * A minimal WebSocket client (RFC 6455, text messages only). One thread reads; a second single thread
 * writes, so {@link #send} and {@link #close} are safe to call from the main thread (Android forbids
 * network writes there). Callbacks arrive on the reading thread: the owner posts them to the main thread.
 * Created per connection attempt; once it has reported {@link Listener#onClosed} it is finished and a new
 * instance makes the next attempt.
 */
final class WebSocketClient {
    private static final String TAG = "TvHome.WebSocket";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    /** No frame at all for this long (Home Assistant answers our 30 s pings) means the link is dead. */
    private static final int READ_TIMEOUT_MS = 75_000;
    /** Messages waiting to be written; past this a stalled peer makes {@link #send} refuse, not grow memory. */
    private static final int MAX_PENDING_BYTES = 4 * 1024 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();

    interface Listener {
        void onOpen();
        void onText(String text);
        /** Always called exactly once; {@code reason} is null for a close we asked for or a clean close. */
        void onClosed(Exception reason);
    }

    private final String wsUrl;
    private final Listener listener;
    private final AtomicInteger pendingBytes = new AtomicInteger();
    private volatile Socket socket;
    private volatile OutputStream out;
    private volatile ExecutorService writer;
    private volatile boolean closedByUs;

    WebSocketClient(String wsUrl, Listener listener) {
        this.wsUrl = wsUrl;
        this.listener = listener;
    }

    void start() {
        Thread t = new Thread(this::run, "HaWebSocket");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Queues a text message and returns at once; the write happens on the writer thread. False when not
     * connected, already closed, or too much is still waiting to be written.
     */
    boolean send(String text) {
        return write(WsFrames.OP_TEXT, text.getBytes(StandardCharsets.UTF_8));
    }

    /** Safe from any thread, including the main thread: the socket is closed on a thread of its own. */
    void close() {
        closedByUs = true;
        ExecutorService w = writer;
        if (w != null) w.shutdownNow();
        Socket s = socket;
        if (s != null) closeInBackground(s);
    }

    private static void closeInBackground(Socket s) {
        Thread t = new Thread(() -> {
            try { s.close(); } catch (IOException ignored) { }
        }, "HaWebSocketClose");
        t.setDaemon(true);
        t.start();
    }

    private boolean write(int opcode, byte[] payload) {
        ExecutorService w = writer;
        OutputStream o = out;
        if (w == null || o == null || closedByUs) return false;
        byte[] mask = new byte[4];
        RANDOM.nextBytes(mask);
        byte[] frame = WsFrames.encode(opcode, payload, mask);
        if (pendingBytes.addAndGet(frame.length) > MAX_PENDING_BYTES) {
            pendingBytes.addAndGet(-frame.length);
            return false;
        }
        try {
            w.execute(() -> {
                try {
                    o.write(frame);
                    o.flush();
                } catch (IOException | RuntimeException e) {
                    // A failed write ends the connection; the reader notices and reports it.
                    Socket s = socket;
                    if (s != null) {
                        try { s.close(); } catch (IOException ignored) { }
                    }
                } finally {
                    pendingBytes.addAndGet(-frame.length);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            pendingBytes.addAndGet(-frame.length);
            return false;
        }
    }

    private void run() {
        Exception reason = null;
        try {
            connectAndRead();
        } catch (Exception e) {
            if (!closedByUs) reason = e;
        } finally {
            out = null;
            ExecutorService w = writer;
            if (w != null) w.shutdownNow();
            Socket s = socket;
            if (s != null) {
                try { s.close(); } catch (IOException ignored) { }
            }
            listener.onClosed(reason);
        }
    }

    private void connectAndRead() throws Exception {
        if (closedByUs) return;
        URI uri = new URI(wsUrl);
        boolean tls = "wss".equalsIgnoreCase(uri.getScheme());
        String host = uri.getHost();
        if (host == null) throw new IOException("bad address");
        int port = uri.getPort() > 0 ? uri.getPort() : (tls ? 443 : 80);
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();

        Socket plain = new Socket();
        socket = plain;
        plain.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        if (closedByUs) return;
        plain.setSoTimeout(CONNECT_TIMEOUT_MS);
        Socket s = plain;
        if (tls) {
            SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(plain, host, port, true);
            socket = ssl;
            ssl.startHandshake();
            SSLSession session = ssl.getSession();
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)) {
                throw new IOException("certificate does not match " + host);
            }
            s = ssl;
        }
        if (closedByUs) return;

        InputStream in = new BufferedInputStream(s.getInputStream());
        OutputStream rawOut = s.getOutputStream();

        byte[] keyBytes = new byte[16];
        RANDOM.nextBytes(keyBytes);
        String key = Base64.getEncoder().encodeToString(keyBytes);
        String hostHeader = uri.getPort() > 0 ? host + ":" + uri.getPort() : host;
        String request = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + hostHeader + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
        rawOut.write(request.getBytes(StandardCharsets.US_ASCII));
        rawOut.flush();

        String status = readLine(in);
        if (status == null || !status.startsWith("HTTP/1.1 101")) {
            throw new IOException("upgrade refused: " + status);
        }
        String accept = null;
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            if (line.toLowerCase(Locale.ROOT).startsWith("sec-websocket-accept:")) {
                accept = line.substring(line.indexOf(':') + 1).trim();
            }
        }
        if (!WsFrames.acceptKey(key).equals(accept)) throw new IOException("bad Sec-WebSocket-Accept");

        s.setSoTimeout(READ_TIMEOUT_MS);
        writer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "HaWebSocketWriter");
            t.setDaemon(true);
            return t;
        });
        out = rawOut;
        if (closedByUs) return;
        listener.onOpen();

        while (!closedByUs) {
            WsFrames.Message m = WsFrames.read(in);
            switch (m.opcode) {
                case WsFrames.OP_TEXT:
                    listener.onText(new String(m.payload, StandardCharsets.UTF_8));
                    break;
                case WsFrames.OP_PING:
                    write(WsFrames.OP_PONG, m.payload);
                    break;
                case WsFrames.OP_CLOSE:
                    write(WsFrames.OP_CLOSE, new byte[0]);
                    return;
                default:
                    break;
            }
        }
        Log.d(TAG, "closed");
    }

    /** Reads one header line (up to 8 KB), without the line ending; null at end of stream. */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) throw new IOException("header line too long");
        }
        if (c < 0 && b.size() == 0) return null;
        return b.toString("ISO-8859-1");
    }
}
