# Desktop vs Android — what is here, what is not, and what the plan is

Written by walking `app/src/main/java/com/dd3boh/outertune/ui/` screen by screen against
`desktop/src/main/kotlin/`. The point is not to claim the desktop should have everything the phone
has — some of it genuinely does not belong here — but to make sure nothing is missing by accident.

Status column: **done**, **partial**, **planned** (a real intent with a known shape), or **not
planned** (deliberately, with a reason).

---

## The player

| Feature | Status | Notes |
|---|---|---|
| Full-window player, cover + controls | done | Matches `LandscapePlayer`'s arrangement |
| Cover-derived background | done | Gradient rather than Android's `RuntimeShader`, which is not portable |
| Transport: play/pause/next/prev/shuffle/repeat | done | Weighted layout copied from `controlsBlock` |
| Seek bar with times | done | |
| Action row (like, equaliser, overflow) | done | In the title row, as `infoBlock` does it |
| Play/pause shape change | done | Rounded square playing, circle paused |
| Seek ±5s buttons | done | Off by default, as on Android |
| Clickable artist credits | done | Every credit; underline marks ones with a real channel |
| Queue sheet | done | Two states, full-window when open |
| Queue reordering | partial | Buttons, not drag - `QueueEdit` does the arithmetic in play-order positions. The Android queue is a `QueueBoard` with multi-queue support; desktop has one flat queue and multi-queue is not planned |
| Swipe-to-skip on the cover | not planned | A pointer has buttons; there is nothing to swipe with |
| Sleep timer | done | Deadline or end-of-song, polled once a second |
| Lyrics | done | BetterLyrics word timings, falling back to LRCLIB/KuGou lines; offset control, click-to-swap with the cover |
| Player layout editor | not planned | The phone's free-placement editor exists because phone screens vary wildly; a resizable window is a different problem |
| Visualiser | partial | Bars exist and are wired but switched off — they read as noise between the credits and the seek bar. Returns as a setting |
| "Elaborate" GPU visualiser | **planned** | Asked for, deferred deliberately until the rest settles |

## Audio

| Feature | Status | Notes |
|---|---|---|
| Equaliser, 12 bands | done | Same band centres as the phone, so gains mean the same thing |
| Shelf filters | done | Needed for AutoEQ; the phone has them too |
| Response graph | done | Desktop-only — there is room for it here |
| Presets | done | 16, against the phone's smaller set |
| Saved custom profiles | **planned** | `EqualizerSettings`/`EqualizerProfile` exist on the phone with save/load/delete; desktop has presets only |
| AutoEQ | done | Same parser as the phone |
| Compressor | done | Full five controls plus a gain-reduction meter the phone does not have |
| Tempo / pitch | done | WSOLA; the phone uses ExoPlayer's own |
| Bass/treble/balance tone knobs | **planned** | `ToneControlsRow` on the phone. Balance needs a per-channel gain stage the desktop pipeline does not have yet |
| Gapless / crossfade | **planned** | The desktop player opens one `SourceDataLine` per track, so there is a real gap. Needs the next track decoded ahead and the line kept open |
| Audio normalisation | not planned yet | Nothing has asked for it |
| Local file playback | **planned** | Android scans folders and plays local files. The desktop decodes AAC from YouTube only — a local-file path needs a decoder per format |

## Library and browsing

