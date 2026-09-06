# Signing in to YouTube Music on the desktop

The specific problem, established from this repository's own code rather than from memory, plus what
each way out actually costs. Written so it can be argued with.

## The mechanism, exactly

`innertube/.../InnerTube.kt:149-159` is the whole of authentication:

```kotlin
if (setLogin && client.loginSupported) {
    cookie?.let { cookie ->
        val cookieMap = parseCookieString(cookie)
        append("cookie", cookie)
        if ("SAPISID" !in cookieMap) return@let
        val currentTime = System.currentTimeMillis() / 1000
        val sapisidHash = sha1("$currentTime ${cookieMap["SAPISID"]} $origin")
        append("Authorization", "SAPISIDHASH ${currentTime}_${sapisidHash} …")
    }
}
```

Three things follow, and they reframe the problem:

**1. This is not OAuth, and no OAuth flow produces it.** It is Google's internal `SAPISIDHASH`
scheme, used by their own first-party web apps. The credential is a *logged-in web session*, not a
token. That is why "do the OAuth dance" has no answer here - not because it is hard, but because the
thing it returns is not the thing this needs. An OAuth access token authenticates against the
**YouTube Data API**, which is a different service with a different catalogue and no playback.

**2. Only one cookie is load-bearing.** `SAPISID` is the sole value that feeds the hash. The rest of
the cookie string is forwarded verbatim because the server wants a coherent session, but the
signature depends on `SAPISID` alone. In practice a working session needs the set Google issues
together: `SID`, `HSID`, `SSID`, `APISID`, `SAPISID`, `__Secure-1PSID`, `__Secure-3PSID`.

**3. Two values are *not* cookies.** `app/.../LoginScreen.kt:94-99` reads them out of the page with
JavaScript after login:

- `VISITOR_DATA` - the desktop already gets this independently via `YouTube.visitorData()`, so it is
  not a blocker.
- `DATASYNC_ID` - selects *which* account when one Google login has several YouTube channels.
  Without it, a multi-channel account gets the default channel. Degraded, not broken.

So the desktop needs: **a cookie string containing a live `SAPISID`.** Nothing more is strictly
required.

## Why a small browser does not solve it

The idea is right in spirit - the cookie comes from a browser, so embed a small one. The problem is
what "small" excludes.

The cookie is not issued by `music.youtube.com`. It is issued by `accounts.google.com`, and getting
one means completing Google's sign-in, which is:

- a JavaScript application, not a form POST;
- behind BotGuard, which executes obfuscated JS and expects a real DOM, real timing and real
  browser APIs;
- often behind reCAPTCHA, 2FA prompts, or passkeys;
- fronted by a check that refuses browsers it does not recognise, with the message *"This browser or
  app may not be secure"*.

**NetSurf specifically:** its JavaScript support is partial - a Duktape engine with an incomplete DOM
binding, intended for progressive enhancement rather than for running a JS application. Google's
sign-in is not a page it can execute. The same applies to Lynx, w3m, Dillo and every other
lightweight engine: the constraint is not rendering, it is that a modern JS runtime *is* the browser
here, and that runtime is most of Chromium's size. Any engine small enough to be worth embedding is,
by construction, too small to sign in.

This is worth stating as a general rule rather than a fact about NetSurf, because it disposes of the
whole category in one go: **anything that can pass Google's sign-in is approximately Chromium, and
anything smaller than Chromium cannot pass it.**

## What actually remains

### A. Import a `cookies.txt` file — cheap, teachable, works today

The user installs a cookie-export extension in the browser they already use, signs into YouTube
Music normally, clicks the extension, saves a file, and points the app at it. The app parses the
Netscape cookie format - the same format `yt-dlp --cookies` takes - and keeps the YouTube entries.

- **Cost to build:** small. A file picker and about thirty lines of parser.
- **Cost to use:** far lower than devtools. "Install this extension, click it, save the file" is
  teachable in one sentence and needs no explanation of what a request header is.
- **Robust:** the format is stable and widely supported. Nothing to break when a browser updates.
- **Verifiable immediately:** one authenticated call on import, so a bad file fails at the moment of
  import rather than silently later.

This is where I would start. It is not elegant, but it is the only option on this list that is
certain to work and cannot rot.

### B. Read the browser's cookie store directly — easy for Firefox, hard for Chrome

`yt-dlp --cookies-from-browser` does this.

- **Firefox:** `cookies.sqlite`, unencrypted, trivial to read with the SQLite driver already added
  for the library. Genuinely easy.
- **Chrome/Edge:** encrypted with a DPAPI-derived key. Historically readable; Chrome 127 introduced
  *app-bound encryption*, which ties the key to the Chrome process itself and broke most external
  extractors. Whether there is now a supported path is worth checking - it is exactly the sort of
  thing that has changed since I last had reliable information.

Worth doing for Firefox regardless, because it is nearly free once the file is located. Not worth
fighting for Chrome.

### C. Device/TV OAuth — the one genuinely worth researching

This is the lead I would most like a second opinion on, because my information may be stale.

