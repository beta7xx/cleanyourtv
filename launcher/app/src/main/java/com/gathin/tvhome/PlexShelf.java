package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Rect;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * This fork's Plex on Home: the Recently Added backdrop (PlexBackdrop) and the Continue Watching row (PlexContinue),
 * shown above the dock while the Plex tile has focus; Up into the row grows it to Plex's poster size and slides the
 * dock down under a scrim. Kept out of MainActivity so a merge from upstream meets a few calls, not the whole row.
 * Nothing runs without a token: `settings put global plex_token <token>` on the TV, never in the repo.
 */
final class PlexShelf {

    static final String PLEX_PACKAGE = "com.plexapp.android";

    /** What the shelf needs from Home. */
    interface Host {
        void glassTo(View v);
        /** Up from the row's tab strip: the top bar takes focus. */
        void focusTopBar();
        /** Down from the row, or the row emptied under it: back to the Plex dock tile. */
        void focusPlexTile();
        /** Runs the focused tile's focus again, which places or hides the row (row items changed). */
        void refit();
    }

    private final Activity activity;
    private final FrameLayout root;
    private final View grid, scrim;
    private final Host host;
    private final boolean animate;
    private final float density;
    private final String token;   // Settings.Global plex_token; null = no Plex at all
    private PlexBackdrop backdrop;
    private PlexContinue row;
    /** The row has focus: posters at the Plex app's size, the dock slid down, details and art up. */
    private boolean large;
    private boolean resumed, searching;
    private int rowEdge = -1, captionEndMargin;
    private final Rect rect = new Rect();

    private final PlexContinue.Host rowHost = new PlexContinue.Host() {
        @Override public void onItemFocused(PlexContinue.Item item) { enterLarge(); backdrop.showArt(item.art); }
        @Override public void onItemsChanged() { refresh(); }
        @Override public void onFocusMoved(View v) { if (v != null) host.glassTo(v); }
        // The first pill takes focus first; the row's shrink then rebinds posters that no longer hold focus, so
        // RecyclerView's focus recovery has nothing to recover and the glass stays on the pill.
        @Override public void onRowUp() {
            host.focusTopBar();
            leaveLarge();
            row.setShown(false);
        }
        @Override public void onRowDown() { leaveLarge(); host.focusPlexTile(); }
    };

    /** animate: animations on and motion not reduced. The server comes from config.xml or Settings, else the LAN. */
    PlexShelf(Activity activity, FrameLayout root, View grid, View scrim, Host host, boolean animate) {
        this.activity = activity;
        this.root = root;
        this.grid = grid;
        this.scrim = scrim;
        this.host = host;
        this.animate = animate;
        density = activity.getResources().getDisplayMetrics().density;
        // The server is config.xml plex_url, else `settings put global plex_url`, else found on the LAN (start).
        String url = activity.getString(R.string.plex_url);
        if (url.isEmpty()) {
            String s = Settings.Global.getString(activity.getContentResolver(), "plex_url");
            if (s != null) url = s.trim();
        }
        String t = Settings.Global.getString(activity.getContentResolver(), "plex_token");
        token = t == null || t.isEmpty() ? null : t;
        if (!url.isEmpty() && token != null) build(url);
    }

    /** Home in front: fetch and show; with no server yet, ask the LAN again (a server may still be booting). */
    void start() {
        resumed = true;
        if (backdrop != null) { backdrop.start(); row.start(); }
        find();
    }

    void stop() {
        resumed = false;
        if (backdrop != null) { backdrop.stop(); row.stop(); leaveLarge(); }
    }

    /** The row's left edge and the caption's end margin, in root coordinates (MainActivity.alignShelf). */
    void setEdges(int rowEdge, int captionEndMargin) {
        this.rowEdge = rowEdge;
        this.captionEndMargin = captionEndMargin;
        if (row != null) applyEdges();
    }

    /**
     * A dock tile has focus with its top at dockTopInRoot: on the Plex tile with items, the row stands where the
     * tile's name would, and true; otherwise the row hides and the caption sits on captionBottom.
     */
    boolean showRow(boolean plexTile, float dockTopInRoot, float captionBottom) {
        if (row == null) return false;
        if (plexTile && row.hasItems()) {
            backdrop.setCaptionShown(false);
            row.setShown(true);
            place(dockTopInRoot);
            return true;
        }
        row.setShown(false);
        backdrop.alignBottom(captionBottom);
        return false;
    }

    /** Focus left the dock tiles: the row and the caption go. */
    void hide() {
        if (row == null) return;
        backdrop.setCaptionShown(false);
        row.setShown(false);
    }

    void hideCaption() {
        if (backdrop != null) backdrop.setCaptionShown(false);
    }

