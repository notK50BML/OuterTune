# OuterTune — sync, artwork, and lyrics fixes

Base: `yuuichi-s/OuterTune` @ `03099c61` (branch `feat/sync-artwork-lyrics`).
Sync-engine architecture and the lenient TTML reader are ported from
`metrolistgroup/metrolist` @ `289ed45d`. Both projects are GPL-3.0.

**This has not been compiled.** The container this was written in has no Android SDK and
no access to Google's Maven or Maven Central, so `assembleDebug` could not be run. Every
symbol the new code touches was checked against the actual sources by hand, but treat the
first build as the real review and expect to fix a stray import or two.

---

## 1. Liked songs now match YouTube's order

**What was wrong.** YouTube Music exposes no per-song "liked at" timestamp — a song's
position in the `LM` playlist is the only ordering signal there is. The old sync reversed
the remote page, stamped `likedDate = LocalDateTime.now()` on each song as it went, and
**skipped any song already liked locally**. So a song's liked date was frozen at whenever
it first happened to be seen, and the local order drifted permanently out of step with
YouTube — which is the bug you hit.

**What it does now.** `likedDate` is derived from each song's index in the unreversed
remote list (`now.minusSeconds(index)`) and re-stamped on every sync, including songs
already liked. Sorting the Liked playlist by liked date (`SongSortType.CREATE_DATE`,
descending — already the default) now reproduces YouTube's order exactly.

Local files that are liked stay liked; they are never in the remote list, so they are
excluded from the unlike pass, and they interleave by their own liked date.

## 2. Metrolist's sync engine, on OuterTune's feature set

`SyncUtils` was rewritten around Metrolist's architecture:

- A single-consumer `Channel<SyncOperation>` queue with per-category **coalescing keys**,
  so two screens asking for the same sync collapse into one.
- One **non-reentrant `Mutex`** serialising every sync body — no more concurrent writers
  to the same tables.
- **Retry with exponential backoff** on every network call.
- A structured **`SyncState`** with a `SyncStatus` per category (`Idle` / `Syncing` /
  `Completed` / `Error(message)`), exposed as `syncUtils.syncState`.

OuterTune-specific behaviour is preserved: `SyncContent` per-category enable flags,
`SyncConflictResolution.OVERWRITE_WITH_REMOTE`, the per-category `Last*SyncKey` cooldowns,
and the `isLocal` exclusions for local media.

The old boolean flows (`isSyncingRemoteLikedSongs` and friends) are kept as values derived
from `syncState`, and every previous suspend method kept its signature, so no caller needed
changing.

### Bugs found while porting

| Bug | Effect |
|---|---|
| The "already syncing" guard was inverted — `if (!syncing && (!enabled \|\| !online))` | A second request proceeded *precisely when* one was already running |
| `SYNC_CD = 60000 * 30` (milliseconds) compared against a delta in **seconds**, comparison also inverted | Cooldown rejected syncs that were due and allowed ones that weren't. Now `SYNC_COOLDOWN = 30 * 60L`; `SYNC_CD` is deprecated |
| `runBlocking` inside `suspend` functions | Blocked sync threads; replaced with structured concurrency |
| `Last*SyncKey` written in `finally` even when the sync bailed or failed | A failed sync suppressed retries for a full cooldown window. Now written only on success |
| `syncPlaylist` cleared the local playlist before writing | An empty remote page wiped a local playlist. Now skipped |
| Playlists synced via `runBlocking { forEach { launch { … } } }` | Hammered the API and interleaved writes. Now sequential with pacing |
| `songMapsToPlaylist(playlistId)` silently bound to the `songMapsToPlaylist(songId: String)` overload | The list was always empty, so the `isNotEmpty()` guard was dead and non-editable playlists with local songs were skipped. Now uses the `(playlistId, from)` overload |
| `withRetry { YouTube.likeVideo(...) }` produced a `Result<Result<Unit>>` | The inner API failure was never observed or retried. `getOrThrow()` moved inside the retry block |

