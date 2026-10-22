# Clean Your TV launcher — home screen design system (branch `local`)

Sony KD-49X8500F, 1920x1080, density 320 (2 px per dp). Values below are dp unless marked. Measured on the
TV from screenshots in `shots/`, not from mock-ups. The user watches from about 3 m with a D-pad remote.

## Direction and feel

Apple TV (tvOS) top shelf over a photo backdrop: black ground, one white ink, no accent except what the
focused app's own artwork brings. Everything that is not the focused thing is quiet and large-spaced. The
signature is the Plex "Continue Watching" row that replaces the app name while the Plex dock tile has
focus and grows to the Plex app's own poster size when focus moves up into it.

## Depth and spacing

- Depth: one strategy, tone steps. The dock band is a darker rounded slab (`DockDecoration`, 26dp radius,
  14dp pad around the tiles, 1dp edge line); the focus highlight is the single floating glass slab. No
  shadows, no borders on tiles.
- Spacing base: 4dp. Section gaps are deliberately larger than in-group gaps (see rhythm below).
- Density decision: in-group gap 14dp (header to posters, poster to poster), group gap 28dp (posters to
  dock band), tile gap 27dp.

## Layout (16:9, 5 columns)

| Element | Value |
|---|---|
| Columns | 5 (`config.xml columns`); 4 would re-centre a 736dp band via grid side padding |
| Tile | 143x80 at 5 columns (design 150x84, `AppAdapter.DESIGN_W_DP/H_DP`); 16:9 |
| Tile gap | 27dp (cell = tile x 1.1 + 12dp) |
| Band width | 848dp edge to edge, same outer margin as the clock (56dp grid padding + 4) |
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
| Plex details title | 28sp medium, white |
| Row header "Continue Watching" | 20sp medium, white, 14dp above the posters |
| Plex episode title | 16sp medium, white |
| Recently Added caption | 16sp regular, `#B3FFFFFF`, right-aligned to the band |
| Summary | 13sp regular, `#D9FFFFFF`, 2 lines, 440dp wide, line spacing +3dp |
| Meta line, cast | 12sp regular, `#B3FFFFFF` / `#99FFFFFF` |

Details block line gaps: title → episode 8, → meta 8, → summary 14, → cast 10.

## Plex Continue Watching row (`PlexContinue`, `plexDetails` in `activity_main.xml`)

- Posters 2:3, corner radius 6dp, gap 14dp, white 1.5dp stroke + scale 1.04 on focus.
- Compact (Plex dock tile focused): 96x144, row bottom 36dp above the dock tiles (28dp air to the band).
- Large (Up into the row): 122x182 (Plex's own size), row bottom 24dp above the screen edge, grid slides
  down so 16dp of the dock band peeks, focused item's art at full opacity under `plex_scrim`
  (`#E6000000` → `#66000000`, 45°), details block top-left at 118dp from the top.
- Progress bar: amber `#E5A00D`, 2dp high, inset 8dp, over a `#66000000` track; hidden at 0 %.
- Meta line: `E3 · 16 ต.ค. 2026 · 54m · TV-MA` — date in the TV's locale (medium style), as Plex does.
- Motion: 260ms for the grow/slide, 160ms fades; snap when animations are off or reduced motion is on;
  the poster list has no item animator (a size switch would otherwise show two sizes).

## Behaviour rules worth keeping

- The row shows only while the Plex dock tile has focus; every path that leaves it (another tile, the
  grid, the footer, the top bar, Home key) hides it. `hideHero` hides the row before its `heroShown`
  early return.
- Down from the top bar goes to the dock tile, never into the row; Up from a non-Plex dock tile goes to
  the top bar.
- Opening an item sends Plex's own `plex://watch/video?uri=server://…` link once, resends once after 20 s
  only if this TV has no video session at all; Home ends the TV's server session with the stopped
  timeline report. Plex's Picture-in-Picture app-op is denied on this TV so Home pauses playback.

## Budget

Release APK gate on this fork: 500 KB (`.github/workflows/ci.yml`, 512,000 bytes); upstream gates at 480.
Current release: about 495 KB.
