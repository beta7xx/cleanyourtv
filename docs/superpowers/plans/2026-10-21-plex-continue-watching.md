# Plex Continue Watching Row Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Plex-styled Continue Watching row tied to the Plex dock tile, smaller tiles, reorder pill in the footer, and a simple open/stop path verified on the TV.

**Architecture:** `PlexContinue` keeps its data role (fetch, parse, open, end sessions) and owns the row view with two sizes; `MainActivity` decides when the row shows (Plex dock tile focused), moves the grid down for the large state and hands the backdrop to `PlexBackdrop.showArt`. The footer row (`row_app_library.xml`) gains the reorder pill; `AppAdapter` design constants shrink the tiles.

**Tech Stack:** Java, AndroidX RecyclerView, plain JVM JUnit 4 tests (`launcher/app/src/test`), Gradle offline builds, adb to the Sony KD-49X8500F at 192.168.1.7.

**Spec:** `docs/superpowers/specs/2026-10-21-plex-continue-watching-design.md`

## Global Constraints

- Release APK under 450,560 bytes (`./gradlew assembleRelease --offline`, file `app/build/outputs/apk/release/app-release.apk`).
- Branch `local` only; commit after every task; never push without being asked.
- No network request while Home is paused except the one 20 s resend check.
- Posters 10 at most, 176x264 RGB_565; one 960x540 RGB_565 backdrop.
- Thai strings in `values-th/strings.xml` for every new user-visible string.
- Build check after every code task: `cd launcher && ./gradlew assembleDebug --offline -q` exits 0.

## Review Focus

1. A Continue Watching item without `grandparentThumb` (movie) must use `thumb`; without either it is skipped (Task 3 test).
2. The resend must not fire after Home resumed (Task 3 test on `PlexContinue.needsResend` plus the cancel in `start()`).
3. `endSession` must only hit sessions whose Player address is this TV's (Task 3 test on `needsResend` covers the address match).
4. Focus moving from the Plex tile to a non-Plex tile must hide the row and bring the hero name back (Task 4 screenshot check).
5. Reorder pill absent when `reorder_button` is false and the library footer still focuses (Task 2 check with the bool flipped).

---

### Task 1: Smaller tiles

**Files:**
- Modify: `launcher/app/src/main/java/com/gathin/tvhome/AppAdapter.java:94` (`DESIGN_W_DP`, `DESIGN_H_DP`)
- Modify: `launcher/app/src/main/res/layout/item_app.xml` (card 180x101 to 150x84, icon 48 to 40, name `layout_marginTop` 108 to 91, name width 180 to 150)
- Test: existing `launcher/app/src/test/java/com/gathin/tvhome/AppAdapterTest.java` if it asserts 180/101 (grep `180` there and update the expected values)

- [ ] **Step 1:** Set `DESIGN_W_DP = 150f, DESIGN_H_DP = 84f`; update item_app.xml as listed.
- [ ] **Step 2:** `cd launcher && ./gradlew testDebugUnitTest --offline -q` exits 0 (fix any size assertions).
- [ ] **Step 3:** Commit `launcher: tiles and dock 150x84dp (from 180x101)`.

### Task 2: Reorder pill beside the app library pill

**Files:**
- Modify: `launcher/app/src/main/res/layout/row_app_library.xml`: root `FrameLayout` 84dp stays; inside, a horizontal `LinearLayout` (`layout_gravity=center`) holding `btnLibrary` (as today) and `btnReorderFooter` (`style=@style/TopButtonIcon`, `drawableStart=@drawable/ic_swap_vert`, `contentDescription=@string/reorder`, `visibility=gone`).
- Modify: `launcher/app/src/main/res/layout/activity_main.xml`: delete the `btnReorder` TextView.
- Modify: `launcher/app/src/main/java/com/gathin/tvhome/AppAdapter.java:355-367` footer holder; `Listener` gets `void onReorderRequested();`. The constructor already receives `Config cfg`: show `btnReorderFooter` when `cfg.reorderButton`. Keys on both pills: Left/Right move between the two visible pills (ends stay put, return true), Up calls `listener.onLibraryUp()`, Down is swallowed.
- Modify: `launcher/app/src/main/java/com/gathin/tvhome/MainActivity.java`: remove field `btnReorder` and every use (lines 535-537, 575-578, the 580 loop, `firstTopButton()` 1828-1832); add `@Override public void onReorderRequested() { toggleReorder(); }`. `toggleReorder` at 958 is unchanged.

