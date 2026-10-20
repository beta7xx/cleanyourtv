package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class PlexBackdropTest {
    private static List<PlexBackdrop.Item> parse(String xml) throws Exception {
        return PlexBackdrop.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void parsesMoviesSeasonsEpisodesAndSkipsItemsWithoutArt() throws Exception {
        List<PlexBackdrop.Item> items = parse("<MediaContainer size=\"4\">"
                + "<Video type=\"movie\" title=\"The Runner\" art=\"/library/metadata/1/art/9\" addedAt=\"100\"/>"
                + "<Directory type=\"season\" title=\"Season 2\" parentTitle=\"Mob Land\" art=\"/library/metadata/2/art/9\" addedAt=\"90\"/>"
                + "<Video type=\"episode\" title=\"Pilot\" grandparentTitle=\"Show\" parentTitle=\"Season 1\" art=\"/library/metadata/3/art/9\" addedAt=\"80\"/>"
                + "<Directory type=\"season\" title=\"No art\" parentTitle=\"X\"/>"
                + "</MediaContainer>");
        assertEquals(3, items.size());
        assertEquals("The Runner", items.get(0).title);
        assertEquals("Mob Land · Season 2", items.get(1).title);
        assertEquals("Show", items.get(2).title);
        assertEquals("Show", items.get(2).show);
        assertEquals(80, items.get(2).addedAt);
        assertEquals("/library/metadata/2/art/9", items.get(1).art);
    }

    @Test
    public void mergeSortsNewestFirstAndKeepsOneItemPerShow() throws Exception {
        List<PlexBackdrop.Item> all = new ArrayList<>();
        all.addAll(parse("<MediaContainer>"
                + "<Video type=\"episode\" title=\"E7\" grandparentTitle=\"Series A\" art=\"/a\" addedAt=\"50\"/>"
                + "<Video type=\"episode\" title=\"E8\" grandparentTitle=\"Series A\" art=\"/a\" addedAt=\"70\"/>"
                + "</MediaContainer>"));
        all.addAll(parse("<MediaContainer><Video type=\"movie\" title=\"Movie\" art=\"/m\" addedAt=\"60\"/></MediaContainer>"));
        List<PlexBackdrop.Item> out = PlexBackdrop.merge(all);
        assertEquals(2, out.size());
        assertEquals("Series A", out.get(0).show);
        assertEquals(70, out.get(0).addedAt);
        assertEquals("Movie", out.get(1).show);
    }
}
