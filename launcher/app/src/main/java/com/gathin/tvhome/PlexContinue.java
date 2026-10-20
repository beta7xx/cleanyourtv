package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.xml.parsers.SAXParserFactory;

/**
 * Plex "Continue Watching" as a row between the top bar and the dock (config.xml plex_url, token in
 * Settings.Global plex_token). Fetched on every Home resume (one small XML request), thumbs 512x288 through
 * Plex's transcoder, at most {@link #COUNT} of them cached. OK opens the item in the Plex app.
 * Up from the dock lands here when the row has items; Up from the row goes to the top bar.
 */
final class PlexContinue {
    static final int COUNT = 10;
    private static final int TW = 512, TH = 288;

    interface Host {
        void onRowFocused();
        void onRowUp();
        void onRowDown();
    }

    static final class Item {
        final String key, show, label, thumb;
        final int progress;   // 0..100, viewOffset / duration
        Item(String key, String show, String label, String thumb, int progress) {
            this.key = key; this.show = show; this.label = label; this.thumb = thumb; this.progress = progress;
        }
        /** "/library/metadata/137585" -> "137585", as the sessions list names it. */
        String ratingKey() { return key.substring(key.lastIndexOf('/') + 1); }
    }

    private final Context ctx;
    private final Host host;
    private final String base, token;
    private final float density;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> thumbs = new LruCache<>(COUNT);
    private final LinearLayout row;
    private final RecyclerView list;
    private final Adapter adapter = new Adapter();
    private String machine;   // Plex server machineIdentifier, for the plex:// link
    private List<Item> items = new ArrayList<>();
    private int lastFocused;
    private boolean running;

    PlexContinue(Context ctx, Host host, String base, String token, float density) {
        this.ctx = ctx;
        this.host = host;
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.token = token;
        this.density = density;
        row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setVisibility(View.GONE);
        TextView title = new TextView(ctx);
        title.setText(R.string.plex_continue);
        title.setTextColor(0xCCFFFFFF);
        title.setTextSize(18);
        title.setTypeface(ctx.getResources().getFont(R.font.outfit));
        title.setPadding(dp(12), 0, 0, dp(4));
        row.addView(title);
        list = new RecyclerView(ctx);
        list.setLayoutManager(new LinearLayoutManager(ctx, RecyclerView.HORIZONTAL, false));
        list.setAdapter(adapter);
        list.setClipToPadding(false);
        list.setClipChildren(false);
        list.setPadding(dp(12), dp(6), dp(12), dp(6));
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        row.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    View view() { return row; }

    private int dp(int v) { return (int) (v * density); }

    private boolean shown = true;

    /** Hidden (and unfocusable) while focus is down in the grid, where the dock moves up over this area. */
    void setShown(boolean s) {
        if (shown == s) return;
        shown = s;
        row.animate().cancel();
        if (s) {
            if (!items.isEmpty()) row.setVisibility(View.VISIBLE);
            row.animate().alpha(1f).setDuration(160).start();
        } else {
            row.animate().alpha(0f).setDuration(160).withEndAction(() -> { if (!shown) row.setVisibility(View.INVISIBLE); }).start();
        }
    }

    /** Focus the row's last item; false when the row is empty or hidden, so the caller falls back to the usual target. */
    boolean focus() {
        if (items.isEmpty() || !shown) return false;
        int pos = Math.min(lastFocused, items.size() - 1);
        RecyclerView.ViewHolder vh = list.findViewHolderForAdapterPosition(pos);
        if (vh != null) { vh.itemView.requestFocus(); return true; }
        list.scrollToPosition(pos);
        list.post(() -> {
            RecyclerView.ViewHolder h = list.findViewHolderForAdapterPosition(pos);
            if (h != null) h.itemView.requestFocus();
        });
        return true;
    }

    static final long REFRESH_MS = 20_000;
    private final Runnable refresh = this::refresh;
    private volatile boolean abortStop;

    void start() {
        running = true;
        // Home is back in front: never re-send a play link now.
        if (pendingVerify != null) { main.removeCallbacks(pendingVerify); pendingVerify = null; }
        abortStop = false;
        new Thread(() -> {
            if (machine == null) machine = fetchMachine();
            loadRow();   // what the server knows right now, before the stop below settles
            // Whatever this TV was streaming is stopped: its player paused, then the server session ended,
            // so nothing lingers in the server's session list while Home is on screen. open() sets abortStop
            // when a new video is started before this finishes, so the two never fight over the player.
            List<Session> live = sessions();
            if (!live.isEmpty() && !abortStop) {
                main.post(this::stopPlayback);
                // Wait for Plex's own "paused" report (its exact position) rather than the last timeline ping.
                for (int i = 0; i < 8 && !abortStop; i++) {
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) { }
                    List<Session> now = sessions();
                    if (now.isEmpty()) { live = now; break; }
                    live = now;
                    boolean allPaused = true;
                    for (Session s : now) allPaused &= "paused".equals(s.state);
                    if (allPaused) break;
                }
                if (!abortStop) {
                    for (Session s : live) endSession(s);
                    loadRow();   // the item just stopped now carries its resume position
                }
                main.post(this::releaseFocus);
            }
        }, "plex-continue").start();
        main.removeCallbacks(refresh);
        main.postDelayed(refresh, REFRESH_MS);
    }

