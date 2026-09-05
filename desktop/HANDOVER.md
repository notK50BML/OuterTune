# OuterTune desktop — where this is up to

Written for whoever picks this up next, in another session or another account.

## What exists

A Windows desktop client (`:desktop`) that searches YouTube Music, plays songs, and remembers what
you played and liked. It runs with:

```
gradlew :desktop:run
```

Working: search, play, pause/resume, stop, next/previous, seek, shuffle, repeat (off/all/one),
album art, liked songs, recently played, keyboard control (space, arrows, media keys).

**Nothing native is involved in playback.** No VLC, no GStreamer, no embedded browser.

## The four things worth knowing before changing anything

These were each established by running something rather than by reasoning, and two of them
overturned an earlier conclusion I had stated confidently and wrongly.

**1. No browser is needed.** An earlier spike concluded that every player client failed without a
PoToken, so a desktop build would have to embed a browser to run BotGuard. That was wrong. The spike
had never set `YouTube.locale` or `visitorData`, both of which `App.onCreate` sets on Android;
without them YouTube answers `400 INVALID_ARGUMENT` and refuses players with "Video unavailable",
which reads exactly like a token refusal. With them set, four clients hand over direct stream URLs.
See `StackProbe`.

**2. One client is not enough.** googlevideo issues a perfectly well-formed URL and then answers 403
when it is fetched, and which client a given track will serve varies by track. `DesktopPlayer` tries
four in order. The probes missed this entirely because they only ever ran against one hardcoded
video — a real trap, and worth remembering when adding any new probe.

**3. The container was the obstacle, not the codec.** Everything YouTube serves is *fragmented* MP4,
whose samples live in `moof`/`trun` boxes rather than the `moov` sample table an ordinary reader
walks. A plain MP4 reader parses the metadata perfectly and then finds zero frames. mp4parser reads
the fragments, jaad decodes the AAC, both in pure Java. See `FragmentedMp4Probe`.

**4. Correct output can still be described incorrectly.** jaad emits big-endian PCM. The first
version of this wrote a WAV declaring little-endian and it played as static — correct data, lying
header. Frame counts and byte totals cannot catch that, which is why `FragmentedMp4Probe` now
measures whether the signal actually looks like audio (mean step between samples: small for music,
huge for noise). Nothing in `DesktopPlayer` assumes an output format; it all comes from the
decoder's first frame.

## Deliberate trades, not oversights

**A song is fetched in full before it plays.** That costs a second or two on a big track. It buys
two things: once audio starts there is no network left to fail, so a mid-song 403 cannot happen; and
every AAC frame is in memory at a fixed 1024 samples each, so **seeking is arithmetic** rather than a
new range request. Streaming playback would remove the wait and give up both. Do it deliberately or
not at all.

**The library is a text file.** `LibraryStore` keeps recently-played and liked songs as TSV. This is
a placeholder with a narrow surface, not an answer — choosing between Room-KMP and SQLDelight has
consequences for migrations, threading, and how much of the Android data layer can eventually be
shared, and that decision should not be made as a side effect of wanting a recently-played list. It
holds everything in memory and rewrites on every change: fine for hundreds of songs, wrong for
thousands.

**No Compose Multiplatform Gradle plugin.** Its releases track a Kotlin version behind this
project's, so the artifacts are taken directly and the compose compiler plugin `:app` already uses
does the real work. The cost is that Skiko, the native renderer, has to be named explicitly — omit
it and the window opens and immediately dies on a missing `skiko-windows-x64.dll`.

## Size

`gradlew :desktop:sizeReport` prints it. Currently **42.5 MB of jars**, of which 9.6 MB is Skiko —
the floor while the UI is Compose, and per-platform, so a Windows build carries only the Windows
renderer. Shipping the jar keeps it around 40 MB (less minified); bundling a trimmed runtime with
`jlink` adds roughly 40 MB. A full untrimmed JRE is what produces the 100 MB+ builds seen elsewhere,
and is the thing to avoid.

## What is genuinely missing

- **Signing in to YouTube Music.** The only large piece left - see below.
- **Signing in to YouTube Music.** The API layer supports it (`YouTube.cookie`), but acquiring the
  cookie is the problem: on Android that is a WebView login. On desktop it means embedding a browser
  (the thing point 1 says is not otherwise needed), driving the system browser, or pasting a cookie
  by hand. Worth deciding on before starting.
