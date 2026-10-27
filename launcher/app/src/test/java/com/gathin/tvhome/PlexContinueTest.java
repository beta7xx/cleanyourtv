package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class PlexContinueTest {
    private static List<PlexContinue.Item> parse(String xml) throws Exception {
        return PlexContinue.parse(new ByteArrayInputStream(("<MediaContainer><Hub>" + xml + "</Hub></MediaContainer>").getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * A DOCTYPE from the server (or whoever answered discovery) must not make the TV fetch anything: every external
     * entity resolves to nothing. The target does not exist, so a parser that tried to fetch it would throw.
     */
    @Test
    public void externalEntitiesAreNeverFetched() throws Exception {
        String missing = new java.io.File(System.getProperty("java.io.tmpdir"), "plex-xxe-" + System.nanoTime()).toURI().toString();
        String video = "<Video key=\"/library/metadata/1\" title=\"A\" thumb=\"/t\"/>";
        String[] bodies = {
                "<!DOCTYPE m [<!ENTITY x SYSTEM \"" + missing + "\">]><MediaContainer><Hub>" + video + "&x;</Hub></MediaContainer>",
                "<!DOCTYPE m [<!ENTITY % p SYSTEM \"" + missing + "\"> %p;]><MediaContainer><Hub>" + video + "</Hub></MediaContainer>",
                "<!DOCTYPE m SYSTEM \"" + missing + "\"><MediaContainer><Hub>" + video + "</Hub></MediaContainer>",
        };
        for (String body : bodies) {
            List<PlexContinue.Item> items = PlexContinue.parse(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
            assertEquals(body, 1, items.size());
        }
    }

    @Test
    public void parsesPosterArtAndMeta() throws Exception {
        List<PlexContinue.Item> items = parse(
                "<Video type=\"episode\" key=\"/library/metadata/137830\" title=\"ตอนที่ 2\" grandparentTitle=\"ปลดหน้ากาก\""
                + " parentIndex=\"1\" index=\"2\" originallyAvailableAt=\"2026-10-16\" duration=\"3010240\" contentRating=\"TV-MA\""
                + " thumb=\"/library/metadata/137830/thumb/1\""
                + " grandparentThumb=\"/library/metadata/137827/thumb/2\" art=\"/library/metadata/137827/art/3\" viewOffset=\"2248000\">"
                + "</Video>");
        assertEquals(1, items.size());
        PlexContinue.Item it = items.get(0);
        assertEquals("ปลดหน้ากาก", it.show);
        assertEquals("ตอนที่ 2", it.title);
        assertEquals("/library/metadata/137827/thumb/2", it.poster);
        assertEquals("/library/metadata/137827/art/3", it.art);
        assertEquals("E2 · " + PlexContinue.displayDate("2026-10-16") + " · 50m · TV-MA", it.meta);
        assertEquals("S1 \u00B7 E2", it.episode);
        assertFalse(PlexContinue.displayDate("2026-10-16").isEmpty());
        assertEquals("not-a-date", PlexContinue.displayDate("not-a-date"));
        assertEquals(74, it.progress);
        assertEquals(2248000, it.offsetMs);   // sent to Plex as &offset=2248 (seconds), so it resumes there
        assertEquals("137830", it.ratingKey());
    }

    @Test
    public void movieWithoutGrandparentUsesThumbAndYear() throws Exception {
        List<PlexContinue.Item> items = parse(
                "<Video type=\"movie\" key=\"/library/metadata/2\" title=\"Movie\" year=\"2024\" duration=\"5400000\" thumb=\"/library/metadata/2/thumb/9\"/>");
        assertEquals(1, items.size());
        assertEquals("Movie", items.get(0).show);
        assertEquals("/library/metadata/2/thumb/9", items.get(0).poster);
        assertEquals("2024 · 90m", items.get(0).meta);
    }

    @Test
    public void skipsItemsWithoutAnyPoster() throws Exception {
        List<PlexContinue.Item> items = parse(
                "<Video type=\"movie\" key=\"/library/metadata/3\" title=\"No poster\"/>"
                + "<Video type=\"movie\" key=\"/library/metadata/4\" title=\"Has one\" thumb=\"/t\"/>");
        assertEquals(1, items.size());
        assertEquals("Has one", items.get(0).show);
    }

    @Test
    public void needsResendOnlyWhenThisTvHasNoVideoSessionAtAll() {
        Set<String> mine = Collections.singleton("192.168.1.7");
        List<PlexContinue.Session> others = Collections.singletonList(
                new PlexContinue.Session("1", "/library/metadata/1", "client-a", "0", "0", "playing", "192.168.1.9"));
        List<PlexContinue.Session> mineToo = Arrays.asList(others.get(0),
                new PlexContinue.Session("2", "/library/metadata/2", "client-b", "0", "0", "buffering", "192.168.1.7"));
        assertTrue(PlexContinue.needsResend(Collections.emptyList(), mine));
        assertTrue(PlexContinue.needsResend(others, mine));
        // Any video from this TV, even another title the user picked inside Plex meanwhile: never yank them back.
        assertFalse(PlexContinue.needsResend(mineToo, mine));
    }

    @Test
    public void sessionParserDoesNotCarryAVideoOntoALaterTrack() throws Exception {
        String xml = "<MediaContainer>"
                + "<Video ratingKey=\"1\" key=\"/library/metadata/1\" viewOffset=\"5\" duration=\"10\">"
                + "<Player machineIdentifier=\"client-a\" address=\"192.168.1.9\" state=\"playing\"/></Video>"
                + "<Track ratingKey=\"7\" key=\"/library/metadata/7\">"
                + "<Player machineIdentifier=\"client-b\" address=\"192.168.1.7\" state=\"playing\"/></Track>"
                + "</MediaContainer>";
        List<PlexContinue.Session> out = PlexContinue.parseSessions(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, out.size());
        assertEquals("1", out.get(0).ratingKey);
        assertEquals("192.168.1.9", out.get(0).address);
    }

    @Test
    public void sameListSkipsTheRebind() throws Exception {
        List<PlexContinue.Item> a = parse("<Video key=\"/library/metadata/1\" title=\"A\" thumb=\"/t\" viewOffset=\"50\" duration=\"100\"/>");
        List<PlexContinue.Item> b = parse("<Video key=\"/library/metadata/1\" title=\"A\" thumb=\"/t\" viewOffset=\"50\" duration=\"100\"/>");
        List<PlexContinue.Item> c = parse("<Video key=\"/library/metadata/1\" title=\"A\" thumb=\"/t\" viewOffset=\"60\" duration=\"100\"/>");
        List<PlexContinue.Item> d = parse("<Video key=\"/library/metadata/1\" title=\"A\" thumb=\"/t2\" viewOffset=\"50\" duration=\"100\"/>");
        assertTrue(PlexContinue.same(a, b));
        assertFalse(PlexContinue.same(a, c));
        assertFalse(PlexContinue.same(a, d));   // a new poster under the same key is a change to show
        assertFalse(PlexContinue.same(a, Collections.emptyList()));
    }

    @Test
    public void keepsEveryItemTheHubReturns() throws Exception {
        StringBuilder xml = new StringBuilder();
        for (int i = 0; i < 25; i++) xml.append("<Video key=\"/library/metadata/").append(i).append("\" title=\"T").append(i).append("\" thumb=\"/t").append(i).append("\"/>");
        assertEquals(25, parse(xml.toString()).size());
    }

    @Test
    public void episodeLineIsSeasonAndEpisodeLikePlex() throws Exception {
        List<PlexContinue.Item> items = parse(
                "<Video key=\"/library/metadata/1\" title=\"Episode 44\" grandparentTitle=\"Show\" parentIndex=\"1\" index=\"44\" grandparentThumb=\"/s\"/>"
                + "<Video key=\"/library/metadata/3\" title=\"Movie\" thumb=\"/m\"/>");
        assertEquals("S1 \u00B7 E44", items.get(0).episode);
        assertEquals("", items.get(1).episode);
    }
}