    void stop() {
        running = false;
        main.removeCallbacks(refresh);
    }

    /** One small XML request every REFRESH_MS while Home is on screen keeps the row in step with Plex. */
    private void refresh() {
        if (!running) return;
        new Thread(this::loadRow, "plex-refresh").start();
        main.postDelayed(refresh, REFRESH_MS);
    }

    private void loadRow() {
        List<Item> fresh = get("/hubs/continueWatching");
        if (fresh.isEmpty()) fresh = get("/library/onDeck");
        final List<Item> result = fresh;
        main.post(() -> {
            if (!running) return;
            items = result;
            adapter.notifyDataSetChanged();
            row.setVisibility(items.isEmpty() ? View.GONE : shown ? View.VISIBLE : View.INVISIBLE);
        });
    }

    // Long enough that the first home-then-play attempt has really failed before a retry re-homes (a retry
    // mid-start would itself flash the error); the play link alone takes several seconds to show a session.
    static final long VERIFY_MS = 13_000;
    static final int RETRIES = 2;
    private Runnable pendingVerify;


    private void open(Item item) {
        if (machine == null) return;
        if (pendingVerify != null) main.removeCallbacks(pendingVerify);
        abortStop = true;      // take over any Home stop sequence already running
        releaseFocus();        // the new video needs the audio focus
        launch(item);
        schedule(item, 1);
    }

    /**
     * Send Plex to its own home, then play the item from there. Playing straight from one video into another
     * makes Plex flash "Playback Error: Item not known when attempting to build decision" for several seconds,
     * because the new item is not in the player's store mid-switch. From its home the item is known, so the
     * play link starts cleanly (plex://home also drops the previous session). Found by decompiling the Plex
     * APK; the same route BACK takes out of the player.
     *
     * The play link waits until Plex has really left the player (its session has cleared) rather than a fixed
     * delay: a fixed wait is too short when Plex home loads slowly, and the link then lands on the old player
     * and flashes the error. Polling the session adapts to however long it takes.
     */
    private final java.util.concurrent.atomic.AtomicInteger launchGen = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Play the item the way a person using Plex would: go to Plex's home first, let it settle, then play.
     * Plex shows "Playback Error: Item not known when attempting to build decision" whenever a watch/video
     * link reaches it before it knows the item — both on a cold start (its library has not loaded) and
     * mid-switch (the old player is still tearing down). plex://home gets it to a state where the item is
     * known; the wait then holds for the home to load its library (a minimum settle) and for any current
     * player to have gone (its session cleared) before the play link is sent. Found by decompiling the Plex
     * APK. If a newer launch starts meanwhile, this one drops out.
     */
    static final long HOME_MIN_SETTLE_MS = 5000;
    static final long HOME_MAX_WAIT_MS = 13000;

