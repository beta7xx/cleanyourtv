package com.gathin.tvhome;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Finds a Plex Media Server on the LAN the way the Plex apps do (GDM): one M-SEARCH to UDP 32414, broadcast and
 * the GDM multicast group, and the server answers with its port. Used when neither config.xml nor
 * Settings.Global names a server, so no LAN address has to be written anywhere.
 * The first server found is pinned (PlexShelf): the token goes only to that address afterwards, so another LAN host
 * answering faster cannot collect it. Not the reply's Resource-Identifier: any host can copy that, but no other host
 * can hold the server's address while it is up.
 * shortcut: the first answer is trusted once, at the first discovery; a LAN with several servers, or a server whose
 * address changes (no DHCP reservation), should set plex_url instead.
 */
final class PlexGdm {
    static final int PORT = 32414;

    /**
     * "http://<host>:<port>" of the first server to answer within timeoutMs that the pin accepts, or null. Blocks:
     * not on the main thread.
     */
    static String find(int timeoutMs, String pin) {
        byte[] ask = "M-SEARCH * HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        try (DatagramSocket s = new DatagramSocket()) {
            s.setBroadcast(true);
            s.setSoTimeout(timeoutMs);
            s.send(new DatagramPacket(ask, ask.length, InetAddress.getByName("255.255.255.255"), PORT));
            s.send(new DatagramPacket(ask, ask.length, InetAddress.getByName("239.0.0.250"), PORT));
            byte[] buf = new byte[2048];
            long end = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < end) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                s.receive(p);
                String url = url(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8), p.getAddress().getHostAddress());
                if (url != null && accepts(pin, url)) return url;
            }
        } catch (SocketTimeoutException ignored) {
        } catch (Exception ignored) { }   // no network yet, or a firmware that blocks broadcast
        return null;
    }

    /** No pin yet (first discovery) takes any server; afterwards only the pinned address. */
    static boolean accepts(String pin, String url) {
        return pin == null || pin.equals(url);
    }

    /** The server URL from one GDM reply sent from host; null for anything that is not a media server's reply. */
    static String url(String reply, String host) {
        String type = null, port = null;
        for (String line : reply.split("\r?\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT), value = line.substring(colon + 1).trim();
            if (name.equals("content-type")) type = value;
            else if (name.equals("port")) port = value;
        }
        if (!"plex/media-server".equalsIgnoreCase(type) || port == null || !port.matches("[0-9]{1,5}")) return null;
        return "http://" + host + ":" + port;
    }
}
