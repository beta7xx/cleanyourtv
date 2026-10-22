# Plex Continue Watching row, smaller tiles, reorder button in the footer

Local branch only (`sony-kd-49x8500f`), not for upstream. TV: Sony KD-49X8500F, 1920x1080, density 320 (2 px per dp),
Android TV 9, Plex for Android TV 2026.20.0 (React Native build, `tv.plex.app.MainActivity`), Plex server
on the LAN at config.xml `plex_url`, token in `Settings.Global plex_token`.

## Intent

Bring back the Plex "Continue Watching" row, this time looking like the Plex app's own home: its posters,
its details block, its backdrop. The row belongs to the Plex dock tile: it shows only while that tile is
focused, and grows to Plex's size when focus moves up into it. Opening an item must be simple and must
not flash Plex's errors.

Two smaller changes ride along: all tiles and the dock get smaller, and the reorder button leaves the top
bar for the footer row beside the app library button.

## What Plex looks like (measured from `shots/plex-01.png`)

- Posters are the series poster (`grandparentThumb`; `thumb` for movies), portrait 2:3, 122x182 dp, corner
  radius 6 dp, 14 dp gaps, row starting at x = 63 dp. Seven are visible.
- Focused poster: 1.5 dp white stroke, scaled up about 1.04. Progress bar: amber `#E5A00D`, 2 dp high,
  inset 8 dp from the poster's sides and bottom, over a dark track.
- Row header "Continue Watching", bold, about 18 sp, 14 dp above the posters.
- Backdrop: the focused item's `art`, full screen, tinted dark with a gradient from the bottom-left.
- Details block, top-left, under the clock area: series title (bold, about 26 sp), episode title (bold,
  about 16 sp), a meta line (`E2 · Oct 16, 2026 · 50m · TV-MA`, 12 sp, grey, rating in a bordered pill),
  summary (13 sp, 2 lines, ellipsised), cast (12 sp, grey, first three `Role` tags).

## The three home states

1. **Another dock tile focused.** As today. No Plex row, no details.
2. **Plex dock tile focused.** The hero name "Plex" is replaced by the header "Continue Watching" and a
   compact poster row (88x132 dp, same proportions, same 14 dp gaps, same progress bar) sitting where the
   hero name was, bottom aligned 14 dp above the dock band. The Recently Added slideshow (`PlexBackdrop`)
   keeps running behind; its "เพิ่งเพิ่ม" caption is hidden while the row shows. If the row has no items,
   the hero name "Plex" shows as before.
3. **Focus in the row** (Up from the Plex tile). The row grows to 122x182 dp with the header above it,
   placed so the row's bottom sits about 24 dp above the screen's bottom edge; the dock slides down with
   the grid so that only the top 16 dp of its band peeks above the bottom edge (translationY on the grid,
   glass hidden). The backdrop fades to the focused item's `art` at full opacity under a dark gradient
   scrim, replacing the slideshow while the row has focus; the details block appears top-left under the
   clock. Left/Right move along the row and update backdrop and details; the ends stay put. Down returns
   to the Plex dock tile and everything returns to state 2. Up goes to the top bar (same target as Up
   from the dock). Back behaves like Down. OK opens the item.

Entering and leaving state 3 animate in about 260 ms (fade and translate), or snap when animations are
off or reduced motion is on, like the rest of the launcher.

## Opening an item (tested on the TV, see `shots/test-*.png`)

Plex's own Watch Next writer (classes.dex, `tv.plex.rntvlaunchercontent`) builds exactly
`plex://watch/video?uri=server://<machineIdentifier>/com.plexapp.plugins.library/library/metadata/<ratingKey>`,
so that link is the supported route. Measured behaviour of the bare link:

- Plex already running: plays within about 10 s, no error, including while another video is open, and
  resumes at the server's `viewOffset` (the 37-minute mark was honoured).
- Plex cold: Plex has not yet chosen the local connection and shows its "Remote Watch Pass" paywall
  instead; the same link sent again about 20 s later plays. 8 s after a cold launch is too early.

Therefore `open(item)`:

1. Send the link once (`ACTION_VIEW`, package `com.plexapp.android`).
2. 20 s later, if `/status/sessions` has no session for that `ratingKey` from one of this TV's addresses,
   send the same link once more. This timer is the one network request allowed while Home is paused; a
   Home resume cancels it (the user came back; the next OK finds Plex warm and plays at once).
