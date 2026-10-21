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

    @Test
    public void parsesPosterArtMetaAndCast() throws Exception {
        List<PlexContinue.Item> items = parse(
                "<Video type=\"episode\" key=\"/library/metadata/137830\" title=\"ตอนที่ 2\" grandparentTitle=\"ปลดหน้ากาก\""
                + " parentIndex=\"1\" index=\"2\" originallyAvailableAt=\"2026-10-16\" duration=\"3010240\" contentRating=\"TV-MA\""
                + " summary=\"A-Chou agrees to marry Lu Zhuo.\" thumb=\"/library/metadata/137830/thumb/1\""
                + " grandparentThumb=\"/library/metadata/137827/thumb/2\" art=\"/library/metadata/137827/art/3\" viewOffset=\"2248000\">"
                + "<Role tag=\"Tian Xiwei\"/><Role tag=\"Yan An\"/><Role tag=\"Pan Junya\"/><Role tag=\"Fourth Person\"/>"
                + "</Video>");
        assertEquals(1, items.size());
        PlexContinue.Item it = items.get(0);
        assertEquals("ปลดหน้ากาก", it.show);
        assertEquals("ตอนที่ 2", it.title);
        assertEquals("/library/metadata/137827/thumb/2", it.poster);
        assertEquals("/library/metadata/137827/art/3", it.art);
        assertEquals("E2 · 2026-10-16 · 50m · TV-MA", it.meta);
        assertEquals("A-Chou agrees to marry Lu Zhuo.", it.summary);
        assertEquals("Tian Xiwei, Yan An, Pan Junya", it.cast);
        assertEquals(74, it.progress);
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
        assertEquals("", items.get(0).cast);
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
    public void needsResendOnlyWhenNoSessionFromThisTv() {
        List<PlexContinue.Session> sessions = Arrays.asList(
                new PlexContinue.Session("1", "/library/metadata/1", "client-a", "0", "0", "playing", "192.168.1.9"),
                new PlexContinue.Session("2", "/library/metadata/2", "client-b", "0", "0", "buffering", "192.168.1.7"));
        Set<String> mine = Collections.singleton("192.168.1.7");
        assertTrue(PlexContinue.needsResend(sessions, mine, "1"));
        assertFalse(PlexContinue.needsResend(sessions, mine, "2"));
        assertTrue(PlexContinue.needsResend(Collections.emptyList(), mine, "2"));
    }
}
