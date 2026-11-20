package com.gathin.tvhome;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** RFC 6455 frame encoding and decoding, and the handshake accept key. Pure Java, JVM-tested. */
final class WsFrames {
    static final int OP_CONT = 0;
    static final int OP_TEXT = 1;
    static final int OP_CLOSE = 8;
    static final int OP_PING = 9;
    static final int OP_PONG = 10;
    /** Largest message accepted; a longer one (or a header claiming more) is an error, never an allocation. */
    static final int MAX_PAYLOAD = 1_048_576;

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private WsFrames() {}

    /** One logical message: a complete text message (fragments joined) or one control frame. */
    static final class Message {
        final int opcode;
        final byte[] payload;

        Message(int opcode, byte[] payload) {
            this.opcode = opcode;
            this.payload = payload;
        }
    }

    static String acceptKey(String clientKey) {
        try {
            byte[] sha = MessageDigest.getInstance("SHA-1")
                    .digest((clientKey + GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(sha);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A single final, masked frame as a client must send it. {@code maskKey} is 4 bytes. */
    static byte[] encode(int opcode, byte[] payload, byte[] maskKey) {
        int n = payload.length;
        ByteArrayOutputStream o = new ByteArrayOutputStream(n + 14);
        o.write(0x80 | opcode);
        if (n < 126) {
            o.write(0x80 | n);
        } else if (n < 65_536) {
            o.write(0x80 | 126);
            o.write(n >> 8);
            o.write(n & 0xFF);
        } else {
            o.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) o.write((int) (((long) n >> (8 * i)) & 0xFF));
        }
        o.write(maskKey, 0, 4);
        for (int i = 0; i < n; i++) o.write(payload[i] ^ maskKey[i & 3]);
        return o.toByteArray();
    }

    /**
     * Reads the next message. Joins continuation frames; returns control frames as they come. Throws
     * {@link IOException} on end of stream, a malformed header, or anything over {@link #MAX_PAYLOAD}.
     * A control frame in the middle of a fragmented message is treated as an error (Home Assistant never
     * sends one); the connection is simply re-made.
     */
    static Message read(InputStream in) throws IOException {
        ByteArrayOutputStream acc = null;
        int firstOp = -1;
        while (true) {
            int b0 = readByte(in);
            int b1 = readByte(in);
            boolean fin = (b0 & 0x80) != 0;
            int op = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7F;
            if (len == 126) {
                len = (readByte(in) << 8) | readByte(in);
            } else if (len == 127) {
                long v = 0;
                for (int i = 0; i < 8; i++) v = (v << 8) | readByte(in);
                len = v;
            }
            if (len < 0 || len > MAX_PAYLOAD) throw new IOException("frame too large");
            byte[] mask = masked ? readFully(in, 4) : null;
            byte[] p = readFully(in, (int) len);
            if (mask != null) for (int i = 0; i < p.length; i++) p[i] ^= mask[i & 3];

            if (op >= 8) {
                if (!fin || p.length > 125) throw new IOException("bad control frame");
                if (acc != null) throw new IOException("control frame inside a fragmented message");
                return new Message(op, p);
            }
            if (op != OP_CONT) {
                if (acc != null) throw new IOException("new message before the last one ended");
                firstOp = op;
                acc = new ByteArrayOutputStream();
            } else if (acc == null) {
                throw new IOException("continuation without a start");
            }
            if ((long) acc.size() + p.length > MAX_PAYLOAD) throw new IOException("message too large");
            acc.write(p, 0, p.length);
            if (fin) return new Message(firstOp, acc.toByteArray());
        }
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new EOFException("connection closed");
        return b;
    }

    private static byte[] readFully(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int got = 0;
        while (got < n) {
            int r = in.read(buf, got, n - got);
            if (r < 0) throw new EOFException("connection closed");
            got += r;
        }
        return buf;
    }
}
