# Changelog — current backlog patch

Patch fingerprint: `cb3a789a…`, 278 commits, 4,306,818 bytes.

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
