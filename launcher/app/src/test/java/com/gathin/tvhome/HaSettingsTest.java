package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class HaSettingsTest {

    @Test
    public void plainTokenIsKept() {
        assertEquals("abc.def", HaSettings.parseTokenFile("abc.def"));
    }

    @Test
    public void trailingNewlineAndCrlfAreRemoved() {
        assertEquals("abc.def", HaSettings.parseTokenFile("abc.def\n"));
        assertEquals("abc.def", HaSettings.parseTokenFile("abc.def\r\n"));
    }

    @Test
    public void byteOrderMarkIsRemoved() {
        assertEquals("abc.def", HaSettings.parseTokenFile("﻿abc.def"));
    }

    @Test
    public void surroundingQuotesAreRemoved() {
        assertEquals("abc.def", HaSettings.parseTokenFile("\"abc.def\""));
        assertEquals("abc.def", HaSettings.parseTokenFile("'abc.def'\n"));
    }

    @Test
    public void innerWhitespaceIsRefused() {
        assertNull(HaSettings.parseTokenFile("abc def"));
        assertNull(HaSettings.parseTokenFile("abc\ndef"));
    }

    @Test
    public void emptyAndBlankAreRefused() {
        assertNull(HaSettings.parseTokenFile(""));
        assertNull(HaSettings.parseTokenFile("   \n"));
        assertNull(HaSettings.parseTokenFile(null));
    }

    @Test
    public void overlongTokenIsRefused() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 2049; i++) b.append('a');
        assertNull(HaSettings.parseTokenFile(b.toString()));
        assertNotNull(HaSettings.parseTokenFile(b.substring(0, 2048)));
    }
}
