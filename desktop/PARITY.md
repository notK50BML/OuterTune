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
| Queue reordering (drag to reorder) | **planned** | The Android queue is a `QueueBoard` with multi-queue support; desktop has one flat queue. Reordering first, multi-queue probably never |
| Swipe-to-skip on the cover | not planned | A pointer has buttons; there is nothing to swipe with |
| Sleep timer | **planned** | Small: a coroutine that pauses at a deadline, plus the button Android shows first in its action row |
| Lyrics | **planned — next** | Explicitly wanted. Needs a lyrics provider, a timed-line view, and the offset control the phone has |
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
| Playlists (create/rename/delete/reorder/add/remove) | done | Local only |
| Artist page | partial | Library songs plus remote songs. No albums tab, no "see all" |
| Album page | **planned** | Nothing on the desktop opens an album. This blocks "View album" in the overflow menu |
| Home feed | **planned** | The biggest single gap. Sign-in works and then the session sits unused — no home, no recommendations, no continue-listening |
| Explore / mood and genres | planned | Follows the home feed; same API surface |
| History | **planned** | The database already records plays; this is mostly a screen |
| Stats | planned | Depends on history |
| Library albums / artists screens | planned | Needs album support first |
| Local files and folders | planned | See local file playback |
| Auto playlists (liked, downloaded, etc.) | partial | Liked exists as a section, not as a playlist |
| Online playlists | planned | Needs the signed-in session used |
| Downloads | **planned** | Blocks the download button, which was asked for beside the like button. Needs a download manager, a cache directory and a "downloaded" state on songs |
| Song / album / playlist context menus | partial | The player has one; list rows do not |
| Multi-select | planned | |
| Sync with a YouTube account | **planned** | Sign-in is done, four ways. Nothing consumes it yet |
| Recognition (identify a song) | not planned | Needs a microphone pipeline and a matching service |

## Settings

The desktop has **no settings screen at all**. Everything is a hardcoded default. This is the second
biggest gap after the home feed, and several things above are waiting on it — the visualiser toggle,
the value-colour toggle, the seek-button toggle.

| Android screen | Desktop plan |
|---|---|
| Appearance (theme, dynamic colour, dark mode) | **planned** — including the frosted-glass and other backgrounds that were asked for |
| Player settings | planned |
| Lyrics settings | with lyrics |
| Equaliser settings | partly in the panel already |
| Library settings | planned |
| Storage / cache | planned, with downloads |
| Privacy | planned |
| Backup and restore | planned — the SQLite file makes this easy |
| Account sync | with the home feed |
| Listen Together | **not yet** — the Android feature is done and works; the desktop has no transport for it. Worth doing, and the protocol is already written and tested |
| Discord rich presence | planned — `:kizzy` is JVM code and should port |
| App icon / updater / about | mostly not applicable |
| Setup wizard | not planned |

---

## What I would do next, in order

1. **Lyrics** — asked for explicitly, and the player has an obvious place for it.
2. **A settings screen** — small in itself and unblocks a half-dozen toggles that are currently
   decisions made on the user's behalf.
3. **Backgrounds and themes** — frosted glass and friends; asked for, and needs settings first.
4. **Downloads** — unblocks the download button and the offline story.
5. **The home feed** — the largest gap, and the thing that makes signing in worth anything.
6. **Albums** — unblocks "View album", library albums, and artist album tabs.
7. **Queue reordering**, **sleep timer**, **gapless** — small, self-contained, each a day.
8. **The elaborate GPU visualiser** — deliberately last.

## Two things worth flagging

**The shared-module problem is getting worse.** `Biquad`, `Compressor` and now the AutoEQ parser are
each written twice, once in `:app` and once in `:desktop`, agreeing by inspection. Three files is
past the point where that is acceptable. The fix is a pure-JVM module holding them with their current
package names, so no import in `:app` changes. See `HANDOVER.md`.

**The value-colour direction disagrees between the two.** The desktop runs yellow at the low end to
blue at the high end. The phone's `valueGradientColor` runs blue to yellow. The desktop follows what
was asked for; flipping the phone to match is one line in that function.
