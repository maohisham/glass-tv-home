package com.gathin.tvhome;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.*;

public class WsFramesTest {

    /** A frame as a server sends it: not masked. */
    private static byte[] serverFrame(int opcode, boolean fin, byte[] payload) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write((fin ? 0x80 : 0) | opcode);
        int n = payload.length;
        if (n < 126) {
            o.write(n);
        } else if (n < 65536) {
            o.write(126);
            o.write(n >> 8);
            o.write(n & 0xFF);
        } else {
            o.write(127);
            for (int i = 7; i >= 0; i--) o.write((int) (((long) n >> (8 * i)) & 0xFF));
        }
        o.write(payload, 0, n);
        return o.toByteArray();
    }

    private static WsFrames.Message read(byte[] bytes) throws IOException {
        return WsFrames.read(new ByteArrayInputStream(bytes));
    }

    private static byte[] payloadOf(int n) {
        byte[] p = new byte[n];
        for (int i = 0; i < n; i++) p[i] = (byte) ('a' + i % 26);
        return p;
    }

    @Test
    public void acceptKeyMatchesRfcExample() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WsFrames.acceptKey("dGhlIHNhbXBsZSBub25jZQ=="));
    }

    @Test
    public void encodeTextIsMaskedWithFinSet() {
        byte[] key = {1, 2, 3, 4};
        byte[] f = WsFrames.encode(WsFrames.OP_TEXT, "hi".getBytes(StandardCharsets.UTF_8), key);
        assertEquals(8, f.length);
        assertEquals((byte) 0x81, f[0]);
        assertEquals((byte) 0x82, f[1]);
        assertArrayEquals(key, Arrays.copyOfRange(f, 2, 6));
        assertEquals((byte) ('h' ^ 1), f[6]);
        assertEquals((byte) ('i' ^ 2), f[7]);
    }

    @Test
    public void encodeUsesExtendedLengths() {
        byte[] key = {9, 9, 9, 9};
        byte[] mid = WsFrames.encode(WsFrames.OP_TEXT, payloadOf(126), key);
        assertEquals((byte) (0x80 | 126), mid[1]);
        assertEquals(0, mid[2]);
        assertEquals(126, mid[3]);
        byte[] big = WsFrames.encode(WsFrames.OP_TEXT, payloadOf(65_536), key);
        assertEquals((byte) (0x80 | 127), big[1]);
        assertEquals(0x01, big[7]);
        assertEquals(0x00, big[8]);
        assertEquals(0x00, big[9]);
    }

    @Test
    public void readsServerFramesOfEveryLengthClass() throws IOException {
        for (int n : new int[]{0, 5, 125, 126, 65_535, 65_536}) {
            WsFrames.Message m = read(serverFrame(WsFrames.OP_TEXT, true, payloadOf(n)));
            assertEquals(WsFrames.OP_TEXT, m.opcode);
            assertArrayEquals("length " + n, payloadOf(n), m.payload);
        }
    }

    @Test
    public void readsMaskedFramesToo() throws IOException {
        byte[] sent = WsFrames.encode(WsFrames.OP_TEXT, payloadOf(300), new byte[]{5, 6, 7, 8});
        assertArrayEquals(payloadOf(300), read(sent).payload);
    }

    @Test
    public void reassemblesFragmentedMessage() throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(serverFrame(WsFrames.OP_TEXT, false, "hel".getBytes(StandardCharsets.UTF_8)));
        o.write(serverFrame(0, true, "lo".getBytes(StandardCharsets.UTF_8)));
        WsFrames.Message m = read(o.toByteArray());
        assertEquals(WsFrames.OP_TEXT, m.opcode);
        assertEquals("hello", new String(m.payload, StandardCharsets.UTF_8));
    }

    @Test
    public void pingIsReturnedAsPing() throws IOException {
        WsFrames.Message m = read(serverFrame(WsFrames.OP_PING, true, new byte[]{1, 2}));
        assertEquals(WsFrames.OP_PING, m.opcode);
        assertArrayEquals(new byte[]{1, 2}, m.payload);
    }

    @Test(expected = IOException.class)
    public void hugeLengthIsRefused() throws IOException {
        byte[] header = {(byte) 0x81, 127, 0, 0, 0, 0, 0x77, 0x35, (byte) 0x94, 0x00};
        read(header);
    }

    @Test(expected = IOException.class)
    public void lengthWithTopBitSetIsRefused() throws IOException {
        byte[] header = {(byte) 0x81, 127, (byte) 0x80, 0, 0, 0, 0, 0, 0, 1};
        read(header);
    }

    @Test(expected = IOException.class)
    public void fragmentsAddingUpPastTheLimitAreRefused() throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(serverFrame(WsFrames.OP_TEXT, false, payloadOf(700_000)));
        o.write(serverFrame(0, true, payloadOf(700_000)));
        read(o.toByteArray());
    }

    @Test(expected = IOException.class)
    public void truncatedStreamIsAnError() throws IOException {
        read(new byte[]{(byte) 0x81});
    }

    @Test(expected = IOException.class)
    public void truncatedPayloadIsAnError() throws IOException {
        byte[] f = serverFrame(WsFrames.OP_TEXT, true, payloadOf(10));
        read(Arrays.copyOf(f, f.length - 3));
    }
}