**Interfaces:**
- Produces: `AppAdapter.Listener.onReorderRequested()`.

- [ ] **Step 1:** Layout and adapter changes; MainActivity wiring.
- [ ] **Step 2:** Build debug, exit 0. Install on the TV (`adb install -r`), focus the footer, screenshot `shots/footer-reorder.png`: two pills centred, Right moves to the reorder pill, OK on it shows the reorder toast.
- [ ] **Step 3:** Flip `reorder_button` to false in config.xml, build, confirm only the library pill shows and Down from the last row still lands on it; flip back.
- [ ] **Step 4:** Commit `launcher: reorder button moves from the top bar to the footer beside the app library`.

### Task 3: PlexContinue data layer: fields, open, stop

**Files:**
- Modify: `launcher/app/src/main/java/com/gathin/tvhome/PlexContinue.java` (rewrite; keep `Host`, `Item`, `Session`, `sessions()`, `endSession`, `localAddresses`, `fetchMachine`, `open(url)`, `get`, `parse`).
- Test: `launcher/app/src/test/java/com/gathin/tvhome/PlexContinueTest.java`.

**Interfaces:**
- Produces:
  - `static final class Item { String key, show, title, poster, art, summary, cast, meta; int progress; String ratingKey(); }` where `poster` = `grandparentThumb` else `thumb`; `art` = `art` else `grandparentArt`; `meta` for episodes `"E<index> · <originallyAvailableAt> · <duration/60000>m · <contentRating>"` (missing parts are left out with their separators) and for movies `"<year> · <duration/60000>m · <contentRating>"`; `cast` = first three `Role` children's `tag` joined with `", "`.
  - `static List<Item> parse(InputStream in)`.
  - `static boolean needsResend(List<Session> sessions, Set<String> mine, String ratingKey)`: true when no session with that ratingKey has a Player address in `mine`.
  - `static final long RESEND_MS = 20_000;`
  - `void open(Item item)`: `sendPlay(item)` then `main.postDelayed(resend, RESEND_MS)`; `resend` runs `sessions()` on a thread and calls `sendPlay` again when `needsResend(...)`.
  - `void start()`: `main.removeCallbacks(resend)`; `loadRow()`; on a thread: for each `Session` in `sessions()` whose address is in `localAddresses()` call `endSession(s)`; then `loadRow()` again.
  - `void stop()`: as today (stops only the 20 s refresh timer; the resend timer survives).
- Removed: `stopPlayback`, `releaseFocus`, the `AudioFocusRequest` field, `launch`, `schedule`, `hasSession`, `launchGen`, `abortStop`, `pendingVerify`, `HOME_*`, `VERIFY_MS`, `RETRIES`.

- [ ] **Step 1: Failing tests** in `PlexContinueTest`:
  - `parsesPosterArtMetaAndCast`: episode XML with `grandparentThumb`, `art`, `index="2"`, `originallyAvailableAt="2026-10-16"`, `duration="3010240"`, `contentRating="TV-MA"`, `summary="A-Chou..."`, three `<Role tag=.../>` children and a fourth that is ignored. Assert `poster` is the grandparentThumb, `meta` equals `"E2 · 2026-10-16 · 50m · TV-MA"`, `cast` equals `"Tian Xiwei, Yan An, Pan Junya"`.
  - `movieWithoutGrandparentUsesThumbAndYear`: movie with `thumb`, `year="2024"`, `duration="5400000"`. Assert `poster` is thumb, `meta` equals `"2024 · 90m"`.
  - `skipsItemsWithoutAnyPoster`.
  - `needsResendOnlyWhenNoSessionFromThisTv`: sessions `[(rk "1", address "192.168.1.9"), (rk "2", address "192.168.1.7")]`, mine `{"192.168.1.7"}`. Assert `needsResend(..., "1")` true and `needsResend(..., "2")` false.
