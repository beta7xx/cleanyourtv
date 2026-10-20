package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class PlexContinueTest {
    @Test
    public void parsesEpisodesAndMoviesWithProgress() throws Exception {
        String xml = "<MediaContainer><Hub>"
                + "<Video type=\"episode\" key=\"/library/metadata/1\" title=\"Pilot\" grandparentTitle=\"Show\" parentIndex=\"1\" index=\"2\""
                + " thumb=\"/library/metadata/1/thumb/9\" viewOffset=\"300000\" duration=\"1200000\"/>"
                + "<Video type=\"movie\" key=\"/library/metadata/2\" title=\"Movie\" year=\"2024\" thumb=\"/library/metadata/2/thumb/9\" duration=\"1\"/>"
                + "<Video type=\"movie\" key=\"/library/metadata/3\" title=\"No thumb\"/>"
                + "</Hub></MediaContainer>";
        List<PlexContinue.Item> items = PlexContinue.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        assertEquals(2, items.size());
        assertEquals("Show", items.get(0).show);
        assertEquals("S1 E2 · Pilot", items.get(0).label);
        assertEquals(25, items.get(0).progress);
        assertEquals("Movie", items.get(1).show);
        assertEquals("2024", items.get(1).label);
        assertEquals(0, items.get(1).progress);
        assertEquals("2", items.get(1).ratingKey());
    }
}