| Feature | Status | Notes |
|---|---|---|
| Search (YouTube) | done | |
| Liked songs | done | |
| Recently played | done | |
| Playlists (create/rename/delete/reorder/add/remove) | done | Local, plus the account's own saved YouTube playlists loaded alongside the home feed |
| Artist page | partial | Library songs plus remote songs. No albums tab, no "see all" |
| Album page | done | Opens from search, home, and the overflow menu |
| Home feed | done | Loads signed-in or signed-out; reloads on account change and on retry |
| Explore / mood and genres | planned | Follows the home feed; same API surface |
| History | **planned** | The database already records plays; this is mostly a screen |
| Stats | planned | Depends on history |
| Library albums / artists screens | planned | Needs a dedicated library-browse screen; album pages themselves exist |
| Local files and folders | planned | See local file playback |
| Auto playlists (liked, downloaded, etc.) | partial | Liked exists as a section, not as a playlist |
| Online playlists | done | The account's saved playlists load and play; a local playlist is still local-only |
| Backup import (from the phone) | done | Merges the phone's zip into this library - songs, likes, playlists. Safe to re-run |
| Downloads | done | A download manager, a cache directory under the library, and a "downloaded" state the player consults before the network |
| Song / album / playlist context menus | partial | The player has one; list rows do not |
| Multi-select | planned | |
| Sync with a YouTube account | done | Feeds the home feed, saved playlists, and library-song lookups |
| Discord rich presence | done | `:kizzy` ported as-is - same gateway client the phone uses, no Android dependency in it to route around |
| Recognition (identify a song) | not planned | Needs a microphone pipeline and a matching service |

## Settings

One scrolling page (`SettingsPane.kt`) rather than the phone's tree of sub-screens - see
`HANDOVER.md` for why a desktop window earns that instead.

Every row that exists today carries the same icon the phone shows next to it - `OuterTuneIcons` grew
a "Settings rows" section for this, sourced from Google's published Material Symbols Outlined set
(matching this file's existing family) rather than guessed at. The "Status" column below still says
what it always said: an icon was added to what already exists, not a row invented to have somewhere
to put an icon. Several rows the phone has - audio quality, crossfade, content language/country,
sync mode and conflict resolution, proxy settings - have no desktop equivalent yet because the
*feature* behind them doesn't exist here, and a settings row with nothing to control would be a lie
rather than parity.

| Android screen | Status | Notes |
|---|---|---|
| Appearance (theme, dynamic colour, dark mode) | done | Light/dark/system, dynamic colour from the cover, and the four background styles below |
| Player settings | partial | Seek buttons and the visualiser toggle exist; most of the rest has no equivalent yet |
| Lyrics settings | done | Word-by-word toggle, click-to-swap, offset dial, clear cache |
| Equaliser settings | done | In the equaliser panel itself, not this screen - presets, AutoEQ, saved profiles still planned |
| Library settings | partial | History toggle and the backup importer; no per-source controls yet |
| Storage / cache | done | Download count, total size, delete-all |
| Privacy | not planned | Nothing here collects anything to have a privacy screen about |
| Backup and restore | partial | Importing a phone backup is done; nothing exports one yet |
| Account sync | done | Feeds the home feed, saved playlists, and library-song lookups |
| Listen Together | done | Same protocol as the phone, byte-for-byte - `Protocol` through `FollowerSession` are ported verbatim. `LanDiscovery` uses jmDNS instead of `NsdManager`; a phone and this build can host or follow each other directly |
| Discord rich presence | done | Token paste, test connection, enable switch, and the logged-in account name |
| App icon / updater / about | mostly not applicable | |
| Setup wizard | not planned | |

---

## What I would do next, in order

The home feed, downloads, albums, lyrics, settings, Discord rich presence, and Listen Together are
all done now. This is what is left.

1. **Queue reordering by drag**, rather than buttons - the arithmetic in `QueueEdit` does not change.
2. **Gapless playback** - the player opens one `SourceDataLine` per track today.
3. **Saved EQ profiles**, **tone knobs**, **local file playback** - each small and self-contained.
4. **Library albums/artists browse screens**, **history**, **multi-select** - round out browsing.
5. **The elaborate GPU visualiser** — deliberately last.

## Two things worth flagging

**The shared-module problem is getting worse.** `Biquad`, `Compressor` and now the AutoEQ parser are
each written twice, once in `:app` and once in `:desktop`, agreeing by inspection. Three files is
past the point where that is acceptable. The fix is a pure-JVM module holding them with their current
package names, so no import in `:app` changes. See `HANDOVER.md`.

**The value-colour direction disagrees between the two.** The desktop runs yellow at the low end to
blue at the high end. The phone's `valueGradientColor` runs blue to yellow. The desktop follows what
was asked for; flipping the phone to match is one line in that function.