    private void launch(Item item) {
        final int gen = launchGen.incrementAndGet();
        new Thread(() -> {
            main.post(() -> sendIntent("plex://home"));
            long start = System.currentTimeMillis();
            while (gen == launchGen.get()) {
                long elapsed = System.currentTimeMillis() - start;
                if (elapsed >= HOME_MAX_WAIT_MS) break;
                if (elapsed >= HOME_MIN_SETTLE_MS && sessions().isEmpty()) break;
                try { Thread.sleep(300); } catch (InterruptedException ignored) { }
            }
            main.post(() -> { if (gen == launchGen.get()) sendPlay(item); });
        }, "plex-launch").start();
    }

    /**
     * The only plex:// route this TV app (tv.plex.app, 2026) acts on: it starts playback of that item at once.
     * plex://server/<id>/details and plex://preplay only bring the app to the front.
     */
    private AudioFocusRequest focus;

    /** A server session played by this TV: enough to report it stopped in the TV client's name. */
    static final class Session {
        final String ratingKey, key, client, time, duration, state;
        Session(String ratingKey, String key, String client, String time, String duration, String state) {
            this.ratingKey = ratingKey; this.key = key; this.client = client; this.time = time; this.duration = duration; this.state = state;
        }
    }

    /** Sessions in /status/sessions whose player address is one of this TV's own IP addresses. */
    private List<Session> sessions() {
        List<Session> out = new ArrayList<>();
        Set<String> mine = localAddresses();
        try {
            HttpURLConnection c = open(base + "/status/sessions?X-Plex-Token=" + token);
            try (InputStream in = c.getInputStream()) {
                SAXParserFactory.newInstance().newSAXParser().parse(in, new DefaultHandler() {
                    String rk, key, time, dur;
                    @Override public void startElement(String uri, String l, String tag, Attributes a) {
                        if ("Video".equals(tag)) {
                            rk = a.getValue("ratingKey"); key = a.getValue("key");
                            time = a.getValue("viewOffset"); dur = a.getValue("duration");
                        } else if ("Player".equals(tag) && rk != null && mine.contains(a.getValue("address")) && a.getValue("machineIdentifier") != null) {
                            out.add(new Session(rk, key, a.getValue("machineIdentifier"), time == null ? "0" : time, dur == null ? "0" : dur, a.getValue("state")));
                        }
                    }
                });
            } finally { c.disconnect(); }
        } catch (Exception ignored) { }
        return out;
    }

    /** The timeline report a Plex client sends when it stops: the server drops the session on it (terminate needs rights this token lacks). */
    private void endSession(Session s) {
        try {
            HttpURLConnection c = open(base + "/:/timeline?ratingKey=" + s.ratingKey + "&key=" + URLEncoder.encode(s.key, "UTF-8")
                    + "&state=stopped&time=" + s.time + "&duration=" + s.duration + "&identifier=com.plexapp.plugins.library"
                    + "&X-Plex-Client-Identifier=" + s.client + "&X-Plex-Token=" + token);
            try { c.getResponseCode(); } finally { c.disconnect(); }
        } catch (Exception ignored) { }
    }

