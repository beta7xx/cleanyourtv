package com.gathin.tvhome;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/** Config.ownerWritten: on Android 8-10 a config.json counts only with the hash theme.sh recorded. */
public class ThemeHashTest {
    private static final byte[] CONFIG = "{\"columns\": 4}\n".getBytes(StandardCharsets.UTF_8);
    // sha256 of CONFIG, as theme.sh's hashlib and theme.ps1's Get-FileHash print it
    private static final String SHA = "7f8a6c6a1b7dbe43ae8f6ff8d9bbbc0ac8e1a76de6d49dd1a1f2d4e3c4e0a6f1";

    private static String sha(byte[] b) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte x : java.security.MessageDigest.getInstance("SHA-256").digest(b)) hex.append(String.format("%02x", x));
        return hex.toString();
    }

    @Test public void theRecordedHashIsAccepted() throws Exception {
        assertTrue(Config.ownerWritten(CONFIG, sha(CONFIG)));
        assertTrue(Config.ownerWritten(CONFIG, sha(CONFIG).toUpperCase() + "\n"));   // case and a trailing newline
    }

    @Test public void anotherFileOrNoHashIsRefused() throws Exception {
        byte[] planted = "{\"app_keys\": [\"KEYCODE_DPAD_CENTER:evil.app\"]}".getBytes(StandardCharsets.UTF_8);
        assertFalse(Config.ownerWritten(planted, sha(CONFIG)));
        assertFalse(Config.ownerWritten(CONFIG, null));
        assertFalse(Config.ownerWritten(CONFIG, ""));
        assertFalse(Config.ownerWritten(CONFIG, SHA));   // a hash that is not this file's
    }
}
