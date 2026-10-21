package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import javax.xml.parsers.SAXParserFactory;

/**
 * Plex "Recently Added" as the home background (config.xml plex_url / plex_token; empty = off).
 * The list is fetched once per {@link #LIST_TTL_MS} while Home is in front, never in the background;
 * one dimmed 960x540 backdrop at a time, swapped every {@link #SLIDE_MS}, with the title at bottom right.
 * Any failure leaves the plain background, so Plex being down never touches the home screen.
 */
final class PlexBackdrop {
    static final int COUNT = 10;
    static final long LIST_TTL_MS = 30 * 60 * 1000L;
    static final long SLIDE_MS = 20 * 1000L;
    private static final int W = 960, H = 540;
    /** Backdrop opacity over the black background; lower = darker, keeps the clock and pills readable. */
    static final float DIM = 0.32f;

    static final class Item {
        final String title, art, show;   // show: the series name (or the movie title) used to keep one item per title
        final long addedAt;
        Item(String title, String art, String show, long addedAt) { this.title = title; this.art = art; this.show = show; this.addedAt = addedAt; }
    }

    private final Context ctx;
    private final String base, token;
    private final String[] sections;
    private final ImageView wall;
    private final TextView caption;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private List<Item> items = new ArrayList<>();
    private long fetchedAt;
    private boolean running;
    private Item current;
    private final Runnable slide = this::next;
    /** Bumped by showArt/resumeSlideshow so a slower fetch from before cannot land over the newer image. */
    private int gen;

    PlexBackdrop(Context ctx, FrameLayout root, String base, String token, String sections, float density) {
        this.ctx = ctx;
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.token = token;
        this.sections = sections.trim().isEmpty() ? new String[0] : sections.split("\\s*,\\s*");
        wall = new ImageView(ctx);
        wall.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wall.setAlpha(0f);
        // Above the wallpaper (WallpaperPlayer puts its image and shade at 0 and 1) and the sky, below the hero art.
        root.addView(wall, root.indexOfChild(root.findViewById(R.id.heroArt)), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        caption = new TextView(ctx);
        caption.setTextColor(0xB3FFFFFF);
        caption.setTextSize(16);
        caption.setSingleLine(true);
        caption.setAlpha(0f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        lp.setMarginEnd((int) (68 * density));
        lp.width = (int) (700 * density);
        caption.setGravity(Gravity.END);
        caption.setEllipsize(android.text.TextUtils.TruncateAt.END);
        root.addView(caption, lp);
    }

    private boolean captionShown;

    /** The caption's right edge, in px from the screen's right: lined up with the dock band by the launcher. */
    void setEndMargin(int px) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) caption.getLayoutParams();
        if (lp.rightMargin == px) return;
        lp.rightMargin = px;   // left-to-right launcher: the resolved margin, not the start/end one
        caption.setLayoutParams(lp);
    }

    /** Caption's bottom edge in root coordinates: aligned with the hero name above the dock, and shown with it. */
    void alignBottom(float bottom) {
        caption.setY(bottom - caption.getHeight());
        setCaptionShown(true);
    }

    /** The caption follows the hero name: hidden while focus is in the grid (the dock moves up over it). */
    void setCaptionShown(boolean shown) {
        if (captionShown == shown) return;
        captionShown = shown;
        caption.animate().cancel();
        caption.animate().alpha(shown && current != null ? 1f : 0f).setDuration(160).start();
    }

    void start() {
        if (running) return;
        running = true;
        if (current == null || System.currentTimeMillis() - fetchedAt > LIST_TTL_MS) {
            new Thread(() -> {
                List<Item> fresh = fetchList();
                main.post(() -> {
                    if (!fresh.isEmpty()) { items = fresh; fetchedAt = System.currentTimeMillis(); }
                    if (running) next();
                });
            }, "plex-list").start();
        } else {
            main.postDelayed(slide, SLIDE_MS);
        }
    }

    void stop() {
        running = false;
        main.removeCallbacks(slide);
    }

