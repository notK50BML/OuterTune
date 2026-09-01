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

- **A real database.** See above.
- **Playlists**, of any kind.
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
3. **Visualiser and EQ.** Both are further from free than they look, and for the same reason as
   the player layout: the visualiser reads Media3's audio processor, and the EQ is Android's
   `android.media.audiofx.Equalizer`. Neither exists here. The good news is that `DesktopPlayer`
   already has the PCM in hand frame by frame, which is exactly what a visualiser needs and exactly
   where a software EQ would sit - so both are real work, but on ground that is already prepared.

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
