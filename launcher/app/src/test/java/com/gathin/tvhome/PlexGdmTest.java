package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class PlexGdmTest {
    private static final String SERVER = "HTTP/1.0 200 OK\r\nContent-Type: plex/media-server\r\nResource-Identifier: abc\r\n"
            + "Name: nas\r\nPort: 32400\r\nUpdated-At: 1\r\nVersion: 1.40\r\n\r\n";

    @Test public void aServerReplyGivesItsUrl() {
        assertEquals("http://192.168.1.50:32400", PlexGdm.url(SERVER, "192.168.1.50"));
    }

    @Test public void aPlayerReplyIsNotAServer() {
        assertNull(PlexGdm.url(SERVER.replace("plex/media-server", "plex/media-player"), "192.168.1.50"));
    }

    @Test public void onlyThePinnedServerIsAcceptedOnceOneIsPinned() {
        assertTrue(PlexGdm.accepts(null, "http://192.168.1.50:32400"));
        assertTrue(PlexGdm.accepts("http://192.168.1.50:32400", "http://192.168.1.50:32400"));
        assertFalse(PlexGdm.accepts("http://192.168.1.50:32400", "http://192.168.1.66:32400"));
        assertFalse(PlexGdm.accepts("http://192.168.1.50:32400", "http://192.168.1.50:32401"));
    }

    @Test public void aReplyWithoutAUsablePortIsIgnored() {
        assertNull(PlexGdm.url(SERVER.replace("Port: 32400", "Port: x"), "192.168.1.50"));
        assertNull(PlexGdm.url("HTTP/1.0 200 OK\r\nContent-Type: plex/media-server\r\n\r\n", "192.168.1.50"));
    }
}
