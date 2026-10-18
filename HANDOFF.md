# Handoff: where we left off (18 Oct 2026)

Branch `metrolist-engine` in the worktree `C:\Outertune-Claude\live`. Remote:
https://github.com/notK50BML/OuterTune.git. All work is committed and pushed. The patch
`outertune-metrolist-engine.patch` is regenerated after each change.

## Builds

| What | Command | Output |
|---|---|---|
| Windows installer | `gradlew :desktop:installer` | `desktop/build/dist/OuterTune-setup.exe` (47.9MB) |
| Windows portable zip | `gradlew :desktop:packageApp` | `desktop/build/dist/OuterTune-windows.zip` (77MB) |
| Android debug APKs | `gradlew :app:assembleFullDebug` | `app/build/outputs/apk/full/debug/` |
| Desktop tests | `gradlew :desktop:test` | all passing |

- **JDK:** builds need JDK 21: `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.11"`.
- **Inno Setup:** the installer needs Inno Setup 6. It is installed per-user, via winget, at
  `%LOCALAPPDATA%\Programs\Inno Setup 6`. On another machine, run
  `winget install JRSoftware.InnoSetup`, or pass `-PisccPath=`.
- **Regenerating the patch:** after each commit run
  `git format-patch --stdout --base=93260340a4f9d8d275485b7784d317b875cb7d71 93260340a4f9d8d275485b7784d317b875cb7d71..HEAD -- . ':!outertune-metrolist-engine.patch' > outertune-metrolist-engine.patch`,
  then commit it as "Regenerate the patch".

## Done in this stretch

### Layout editor (`tools/layout-editor.html`)
- **Exact match:** the phone preview is built from a dp model of `Player.kt`. On a 1080x2400 /
  420dpi emulator, the Guided default and the Free layout seeded from it land within 1px of the
  built-in player.
- **Renames and defaults:** "Stacked" is now "Guided", and the default preset is the built-in player.
- **New controls:** a density (dpi) field.
- **Player buttons:** the stand-ins are like, download, equaliser and ⋮.

### Android
- **Imported layouts:** they honour side padding, cover size, radius and hiding, and title
  alignment. Free blocks are centred on their x/y.
- **Bug fixes:** the progress times no longer overlap the bar, and the separated transport row
  keeps play's 8dp gaps.
- **Debug-only hook:** `adb shell am start ... --es ot_layout_b64 <base64 json>` sets a layout.
  This is how the emulator checks were done; see `MainActivity`.
- **Player buttons:** "Add to queue" is no longer offered beside the player's ⋮. It is still in
  the menu.

### Windows
- **Song rows:** a ⋮ on every song row opens an animated, rounded panel of tiles. Right-click opens
  the same panel. Settings > Appearance > Song rows picks the quick buttons, and can hide the ⋮.
- **Player ⋮:** it opens the same panel (`MenuPanel` in `SongMenuPanel.kt`). The panel flips above
  its button when it is low in the window.
- **Settings:** the sections follow the phone's sections, names and order.
- **Search:** the suggestions have their own open state, so clicking inside them, or the ✕, no
  longer closes them.
- **Downloads:** a downloaded song, playlist or album shows the phone's downloaded icon
  (OfflinePin). Clicking it removes the download; for a list, every song in it.
- **Installer:** see Builds above.
- **Earlier:**
  - playlist and album "Download all"
  - saved queues (up to 19)
  - ferrofluid reactivity setting
  - full player as an overlay, so settings and scroll positions survive closing it
  - the layout editor bundled into both apps

## Open / next

- **Installer, disk size:** the installed size is 226MB, up from 111MB. That is the price of the
  small download: the jar is stored rather than deflated, and the runtime is jlinked with
  `zip-0`. To swap back, revert those two lines in `desktop/build.gradle.kts` (`entryCompression`
  and `--compress`).
- **Installer, smaller still (optional):** ProGuard on the jar would probably take the installer
  to about 35MB. Most of the jar is Compose and Skia. This was not done: it needs careful keep
  rules and a full test pass.
- **Installer, signing:** it is unsigned, so Windows SmartScreen will warn on first run.
- **Bulk download removal:** removing a whole list's downloads happens at once, with no
  confirmation. Add one if misclicks become a problem.
- **Not verified by hand:** the Windows search panel and the player ⋮ panel compile and pass the
  tests, but were not clicked through.
- **Emulator:** the AVD `ot` (Pixel 7, API 35) was used for the layout checks. Helper scripts and
  reference shots are in the session scratchpad, not the repo.
- **Untracked folder:** `screenshots/` holds the competition shots (default, everything,
  equaliser, minimal, frosted). It is not committed.

## House rules (from you)
- Ask when unsure.
- Push periodically.
- Never ask for the GitHub token in chat.
- Never extract your YouTube cookie from your devices.
- Don't commit the desktop jar.
- Don't force-push over `beta`.
- Icons must match the Android app.
