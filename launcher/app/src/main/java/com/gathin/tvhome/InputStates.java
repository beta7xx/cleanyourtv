package com.gathin.tvhome;

import android.media.tv.TvInputManager;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Whether something is plugged into each input, for the Inputs menu's dots and the pills under the gear. The TV's
 * own states, unless it reports every HDMI port as connected whether or not anything is plugged in (Sony): then
 * Sony's table, read only in that case. An HDMI-CEC device gets an input of its own whose parent is its port.
 * Plain values in, so the rules are tested on the JVM (InputStatesTest).
 */
final class InputStates {

    static final int UNKNOWN = -1;

    /** One TvInputInfo, as far as the states need it; state is TvInputManager.getInputState. */
    static final class Input {
        final String id, parentId;
        final boolean hdmi, passthrough;
        final int state;

        Input(String id, String parentId, boolean hdmi, boolean passthrough, int state) {
            this.id = id;
            this.parentId = parentId;
            this.hdmi = hdmi;
            this.passthrough = passthrough;
            this.state = state;
        }
    }

    private final Map<String, Input> byId = new HashMap<>();
    private final Set<String> portsWithDevice = new HashSet<>();
    private final boolean bogus;
    private final Map<String, Integer> sony;

    InputStates(List<Input> inputs, Supplier<Map<String, Integer>> sonyTable) {
        for (Input in : inputs) byId.put(in.id, in);
        for (Input in : inputs) if (in.passthrough && isCecDevice(in.id)) portsWithDevice.add(in.parentId);
        bogus = statesBogus(inputs);
        sony = bogus ? sonyTable.get() : null;
    }

    /** A device that answered over CEC, sitting on one of the listed ports. */
    boolean isCecDevice(String id) {
        Input in = byId.get(id);
        return in != null && in.parentId != null && byId.containsKey(in.parentId);
    }

    /**
     * TvInputManager.INPUT_STATE_*, or UNKNOWN. On a TV whose states mean nothing a CEC device still counts as
     * connected (it answered), and a bare port takes Sony's table.
     */
    int state(String id) {
        Input in = byId.get(id);
        if (in == null) return UNKNOWN;
        if (!bogus) return in.state;
        if (isCecDevice(id)) return TvInputManager.INPUT_STATE_CONNECTED;
        Integer s = sony.get(id);
        return s == null ? UNKNOWN : s;
    }

    /** Worth a pill: a CEC device, or a connected port with no CEC device on it (the device has its own pill). */
    boolean pluggedIn(String id) {
        if (isCecDevice(id)) return true;
        return !portsWithDevice.contains(id) && state(id) == TvInputManager.INPUT_STATE_CONNECTED;
    }

    /** Every bare HDMI port says connected: a TV with every port really in use is misread too; a CEC device still counts. */
    private boolean statesBogus(List<Input> inputs) {
        int ports = 0, connected = 0;
        for (Input in : inputs) {
            if (!in.passthrough || !in.hdmi || isCecDevice(in.id)) continue;
            ports++;
            if (in.state == TvInputManager.INPUT_STATE_CONNECTED) connected++;
        }
        return ports > 1 && connected == ports;
    }
}