- **The Android player layout.** `Player.kt` is 1754 lines with 108 Android imports, and reaches for
  `LocalPlayerConnection` (MediaController), `rememberPreference` (DataStore), haptics and window
  insets throughout. The liquid/ferrofluid background is `android.graphics.RuntimeShader`, which does
  not exist off Android — Skiko has `RuntimeEffect` and AGSL is SkSL-derived, so the shader itself
  could be ported, but not by copying the file. Recreating the layout is realistic; copying it is
  not.

## The pattern to keep

Every bug in this module was found by someone actually using it, not by the checks that "passed".
Static that decoded to exactly the right byte count. A 403 the probes never saw because they used
one video. A pause that deleted the song because writes to a stopped line do not block on Windows.
Verify the experience, not just the mechanism.

## Where it was heading next

In the order last discussed:

1. **UI** — recreating the Android player's look (see the caveat above about copying it).
2. **Queue refinements** — reordering, save-as-playlist, queue persistence across restarts.
3. ~~**Visualiser and EQ**~~ - both done, see below.

## Storage

`Database.kt` - SQLite through the JDBC driver, with the SQL written out rather than generated. No
Gradle plugin and no code generator, for the same reason this module takes Compose as artifacts: both
pin versions that drift against the Kotlin this project builds with, and neither earns that for a
schema this size. SQLite specifically because the Android side is already SQLite, so a schema and a
query mean the same thing there.

The published sqlite-jdbc jar carries native binaries for eleven platforms. `fatJar` keeps only the
host's, which is the difference between +2.5MB and +14MB.

Things worth not undoing:

- **One connection, one lock.** Several connections mean a write in one is invisible to a read
  already in flight in another, and the failure is a lock timeout under exactly the conditions
  hardest to reproduce. Reentrant, and `transaction` counts its depth so a nested call joins the
  outer one instead of committing it early.
- **`user_version`, not a migrations table.** Already there, atomic with the transaction that sets
  it, and cannot itself need migrating. Each step is written to be safe to re-run, because a
  migration that fails halfway leaves the version unchanged and will be attempted again.
- **An upsert never blanks what it does not know.** A song re-encountered from a sparse source - a
  queue entry, a search result - would otherwise erase a cover a richer source had supplied.
- **Positions are renumbered after a removal.** Leaving a hole reads fine in order, so it looks
  correct, but the next insert is computed from `MAX(position)` and the gap grows.

An existing text-file library is imported once on first run and the files renamed rather than
deleted, so a failed import can be looked at and a second run cannot duplicate anything.

## Playlists

Create, rename, delete, add, remove, reorder - all persisted. Reordering is buttons rather than
drag: drag-to-reorder is what it wants to be and needs its own gesture handling, an animated
placeholder and autoscroll. The buttons reorder correctly today and nothing about them has to be
undone to add dragging later.

`reorderPlaylist` deletes and re-inserts inside one transaction rather than updating positions one
at a time, because the song is part of a unique constraint and the position is not - moving one song
would collide with whatever currently holds its destination.

## The equaliser

`Equalizer.kt` - twelve peaking biquads, applied to the decoded PCM in place immediately before it
is written to the output line. That is not a design preference: Java Sound has no effect chain, so
between the decoder and the write is the only place audio can be shaped at all. It is a real
implementation rather than a binding to something the platform provides, which is what Android's
`audiofx.Equalizer` is.

Applied *before* the visualiser measures the signal, which is the only arrangement that is not
confusing - boosting the bass should move the bass bars.

Three things it would be easy to get wrong, each with a test:

- **Every channel needs its own filters.** A biquad remembers two input and two output samples, so
  running one filter across an interleaved stream feeds left's history into right's output and back.
  That is not a subtle degradation, it is a comb filter smeared across the stereo image. The test
  feeds a tone to one channel only and fails if the silent one comes out loud.
- **Byte order applies on the way out too.** The decoder returns big-endian; reading one way and
  writing the other is not an error, it is noise.
- **Clamp, do not wrap.** A boosted band can exceed full scale, and an integer that overflows goes
  from loudest positive to loudest negative - heard as a crack, not as distortion.

Band centres match the Android app's `EqualizerSettings.DEFAULT_FREQUENCIES` exactly, so a set of
gains means the same thing on both and presets could move between them.

### These two should share a module

`Equalizer.kt`'s biquad and the Android module's `audio/Biquad.kt` are the same filter written twice,
including two pieces of hardening - the NaN clamp and the denormal flush - that were learned there
rather than reasoned about here. They agree by inspection, which is not a way to keep two files in
step.

