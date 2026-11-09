# Changelog — current backlog patch

Patch fingerprint: `cb3a789a…`, 278 commits, 4,306,818 bytes.

## Fixes (9 Nov 2026)

- **The visualiser reacts to the music again.** Every Liquid shape drew, but none of them moved
  with the audio. The player background is drawn in three places at once (the player sheet, the
  equaliser panel, the player screen), and each one switched the audio analysis off as it closed,
  so opening and closing the equaliser stopped it under the player that was still showing. The
  processor now counts who is using it and only stops when nobody is.
- **Player ⋮ menu icons no longer shrink to dots.** On low-density screens, or with a larger system
  font, a two-line label took almost all of the tile's fixed 96dp and the icon got what was left.
  The icon now has its own fixed slot and the tile grows instead.
- **Music videos show as 16:9 in the player** instead of being cropped to a square, on Android and
  Windows. Android also checks the loaded image's shape, so a downloaded video thumbnail is
  caught too, and a 4:3 frame with baked-in black bars loses the bars.
- **Equaliser handle:**
  - Pulling it no longer stalls and then jumps. The panel is now built when your finger touches
    the handle, not on the first frame of the drag.
  - Letting go settles by how far you pulled. Before, any release faster than about 90dp a second
    counted as a flick and opened the whole panel.
  - The close handle at the top of the panel now follows your finger too, instead of waiting for
    40dp and then playing a canned animation.
- **Equaliser panel closes off the top edge on low-density screens.** Its travel came from the
  configured screen height, which can be shorter than the space the panel fills, so it slid up,
  stopped short and vanished. It now uses its measured height.
- **Explicit tags stick.** Only rows built straight from a YouTube response could show the badge,
  so the same song was tagged in search but not in the library, a saved album or the queue.
  Songs now remember it: a new database column (version 27, one-line migration, nothing else
  touched) that is set whenever any source reports the song as explicit and never cleared by a
  source that simply carries no badges. Online rows also show the badge when only the library
  knows it.
- **Discord presence no longer sticks on the previous song.**
  - Each update used to register the cover and the artist picture with Discord before sending
    anything, one after the other, each allowed 30 seconds, and the card showed the old song
    until both had finished. The title and artist now go out straight away, and the pictures
    follow once they're ready.
  - Registered images are kept across the periodic connection rebuild instead of thrown away.
  - Two updates close together could each open a gateway connection, and the orphaned one could
    keep showing an old song. Only one connection can exist now.
- **Notification artwork for local and downloaded songs.** The media session was only ever given
  the remote thumbnail, so a song from your own storage had no picture at all and the system
  media card (HyperOS especially) fell back to the app icon. It now gets the saved full-size
  cover, then the remote art, then the cover embedded in the audio file. Saved covers under
  /data/ also no longer fail to load and turn into the placeholder.
- **Home-screen widget (new).** Cover, title, artist, previous / play-pause / next. Plain Android
  widget, no new libraries; updated by the player when the song or play state changes, never
  polled. The buttons work when the app is closed, and play resumes the saved queue.
- **AutoEQ fills in your headphones.** Opening the AutoEQ search pre-fills the connected Bluetooth
  or USB headset's name, read from Android's audio outputs. This needs no Bluetooth permission.
  Wired 3.5mm headphones can't be identified, so they're skipped.

## Large fonts and small screens

A pass for the same kind of bug as the ⋮ menu: fixed-height boxes holding text, which a larger
system font (the default on HyperOS and One UI) overflows.

- **Song, album and artist rows** are at least 64dp rather than exactly 64dp, so the subtitle is no
  longer clipped off the bottom with a large font.
- **Home's Recent activity cards** can't grow (fixed grid), so their titles drop to one line when
  the font is more than 15% larger, instead of showing half of a second line.
- **Sleep timer buttons** and the player's sleep-timer pill grow with the font instead of clipping.
- Checked and fine as they are:
  - the mini player (single-line text)
  - the player's cover, which shrinks on short screens
  - the tablet breakpoints and the artist header, which size from the screen

## Widget editor and pre-update backups (after build 180)

- **Arrange the widget** in Settings → Appearance → Home-screen widget.
  - Pieces: cover, artist picture, title and artist, up next, seek bar, previous, play/pause,
    next, shuffle and repeat. Show any of them, in any order, across up to three rows.
  - Pieces sit side by side in rows and never overlap. Title, seek bar and up next stretch to
    fill their row; the others keep their size.
  - The editor has a live preview, and the widget on your home screen updates as you go.
  - Shuffle and repeat use the same commands as the notification's buttons.
- **Backup before installing an update** (on by default; Settings → Updater).
  - Just before the installer opens, the library and settings are saved to
    Downloads/OuterTune Backup.
  - The file is named after the version you're updating from, and the last three are kept,
    separately from automatic backups.
  - It never blocks the update.

## More (9 Nov 2026, after 0.22.1 build 179)

- **Listen together measures the delay for you.** On the host, tap a listener. The host's
  microphone hears a click from its own speaker, then one from the listener 1.5 seconds later on
  the shared clock. The gap minus 1.5 seconds is how far behind the listener's speakers are, and
  it's sent back and applied as their timing offset. The microphone's own delay is in both clicks,
  so it cancels out. Microphone permission is asked only when you tap. The offset description now
  explains why the offset is needed, and "How it works" is gone.