    /** Up from the Plex tile; false when there is no row to take focus. */
    boolean focusRow() {
        return row != null && row.focus();
    }

    /**
     * The grid scrolled by dy. Down from the Plex tile the row is hidden at once and rides up with the dock as it
     * fades, instead of vanishing before the dock moves; place puts it back when it shows again.
     */
    void onGridScrolled(int dy) {
        if (row == null || row.shown()) return;
        View v = row.view();
        if (v.getAlpha() > 0f) v.setY(v.getY() - dy);
    }

    /** Back to the compact row: the dock returns, the scrim goes, the slideshow resumes. */
    void leaveLarge() {
        if (!large) return;
        large = false;
        row.setLarge(false);
        backdrop.resumeSlideshow();
        grid.animate().cancel();
        scrim.animate().cancel();
        if (!animate) {
            grid.setTranslationY(0f);
            scrim.setAlpha(0f);
            scrim.setVisibility(View.GONE);
            return;
        }
        grid.animate().translationY(0f).setDuration(260).start();
        scrim.animate().alpha(0f).setDuration(260).withEndAction(() -> { if (!large) scrim.setVisibility(View.GONE); }).start();
    }

    /** The backdrop and the row for this server; started at once if Home is already in front. */
    private void build(String url) {
        String sections = activity.getString(R.string.plex_sections);
        backdrop = new PlexBackdrop(activity, root, url, token, sections, density);
        row = new PlexContinue(activity, rowHost, url, token, sections, density);
        root.addView(row.view(), root.indexOfChild(backdrop.caption()) + 1, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        if (rowEdge >= 0) applyEdges();   // found after the first layout: line it up with the band now
        if (resumed) { backdrop.start(); row.start(); }
    }

    private void applyEdges() {
        row.setEdge(rowEdge);
        backdrop.setEndMargin(captionEndMargin);
    }

    /** No server configured: ask the LAN (GDM). Again on every start until one answers. */
    private void find() {
        if (backdrop != null || token == null || searching) return;
        searching = true;
        new Thread(() -> {
            String url = PlexGdm.find(2000);
            activity.runOnUiThread(() -> {
                searching = false;
                if (url != null && backdrop == null && !activity.isDestroyed()) build(url);
            });
        }, "plex-gdm").start();
    }

    /** Items arrived or changed while the Plex dock tile has focus: run the dock focus again so the row is placed. */
    private void refresh() {
        if (large) {
            // Continue Watching emptied under the focused row (finished elsewhere): back to the Plex tile, nothing
            // stranded. An empty library tab stays, with focus on its tab, so Left/Right reach the other tabs.
            if (!row.hasItems() && row.onFirstTab()) { leaveLarge(); host.focusPlexTile(); }
            return;
        }
        host.refit();
    }

    /**
     * Compact row, bottom 36dp above the dock tiles: with the band's 14dp pad and the list's 6dp, 28dp of air
     * between posters and band, twice the 14dp between header and posters, so the row reads as its own group.
     */
    private void place(float dockTopInRoot) {
        View v = row.view();
        float y = dockTopInRoot - 36 * density - row.height();
        // No cancel here: setShown's fade may be running on the same animator, and y joins it.
        if (animate && v.getAlpha() > 0f) v.animate().y(y).setDuration(260).start();
        else v.setY(y);
    }

    /** Up into the row: Plex's poster size, the row near the bottom, the dock band peeking 16dp, details and art. */
    private void enterLarge() {
        if (large) return;
        large = true;
        row.setLarge(true);
        row.setShown(true);
        View v = row.view();
        // The row keeps the captions' room on every tab (PlexContinue.captionRoom), so the tab strip never moves when
        // the tab changes; its bottom sits 24dp above the screen's edge.
        float rowY = root.getHeight() - 24 * density - row.height();
        // The dock is parked at the grid's top padding while a dock tile has focus: that is the band's top.
        rect.set(0, 0, grid.getWidth(), grid.getHeight());
        root.offsetDescendantRectToMyCoords(grid, rect);
        float dy = root.getHeight() - 16 * density - (rect.top + grid.getPaddingTop());
        scrim.setVisibility(View.VISIBLE);
        grid.animate().cancel();
        if (!animate) {
            v.setY(rowY);
            grid.setTranslationY(dy);
            scrim.setAlpha(1f);
            return;
        }
        // The glass was placed from the focus event, while the row was still up at its compact height: follow it down.
        v.animate().y(rowY).setDuration(260).withEndAction(() -> { View f = v.findFocus(); if (f != null) host.glassTo(f); }).start();
        grid.animate().translationY(dy).setDuration(260).start();
        scrim.animate().alpha(1f).setDuration(260).start();
    }
}
