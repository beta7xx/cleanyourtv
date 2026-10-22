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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    static final long REFRESH_MS = 10_000;
    private static final int TW = 244, TH = 364;   // 2x the large 122x182dp poster; the compact row scales it down

    interface Host {
        /** A poster took focus: the row grows and its art and details show. */
        void onItemFocused(Item item);
        /** The list was (re)loaded: the host re-places or hides the row for the tile that has focus. */
        void onItemsChanged();
        /** The focused poster or tab moved under the glass (the list scrolled, the row resized): follow it. */
        void onFocusMoved(View v);
        void onRowUp();
        void onRowDown();
    }

    /** Poster sizes in dp: compact above the dock, and the Plex app's own 122x182 when the row has focus. */
    static final int SMALL_W = 96, SMALL_H = 144, LARGE_W = 122, LARGE_H = 182, GAP = 14, EDGE = 63;

    static final class Item {
        final String key, show, title, poster, art, meta, episode;   // episode: "S1 · E44", "" for a movie
        final int progress;   // 0..100, viewOffset / duration
        Item(String key, String show, String title, String poster, String art, String meta, String episode, int progress) {
            this.key = key; this.show = show; this.title = title; this.poster = poster; this.art = art;
            this.meta = meta; this.episode = episode; this.progress = progress;
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
    /** Bumped by start() and open(): a resend check already running (sessions() can take seconds) must not send after Home resumed. */
    private int resendGen;
    private boolean large;

    /** A hub the row can show: Continue Watching, or a library's Recently Added, as Plex's home lists them. */
    static final class Tab {
        final String title, key;
        Tab(String title, String key) { this.title = title; this.key = key; }
    }

    private final String[] sections;
    private List<Tab> tabs = new ArrayList<>();
    private int tab;   // index into tabs; 0 is Continue Watching
    private final LinearLayout tabsRow;

    PlexContinue(Context ctx, Host host, String base, String token, String sections, float density) {
        this.ctx = ctx;
        this.host = host;
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.token = token;
        this.sections = sections.trim().isEmpty() ? new String[0] : sections.split("\\s*,\\s*");
        this.density = density;
        row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setVisibility(View.GONE);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        // The hub tabs as Plex's home lists them, the current one white, the others dim; focusable from the posters.
        tabsRow = new LinearLayout(ctx);
        tabsRow.setOrientation(LinearLayout.HORIZONTAL);
        tabsRow.setPadding(dp(EDGE) - dp(14), dp(6), 0, dp(8));   // 6dp above: Thai vowel marks; plus the list's 6dp: Plex's 14dp to the posters
        tabsRow.setClipChildren(false);
        tabsRow.setClipToPadding(false);
        tabs.add(new Tab(ctx.getString(R.string.plex_continue), "/hubs/continueWatching"));
        buildTabs();
        row.addView(tabsRow);
        list = new RecyclerView(ctx);
        list.setLayoutManager(new LinearLayoutManager(ctx, RecyclerView.HORIZONTAL, false));
        adapter.setHasStableIds(true);   // a refresh keeps the focused poster focused
        list.setItemAnimator(null);      // a size switch rebinds every poster: no cross-fade between two sizes
        list.setAdapter(adapter);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView rv, int dx, int dy) { host.onFocusMoved(rv.findFocus()); }
        });
        list.setClipToPadding(false);
        list.setClipChildren(false);
        // 6dp above and below: room for the focus scale and stroke.
        list.setPadding(dp(EDGE), dp(6), dp(EDGE), dp(6));
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        row.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    boolean hasItems() { return !items.isEmpty(); }

    /** Left edge of the header and the posters, in px: the launcher lines it up with the dock band. */
    void setEdge(int px) {
        row.getChildAt(0).setPadding(px - dp(14), dp(6), 0, dp(8));
        list.setPadding(px, dp(6), px, dp(6));
        // The layout manager keeps the first poster where it was when the padding changes; put it back at the edge.
        ((LinearLayoutManager) list.getLayoutManager()).scrollToPositionWithOffset(0, 0);
    }

    /** The row's height for the current size: header, the list's 6dp above and below, the poster. */
    int height() {
        View title = row.getChildAt(0);
        if (title.getMeasuredHeight() == 0)
            title.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return title.getMeasuredHeight() + dp(12) + posterH() + (captionRoom() ? dp(CAPTION_DP) : 0);
    }

    /** Compact (above the dock) or the Plex app's size (row focused). Applies to every poster at once. */
    void setLarge(boolean large) {
        if (this.large == large) return;
        this.large = large;
        // Shrinking with focus still inside: RecyclerView would hand it to a neighbour on rebind. The host has
        // already chosen where focus goes; drop ours so the rebind leaves it there.
        if (!large && row.hasFocus()) row.clearFocus();
        if (!large && tab != 0) selectTab(0);   // the compact row above the dock is always Continue Watching
        // Rebind rather than resize the attached children only: posters parked in the view cache keep their
        // size otherwise and come back wrong. Stable ids keep the focused poster focused through it.
        adapter.notifyItemRangeChanged(0, items.size());
        paintTabs();
        list.post(() -> host.onFocusMoved(row.findFocus()));   // the posters resized under the glass
    }

    private int posterW() { return dp(large ? LARGE_W : SMALL_W); }
    private int posterH() { return dp(large ? LARGE_H : SMALL_H); }

    View view() { return row; }

    private void buildTabs() {
        tabsRow.removeAllViews();
        for (int i = 0; i < tabs.size(); i++) {
            final int index = i;
            TextView t = new TextView(ctx);
            t.setText(tabs.get(i).title);
            t.setTextSize(20);
            t.setTypeface(ctx.getResources().getFont(R.font.outfit_medium));
            t.setPadding(dp(14), dp(4), dp(14), dp(6));
            t.setIncludeFontPadding(true);   // Thai marks above the x-height need the font's own top padding
            t.setFocusable(true);
            MainActivity.glassTarget(t, 1f, dp(21));   // the floating glass marks the focused tab
            t.setOnFocusChangeListener((v, focused) -> { if (focused) selectTab(index); paintTabs(); });
            t.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_LEFT: if (index > 0) tabsRow.getChildAt(index - 1).requestFocus(); return true;
                    case KeyEvent.KEYCODE_DPAD_RIGHT: if (index < tabs.size() - 1) tabsRow.getChildAt(index + 1).requestFocus(); return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN: if (!focus()) host.onRowDown(); return true;
                    case KeyEvent.KEYCODE_DPAD_UP: host.onRowUp(); return true;
                    case KeyEvent.KEYCODE_BACK:
                    case KeyEvent.KEYCODE_ESCAPE: host.onRowDown(); return true;
                    default: return false;
                }
            });
            tabsRow.addView(t);
        }
        paintTabs();
    }

    /** The current tab reads white, the others dim; the focused one gets an underline-like brighter white. */
    private void paintTabs() {
        for (int i = 0; i < tabsRow.getChildCount(); i++) {
            TextView t = (TextView) tabsRow.getChildAt(i);
            t.setTextColor(t.isFocused() || i == tab ? Color.WHITE : 0x80FFFFFF);
            t.setAlpha(1f);
            t.setVisibility(large || i == tab ? View.VISIBLE : View.GONE);   // compact: one header, as before
        }
    }

    private void selectTab(int index) {
        if (index == tab) return;
        tab = index;
        lastFocused = 0;
        paintTabs();
        new Thread(this::loadRow, "plex-tab").start();
    }

    /**
     * Plex's home hubs for the configured libraries (config.xml plex_sections), as the server titles them:
     * "Recently Added in Anime". Fetched once per Home session, after Continue Watching.
     */
    private void loadTabs() {
        List<String> keys = sectionKeys();
        if (keys.isEmpty()) return;
        List<Tab> fresh = new ArrayList<>();
        fresh.add(tabs.get(0));
        try {
            HttpURLConnection c = open(base + "/hubs/promoted?contentDirectoryID=" + String.join(",", keys) + "&count=1&X-Plex-Token=" + token);
            try (InputStream in = c.getInputStream()) {
                SAXParserFactory.newInstance().newSAXParser().parse(in, new DefaultHandler() {
                    @Override public void startElement(String uri, String l, String tag, Attributes a) {
                        String id = a.getValue("hubIdentifier");
                        if ("Hub".equals(tag) && id != null && id.contains(".recentlyadded.") && a.getValue("key") != null && a.getValue("title") != null)
                            fresh.add(new Tab(a.getValue("title"), a.getValue("key")));
                    }
                });
            } finally { c.disconnect(); }
        } catch (Exception e) { return; }
        if (fresh.size() < 2) return;
        // In the order plex_sections names the libraries, titled "Recently added in <library>" in the TV's language.
        // A hub key names its library as sectionID=<key> (TV) or /library/sections/<key>/ (movies).
        List<Tab> ordered = new ArrayList<>();
        ordered.add(fresh.get(0));
        for (int i = 0; i < keys.size(); i++) {
            for (Tab t : fresh) {
                if (!t.key.contains("sectionID=" + keys.get(i)) && !t.key.contains("/sections/" + keys.get(i) + "/")) continue;
                ordered.add(new Tab(ctx.getString(R.string.plex_recent_in, sectionTitles.get(i)), t.key));
            }
        }
        main.post(() -> { tabs = ordered; buildTabs(); });
    }

    /** Library keys for the configured section titles, from /library/sections. */
    private final List<String> sectionTitles = new ArrayList<>();

    /** Library keys for the configured section titles, in config order; sectionTitles gets the names alongside. */
    private List<String> sectionKeys() {
        List<String> keys = new ArrayList<>();
        sectionTitles.clear();
        if (sections.length == 0) return keys;
        Map<String, String> byTitle = new HashMap<>();
        try {
            HttpURLConnection c = open(base + "/library/sections?X-Plex-Token=" + token);
            try (InputStream in = c.getInputStream()) {
                SAXParserFactory.newInstance().newSAXParser().parse(in, new DefaultHandler() {
                    @Override public void startElement(String uri, String local, String tag, Attributes a) {
                        if ("Directory".equals(tag) && a.getValue("title") != null && a.getValue("key") != null)
                            byTitle.put(a.getValue("title").toLowerCase(Locale.ROOT), a.getValue("key"));
                    }
                });
            } finally { c.disconnect(); }
        } catch (Exception ignored) { }
        for (String s : sections) {
            String key = byTitle.get(s.toLowerCase(Locale.ROOT));
            if (key != null) { keys.add(key); sectionTitles.add(s); }
        }
        return keys;
    }

    private int dp(int v) { return (int) (v * density); }

    /** Hidden (and unfocusable) while focus is down in the grid, where the dock moves up over this area. */
    void setShown(boolean s) {
        if (shown == s) return;
        shown = s;
        if (s && running && System.currentTimeMillis() - loadedAt > REFRESH_MS) new Thread(this::loadRow, "plex-refresh").start();
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
        if (vh != null) { ((Holder) vh).card.requestFocus(); return true; }
        list.scrollToPosition(pos);
        list.post(() -> {
            RecyclerView.ViewHolder h = list.findViewHolderForAdapterPosition(pos);
            if (h != null) ((Holder) h).card.requestFocus();
        });
        return true;
    }

    /** Home is in front: drop a pending resend (the user came back), end this TV's server sessions, refresh. */
    void start() {
        running = true;
        resendGen++;
        if (resend != null) { main.removeCallbacks(resend); resend = null; }
        new Thread(() -> {
            if (machine == null) machine = fetchMachine();
            loadRow();
            if (tabs.size() < 2) loadTabs();
            // Plex reports "paused" with its exact position a moment after Home; ending the session before that
            // would store the last timeline ping's position instead and let the paused report re-register it.
            try { Thread.sleep(3000); } catch (InterruptedException ignored) { }
            if (!running) return;
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

    /** One small XML request every REFRESH_MS (10 s) while Home is on screen keeps the row in step with Plex. */
    private long loadedAt;

    private void refresh() {
        if (!running) return;
        // Only while the row is on screen: hidden behind another tile's name there is nothing to keep fresh.
        if (shown && row.getVisibility() == View.VISIBLE) new Thread(this::loadRow, "plex-refresh").start();
        main.postDelayed(refresh, REFRESH_MS);
    }

    private void loadRow() {
        final int forTab = tab;
        List<Item> fresh = get(tabs.get(forTab).key);
        if (forTab == 0 && fresh != null && fresh.isEmpty()) fresh = get("/library/onDeck");
        if (fresh == null || forTab != tab) return;   // no answer, or the tab moved on: keep what the row shows
        loadedAt = System.currentTimeMillis();
        final List<Item> result = fresh;
        main.post(() -> {
            if (!running || same(items, result)) return;
            items = result;
            adapter.notifyDataSetChanged();
            row.setVisibility(items.isEmpty() ? View.GONE : shown ? View.VISIBLE : View.INVISIBLE);
            host.onItemsChanged();
            // A tab switch while the row is large: the art and details follow the new list's first item.
            if (large && forTab != 0 && !items.isEmpty()) { list.scrollToPosition(0); host.onItemFocused(items.get(0)); }
        });
    }

    /** Same items, order, progress, poster, title and meta: nothing to rebind (a rebind unsettles focus). */
    static boolean same(List<Item> a, List<Item> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++)
            if (!a.get(i).key.equals(b.get(i).key) || a.get(i).progress != b.get(i).progress
                    || !a.get(i).poster.equals(b.get(i).poster) || !a.get(i).title.equals(b.get(i).title)
                    || !a.get(i).meta.equals(b.get(i).meta)) return false;
        return true;
    }

    // ---- opening ---------------------------------------------------------------------------------

    /** The link Plex's own Watch Next entries carry; sent once, and once more if the server shows no playback. */
    private void open(Item item) {
        if (machine == null) return;
        if (resend != null) main.removeCallbacks(resend);
        final int g = ++resendGen;
        sendPlay(item);
        resend = () -> new Thread(() -> {
            if (g != resendGen) return;
            boolean again = needsResend(sessions(), localAddresses());
            main.post(() -> { if (again && g == resendGen) sendPlay(item); });
        }, "plex-resend").start();
        main.postDelayed(resend, RESEND_MS);
    }

    /**
     * No video session at all from this TV: the first link met a cold Plex. Any session, even another title
     * the user picked inside Plex meanwhile, means the link is not sent again (it would yank them back).
     * shortcut: a user who backs out of the player into Plex's home within 20 s still gets the resend; telling
     * that apart needs to know which app is in front, upgrade if it is ever reported.
     */
    static boolean needsResend(List<Session> sessions, Set<String> mine) {
        for (Session s : sessions) if (mine.contains(s.address)) return false;
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

    /** Every video session in /status/sessions with its Player's address; empty when the request failed. */
    private List<Session> sessions() {
        try {
            HttpURLConnection c = open(base + "/status/sessions?X-Plex-Token=" + token);
            try (InputStream in = c.getInputStream()) { return parseSessions(in); } finally { c.disconnect(); }
        } catch (Exception ignored) { }
        return new ArrayList<>();
    }

    /** Video sessions only: a Player inside a Track or Photo (music, a slideshow) is not a video to stop. */
    static List<Session> parseSessions(InputStream in) throws Exception {
        List<Session> out = new ArrayList<>();
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
            @Override public void endElement(String uri, String l, String tag) {
                if ("Video".equals(tag)) rk = null;
            }
        });
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
        final FrameLayout card;
        final ImageView image;
        final View bar, track;
        final TextView show, title, episode;
        final GradientDrawable frame = new GradientDrawable();
        int progress;
        Holder(LinearLayout item, FrameLayout card, ImageView image, View bar, View track, TextView show, TextView title, TextView episode) {
            super(item);
            this.card = card; this.image = image; this.bar = bar; this.track = track;
            this.show = show; this.title = title; this.episode = episode;
        }
    }

    /** Caption lines under a poster, Plex's three: 14sp white, 12sp and 12sp dim. In the large row, on every tab. */
    static final int CAPTION_DP = 62;
    private boolean captions() { return large; }
    private boolean captionRoom() { return large; }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @Override public int getItemCount() { return items.size(); }
        @Override public long getItemId(int position) { return items.get(position).key.hashCode(); }

        private TextView caption(int sp, int color, int topPad) {
            TextView t = new TextView(ctx);
            t.setTextSize(sp);
            t.setTextColor(color);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setPadding(0, topPad, 0, 0);
            t.setTypeface(ctx.getResources().getFont(sp == 14 ? R.font.outfit_medium : R.font.outfit_regular));
            return t;
        }

        /** The poster's size for the current mode, and the progress bar's width inside it. */
        void size(Holder h) {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) h.itemView.getLayoutParams();
            lp.width = posterW();
            lp.height = posterH() + (captionRoom() ? dp(CAPTION_DP) : 0);
            lp.setMarginEnd(dp(GAP));
            h.itemView.setLayoutParams(lp);
            h.card.getLayoutParams().height = posterH();
            int vis = captions() ? View.VISIBLE : View.GONE;
            h.show.setVisibility(vis); h.title.setVisibility(vis); h.episode.setVisibility(vis);
            h.bar.getLayoutParams().width = (posterW() - 2 * dp(8)) * h.progress / 100;
            h.bar.requestLayout();
        }

        @Override public Holder onCreateViewHolder(ViewGroup parent, int type) {
            LinearLayout item = new LinearLayout(ctx);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setClipChildren(false);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(posterW(), posterH());
            lp.setMarginEnd(dp(GAP));
            item.setLayoutParams(lp);
            FrameLayout card = new FrameLayout(ctx);
            card.setFocusable(true);
            item.addView(card, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, posterH()));
            TextView show = caption(14, Color.WHITE, dp(6)), title = caption(12, 0x99FFFFFF, dp(1)), episode = caption(12, 0x99FFFFFF, dp(1));
            item.addView(show); item.addView(title); item.addView(episode);
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
            Holder h = new Holder(item, card, image, bar, track, show, title, episode);
            MainActivity.glassTarget(card, 1.04f, dp(6) * 1.04f);
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
                view.animate().scaleX(focused ? 1.04f : 1f).scaleY(focused ? 1.04f : 1f).setDuration(120).start();
                if (focused) {
                    int pos = h.getBindingAdapterPosition();
                    if (pos >= 0) lastFocused = pos;
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
                    case KeyEvent.KEYCODE_DPAD_UP:
                        if (tabs.size() > 1) tabsRow.getChildAt(tab).requestFocus(); else host.onRowUp();
                        return true;
                    case KeyEvent.KEYCODE_BACK:
                    case KeyEvent.KEYCODE_ESCAPE:
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
            h.show.setText(it.show);
            h.title.setText(it.title.equals(it.show) ? "" : it.title);
            h.episode.setText(it.episode);
            size(h);
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

    /** The hub's items, or null when the request failed. */
    private List<Item> get(String path) {
        try {
            // A hub key may carry its own query (?type=2&sectionID=24); count= is the hub page size, Container-Size the library's.
            HttpURLConnection c = open(base + path + (path.contains("?") ? "&" : "?") + "count=40&X-Plex-Container-Size=40&X-Plex-Token=" + token);
            try (InputStream in = c.getInputStream()) { return parse(in); } finally { c.disconnect(); }
        } catch (Exception e) {
            return null;
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

    /** "2026-10-16" as the TV's locale writes a medium date, as the Plex app does; the raw value when it is not a date. */
    static String displayDate(String iso) {
        try {
            return java.time.LocalDate.parse(iso).format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM));
        } catch (Exception e) {
            return iso;
        }
    }

    /**
     * What the Plex app shows per item: the series poster (the movie's own for movies), the backdrop art, a
     * meta line ("E2 · 2026-10-16 · 50m · TV-MA", or "2024 · 90m" for a movie) and the "S1 · E2" caption line.
     * Items without any poster are skipped.
     */
    static List<Item> parse(InputStream in) throws Exception {
        List<Item> out = new ArrayList<>();
        SAXParserFactory.newInstance().newSAXParser().parse(in, new DefaultHandler() {
            String key, show, title, poster, art, meta, episode;
            int progress;

            @Override public void startElement(String uri, String local, String tag, Attributes a) {
                if (!"Video".equals(tag)) return;
                key = a.getValue("key"); title = a.getValue("title");
                poster = a.getValue("grandparentThumb") != null ? a.getValue("grandparentThumb") : a.getValue("thumb");
                if (key == null || title == null || poster == null) { key = null; return; }
                show = a.getValue("grandparentTitle") != null ? a.getValue("grandparentTitle") : title;
                art = a.getValue("art") != null ? a.getValue("art") : a.getValue("grandparentArt");
                List<String> parts = new ArrayList<>();
                episode = "";
                if (a.getValue("grandparentTitle") != null) {
                    if (a.getValue("index") != null) parts.add("E" + a.getValue("index"));
                    // Plex's caption line under a Recently Added poster: "S1 · E44".
                    if (a.getValue("parentIndex") != null && a.getValue("index") != null) episode = "S" + a.getValue("parentIndex") + " \u00B7 E" + a.getValue("index");
                    if (a.getValue("originallyAvailableAt") != null) parts.add(displayDate(a.getValue("originallyAvailableAt")));
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
                out.add(new Item(key, show, title, poster, art, meta, episode, progress));
                key = null;
            }
        });
        return out;
    }
}