## 3. Album covers are no longer soft — with a toggle

**What was wrong.** `getThumbnailModel()` and `ItemThumbnail` handed remote URLs to Coil
verbatim. YouTube returns whatever size suited the response the URL came from, and browse
and library responses routinely hand back 60–226px art. Coil then upscaled that to fill a
full-width player. Only `SongItem.toMediaMetadata()` (1080px) and artist rows (544px) ever
asked for a bigger image.

**What it does now.** A new `remoteArtwork()` helper rewrites the URL to request the size
the view will actually draw, applied in `ItemThumbnail` (measured from its
`BoxWithConstraints`), the player cover, and `AlbumScreen`. As a side benefit, list rows
now request *smaller* images than the 1080px URLs stored for songs.

**The toggle:** Settings → Appearance → Display → **High resolution artwork**, default on.
Off restores the previous verbatim-URL behaviour, which uses less data.

## 4. BetterLyrics works, line by line

Four separate things were stopping it:

1. The provider defaulted to **off**.
2. No `User-Agent` — the API rejects requests without a browser-shaped one.
3. No request timeout, so a stalled connection hung the whole provider chain.
4. The real one: OuterTune's strict TTML2 parser (gramophone's `parseTtml`) requires a
   `<head>` element immediately after `<tt>`. Many BetterLyrics responses omit it, so the
   parser threw — which became an "Unable to parse lyrics" placeholder on the render path
   and an `UNPARSEABLE` classification on the fetch path, discarding the result outright.

`betterlyrics/TTMLParser.kt` is a lenient TTML reader ported from Metrolist. It walks the
DOM for `<p>` elements wherever they are, accepts unprefixed or `ttp:`-namespaced timing,
falls back to the earliest child `<span>` when a line has no `begin`, and reassembles
Apple-style syllable spans back into words. `toLrc()` emits plain standard LRC.

`LyricsHelper.parseResilient()` tries the strict parser first and falls back to the
lenient reader when it can't read the document.

Unlike Metrolist's version, `toLrc()` emits **standard** LRC rather than Metrolist's
extended `<word:start:end|…>` / `{bg}` / `{agent:v1}` form, which OuterTune's renderer
would show as literal text. Background vocals become their own timed lines; speaker/agent
tags are dropped.

> Superseded in part by §6: `toLrc()` now has an enhanced mode, so the lenient path keeps
> its word timings instead of flattening to line level.

## 5. Lyrics button removed from the player

The dedicated lyrics button is gone from the player controls. Lyrics toggle by tapping the
cover (`DEFAULT_SHOW_LYRICS_ON_CLICK` was already `true`) or via ⋮ → Toggle lyrics, as in
older OuterTune. The unused imports and the local `showLyrics` state in `ActionButtons`
went with it.

## 6. Word-by-word lyrics, and a say in where lyrics come from

