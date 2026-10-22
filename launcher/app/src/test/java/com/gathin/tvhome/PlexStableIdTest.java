package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public class PlexStableIdTest {
    @Test public void numericRatingKeyIsTheId() {
        assertEquals(137585L, PlexContinue.stableId("/library/metadata/137585"));
        assertNotEquals(PlexContinue.stableId("/library/metadata/1"), PlexContinue.stableId("/library/metadata/2"));
    }

    @Test public void oddKeyFallsBackToItsHash() {
        assertEquals("/library/metadata/abc".hashCode(), PlexContinue.stableId("/library/metadata/abc"));
    }
}
