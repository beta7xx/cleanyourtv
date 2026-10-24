package com.gathin.tvhome;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class SystemStatsTest {
    private final Map<String, String> files = new HashMap<>();
    private long nowMs = 1000;
    private final SystemStats stats = new SystemStats(files::get, () -> nowMs);

    private void procStat(long user, long idle) {
        files.put("/proc/stat", "cpu  " + user + " 0 0 " + idle + " 0 0 0 0 0 0\ncpu0 1 2 3 4\n");
    }

    @Test public void cpuFromProcStatNeedsTwoSamples() {
        procStat(100, 900);
        assertEquals(-1, stats.cpuPercent());
        procStat(130, 970);   // 30 busy of 100
        assertEquals(30, stats.cpuPercent());
        procStat(130, 970);   // no time passed: no figure rather than 0 or a division by zero
        assertEquals(-1, stats.cpuPercent());
    }

    @Test public void hiddenProcStatFallsBackToCpuidle() {
        // Android 9 on many TVs: /proc/stat cannot be read, the cores' idle times (microseconds) can.
        files.put("/sys/devices/system/cpu/cpu0/cpuidle/state0/time", "1000000\n");
        files.put("/sys/devices/system/cpu/cpu1/cpuidle/state0/time", "1000000\n");
        assertEquals(-1, stats.cpuPercent());
        nowMs += 1000;   // 2 cores x 1 s = 2,000,000 us of wall time, 500,000 of it idle
        files.put("/sys/devices/system/cpu/cpu0/cpuidle/state0/time", "1250000\n");
        files.put("/sys/devices/system/cpu/cpu1/cpuidle/state0/time", "1250000\n");
        assertEquals(75, stats.cpuPercent());
    }

    @Test public void onceProcStatFailsItIsNotReadAgain() {
        files.put("/proc/stat", "garbage");
        files.put("/sys/devices/system/cpu/cpu0/cpuidle/state0/time", "0");
        assertEquals(-1, stats.cpuPercent());
        procStat(100, 900);   // readable now, but the fallback keeps its baseline
        nowMs += 1000;
        files.put("/sys/devices/system/cpu/cpu0/cpuidle/state0/time", "500000");
        assertEquals(50, stats.cpuPercent());
    }

    @Test public void noSourceAtAllGivesNoFigure() {
        assertEquals(-1, stats.cpuPercent());
        assertEquals(-1, stats.cpuPercent());
    }

    @Test public void memoryIsUsedOfTotalInMb() {
        files.put("/proc/meminfo", "MemTotal:        1572864 kB\nMemFree:          102400 kB\nMemAvailable:     604160 kB\n");
        assertArrayEquals(new long[]{946, 1536}, stats.memUsedOfTotalMb());
    }

    @Test public void memoryWithoutAvailableOrUnreadableIsNull() {
        assertNull(stats.memUsedOfTotalMb());
        files.put("/proc/meminfo", "MemTotal:        1572864 kB\nMemFree:          102400 kB\n");   // kernels before 3.14
        assertNull(stats.memUsedOfTotalMb());
    }
}
