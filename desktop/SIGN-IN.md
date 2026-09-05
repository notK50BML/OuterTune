# Signing in to YouTube Music on the desktop

Groundwork, not an implementation. The work itself is straightforward once the approach is chosen;
choosing badly is expensive, so this writes down what the options actually cost.

## What is needed

`YouTube.cookie` is the whole of it. The innertube layer already sends it, and everything that needs
an account - the library, subscriptions, liked songs, personalised home - starts working once it is
set. There is nothing else to build on the API side.

So the entire problem is: **how does a cookie for `music.youtube.com` get into the app?**

On Android that question has an easy answer, which is why it was never faced there: a WebView shows
Google's own login page, and the app reads the cookie jar afterwards. There is no WebView here.

## The options, and what each really costs

### 1. Paste the cookie by hand

The user signs in with their normal browser, opens developer tools, copies the `Cookie` header from
any `music.youtube.com` request, and pastes it into a text field.

- **Cost to build:** an afternoon. A text field, validation by making one authenticated call, and
  storage.
- **Cost to use:** high, and the part that matters is that it is *unteachable*. "Open devtools, find
  the network tab, filter requests, find the header" is a support burden forever.
- **Risk:** the app never touches the password. That is worth something.
- **Already precedented here:** the Discord integration takes a token this way, for the same reason,
  and the reason is written up in `DiscordSettings.kt` - the embedded browser refused to cooperate
  often enough that a manual path had to exist regardless.

### 2. Drive the system browser and catch the redirect

Open the login page in the user's real browser, and have the app listen on `localhost` for a
redirect carrying the credential.

- **Blocker:** this is the OAuth shape, and Google's music endpoints are not an OAuth API. There is
  no redirect that hands over a `music.youtube.com` session cookie. What comes back from a Google
  OAuth flow is an access token for the *Data* API, which is a different service with a different
  catalogue and no playback.
- **Verdict:** the right answer to a different question. Not viable as stated.

### 3. Embed a browser (JCEF or similar)

Bundle Chromium, show the real login page, read the cookies out of it.

- **Cost to build:** moderate. JCEF has a Kotlin/Compose story and the cookie extraction is
  supported.
- **Cost to ship:** roughly **150MB** on top of a 47MB jar, per platform, and a native dependency -
  which is precisely what this port has avoided from the start. The audio chain is pure Java
  specifically so there is nothing native to bundle; this would undo that for one screen.
- **Note:** an earlier session concluded JCEF was needed for playback and was wrong - the spike had
  simply never set `locale`/`visitorData`. Worth not repeating that reasoning by momentum: playback
  does not need a browser, and it is only sign-in that ever did.

### 4. Read the cookie from a browser already installed

Chrome and Firefox keep cookies in a SQLite file. The app could read it directly.

- **Cost to build:** moderate, and it never stops. Chrome encrypts its cookie store with a key from
  the Windows DPAPI; the format has changed several times; Firefox differs again; a browser update
  can break it silently.
- **Risk:** reading another application's credential store is the kind of thing security software
  objects to, with reason.
- **Verdict:** works until it does not, and fails in a way the user cannot diagnose.

## What this suggests

**Option 1 now, option 3 only if it is genuinely wanted.**

Pasting a cookie is unpleasant and it is honest about it: it works on every platform, adds nothing to
the download, cannot break when a browser updates, and never handles a password. The project already
took exactly this trade for Discord and documented why.

If a nicer flow is wanted later, option 3 is the only one that actually delivers it, and it should be
an *optional download* rather than baked into the jar - which is a real piece of design work
(separate distribution, or a plugin loaded at runtime), not a dependency line.

## If option 1 is chosen, what it involves

1. **Storage.** A `credentials` table, or a file beside the database. Worth deciding whether to
   obfuscate it - full encryption needs a key, and a key stored beside the thing it encrypts is
   decoration. Saying plainly that it is stored in the clear may be better than implying otherwise.
2. **Validation on entry.** One authenticated call, so a bad paste fails at the moment of pasting
   rather than silently later. The Discord settings screen does this and it is the difference
   between a setting that works and a setting that appears to.
3. **`YouTube.cookie` set at startup**, before the first request, next to where `visitorData` is set
   in `Main.kt` - and with the same visible status, since a silent failure there taught this port
   the lesson already.
4. **Signing out** clears it. Obvious, and the thing most often left until someone asks.
5. **Expiry.** Cookies die. The failure should say "signed out" rather than surfacing an HTTP error,
   because the user's next question is always "am I still signed in".