- **Refetch all lyrics keeps your lyrics.** It now marks lyrics as due for a re-check instead of
  deleting them. Each song keeps what it has, offline included, until a provider actually returns
  replacements. A re-check also never swaps lyrics you have for "not found".
- **Thumbnails:** "Download all thumbnails" moved to Experimental and debug, and "Remove
  thumbnail" is gone from the song menu.
- **Widget follows the player's look:**
  - The background matches your player setting: blurred or frosted cover, cover gradient, a
    still of the Liquid shape (petal, spheres or ferrofluid crown on its backdrop), or Material
    You colours.
  - A seek bar in your slider style and thickness, updated every 2 seconds while playing.
- **Search tab (new, optional).** Add Search in the tab settings. With it on, the search bar
  across the top of the other tabs goes, Stats / Settings / Account move to the right end of
  Home's chips row, and tapping the tab opens search.

## New defaults (9 Nov 2026)

What a fresh install looks like. Anything already set by hand keeps its value.

- Seek bar: wave lines, thinner, no position dot.
- Player background: Frosted. Material (dynamic) theming stays on.
- Tabs: Home, Library, Playlists.
- Search bar: no app logo; Identify song sits in the search field.
- Swipe to queue on (unchanged). Settings descriptions hidden.
- "Match text to the artwork" is renamed **Auto contrast** (still on).
- Lyrics:
  - Providers are asked in order: BetterLyrics, QQ Music, LrcLib, Kugou, SimpMusic, then
    YouTube. "In my order" is now the default fetch mode, so the order is honoured.
  - Left-aligned, word-by-word and estimated word timings on.
  - Singer colour-coding off.
  - Stored lyrics are re-checked every two weeks.
- Equaliser: colour by value on.

## Listen together (new)

Play the same song, at the same moment, on several devices on one Wi-Fi.
Settings → Listen together. One device shares; the others see it in a list and
join.

**Nothing leaves your network, and no audio is streamed between devices.** Each
device plays the song itself and keeps time with the host — so a follower needs
its own copy or its own connection, but a downloaded song starts instantly and
quality is whatever that device would normally get.

- The list shows **hosts, not devices**. Scanning the subnet and probing each
  address for OuterTune would be slow, would look like a port scan, and is
  largely blocked on modern Android anyway. Browsing for a service inverts it:
  only devices actually sharing ever answer, so "is OuterTune installed" is
  answered by the device appearing in the list at all. Nothing that cannot be
  joined is ever shown.
- **Drift is corrected without anything audible.** Under 30ms nothing happens —
  two sources that close are heard as one. Up to 250ms the follower's playback
  rate is nudged by a few percent, which Media3 time-stretches, so tempo moves
  and pitch does not. Only past that does it seek, because by then the gap is an
  obvious echo and nudging would take longer than tolerating it.
- Clock offset between devices uses NTP's algorithm, taking the **fastest**
  exchange rather than the average — path asymmetry is the one error the formula
  cannot see through, and it grows with delay.
- The host is unaffected by its followers. It never waits for one, slows for
  one, or is seeked by one, so one bad connection cannot degrade anyone else.
- Pausing, resuming, seeking and changing song all follow.
- If the host plays a file from its own storage, followers say so rather than
  failing repeatedly at something that cannot work.

68 unit tests, including a real host and follower talking over a socket and
landing within 150ms of each other.

## Player background

- **The ferrofluid renderer now models the real thing.** It was a ring of spheres
  orbiting each other — the generic metaball look. Real ferrofluid in a magnetic
  field undergoes the *Rosensweig instability*: past a critical field strength the
  flat surface breaks into a regular **hexagonal lattice of peaks**. That's what it
  renders now — a two-ring lattice (1 + 6 + 12) of cusped peaks rising out of a
  shallow pool, with bass playing the part of field strength, so quiet leaves the
  surface near-flat and loud drives the peaks up.
- Peaks are an exact cone SDF rather than spheres, so they have a broad foot and a
  near-point tip. Blending spheres could only ever give mounds.
- Rebuilt material: two sharp highlights, a Schlick Fresnel edge, and a reflected
  vertical gradient standing in for the room. The body is tinted with the theme
  colour rather than left physically black, so it has something to contrast against.
- **New ULTRA quality tier**, and the existing tiers now also control lattice size
  and frame rate.
- Frame rate is capped per tier (24/30/60/60). Previously the full per-pixel
  raymarch re-ran on every vsync, so a 120 Hz phone paid double the GPU of a 60 Hz
  one for an effect that reads identically either way.

## Downloads

- Parallel downloads raised 3 → 5. Affects queues only; a single song is unchanged.

## Queue

- **Swipe-to-remove is now undoable.** It was silent and irreversible. It now says
  what was removed and offers Undo, restoring the song to its original position —
  and to the queue it came from, even if you've switched queues since.

## Artists

- `MPLAUC…` and `UC…` are now treated as the one artist they are, including merging
  the duplicate artist *rows*, not just the credits pointing at them. This is what
  was causing linked artists to revert and duplicates to reappear after each sync.
- A bare `"- Topic"` channel can no longer overwrite an artist name.
- Credits pointing at an empty Topic channel are repointed to the real artist.
- Pull-to-refresh now relinks all artists.

## Library

- Playlists that are fully downloaded now show the downloaded pin.
- "View album" from a song no longer redirects to a different album.
- Artist header uses a fixed square with a crop, fixing the placement.

## Other

- Stats can count plays from this account's other devices (Settings → Player).
- EQ bands are coloured by value; the contrast switch is gone; UK spelling.