YouTube's TV client uses the OAuth 2.0 **device authorization grant**: the app shows a short code,
the user types it at `youtube.com/activate` on any device, and the app receives a refresh token.
`ytmusicapi` shipped this as `setup_oauth`, and it authenticated InnerTube requests - not the Data
API - by pairing the token with the `TVHTML5` client context.

What makes it attractive: no embedded browser, no cookie file, no scraping, and the user signs in on
whatever device they like.

What makes it uncertain:

- Google restricted the well-known TV client credentials at some point, after which `ytmusicapi`
  required each user to create **their own Google Cloud OAuth client** (type: *TVs and Limited Input
  devices*) and enable the YouTube Data API. That is a one-time setup in a browser, but it is a
  fiddly one - arguably worse than exporting a cookie file.
- yt-dlp's OAuth plugin was deprecated, which suggests the approach became unreliable.
- **This module has no Bearer-token path at all.** Adding it means a second authentication mode in
  `ytClient()` - `Authorization: Bearer <token>` instead of `SAPISIDHASH` - plus token refresh, plus
  forcing the `TVHTML5` client for authenticated calls. `TVHTML5` already exists here with
  `loginSupported = true`, so the client is not the obstacle; the auth path is.

**The specific question worth answering:** as of now, can a self-created "TVs and Limited Input
devices" OAuth client obtain a token that InnerTube accepts on `music.youtube.com` with the TVHTML5
client - and does it reach the library, liked songs and playlists, or only a subset? If yes, C beats
A on user experience by a wide margin and is worth the auth-path work. If the answer is "only the
Data API", it collapses for the same reason ordinary OAuth does.

### C-bis. OAuth → cookies, which would remove the need for a Bearer path

Worth knowing while researching C, because it changes what a positive answer would be worth.

A token and a cookie are not as far apart as they look. Google has a documented-by-observation path
for turning one into the other, which is how Chrome and Android sign you into Google *websites* after
you have signed into the *device*:

1. Obtain an access token with the scope `https://www.google.com/accounts/OAuthLogin`.
2. `GET https://accounts.google.com/OAuthLogin?source=…&issueuberauth=1` with that token, which
   returns an opaque "uberauth" string.
3. `GET https://accounts.google.com/MergeSession?uberauth=…` (or the `oauth/multilogin` endpoint),
   whose response carries `Set-Cookie` for the Google domains — **including `SAPISID`**.

If that still works, device OAuth would not need a Bearer path in `InnerTube` at all. It would mint
an ordinary cookie session, and every existing code path would work unchanged. That is a much
smaller change than adding a second authentication mode, and it removes the "does the TV client
reach the library or only a subset" question entirely, because the result is indistinguishable from
signing in normally.

**The likely blocker, and the thing to check:** `OAuthLogin` has historically been a *first-party
scope* — grantable only to Google's own client IDs (Chrome, the Android account manager), not to a
client you create in Google Cloud. If that is still true, this only works with a well-known
first-party client ID, which is precisely what Google restricted for third parties, and it collapses
back into C.

So the two questions are really one question asked twice:

- **For C:** can a self-created *TVs and Limited Input devices* client get a token InnerTube accepts?
- **For C-bis:** can any token you can legitimately obtain carry the `OAuthLogin` scope?

If either is yes, sign-in becomes a code on screen instead of a file to export. If both are no, then
A and B are the whole of what is available, and that is worth knowing definitively rather than
suspecting.

I am flagging this as recalled rather than verified. The endpoints are not documented by Google and
have changed before; treat the shape as a lead to test, not as a spec.

### D. Embed Chromium (JCEF) — works, and costs ~150MB

Reliable, and the same flow as Android. It undoes the thing this port has been careful about: the
audio chain is pure Java precisely so there is nothing native to ship. If it is ever done it should
be an optional download rather than baked into the jar, which is real distribution work rather than
a dependency line.

Worth recording that an earlier session concluded JCEF was needed for *playback* and was wrong - the
spike had simply never set `locale`/`visitorData`. Only sign-in ever needed a browser.

## Suggested order

1. ~~**A**~~ — done. `CookieImport.kt`, 13 tests.
2. ~~**B for Firefox**~~ — done. `FirefoxCookies.kt`, 10 tests. One button, no export, no file
   picker, for anyone who uses Firefox. Chrome deliberately not attempted: app-bound encryption
   since Chrome 127 ties the key to the Chrome process, and chasing that is a commitment to keep
   chasing it.
3. **C / C-bis** if either question comes back positive. That is the research.
4. **D** only if both fail and the file flow proves genuinely unacceptable.

## Whichever is chosen

- Store it beside the database. Encrypting it needs a key, and a key stored next to what it encrypts
  is decoration - better to say plainly that it is stored in the clear.
- Validate on entry with one authenticated call, so a bad credential fails where the user can see it.
- Set `YouTube.cookie` at startup before the first request, next to where `visitorData` is set in
  `Main.kt`, with the same visible status - a silent failure there has already cost this port a
  debugging session once.
- Signing out clears it.
- Expiry should report "signed out", not an HTTP error. The user's next question is always whether
  they are still signed in.