The reason they are not shared yet: the Android one reaches `EqualizerSettings`, which parses with
`org.json` - built into Android, an extra artifact here, and one that clashes with the platform copy
if added carelessly. The fix is a pure-JVM module holding `Biquad`, `Compressor`, `EqualizerSettings`
and `EqualizerProfile`, keeping their current package names so no import in `:app` changes. Worth
doing deliberately; not worth doing halfway in the middle of something else.

## The visualiser, and the one thing that makes it work

`AudioSpectrum.kt` (FFT, PCM decode, log-spaced bands), `VisualizerTap.kt` (alignment), drawn by
`Visualizer.kt`. 27 tests cover the DSP against signals whose answer is known in advance, because
none of it can be checked by looking: an FFT with a transposed index, a byte order taken the wrong
way round, or a missing sign extension all produce bars that move plausibly in time with the music.

The part worth not undoing is `VisualizerTap`. Audio is written to the output line well before it is
heard - the line buffers close to a second - so a spectrum computed when a block is decoded describes
music that has not reached the speakers. Drawn directly, the bars lead the sound by about a second:
they jump before the kick and settle before the note ends, which reads as a fault rather than as a
decoration. So each spectrum is tagged with the frame position at which it becomes audible, and held
until the line reports having played that far.

This is the same lag `DesktopPlayer` already corrects for when reporting position, by reading the
line's own frame counter instead of counting what it has written. A seek re-anchors both, since
flushing the line discards audio that was written but never played.

Bands are spaced logarithmically and levels are in decibels, because pitch and loudness both are.
Linear bins put six of the seven audible octaves into the top quarter of the display and squash
everything a listener would call the bass into the first bar.

## Is this "a Compose Multiplatform app"? (asked, and worth answering properly)

Effectively yes, minus the Gradle plugin. It uses Compose Multiplatform's own artifacts
(`org.jetbrains.compose.*`) and the Compose compiler plugin — it just does not apply the
`org.jetbrains.compose` Gradle plugin, whose releases track a Kotlin version behind this project's.

What that plugin would add, and what it costs:

- **`compose.desktop` DSL and `packageMsi`/`packageDmg`.** Genuinely useful when packaging, which is
  not done yet.
- **Automatic Skiko selection.** Currently named explicitly; the plugin would pick it per host.
- **A Kotlin version constraint.** This is the reason it was skipped, and the reason to check before
  adopting: if the plugin still lags the project's Kotlin, adopting it means downgrading Kotlin for
  the whole repo — `:app` included — which is not a trade worth making for packaging convenience.

So the honest answer is: adopt it when packaging, *if* its Kotlin support has caught up. It is a
build-config change of a few lines, not a rewrite, and nothing in the source depends on the
difference.

A separate question is whether `:app` and `:desktop` should share a Compose Multiplatform *source
set* so screens are written once. That is a much larger change: `:app`'s composables are wired to
Hilt, Room, DataStore and MediaSession throughout, so sharing them means abstracting all four behind
`expect`/`actual` first. Worth wanting; not worth starting without deciding to do it properly.

## Immediate next step

The player still uses emoji glyphs (▶ ⏸ ⏮ ⏭ 🔀 🔁) for its controls. The intent is to use the same
`Icons.Rounded` set the Android player uses — `PlayArrow`, `Pause`, `SkipNext`, `SkipPrevious`,
`Shuffle`, `Repeat`, `RepeatOne`, `Favorite`/`FavoriteBorder` — via
`org.jetbrains.compose.material:material-icons-extended`. That dependency was added and then removed
again rather than left sitting unused: it is ~11MB for a handful of icons, which matters against a
42MB total, so add it back at the same moment the icons are actually wired up.

## Icons

`OuterTuneIcons` holds the app's own vector paths, lifted verbatim from the Android module's
`res/drawable` vectors, so the desktop controls are the same shapes the phone draws rather than
lookalikes. `material-icons-extended` was tried and dropped: ~11MB for a handful of glyphs that
would have been *less* correct than these. Android's XML vector format cannot be read off Android,
but only the path carries meaning and `PathParser` reads exactly that.

## Running it without Gradle

```
gradlew :desktop:fatJar
java -jar desktop/build/libs/outertune-desktop.jar
```

One jar, ~43MB, needs only a JRE 21. The jar is deliberately not committed - it is build output that
changes with every commit, and git would keep every version of it forever. Attach it to a release
instead.