- [ ] **Step 2:** `./gradlew testDebugUnitTest --offline -q` fails to compile on the new fields.
- [ ] **Step 3:** Implement the data layer per Interfaces. `Session` gets a package-visible `address` field for the test.
- [ ] **Step 4:** Tests pass.
- [ ] **Step 5:** Commit `PlexContinue: Plex-style item fields, bare watch link with one resend, end this TV's sessions on Home`.

### Task 4: Row view, two sizes, and MainActivity integration

**Files:**
- Modify: `PlexContinue.java` view part. `view()` returns a vertical `LinearLayout` holding the header `TextView` ("Continue Watching", bold 18sp white) and a horizontal `RecyclerView`. `void setLarge(boolean large)` switches poster size 88x132dp to 122x182dp (gap 14dp, radius 6dp in both) by updating every holder's `LayoutParams` and `notifyDataSetChanged`. Poster card: `ImageView` CENTER_CROP over `#FF1E1E1E`, amber `#FFE5A00D` 2dp progress bar inset 8dp over a `#66000000` track, white 1.5dp stroke plus scale 1.04 on focus. `Host` gains `void onItemFocused(Item item)`.
- Modify: `PlexBackdrop.java`: add `void showArt(String art)` (fetch 960x540 through `fetchArt`, fade `wall` to alpha 1f, keep the caption hidden, stop the slide timer) and `void resumeSlideshow()` (fade back to `DIM`, restart the slide timer). Each cancels the other's in-flight fetch with a generation counter.
- Modify: `activity_main.xml`: add `plexDetails` `LinearLayout` (vertical, `layout_marginStart=63dp`, `layout_marginTop=110dp`, `visibility=gone`, alpha 0) with `plexTitle` (bold 26sp white), `plexEpisode` (bold 16sp white), `plexMeta` (12sp `#B3FFFFFF`), `plexSummary` (13sp `#D9FFFFFF`, maxLines 2, ellipsize end, width 420dp), `plexCast` (12sp `#99FFFFFF`); and `plexScrim` `View` (match_parent, drawable `plex_scrim.xml`: gradient `#E6000000` bottom-left to `#66000000` top-right, angle 45, `visibility=gone`) placed right after `heroArt`.
- Modify: `MainActivity.java`:
  - onCreate (331-343): build `plexRow = new PlexContinue(this, host, plexUrl, plexToken, density)` when the token is set; add `plexRow.view()` to `root` (`MATCH_PARENT` width, `WRAP_CONTENT` height, `Gravity.TOP`), `GONE`.
  - `boolean isPlexDock(int position)`: `position < COLUMNS && "com.plexapp.android".equals(adapter.packageAt(position))`.
  - `showHero(position, dockTopInRoot)` (1289): when `isPlexDock(position)` and the row has items: `plexRow.setLarge(false)`, `plexRow.setShown(true)`, set the row's Y so its bottom is `dockTopInRoot - 14*density`, `hero.setAlpha(0)`, `plex.setCaptionShown(false)`; otherwise `plexRow.setShown(false)` and the existing hero path.
  - `onTileVerticalKey` Up from the dock (1801-1804): `return isPlexDock(position) && plexRow != null && plexRow.focus();`
  - `Host.onRowFocused()` calls `enterPlexLarge()`: `plexRow.setLarge(true)`, row bottom at `root.getHeight() - 24*density`, `grid.animate().translationY(dy)` with `dy` chosen so 16dp of the dock band stays above the bottom edge (`dy = root.getHeight() - 16*density - dockBandTopInRoot`), `hideGlass()`, `plexScrim` and `plexDetails` fade in over 260 ms, `plex.showArt(item.art)`.
  - `Host.onItemFocused(item)` fills `plexDetails` from `item` (`show`, `title`, `meta`, `summary`, `cast`) and calls `plex.showArt(item.art)`.
  - `Host.onRowDown()` calls `leavePlexLarge()` (reverse the above, `grid.animate().translationY(0)`, `plex.resumeSlideshow()`) then `focusTile(plexDockPosition())`, the dock index whose package is Plex.
  - `Host.onRowUp()` calls `leavePlexLarge()` then `firstTopButton().requestFocus()` (`firstTopButton()` without `btnReorder` after Task 2).
  - `dispatchKeyEvent`: BACK while a row item is focused behaves as `onRowDown()`.
  - Resume (688-689): `plexRow.start()`; pause (815-816): `plexRow.stop()` and `leavePlexLarge()` if active.
  - Snap instead of animate when `!animations || reducedMotion`.
