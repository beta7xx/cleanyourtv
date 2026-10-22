# Clean Your TV launcher — home screen design system (branch `sony-kd-49x8500f`)

Sony KD-49X8500F, 1920x1080, density 320 (2 px per dp). Values below are dp unless marked. Measured on the
TV from screenshots in `shots/`, not from mock-ups. The user watches from about 3 m with a D-pad remote.

## Direction and feel

Apple TV (tvOS) top shelf over a photo backdrop: black ground, one white ink, no accent except what the
focused app's own artwork brings. Everything that is not the focused thing is quiet and large-spaced. The
signature is the Plex "Continue Watching" row that replaces the app name while the Plex dock tile has
focus and grows to the Plex app's own poster size when focus moves up into it.

## Depth and spacing

- Focus: one highlight for the whole screen. The floating glass slab (`GlassView`, springs on X/Y) marks
  every focused thing: tiles, the top and input pills, the footer pills, the Plex posters and hub tabs. A view
  joins it with `MainActivity.glassTarget(view, focusScale, radiusPx)` (pills 1.0 or 1.04 / 21dp, posters
  1.04 / 6dp). No view draws its own focus fill, stroke or dark-text state; the glass is the focus.
- Depth: one strategy, frosted glass. The dock band is the pills' glass (`dock_glass`: white 14% fading to
  5%, 1dp edge at 22% white, 26dp radius, 14dp pad around the tiles); the focus highlight is the single
  floating glass slab. No shadows, no borders on tiles.
- Spacing base: 4dp. Section gaps are deliberately larger than in-group gaps (see rhythm below).
- Density decision: in-group gap 14dp (header to posters, poster to poster), group gap 28dp (posters to
  dock band), tile gap 27dp.

## Layout (16:9, 4 columns)

| Element | Value |
|---|---|
| Columns | 4 (`config.xml columns`), the dock's four apps with no slot filled from the grid; the 736dp band is centred by the grid's side padding (5 columns fill 848dp edge to edge) |
| Tile | 150x84 at 4 columns (`AppAdapter.DESIGN_W_DP/H_DP`; 143x80 at 5); 16:9 |
| Tile gap | 27dp (cell = tile x 1.1 + 12dp) |
| Band width | 736dp at 4 columns, centred (848dp edge to edge at 5, on the clock's margin) |
| Dock bottom | 24dp of band above the screen's bottom edge (`dockNameRoom` 44dp: the dock's unused name room is let below the bottom room; `BOTTOM_ROOM_DP` stays 30 for themes) |
| Dock with fewer apps than slots | tiles translate by half the free slots' width (`AppAdapter.dockShift`); band and glass follow; the footer row never shifts |
| Shelf inset | hero name, Plex row header and posters, details block and the Recently Added caption all start 12dp inside the band's edge (`alignShelf`) |
| Footer | library pill + reorder pill (icon-only), centred, 10dp apart, 84dp row |

## Type scale (Outfit)

Three visible tiers; weight and opacity do the rest.

| Role | Size / weight / colour |
|---|---|
| Clock | 52sp light |
| Hero app name | 40sp light, white (was 48 before the smaller tiles) |
| Plex hub tabs | 20sp medium; current tab white, others `#80FFFFFF`; 14dp above the posters |
| Poster caption: series | 14sp medium, white, 6dp under the poster |
| Poster caption: episode title, "S1 · E4" | 12sp regular, `#99FFFFFF` |
| Recently Added caption | 16sp regular, `#B3FFFFFF`, right-aligned to the band |

## Plex row (`PlexContinue`)

- Posters 2:3, corner radius 6dp, gap 14dp, scale 1.04 on focus; the glass is the focus (no stroke).
- Compact (Plex dock tile focused): header "ดูต่อ" only, 96x144 posters, no captions, row bottom 36dp above
  the dock tiles (28dp air to the band).
- Large (Up into the row): a tab strip (Continue Watching, then "เพิ่มล่าสุดใน <library>" per `plex_sections`
  entry, from Plex's `/hubs/promoted`), 122x182 posters (Plex's own size) with a 62dp caption area under
  each (series / episode title / "S1 · E4", as Plex), the same height on every tab so the strip never
  moves; row bottom 24dp above the screen edge; the grid slides down so 16dp of the dock band peeks; the
  focused item's art at full opacity under `plex_scrim` (`#E6000000` → `#66000000`, 45°). No details
  block above the row.
- Progress bar: amber `#E5A00D`, 2dp high, inset 8dp, over a `#66000000` track; hidden at 0 %.
- Motion: 260ms for the grow/slide, 160ms fades; snap when animations are off or reduced motion is on;
  the poster list has no item animator (a size switch would otherwise show two sizes).

## Behaviour rules worth keeping

- The row shows only while the Plex dock tile has focus; every path that leaves it (another tile, the
  grid, the footer, the top bar, Home key) hides it. `hideHero` hides the row before its `heroShown`
  early return.
- Down from the top bar goes to the dock tile, never into the row; Up from a non-Plex dock tile goes to
  the top bar.
- Up from a poster goes to the current tab; Left/Right on the tabs switch hubs and reload; Down returns to
  the posters; Up from a tab leaves to the first top pill (Inputs). Back anywhere in the row returns to the
  Plex dock tile.
- Opening an item sends Plex's own `plex://watch/video?uri=server://…` link once, resends once after 20 s
  only if this TV has no video session at all; Home ends the TV's server session with the stopped
  timeline report. Plex's Picture-in-Picture app-op is denied on this TV so Home pauses playback.

## Budget

Release APK gate on this fork: 500 KB (`.github/workflows/ci.yml`, 512,000 bytes); upstream gates at 480.
Current release: about 499 KB.
