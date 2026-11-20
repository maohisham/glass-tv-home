package com.gathin.tvhome;

import org.junit.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Runs the real client against a tiny loopback WebSocket server. */
public class WebSocketClientTest {

    private static final class Server {
        final ServerSocket ss;
        final BlockingQueue<String> received = new LinkedBlockingQueue<>();
        volatile boolean accepted;
        final boolean readFrames;

        Server(boolean readFrames) throws IOException {
            this.ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            this.readFrames = readFrames;
            Thread t = new Thread(this::serve, "test-ws-server");
            t.setDaemon(true);
            t.start();
        }

        String url() { return "ws://127.0.0.1:" + ss.getLocalPort() + "/api/websocket"; }

        void close() { try { ss.close(); } catch (IOException ignored) { } }

        private void serve() {
            try (Socket s = ss.accept()) {
                accepted = true;
                InputStream in = new BufferedInputStream(s.getInputStream());
                String key = null;
                String line;
                while ((line = readLine(in)) != null && !line.isEmpty()) {
                    if (line.toLowerCase().startsWith("sec-websocket-key:")) key = line.substring(18).trim();
                }
                String reply = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                        + "Sec-WebSocket-Accept: " + WsFrames.acceptKey(key) + "\r\n\r\n";
                s.getOutputStream().write(reply.getBytes(StandardCharsets.US_ASCII));
                s.getOutputStream().flush();
                if (readFrames) {
                    while (true) {
                        WsFrames.Message m = WsFrames.read(in);
                        if (m.opcode == WsFrames.OP_TEXT) received.offer(new String(m.payload, StandardCharsets.UTF_8));
                    }
                } else {
                    Thread.sleep(20_000);   // hold the connection open and never read
                }
            } catch (Exception ignored) {
                // the test ended and closed the sockets
            }
        }

        private static String readLine(InputStream in) throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            int c;
            while ((c = in.read()) >= 0) {
                if (c == '\n') break;
                if (c != '\r') b.write(c);
            }
            return c < 0 && b.size() == 0 ? null : b.toString("ISO-8859-1");
        }
    }

    private static final class Probe implements WebSocketClient.Listener {
        final CountDownLatch opened = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        @Override public void onOpen() { opened.countDown(); }
        @Override public void onText(String text) { }
        @Override public void onClosed(Exception reason) { closed.countDown(); }
    }

    @Test(timeout = 20_000)
    public void aSentMessageReachesTheServer() throws Exception {
        Server server = new Server(true);
        Probe probe = new Probe();
        WebSocketClient c = new WebSocketClient(server.url(), probe);
        try {
            c.start();
            assertTrue("opened", probe.opened.await(5, TimeUnit.SECONDS));
            assertTrue(c.send("hello"));
            assertEquals("hello", server.received.poll(5, TimeUnit.SECONDS));
        } finally {
            c.close();
            server.close();
        }
    }

    @Test(timeout = 20_000)
    public void sendNeverBlocksTheCallerEvenWhenThePeerStopsReading() throws Exception {
        Server server = new Server(false);
        Probe probe = new Probe();
        WebSocketClient c = new WebSocketClient(server.url(), probe);
        try {
            c.start();
            assertTrue("opened", probe.opened.await(5, TimeUnit.SECONDS));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 1_000_000; i++) b.append('x');
            String big = b.toString();
            long t0 = System.nanoTime();
            for (int i = 0; i < 40; i++) c.send(big);   // 40 MB the peer never reads
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            assertTrue("send blocked the caller for " + ms + " ms", ms < 3_000);
        } finally {
            c.close();
            server.close();
        }
    }

    @Test(timeout = 20_000)
    public void closingBeforeStartNeverConnects() throws Exception {
        Server server = new Server(true);
        Probe probe = new Probe();
        WebSocketClient c = new WebSocketClient(server.url(), probe);
        try {
            c.close();
            c.start();
            assertTrue("reported closed", probe.closed.await(5, TimeUnit.SECONDS));
            assertEquals("never opened", 1, probe.opened.getCount());
            Thread.sleep(300);
            assertFalse("never connected", server.accepted);
        } finally {
            server.close();
        }
    }
}