- Modify: `values/strings.xml`, `values-th/strings.xml`: keep `plex_continue` ("Continue Watching" / "ดูต่อ"); drop `plex_recently_added` only if nothing uses it.

**Interfaces:**
- Consumes: Task 3 `Item`, `open`, `start`, `stop`; `PlexBackdrop.setCaptionShown`.
- Produces: `PlexContinue.setLarge(boolean)`, `Host.onItemFocused(Item)`, `PlexBackdrop.showArt(String)`, `PlexBackdrop.resumeSlideshow()`.

- [ ] **Step 1:** Implement the view and backdrop pieces; build debug, exit 0.
- [ ] **Step 2:** Install on the TV. Screenshots: `shots/state2-plex-tile.png` (compact row above the dock, no "Plex" name, caption hidden), `shots/state3-row.png` (large row, 16dp of the dock band visible, scrim, details, backdrop is the focused item's art), `shots/state1-other-tile.png` (YouTube tile: hero name back, row gone). Compare state 3 with `shots/plex-01.png`: posters 245x365 px, header position.
- [ ] **Step 3:** D-pad check with `input keyevent`: Plex tile Up lands in the row; Right/Left move; Down returns to the Plex tile; Up then Up reaches the top bar; BACK from the row returns to the Plex tile. Fix what the screenshots show.
- [ ] **Step 4:** Commit `launcher: Plex Continue Watching row in two sizes, dock slides down, Plex-style details and backdrop`.

### Task 5: Device setting, docs, release size, on-TV open/stop check

**Files:**
- Modify: `examples/sony-kd-49x8500f/debloat.txt` header: a note that the Plex row needs `appops set com.plexapp.android PICTURE_IN_PICTURE ignore` (also Settings > Apps > Plex > Picture-in-picture) so Plex pauses on Home, and that the launcher ends the server session on resume.
- Modify: `launcher/app/src/main/res/values/config.xml` comment above `plex_url`: one line pointing at that note.

- [ ] **Step 1:** Write the notes.
- [ ] **Step 2:** `./gradlew assembleRelease --offline -q`; print the APK size; it must be below 450,560 bytes.
- [ ] **Step 3:** On the TV: OK on a row item with Plex warm plays within about 10 s (`/status/sessions` shows the ratingKey from 192.168.1.7). Press Home: within 5 s the session is gone from `/status/sessions` and the row's progress bar shows the new offset on the next refresh. Force-stop Plex, OK on an item: paywall, then playback after the 20 s resend; screenshot `shots/open-cold-40s.png`.
- [ ] **Step 4:** Commit `KD-49X8500F: Plex PiP app-op note; config.xml pointer`.
