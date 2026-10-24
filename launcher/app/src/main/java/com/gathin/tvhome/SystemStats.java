package com.gathin.tvhome;

import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * CPU and memory for the show_stats line, from /proc and /sys. CPU is a busy percent between two samples, so a new
 * instance needs two calls before it has a figure. Android 8 and later hide /proc/stat from apps on many TVs: then
 * it falls back to the cores' cpuidle times for good. Not thread-safe; the stats thread owns one.
 */
final class SystemStats {

    // -1: need another sample. -2: this source cannot be read (Android 9 hides /proc/stat from apps).
    private static final int CPU_UNREADABLE = -2;

    private final Function<String, String> read;
    private final LongSupplier elapsedMs;
    private long cpuTotalPrev = -1, cpuIdlePrev = -1;
    private boolean procStatDead;
    private long idlePrev = -1, wallPrev = -1;

    SystemStats() {
        this(SystemStats::readFile, SystemClock::elapsedRealtime);
    }

    /** read: a file's text, or null when it cannot be read. elapsedMs: a monotonic clock. */
    SystemStats(Function<String, String> read, LongSupplier elapsedMs) {
        this.read = read;
        this.elapsedMs = elapsedMs;
    }

    /** Busy percent 0-100 since the last call, or -1 when there is no figure (yet, or at all on this TV). */
    int cpuPercent() {
        if (!procStatDead) {
            int fromStat = cpuFromProcStat();
            if (fromStat != CPU_UNREADABLE) return fromStat;
            procStatDead = true;
            idlePrev = -1;
        }
        return cpuFromIdle();
    }

    /** RAM usage as {used, total} in MB, used = MemTotal - MemAvailable, e.g. "945 / 1536 MB". Used over
     *  free so the line reads as a load against a known whole; null when /proc/meminfo cannot be read. */
    long[] memUsedOfTotalMb() {
        String text = read.apply("/proc/meminfo");
        if (text == null) return null;
        long available = -1, total = -1;
        try {
            for (String line : text.split("\n")) {
                String[] t;
                if (line.startsWith("MemAvailable:")) {
                    t = line.split("\\s+");
                    if (t.length >= 2) available = Long.parseLong(t[1]) / 1024;
                } else if (line.startsWith("MemTotal:")) {
                    t = line.split("\\s+");
                    if (t.length >= 2) total = Long.parseLong(t[1]) / 1024;
                }
            }
        } catch (NumberFormatException ignored) {
        }
        if (available < 0 || total <= 0) return null;
        return new long[]{Math.max(0, total - available), total};
    }

    private int cpuFromProcStat() {
        String s = firstLine("/proc/stat");
        if (s == null || !s.startsWith("cpu")) return CPU_UNREADABLE;
        String[] t = s.trim().split("\\s+");
        if (t.length < 5) return CPU_UNREADABLE;
        try {
            long user = Long.parseLong(t[1]), nice = Long.parseLong(t[2]), sys = Long.parseLong(t[3]);
            long idle = Long.parseLong(t[4]);
            long iowait = t.length > 5 ? Long.parseLong(t[5]) : 0;
            long irq = t.length > 6 ? Long.parseLong(t[6]) : 0;
            long soft = t.length > 7 ? Long.parseLong(t[7]) : 0;
            long total = user + nice + sys + idle + iowait + irq + soft;
            long idleAll = idle + iowait;
            if (cpuTotalPrev < 0) {
                cpuTotalPrev = total;
                cpuIdlePrev = idleAll;
                return -1;
            }
            long dt = total - cpuTotalPrev, di = idleAll - cpuIdlePrev;
            cpuTotalPrev = total;
            cpuIdlePrev = idleAll;
            if (dt <= 0) return -1;
            int busy = (int) Math.round((dt - di) * 100.0 / dt);
            return Math.max(0, Math.min(100, busy));
        } catch (NumberFormatException e) {
            return CPU_UNREADABLE;
        }
    }

    /** Busy percent from cpuidle time (microseconds) across the cores. -1 until the second sample. */
    private int cpuFromIdle() {
        long idle = 0;
        int cores = 0;
        for (int i = 0; i < 8; i++) {
            boolean any = false;
            for (int state = 0; state < 8; state++) {
                String t = firstLine("/sys/devices/system/cpu/cpu" + i + "/cpuidle/state" + state + "/time");
                if (t == null) break;
                try {
                    idle += Long.parseLong(t.trim());
                    any = true;
                } catch (NumberFormatException e) {
                    break;
                }
            }
            if (!any) break;
            cores++;
        }
        if (cores == 0) return -1;
        long wall = elapsedMs.getAsLong() * 1000L;
        if (idlePrev < 0) {
            idlePrev = idle;
            wallPrev = wall;
            return -1;
        }
        long di = idle - idlePrev;
        long dw = (wall - wallPrev) * cores;
        idlePrev = idle;
        wallPrev = wall;
        if (dw <= 0) return -1;
        int busy = (int) Math.round((dw - di) * 100.0 / dw);
        return Math.max(0, Math.min(100, busy));
    }

    private String firstLine(String path) {
        String text = read.apply(path);
        if (text == null) return null;
        int end = text.indexOf('\n');
        return end < 0 ? text : text.substring(0, end);
    }

    private static String readFile(String path) {
        StringBuilder text = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = r.readLine()) != null) text.append(line).append('\n');
            return text.toString();
        } catch (IOException e) {
            return null;
        }
    }
}
