package com.gathin.tvhome;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class InputStatesTest {
    // TvInputManager.INPUT_STATE_CONNECTED and _DISCONNECTED
    private static final int ON = 0, OFF = 2;

    private static InputStates.Input port(String id, int state) {
        return new InputStates.Input(id, null, true, true, state);
    }

    private static InputStates.Input device(String id, String port, int state) {
        return new InputStates.Input(id, port, true, true, state);
    }

    private static final Map<String, Integer> NO_TABLE = Collections.emptyMap();

    @Test public void trustsTheTvWhenSomePortIsDisconnected() {
        InputStates s = new InputStates(Arrays.asList(port("HW1", ON), port("HW2", OFF)), () -> {
            throw new AssertionError("Sony's table is read only when the states are bogus");
        });
        assertEquals(ON, s.state("HW1"));
        assertEquals(OFF, s.state("HW2"));
        assertTrue(s.pluggedIn("HW1"));
        assertFalse(s.pluggedIn("HW2"));
    }

    @Test public void everyPortConnectedFallsBackToSonysTable() {
        Map<String, Integer> sony = new HashMap<>();
        sony.put("HW1", OFF);
        sony.put("HW2", ON);
        InputStates s = new InputStates(Arrays.asList(port("HW1", ON), port("HW2", ON), port("HW3", ON)), () -> sony);
        assertEquals(OFF, s.state("HW1"));
        assertEquals(ON, s.state("HW2"));
        assertEquals(InputStates.UNKNOWN, s.state("HW3"));   // not in the table: no dot rather than a wrong one
        assertFalse(s.pluggedIn("HW1"));
        assertTrue(s.pluggedIn("HW2"));
        assertFalse(s.pluggedIn("HW3"));
    }

    @Test public void oneConnectedPortIsNotBogus() {
        InputStates s = new InputStates(Collections.singletonList(port("HW1", ON)), () -> {
            throw new AssertionError("a single port proves nothing");
        });
        assertTrue(s.pluggedIn("HW1"));
    }

    @Test public void cecDeviceCountsAndTakesItsPortsPill() {
        InputStates s = new InputStates(Arrays.asList(port("HW1", ON), port("HW2", OFF), device("HW1,dev", "HW1", OFF)), () -> NO_TABLE);
        assertTrue(s.isCecDevice("HW1,dev"));
        assertFalse(s.isCecDevice("HW1"));
        assertTrue(s.pluggedIn("HW1,dev"));    // it answered over CEC, whatever its state says
        assertFalse(s.pluggedIn("HW1"));       // the device has the pill, not the bare port
        assertEquals(OFF, s.state("HW1,dev")); // states trusted here: the menu shows the device's own
    }

    @Test public void cecDeviceIsConnectedWhenTheStatesAreBogus() {
        InputStates s = new InputStates(Arrays.asList(port("HW1", ON), port("HW2", ON), device("HW1,dev", "HW1", OFF)), () -> NO_TABLE);
        assertEquals(ON, s.state("HW1,dev"));
    }

    @Test public void cecDevicesAndNonHdmiInputsDoNotDecideBogus() {
        InputStates.Input av = new InputStates.Input("AV", null, false, true, OFF);
        // Two HDMI ports, both connected, plus a disconnected CEC device and AV jack: still bogus.
        InputStates s = new InputStates(Arrays.asList(port("HW1", ON), port("HW2", ON), device("HW1,dev", "HW1", OFF), av),
                () -> NO_TABLE);
        assertEquals(InputStates.UNKNOWN, s.state("HW2"));
    }

    @Test public void deviceWhosePortIsNotListedIsNotCec() {
        InputStates s = new InputStates(Arrays.asList(port("HW1", OFF), device("dev", "HW9", ON)), () -> NO_TABLE);
        assertFalse(s.isCecDevice("dev"));
        assertTrue(s.pluggedIn("dev"));
        assertEquals(InputStates.UNKNOWN, s.state("missing"));
    }
}