**Word timings survive the lenient TTML path.** `TTMLParser` always extracted per-word
timings — it needs them to rebuild a line's text out of syllable spans — but `toLrc()`
threw them away, so any song only the lenient reader could parse was stuck at line level.
`toLrc()` now has an enhanced mode that writes them back out as Enhanced LRC (the "A2
extension"): a `<mm:ss.cc>` mark before each word, and one after the last word to pin its
end time.

Enhanced LRC rather than a schema change because `LyricsEntity.lyrics` is a plain string.
Nobody takes a Room migration, a line-level reader still sees ordinary LRC because it
ignores the `<...>` marks, and providers that return plain LRC need no special casing. A
line the encoding can't represent safely — a word containing `<`, `>`, `[` or `]`, or
timings that run backwards — is written line-level, so a mixed document degrades per line.

**The sweep renderer was rewritten.** The old karaoke path measured nothing: progress
across a line was the fraction of *words* completed, so the highlight moved in equal steps
regardless of how wide each word actually was. A wrapped line was handled by re-splitting
the text on spaces and slicing the word list by the resulting counts, which threw outright
when the two disagreed, and it ignored the lyric alignment setting.

`KaraokeLyricLine.kt` asks the text layout where the characters are instead. The line is
measured once, the playback position becomes a fractional character offset, and
`TextLayoutResult.getBoundingBox` turns that into an x coordinate. Wrapping then needs no
special case at all, and shaping, kerning and alignment are whatever `Text` would have
produced, because it is the same layout. The bright text is masked by a gradient at the
boundary, so it reads as a light moving across the words.

Two performance changes came with it: the position is read inside the *draw* lambda, so
the clock ticking sixty times a second invalidates drawing rather than recomposing every
visible lyric, and only the current line and the next one are drawn this way. The sweep is
a pure function of the player's position rather than an animation running alongside it, so
a seek lands where it should; between the player's own coarse position updates the wall
clock carries it.

**Provider selection.** Settings → Lyrics → Lyric sources gains two entries:

| Setting | Behaviour |
|---|---|
| **Provider selection: Automatic** (default) | Exactly as before — every enabled provider at once, first synced result wins |
| **Provider selection: In my order** | Providers are asked one at a time from the top; the first synced result wins. Slower on purpose |
| **Provider priority** | Drag to reorder. Also decides how the alternate-lyrics picker lists candidates, so it is useful in either mode |

The order is stored as a comma-joined list of provider ids. Unknown ids are ignored and
providers the stored order does not mention keep their built-in position and go last, so
the preference survives a provider being added, removed or renamed. `providerSignature`
now carries the mode and the enabled ids — in the user's order under manual, sorted under
automatic — so a cached miss recorded under one configuration is never served under
another, and reordering under automatic does not throw away caches that are still valid.

Going in order gets a per-provider time budget rather than the racing cap, which applied
to a sequential walk would have meant the last entries in the order were never asked.

## 7. shazamkit built against the wrong ktor engine

`:shazamkit:compileKotlin` failed with `Unresolved reference 'requestTimeout'` and stopped
the build before the app module was reached. The client is `HttpClient(OkHttp)`, so its
`engine { }` block is an `OkHttpConfig`; `requestTimeout` is a CIO setting. Replaced with
the engine-independent `HttpTimeout` plugin, which `:betterlyrics` and `:kizzy` already use.

---

## Building

The `media` submodule (a fork of AndroidX Media3) is required — `settings.gradle.kts` pulls
it in via `includeBuild`.

From a fresh clone:

```bash
git clone https://github.com/yuuichi-s/OuterTune.git
cd OuterTune
git submodule update --init --recursive
git apply /path/to/outertune-sync-artwork-lyrics.patch    # or: git am
./gradlew assembleDebug
```

**Use the patch route to build.** The accompanying `OuterTune-patched-src.zip` is the full
patched source *minus three things too large to attach*: the `media/` submodule (247 MB),
`prebuilt/ffMetadataEx-release.aar` (40 MB, a required native dependency) and
`assets/gallery/` (screenshots). It is there for reading the changes in context, not for
building. To build from it instead of applying the patch, clone the repo as above and copy
the zip's `app/`, `betterlyrics/` and `PATCH_NOTES.md` over the clone.

The `media` submodule is pinned at `3f52f92e3532b9915ae6fec3dd72ebff9bde0e0a`
(`github.com/nift4/media`) if you ever need to check it out by hand.

No CI changes were made — the repo's existing `.github/workflows/build.yml` should produce
an APK on push.

## Not done

- **The `hifi.geeked.wtf` / `qqdl.site` / `api.monochrome.tf` streaming APIs.** These are
  unofficial proxies that resell access to Tidal/Qobuz catalogs using borrowed credentials.
  Integrating them means shipping a tool for unauthorized access to paid catalogs, so it
  was left out. If the goal is better audio quality, OuterTune's own audio-quality setting
  and YouTube Music's higher-bitrate itags are the place to look.
- **Passing album title to BetterLyrics.** It measurably improves match rate, but
  `LyricsProvider.getLyrics()` has no `album` parameter, so adding it means touching all
  seven providers. Left alone to keep this diff reviewable.
