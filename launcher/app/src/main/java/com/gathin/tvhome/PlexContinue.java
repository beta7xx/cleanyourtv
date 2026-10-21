package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
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
 * Plex "Continue Watching" (config.xml plex_url, token in Settings.Global plex_token), styled after the Plex
 * app's own row: series posters, the focused item's details and art. Fetched on every Home resume (one small
 * XML request), every item the hub returns, posters through Plex's transcoder in a memory-bounded cache.
 *
 * Opening an item sends the same link Plex's own Watch Next entries use, once; a cold Plex has not picked
 * the local connection yet and shows its Remote Watch Pass screen instead, so the link is sent a second time
 * after {@link #RESEND_MS} when the server still has no session for it from this TV. On Home, Plex pauses
 * (with its Picture-in-Picture app-op denied, see the KD-49X8500F example) and the row ends its server
 * session with the stopped timeline report Plex itself sends when Back leaves the player.
 */
final class PlexContinue {
    /** Poster cache: about 34 posters at 244x364 RGB_565; older ones are refetched when scrolled back to. */
    static final int POSTER_CACHE_BYTES = 6 * 1024 * 1024;
    static final long RESEND_MS = 20_000;
    static final long REFRESH_MS = 20_000;
    private static final int TW = 244, TH = 364;   // 2x the large 122x182dp poster; the compact row scales it down

    interface Host {
        void onRowFocused();
        /** A poster took focus: show its art and details. */
        void onItemFocused(Item item);
        /** The list was (re)loaded: the host re-places or hides the row for the tile that has focus. */
        void onItemsChanged();
        void onRowUp();
        void onRowDown();
    }

    /** Poster sizes in dp: compact above the dock, and the Plex app's own 122x182 when the row has focus. */
    static final int SMALL_W = 88, SMALL_H = 132, LARGE_W = 122, LARGE_H = 182, GAP = 14, EDGE = 63;

    static final class Item {
        final String key, show, title, poster, art, summary, cast, meta;
        final int progress;   // 0..100, viewOffset / duration
        Item(String key, String show, String title, String poster, String art, String summary, String cast, String meta, int progress) {
            this.key = key; this.show = show; this.title = title; this.poster = poster; this.art = art;
            this.summary = summary; this.cast = cast; this.meta = meta; this.progress = progress;
        }
        /** "/library/metadata/137585" -> "137585", as the sessions list names it. */
        String ratingKey() { return key.substring(key.lastIndexOf('/') + 1); }
    }

    /** A server session: enough to report it stopped in the playing client's name. */
    static final class Session {
        final String ratingKey, key, client, time, duration, state, address;
        Session(String ratingKey, String key, String client, String time, String duration, String state, String address) {
            this.ratingKey = ratingKey; this.key = key; this.client = client; this.time = time;
            this.duration = duration; this.state = state; this.address = address;
        }
    }

    private final Context ctx;
    private final Host host;
    private final String base, token;
    private final float density;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> thumbs = new LruCache<String, Bitmap>(POSTER_CACHE_BYTES) {
        @Override protected int sizeOf(String key, Bitmap b) { return b.getByteCount(); }
    };
    private final LinearLayout row;
    private final RecyclerView list;
    private final Adapter adapter = new Adapter();
    private String machine;   // Plex server machineIdentifier, for the plex:// link
    private List<Item> items = new ArrayList<>();
    private int lastFocused;
    private boolean running;
    private boolean shown = true;
    private final Runnable refresh = this::refresh;
    private Runnable resend;
    private boolean large;

    PlexContinue(Context ctx, Host host, String base, String token, float density) {
        this.ctx = ctx;
        this.host = host;
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.token = token;
        this.density = density;
        row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setVisibility(View.GONE);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        TextView title = new TextView(ctx);
        title.setText(R.string.plex_continue);
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setTypeface(ctx.getResources().getFont(R.font.outfit_medium));
        title.setPadding(dp(EDGE), 0, 0, dp(GAP));
        row.addView(title);
        list = new RecyclerView(ctx);
        list.setLayoutManager(new LinearLayoutManager(ctx, RecyclerView.HORIZONTAL, false));
        adapter.setHasStableIds(true);   // a refresh keeps the focused poster focused
        list.setAdapter(adapter);
        list.setClipToPadding(false);
        list.setClipChildren(false);
        // 6dp above and below: room for the focus scale and stroke.
        list.setPadding(dp(EDGE), dp(6), dp(EDGE), dp(6));
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        row.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    boolean hasItems() { return !items.isEmpty(); }

    /** The row's height for the current size: header, the list's 6dp above and below, the poster. */
    int height() {
        View title = row.getChildAt(0);
        if (title.getMeasuredHeight() == 0)
            title.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return title.getMeasuredHeight() + dp(12) + posterH();
    }

    /** Compact (above the dock) or the Plex app's size (row focused). Applies to every poster at once. */
    void setLarge(boolean large) {
        if (this.large == large) return;
        this.large = large;
        // Rebind rather than resize the attached children only: posters parked in the view cache keep their
        // size otherwise and come back wrong. Stable ids keep the focused poster focused through it.
        adapter.notifyItemRangeChanged(0, items.size());
    }

    private int posterW() { return dp(large ? LARGE_W : SMALL_W); }
    private int posterH() { return dp(large ? LARGE_H : SMALL_H); }

    View view() { return row; }

    private int dp(int v) { return (int) (v * density); }

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
        if (items.isEmpty()) return false;
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

    /** Home is in front: drop a pending resend (the user came back), end this TV's server sessions, refresh. */
    void start() {
        running = true;
        if (resend != null) { main.removeCallbacks(resend); resend = null; }
        new Thread(() -> {
            if (machine == null) machine = fetchMachine();
            loadRow();
            Set<String> mine = localAddresses();
            boolean ended = false;
            for (Session s : sessions()) if (mine.contains(s.address)) { endSession(s); ended = true; }
            if (ended) loadRow();   // the item just stopped now carries its resume position
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
            if (!running || same(items, result)) return;
            items = result;
            adapter.notifyDataSetChanged();
            row.setVisibility(items.isEmpty() ? View.GONE : shown ? View.VISIBLE : View.INVISIBLE);
            host.onItemsChanged();
        });
    }

    /** Same items in the same order with the same progress: nothing to rebind (a rebind unsettles focus). */
    static boolean same(List<Item> a, List<Item> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++)
            if (!a.get(i).key.equals(b.get(i).key) || a.get(i).progress != b.get(i).progress) return false;
        return true;
    }

    // ---- opening ---------------------------------------------------------------------------------

    /** The link Plex's own Watch Next entries carry; sent once, and once more if the server shows no playback. */
    private void open(Item item) {
        if (machine == null) return;
        if (resend != null) main.removeCallbacks(resend);
        sendPlay(item);
        resend = () -> new Thread(() -> {
            if (needsResend(sessions(), localAddresses(), item.ratingKey())) main.post(() -> sendPlay(item));
        }, "plex-resend").start();
        main.postDelayed(resend, RESEND_MS);
    }

    /** No session for the item from one of this TV's addresses: the first link met a cold Plex. */
    static boolean needsResend(List<Session> sessions, Set<String> mine, String ratingKey) {
        for (Session s : sessions) if (ratingKey.equals(s.ratingKey) && mine.contains(s.address)) return false;
        return true;
    }

    private void sendPlay(Item item) {
        String uri = "server://" + machine + "/com.plexapp.plugins.library" + item.key;
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("plex://watch/video?uri=" + Uri.encode(uri)));
        i.setPackage("com.plexapp.android");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { ctx.startActivity(i); } catch (ActivityNotFoundException ignored) { }
    }

    // ---- sessions --------------------------------------------------------------------------------

    /** Every session in /status/sessions with its Player's address. */
    private List<Session> sessions() {
        List<Session> out = new ArrayList<>();
        try {
            HttpURLConnection c = open(base + "/status/sessions?X-Plex-Token=" + token);
            try (InputStream in = c.getInputStream()) {
                SAXParserFactory.newInstance().newSAXParser().parse(in, new DefaultHandler() {
                    String rk, key, time, dur;
                    @Override public void startElement(String uri, String l, String tag, Attributes a) {
                        if ("Video".equals(tag)) {
                            rk = a.getValue("ratingKey"); key = a.getValue("key");
                            time = a.getValue("viewOffset"); dur = a.getValue("duration");
                        } else if ("Player".equals(tag) && rk != null && a.getValue("machineIdentifier") != null) {
                            out.add(new Session(rk, key, a.getValue("machineIdentifier"), time == null ? "0" : time,
                                    dur == null ? "0" : dur, a.getValue("state"), a.getValue("address")));
                        }
                    }
                });
            } finally { c.disconnect(); }
        } catch (Exception ignored) { }
        return out;
    }

    /** The timeline report a Plex client sends when Back leaves its player: the server drops the session on it. */
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

    // ---- row view --------------------------------------------------------------------------------

    private final class Holder extends RecyclerView.ViewHolder {
        final ImageView image;
        final View bar, track;
        final GradientDrawable frame = new GradientDrawable();
        int progress;
        Holder(FrameLayout card, ImageView image, View bar, View track) {
            super(card);
            this.image = image; this.bar = bar; this.track = track;
        }
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @Override public int getItemCount() { return items.size(); }
        @Override public long getItemId(int position) { return items.get(position).key.hashCode(); }

        /** The poster's size for the current mode, and the progress bar's width inside it. */
        void size(View card) {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) card.getLayoutParams();
            lp.width = posterW();
            lp.height = posterH();
            lp.setMarginEnd(dp(GAP));
            card.setLayoutParams(lp);
            RecyclerView.ViewHolder vh = list.getChildViewHolder(card);
            if (vh instanceof Holder) {
                Holder h = (Holder) vh;
                h.bar.getLayoutParams().width = (posterW() - 2 * dp(8)) * h.progress / 100;
                h.bar.requestLayout();
            }
        }

        @Override public Holder onCreateViewHolder(ViewGroup parent, int type) {
            FrameLayout card = new FrameLayout(ctx);
            card.setFocusable(true);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(posterW(), posterH());
            lp.setMarginEnd(dp(GAP));
            card.setLayoutParams(lp);
            ImageView image = new ImageView(ctx);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackgroundColor(0xFF1E1E1E);
            card.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            // Plex's progress bar: amber over a dark track, 2dp high, inset 8dp from the poster's sides and bottom.
            View track = new View(ctx);
            track.setBackgroundColor(0x66000000);
            FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2), Gravity.BOTTOM);
            tlp.setMargins(dp(8), 0, dp(8), dp(8));
            card.addView(track, tlp);
            View bar = new View(ctx);
            bar.setBackgroundColor(0xFFE5A00D);
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(0, dp(2), Gravity.BOTTOM | Gravity.START);
            blp.setMargins(dp(8), 0, 0, dp(8));
            card.addView(bar, blp);
            Holder h = new Holder(card, image, bar, track);
            h.frame.setCornerRadius(dp(6));
            h.frame.setColor(Color.TRANSPARENT);
            card.setForeground(h.frame);   // the stroke draws over the poster, clipped with it
            card.setClipToOutline(true);
            card.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(6));
                }
            });
            card.setOnFocusChangeListener((view, focused) -> {
                h.frame.setStroke(focused ? Math.round(1.5f * density) : 0, Color.WHITE);
                view.animate().scaleX(focused ? 1.04f : 1f).scaleY(focused ? 1.04f : 1f).setDuration(120).start();
                if (focused) {
                    int pos = h.getBindingAdapterPosition();
                    if (pos >= 0) lastFocused = pos;
                    host.onRowFocused();
                    if (pos >= 0 && pos < items.size()) host.onItemFocused(items.get(pos));
                }
            });
            card.setOnClickListener(view -> {
                int pos = h.getBindingAdapterPosition();
                if (pos >= 0 && pos < items.size()) open(items.get(pos));
            });
            card.setOnKeyListener((view, keyCode, event) -> {
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
            h.progress = it.progress;
            size(h.itemView);
            // Plex shows the bar only on items with progress.
            h.bar.setVisibility(it.progress > 0 ? View.VISIBLE : View.GONE);
            h.track.setVisibility(it.progress > 0 ? View.VISIBLE : View.GONE);
            Bitmap cached = thumbs.get(it.poster);
            h.image.setImageBitmap(cached);
            if (cached != null) return;
            new Thread(() -> {
                Bitmap b = fetchThumb(it.poster);
                if (b == null) return;
                main.post(() -> {
                    thumbs.put(it.poster, b);
                    int pos = h.getBindingAdapterPosition();
                    if (pos >= 0 && pos < items.size() && items.get(pos).poster.equals(it.poster)) h.image.setImageBitmap(b);
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
            HttpURLConnection c = open(base + path + "?X-Plex-Token=" + token);   // the whole hub, as Plex shows it
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

    /**
     * What the Plex app shows per item: the series poster (the movie's own for movies), the backdrop art, a
     * meta line ("E2 · 2026-10-16 · 50m · TV-MA", or "2024 · 90m" for a movie), the summary and three cast
     * names. Items without any poster are skipped.
     */
    static List<Item> parse(InputStream in) throws Exception {
        List<Item> out = new ArrayList<>();
        SAXParserFactory.newInstance().newSAXParser().parse(in, new DefaultHandler() {
            String key, show, title, poster, art, summary, meta;
            int progress;
            List<String> cast;

            @Override public void startElement(String uri, String local, String tag, Attributes a) {
                if ("Role".equals(tag) && cast != null) {
                    if (cast.size() < 3 && a.getValue("tag") != null) cast.add(a.getValue("tag"));
                    return;
                }
                if (!"Video".equals(tag)) return;
                key = a.getValue("key"); title = a.getValue("title");
                poster = a.getValue("grandparentThumb") != null ? a.getValue("grandparentThumb") : a.getValue("thumb");
                if (key == null || title == null || poster == null) { key = null; return; }
                show = a.getValue("grandparentTitle") != null ? a.getValue("grandparentTitle") : title;
                art = a.getValue("art") != null ? a.getValue("art") : a.getValue("grandparentArt");
                summary = a.getValue("summary") == null ? "" : a.getValue("summary");
                cast = new ArrayList<>();
                List<String> parts = new ArrayList<>();
                if (a.getValue("grandparentTitle") != null) {
                    if (a.getValue("index") != null) parts.add("E" + a.getValue("index"));
                    if (a.getValue("originallyAvailableAt") != null) parts.add(a.getValue("originallyAvailableAt"));
                } else if (a.getValue("year") != null) parts.add(a.getValue("year"));
                long dur = 0;
                try { dur = Long.parseLong(a.getValue("duration")); } catch (Exception ignored) { }
                if (dur > 0) parts.add(dur / 60000 + "m");
                if (a.getValue("contentRating") != null) parts.add(a.getValue("contentRating"));
                meta = String.join(" · ", parts);
                progress = 0;
                try {
                    long off = Long.parseLong(a.getValue("viewOffset"));
                    if (dur > 0) progress = (int) Math.max(0, Math.min(100, off * 100 / dur));
                } catch (Exception ignored) { }
            }

            @Override public void endElement(String uri, String local, String tag) {
                if (!"Video".equals(tag) || key == null) return;
                out.add(new Item(key, show, title, poster, art, summary, String.join(", ", cast), meta, progress));
                key = null; cast = null;
            }
        });
        return out;
    }
}
