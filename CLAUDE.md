# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

Debloat Android TVs over ADB (no root, nothing uninstalled, every step reversible) and replace the stock
home with a small native launcher. Three parts: `PROMPT.md` (what end users paste into an assistant),
`examples/<brand>-<model>/debloat.txt` lists with `tools/` scripts that apply and undo them, and the
launcher in `launcher/`. Branch `sony-kd-49x8500f` is this fork's working branch; `upstream` is Gathin23/cleanyourtv.
Branch `sony-kd-49x8500f` also carries the Plex Continue Watching row, which upstream does not have.

## Commands

Launcher (needs JDK 17, Android SDK platform 34, build-tools 34.0.0; run from `launcher/`):

```bash
./gradlew testDebugUnitTest --offline -q                 # all JVM unit tests (plain JUnit 4, no device)
./gradlew testDebugUnitTest --offline -q --tests '*PlexContinueTest*'   # one test class
./gradlew assembleDebug --offline -q                     # debug APK
./gradlew testReleaseUnitTest lintRelease assembleRelease  # what CI runs
```

Release is R8-minified and signed with the debug key, so it installs over a debug build. CI fails a
release APK over the size gate in `.github/workflows/ci.yml` (500 KB on this fork, 480 KB upstream; this fork's release is about 500.4 KB, 500,407 B); the
launcher targets 1 GB TVs and sits within a few KB of the gate, so check the size after any addition:
`stat -c %s app/build/outputs/apk/release/app-release.apk`.

Repo lists, tools and docs (from the repo root, Python 3.12, `tools/requirements.txt` in `tools/venv`):

```bash
python3 -m unittest discover -s tests -v                 # examples/ tables, tool scripts, docs consistency
python3 -m unittest tests.test_examples -v               # one module
CLEANYOURTV_STRICT=1 python3 -m unittest discover -s tests  # also checks README counts (CI does this on push to main)
```

On the TV (set `TV_HOST=<ip>`; `tools/tv.py` is an adb-shell client in Python, no adb binary needed):

```bash
tools/tv.py --probe                                      # model, Android version, firmware
tools/tv.py 'input keyevent KEYCODE_DPAD_DOWN'           # inject a remote key
tools/tv.py --screenshot shots/after.png                 # capture on the TV and pull it
TV_HOST=<ip> launcher/build-and-install.sh               # build, push, install, set as Home
tools/theme.sh themes/aurora | tools/theme.sh --reset    # push a theme overlay / back to config.xml
tools/disable.sh examples/<model>/debloat.txt            # apply a list; tools/restore.sh undoes it
tools/check.py examples/<model>/debloat.txt              # what a firmware update re-enabled
```

In Git Bash on Windows, device paths given to adb need `MSYS_NO_PATHCONV=1` or they get rewritten. Run the
tools with the venv's interpreter (`tools/venv/Scripts/python.exe tools/tv.py ...`): the system Python lacks
`adb_shell`. On Windows the Python suite has about 100 failures that are not real (the shell-script tests in
`test_tvinput_guard`, `test_debloat_paths`, `test_adb_exposure`, `test_setupwraith_guard` and others, which flip
between FAIL and ERROR run to run) plus one in `test_examples` (the Sony row differs between the two README
tables); compare against `git stash` before blaming a change. Python scripts that edit repo files must write
bytes (`open(p, 'wb')`): text mode on Windows turns the files' LF endings into CRLF.
Verify launcher UI changes on the real TV with a screenshot; the emulator is not enough and CONTRIBUTING
asks for a screenshot in launcher PRs.

## Launcher architecture

All Java under `launcher/app/src/main/java/com/gathin/tvhome/`, no Kotlin, no Compose, no network
except the optional Plex row. The constraints that shape every decision: 1 GB of RAM, deterministic
D-pad navigation, nothing TV-specific in code.

- **Configuration layering.** `res/values/config.xml` holds every per-TV choice (dock apps, columns,
  Live TV package, key services, Plex URL). `Config.load()` overlays `config.json` from the app's
  external files dir (pushed by `tools/theme.sh`, see `themes/README.md` for the keys); unknown or
  mistyped keys are ignored so a theme can never break Home. On-TV choices from Launcher settings sit
  above both until "Follow theme". `ThemeProvider` is a ContentProvider that lets the adb shell write
  theme files on firmware that locks Android/data.
- **MainActivity** (large, ~3500 lines) owns the home screen: one RecyclerView `grid` whose first
  `COLUMNS` positions are the dock (drawn as a band by `DockDecoration`), the rest the app grid, and an
  optional footer row (`row_app_library.xml`: app library pill and reorder pill). Above the grid in the
  root FrameLayout sit the backdrop layers (sky, wallpaper, heroArt, ambient glow), the top shelf
  (`hero`: the focused dock app's name), and the single floating `GlassView` focus highlight that
  springs between tiles. The dock is "parked" by setting the grid's top padding so it sits near the
  bottom; moving Down scrolls the grid. When the grid's height changes after start-up, the new top padding is
  applied posted and `refitGlass(true)` re-parks the dock; nothing in the header may change its height at
  runtime, or the grid moves (hence the floating input pills below). Up/Down/Left/Right are handled explicitly in
  `onTileVerticalKey` and the pill key listeners, not left to Android's focus finder.
- **AppAdapter** builds the item list: configured dock apps first, then module cards, then the grid
  alphabetically, with `fill_dock` filling free dock slots from the grid or `DockSlots` inserting
  EMPTY entries. Tile geometry is derived from `DESIGN_W_DP/H_DP` and the column count
  (`tileWidthPx`); `TileFitTest` pins it. When the dock has fewer apps than slots the dock row is
  translated to centre it (`dockShift`), and the band and glass add that translation. The dock has exactly
  `columns` slots; this fork runs 4 columns (150dp tiles in a centred 736dp band) for its four dock apps.
- **Plex (this fork).** `PlexBackdrop` shows the server's Recently Added art as a dimmed slideshow.
  `PlexContinue` is the Continue Watching row: it owns the data (hub fetch, SAX parsing into `Item`,
  opening via Plex's own `plex://watch/video?uri=server://...` link with one resend, ending this TV's
  server sessions on Home via the stopped timeline report) and the poster row view in two sizes with a
  tab strip of hubs (Continue Watching plus a Recently Added tab per `plex_sections` library, from Plex's
  own `/hubs/promoted`; an empty library tab keeps the strip and focus, only an emptied Continue Watching sends
  focus back to the dock). Posters load on a two-thread pool, one request per poster; `PlexBackdrop` holds the
  shared HTTP helpers (`open`, `transcode`, `sectionKeys`). MainActivity decides when it shows: only while the Plex dock tile has focus,
  replacing the hero name; Up grows it and slides the grid down (`enterPlexLarge`/`leavePlexLarge`). The row's
  left edge is pinned 11dp inside the clock's column (`plexEdge`), not to the band, so the column count does not
  move it. Server URL and token
  are on the TV, never in the repo: config.xml `plex_url`, else `Settings.Global plex_url`, else the first server
  that answers Plex's LAN discovery (`PlexGdm`, retried on each resume until one answers, then `startPlex`
  builds both views late); the token is `Settings.Global plex_token`, and without it nothing is fetched. The device needs
  Plex's Picture-in-Picture app-op denied so Home pauses playback (noted in the KD-49X8500F example).
- **Focus (this fork).** One floating `GlassView` marks every focus on Home. Tiles place it from
  `onTileFocused`; any other focusable view opts in with `MainActivity.glassTarget(view, scale, radiusPx)`
  and the global focus listener calls `glassTo`, which reads the view's window position (not
  `offsetDescendantRectToMyCoords`, which subtracts a TextView's internal scroll) and undoes its current
  focus scale. Rows inside popup windows (Inputs menu, module cards) draw the same glass themselves via
  `input_row_bg`. No view keeps a white fill or dark text on focus.
- **Inputs (this fork).** The Inputs menu shows a connection dot per HDMI port, and `updateInputTiles` adds
  one pill under the gear per input with something plugged in (Down from the gear walks them). The pills float
  on root (`makeInputTiles`), placed under `topButtons` in code, not inside the header: in the header each pill
  made it taller and plugging a cable moved the whole grid. A rebuild is skipped when the inputs and labels are
  unchanged, and focus returns to the same input after one. The state
  comes from `TvInputManager`, except on TVs whose ports all claim connected (`hdmiStateBogus`): there a
  CEC device on the port counts, and on Sony BRAVIA the real cable state is read from Sony's exported
  provider `content://com.sony.dtv.tvinput.external.provider/sony_external_input` (`connect_state`),
  watched with a content observer. Found by reading Sony's ExternalInputServices APK; see
  `sonyConnectStates` in MainActivity.
- **Services.** `InputKeyService` and `HomeKeyService` are accessibility services that remap remote
  buttons the stock launcher used to answer; `ClockDream` is the screensaver; `Cards` implements the
  module-card contract in `docs/specs/card.md` (apps declaring their own tile, approved per signing key).

Design values for the home screen (tile sizes, gaps, type scale, Plex row geometry) are recorded in
`.interface-design/system.md`; keep changes consistent with it or update it. Vendor APKs pulled from the
TV for reading (Plex, Sony) go in the scratchpad, never in the repo.

## Syncing this fork with upstream

```bash
git fetch upstream main && git branch -f main upstream/main && git push origin main   # fork's main mirrors upstream
git checkout sony-kd-49x8500f && git merge main                                                # bring upstream into local
```

Expect conflicts in `MainActivity.java`, `config.xml` and the two `strings.xml` files, where the Plex row
lives; keep both sides and keep this fork's `reorder_button`/`ambient_glow` values. GitHub's web merge
cannot resolve these, so merge locally and push `sony-kd-49x8500f`. The branch's history was rewritten once to
remove a LAN address from `config.xml`; any future rewrite must exclude upstream's commits
(`git filter-branch ... -- sony-kd-49x8500f ^<first fork commit>^ ^upstream/main`), or their hashes change and
every later merge from upstream conflicts. In a filter-branch filter, `exit` ends filter-branch itself. The fork's design and plan documents are in
`docs/superpowers/specs/` and `docs/superpowers/plans/`; `shots/` holds TV screenshots and is not committed.

## Conventions worth knowing

- Debloat lists disable only (`pm disable-user --user 0`), never uninstall; keep headers with the exact
  model, Android version and firmware; add a row to `examples/README.md` and the README model table
  (tests check both tables agree with the folders).
- Unit tests are plain JVM: the Plex parsers use JDK SAX rather than XmlPullParser for that reason.
  Anything touching Android views is verified on the TV, not unit-tested.
- Comments in the launcher explain the why and the TV that motivated a quirk (often with an issue
  number); keep that style.

The guide for every coding agent in this repo is AGENTS.md, imported below.

@AGENTS.md