    private static Set<String> localAddresses() {
        Set<String> out = new HashSet<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces()))
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (!a.isLoopbackAddress() && a instanceof Inet4Address) out.add(a.getHostAddress());
        } catch (Exception ignored) { }
        return out;
    }

    /**
     * Plex's watch/video playback runs in a foreground service and keeps playing after Home; media keys and the
     * server cannot stop it. Taking audio focus for a moment makes its player pause, the way any player does
     * when another app starts playing, and a permanent loss means it does not resume when the focus is given back.
     */
    private void stopPlayback() {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        releaseFocus();
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                .setOnAudioFocusChangeListener(change -> { }).build();
        am.requestAudioFocus(focus);
        // The caller releases the focus when its stop sequence is done (or open() releases it to hand the
        // focus to the next video); a 12 s safety net covers any path that forgets. It must outlast the
        // stop poll loops, or Plex regains focus and resumes mid-stop.
        main.postDelayed(this::releaseFocus, 12000);
    }

    private void releaseFocus() {
        if (focus == null) return;
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) am.abandonAudioFocusRequest(focus);
        focus = null;
    }

    private void sendPlay(Item item) {
        String uri = "server://" + machine + "/com.plexapp.plugins.library" + item.key;
        sendIntent("plex://watch/video?uri=" + Uri.encode(uri));
    }

    private void sendIntent(String data) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(data));
        i.setPackage("com.plexapp.android");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { ctx.startActivity(i); } catch (ActivityNotFoundException ignored) { }
    }

    /**
     * A cold-started Plex can drop the first link while it is still launching, so nothing plays. The server's
     * session list says whether this TV already has a session for the item (buffering or playing). Only when it
     * does NOT is the link sent again, once the app is warm: re-sending onto a session that is already starting
     * makes Plex show "Playback error", and a warm app resumes at the server's stored position on its own, so a
     * single successful send is left strictly alone.
     */
    private void schedule(Item item, int attempt) {
        pendingVerify = () -> new Thread(() -> {
            if (hasSession(item.ratingKey())) return;   // the send worked; never send a second time over it
            if (attempt > RETRIES) return;
            // Re-home before retrying too: a bare play link onto a Plex that has not resolved the item yet
            // is exactly what flashes the "item not known" error.
            main.post(() -> { launch(item); schedule(item, attempt + 1); });
        }, "plex-verify").start();
        main.postDelayed(pendingVerify, VERIFY_MS);
    }

    /** Does this TV already have a session (any state) for the item? Then the play link took, and must not repeat. */
    private boolean hasSession(String ratingKey) {
        for (Session s : sessions()) if (ratingKey.equals(s.ratingKey)) return true;
        return false;
    }

    private final class Holder extends RecyclerView.ViewHolder {
        final ImageView image;
        final TextView show, label;
        final View bar;
        final GradientDrawable frame = new GradientDrawable();
        Holder(LinearLayout v, ImageView image, TextView show, TextView label, View bar) {
            super(v);
            this.image = image; this.show = show; this.label = label; this.bar = bar;
        }
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @Override public int getItemCount() { return items.size(); }

        @Override public Holder onCreateViewHolder(ViewGroup parent, int type) {
            LinearLayout v = new LinearLayout(ctx);
            v.setOrientation(LinearLayout.VERTICAL);
            v.setFocusable(true);
            v.setLayoutParams(new RecyclerView.LayoutParams(dp(172), ViewGroup.LayoutParams.WRAP_CONTENT));
            v.setPadding(dp(6), dp(6), dp(6), dp(6));
            FrameLayout card = new FrameLayout(ctx);
            ImageView image = new ImageView(ctx);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackgroundColor(0xFF1E1E1E);
            card.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            View track = new View(ctx);
            track.setBackgroundColor(0x66000000);
            card.addView(track, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4), Gravity.BOTTOM));
            View bar = new View(ctx);
            bar.setBackgroundColor(0xFFE5A00D);   // Plex amber
            card.addView(bar, new FrameLayout.LayoutParams(0, dp(4), Gravity.BOTTOM | Gravity.START));
            v.addView(card, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(90)));
            TextView show = new TextView(ctx);
            show.setTextColor(Color.WHITE);
            show.setTextSize(14);
            show.setSingleLine(true);
            show.setEllipsize(android.text.TextUtils.TruncateAt.END);
            show.setPadding(0, dp(5), 0, 0);
            v.addView(show);
            TextView label = new TextView(ctx);
            label.setTextColor(0x99FFFFFF);
            label.setTextSize(12);
            label.setSingleLine(true);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            v.addView(label);
            Holder h = new Holder(v, image, show, label, bar);
            h.frame.setCornerRadius(dp(8));
            h.frame.setColor(Color.TRANSPARENT);
            card.setBackground(h.frame);
            card.setClipToOutline(true);
            v.setOnFocusChangeListener((view, focused) -> {
                h.frame.setStroke(focused ? dp(3) : 0, Color.WHITE);
                view.animate().scaleX(focused ? 1.06f : 1f).scaleY(focused ? 1.06f : 1f).setDuration(120).start();
                if (focused) {
                    int pos = h.getBindingAdapterPosition();
                    if (pos >= 0) lastFocused = pos;
                    host.onRowFocused();
                }
            });
            v.setOnClickListener(view -> {
                int pos = h.getBindingAdapterPosition();
                if (pos >= 0 && pos < items.size()) open(items.get(pos));
            });
            v.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int pos = h.getBindingAdapterPosition();
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_UP: host.onRowUp(); return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN: host.onRowDown(); return true;
                    case KeyEvent.KEYCODE_DPAD_LEFT: return pos == 0;                 // the ends stay put
                    case KeyEvent.KEYCODE_DPAD_RIGHT: return pos == items.size() - 1;
                    default: return false;
                }
            });
            return h;
        }

        @Override public void onBindViewHolder(Holder h, int position) {
            Item it = items.get(position);
            h.show.setText(it.show);
            h.label.setText(it.label);
            h.bar.getLayoutParams().width = 0;
            h.bar.setTag(it.progress);
            h.image.post(() -> {
                if (h.bar.getTag() instanceof Integer) {
                    h.bar.getLayoutParams().width = h.image.getWidth() * (Integer) h.bar.getTag() / 100;
                    h.bar.requestLayout();
                }
            });
            Bitmap cached = thumbs.get(it.thumb);
            h.image.setImageBitmap(cached);
            if (cached != null) return;
            new Thread(() -> {
                Bitmap b = fetchThumb(it.thumb);
                if (b == null) return;
                main.post(() -> {
                    thumbs.put(it.thumb, b);
                    int pos = h.getBindingAdapterPosition();
                    if (pos >= 0 && pos < items.size() && items.get(pos).thumb.equals(it.thumb)) h.image.setImageBitmap(b);
                });
            }, "plex-thumb").start();
        }
    }

    // ---- Plex HTTP ---------------------------------------------------------------------------------

    private HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(4000);
        c.setReadTimeout(8000);
        return c;
    }

    private List<Item> get(String path) {
        try {
            HttpURLConnection c = open(base + path + "?X-Plex-Container-Start=0&X-Plex-Container-Size=" + COUNT + "&X-Plex-Token=" + token);
            try (InputStream in = c.getInputStream()) { return parse(in); } finally { c.disconnect(); }
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private String fetchMachine() {
        final String[] out = new String[1];
        try {
            HttpURLConnection c = open(base + "/identity?X-Plex-Token=" + token);
            try (InputStream in = c.getInputStream()) {
                SAXParserFactory.newInstance().newSAXParser().parse(in, new DefaultHandler() {
                    @Override public void startElement(String uri, String local, String tag, Attributes a) {
                        if ("MediaContainer".equals(tag)) out[0] = a.getValue("machineIdentifier");
                    }
                });
            } finally { c.disconnect(); }
        } catch (Exception ignored) { }
        return out[0];
    }

    private Bitmap fetchThumb(String thumb) {
        try {
            HttpURLConnection c = open(base + "/photo/:/transcode?width=" + TW + "&height=" + TH + "&minSize=1&upscale=1&url="
                    + URLEncoder.encode(thumb, "UTF-8") + "&X-Plex-Token=" + token);
            try (InputStream in = c.getInputStream()) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                return BitmapFactory.decodeStream(in, null, o);
            } finally { c.disconnect(); }
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    /** Episodes: series name + "S1 E2 · title"; movies: title + year. Items without key or thumb are skipped. */
    static List<Item> parse(InputStream in) throws Exception {
        List<Item> out = new ArrayList<>();
        SAXParserFactory.newInstance().newSAXParser().parse(in, new DefaultHandler() {
            @Override public void startElement(String uri, String local, String tag, Attributes a) {
                if (out.size() >= COUNT || !"Video".equals(tag)) return;
                String key = a.getValue("key"), title = a.getValue("title"), thumb = a.getValue("thumb");
                if (key == null || title == null || thumb == null) return;
                String show = a.getValue("grandparentTitle");
                String label;
                if (show != null) {
                    String s = a.getValue("parentIndex"), e = a.getValue("index");
                    label = (s != null && e != null ? "S" + s + " E" + e + " · " : "") + title;
                } else {
                    show = title;
                    String year = a.getValue("year");
                    label = year != null ? year : "";
                }
                int progress = 0;
                try {
                    long off = Long.parseLong(a.getValue("viewOffset")), dur = Long.parseLong(a.getValue("duration"));
                    if (dur > 0) progress = (int) Math.max(0, Math.min(100, off * 100 / dur));
                } catch (Exception ignored) { }
                out.add(new Item(key, show, label, thumb, progress));
            }
        });
        return out;
    }
}