3. Nothing else: no `plex://home`, no settle polling, no audio focus, no media keys (Plex registers no
   MediaSession and runs no Companion server, so media keys reach nothing).

## Stopping on Home (tested)

Plex's activity declares `supportsPictureInPicture` and the TV advertises `android.software.picture_in_picture`,
so on Home Plex believes it is going into PiP and keeps its `PlayerService` playing, although no PiP window
exists on this set. With the Picture-in-Picture app-op denied
(`adb shell appops set com.plexapp.android PICTURE_IN_PICTURE ignore`, also reachable as Settings > Apps >
Plex > Picture-in-picture), Plex's own BackgroundBehaviour pauses playback on Home and reports `paused`
with the exact offset. This is a one-time device setting, recorded in the KD-49X8500F example notes; the
launcher cannot set it itself.

The launcher then cuts the server session: on every Home resume the row's refresh fetches
`/status/sessions`; for each session whose Player address is one of this TV's addresses it sends the
timeline report `/:/timeline?...&state=stopped&time=<viewOffset>` with that session's client identifier
(the existing `endSession` request). The server drops the session and keeps `viewOffset` as the resume
point, which is what the link resumes at. Audio-focus tricks are dropped: Plex re-takes focus and resumes
as soon as the taker lets go, so they only ever paused it transiently.

## Poster and backdrop bitmaps

Posters (every item the hub returns, 244x364 RGB_565, in a 6 MB LruCache) live for the activity's lifetime,
like today's `PlexContinue.thumbs`, so gliding over the Plex tile never shows grey placeholders. The item
list is refetched on every Home resume, not on every focus. The large-row backdrop is one 960x540 RGB_565
bitmap fetched through the same transcoder path as `PlexBackdrop` and dropped when state 3 ends.

## Smaller tiles

`AppAdapter.DESIGN_W_DP/DESIGN_H_DP` go from 180x101 to 150x84 (same 16:9). Everything derived follows:
tile height, name label offset, corner radius (scaled by width), dock band height, grid padding. Columns
stay 4. The hero/title room constants (`TITLE_ROOM_DP`) stay.

## Reorder button in the footer

`row_app_library.xml` becomes a centred horizontal row of two pills: the app library pill as today and,
when `reorder_button` is on, a reorder pill (`ic_swap_vert`, icon-only style, `contentDescription`
reorder) 10 dp to its right. The top-bar `btnReorder` is removed from the layout and its key and focus
wiring in MainActivity goes with it. Footer key handling: Left/Right between the two pills (ends stay
put), Up calls `onLibraryUp` from either, Down is swallowed. `focusLibrary` and the footer-focus checks keep
targeting `btnLibrary`. Reorder mode itself is unchanged (`toggleReorder`).

## Goals, non-goals, anti-goals

- Goals: the row is indistinguishable in size and style from Plex's own; D-pad behaviour is deterministic;
  opening never shows a Plex error dialog on a warm app; nothing of this runs while the Plex tile is not
  focused (no polling, no bitmaps held).
- Non-goals: other Plex hubs, search, Plex Discover items, multiple servers, a theme override for the row.
- Anti-goals: network requests while Home is in the background; any API that needs privileged permissions
  (the TV provider's Watch Next table needs READ_EPG_DATA, which the launcher cannot hold).

## Constraints

- 1 GB-class TVs: see the bitmap section; nothing is fetched while Home is in the background except the
  single 20 s resend check.
- Release APK must stay under the CI gate (450,560 bytes; 432 KB today). The old `PlexContinue` (563
  lines) is rewritten, not extended: its session-stop, audio-focus and home-settle code goes.
- Strings: Thai (`values-th`) and English for the header and for the meta line's separators.

## Left to the builder

- Exact dp of the gradient scrim and the details block's position under the clock (match the screenshot).
- Whether the compact row scales the large row's views (one RecyclerView, two sizes) or uses two
  adapters; one view with a size switch is preferred.
- Unit tests: the XML parser for the new fields (poster, art, summary, cast, meta) and the
  "resend once if no session" decision, both as plain JVM tests like `PlexContinueTest`.