    /** The Continue Watching row has focus: this item's art, undimmed, instead of the slideshow. */
    void showArt(String art) {
        main.removeCallbacks(slide);
        final int g = ++gen;
        if (art == null) return;
        new Thread(() -> {
            Bitmap bmp = fetchArt(art);
            main.post(() -> {
                if (g != gen || bmp == null) return;
                wall.animate().cancel();
                wall.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                    if (g != gen) return;
                    wall.setImageBitmap(bmp);
                    wall.animate().alpha(1f).setDuration(400).start();
                }).start();
            });
        }, "plex-art").start();
    }

    /** Focus left the row: dim what is showing and let the slideshow carry on from here. */
    void resumeSlideshow() {
        ++gen;
        wall.animate().cancel();
        wall.animate().alpha(current == null ? 0f : DIM).setDuration(400).start();
        main.removeCallbacks(slide);
        if (running) main.postDelayed(slide, SLIDE_MS);
    }

    private void next() {
        main.removeCallbacks(slide);
        final int g = ++gen;
        if (!running || items.isEmpty()) return;
        Item pick = items.get(random.nextInt(items.size()));
        if (items.size() > 1 && pick == current) pick = items.get((items.indexOf(pick) + 1) % items.size());
        final Item item = pick;
        new Thread(() -> {
            Bitmap bmp = fetchArt(item.art);
            main.post(() -> {
                if (g != gen) return;
                if (!running || bmp == null) { main.postDelayed(slide, SLIDE_MS); return; }
                current = item;
                wall.animate().alpha(0f).setDuration(400).withEndAction(() -> {
                    wall.setImageBitmap(bmp);
                    wall.animate().alpha(DIM).setDuration(600).start();
                }).start();
                caption.animate().alpha(0f).setDuration(300).withEndAction(() -> {
                    caption.setText(ctx.getString(R.string.plex_recently_added, item.title));
                    if (captionShown) caption.animate().alpha(1f).setDuration(400).start();
                }).start();
                main.postDelayed(slide, SLIDE_MS);
            });
        }, "plex-art").start();
    }

    /** The newest COUNT items across the configured libraries (all libraries when none are named), one per title. */
    private List<Item> fetchList() {
        List<Item> all = new ArrayList<>();
        if (sections.length == 0) {
            all.addAll(get("/library/recentlyAdded"));
        } else {
            for (String key : sectionKeys()) all.addAll(get("/library/sections/" + key + "/recentlyAdded"));
        }
        return merge(all);
    }

    static List<Item> merge(List<Item> all) {
        Collections.sort(all, (a, b) -> Long.compare(b.addedAt, a.addedAt));
        List<Item> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Item i : all) if (seen.add(i.show) && out.size() < COUNT) out.add(i);
        return out;
    }

    private List<Item> get(String path) {
        try {
            HttpURLConnection c = open(base + path + "?X-Plex-Container-Start=0&X-Plex-Container-Size=" + COUNT + "&X-Plex-Token=" + token);
            try (InputStream in = c.getInputStream()) { return parse(in); } finally { c.disconnect(); }
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** Library keys for the configured section titles, from /library/sections. */
    private List<String> sectionKeys() {
        List<String> keys = new ArrayList<>();
        try {
            HttpURLConnection c = open(base + "/library/sections?X-Plex-Token=" + token);
            try (InputStream in = c.getInputStream()) {
                SAXParserFactory.newInstance().newSAXParser().parse(in, new DefaultHandler() {
                    @Override
                    public void startElement(String uri, String local, String tag, Attributes a) {
                        if (!"Directory".equals(tag)) return;
                        for (String s : sections) if (s.equalsIgnoreCase(a.getValue("title")) && a.getValue("key") != null) keys.add(a.getValue("key"));
                    }
                });
            } finally { c.disconnect(); }
        } catch (Exception ignored) { }
        return keys;
    }

    private Bitmap fetchArt(String art) {
        try {
            HttpURLConnection c = open(base + "/photo/:/transcode?width=" + W + "&height=" + H + "&minSize=1&upscale=1&url="
                    + URLEncoder.encode(art, "UTF-8") + "&X-Plex-Token=" + token);
            try (InputStream in = c.getInputStream()) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;   // about 1 MB at 960x540
                return BitmapFactory.decodeStream(in, null, o);
            } finally { c.disconnect(); }
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(4000);
        c.setReadTimeout(8000);
        return c;
    }

    /** Movies use their title; seasons and episodes use the show's name plus their own. Items without art are skipped. */
    static List<Item> parse(InputStream in) throws Exception {
        List<Item> out = new ArrayList<>();
        // SAX from the JDK rather than XmlPullParser: the same code runs in plain JVM unit tests.
        SAXParserFactory.newInstance().newSAXParser().parse(in, new DefaultHandler() {
            @Override
            public void startElement(String uri, String local, String tag, Attributes a) {
                if (out.size() >= COUNT || (!"Video".equals(tag) && !"Directory".equals(tag))) return;
                String art = a.getValue("art");
                String title = a.getValue("title");
                if (art == null || art.isEmpty() || title == null) return;
                String show = a.getValue("grandparentTitle");
                if (show == null) show = a.getValue("parentTitle");
                long added = 0;
                try { added = Long.parseLong(a.getValue("addedAt")); } catch (Exception ignored) { }
                // An episode is captioned by its series only; a season adds its own name; a movie is its title.
                String caption = show == null ? title : "episode".equals(a.getValue("type")) ? show : show + " · " + title;
                out.add(new Item(caption, art, show != null ? show : title, added));
            }
        });
        return out;
    }
}
