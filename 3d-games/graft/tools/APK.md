# Turning GRAFT into a real Android app

Honest version first: **no APK was built here and none can be.** This machine has
no Android SDK, no Gradle, no JDK for Android, no `adb`. Building an APK needs
about **8-12 GB** of tooling installed before the first line of anything
compiles. What is built instead is the installable-PWA path, which on Android
gets you a home-screen icon, a name, no browser chrome, offline play and the
content-pack downloader — and which is the half that actually mattered in the
question, because the 70 GB in a Hoshino game is not in the APK either. The
store download is small; the game fetches the bulk on first run. That part is
built and tested.

This file is what to do when you want the store listing as well.

---

## What you have today

    app/manifest.webmanifest    name, icons, standalone, portrait, #060912
    app/sw.js                   offline shell + immutable pack cache
    app/src/view/packs.js       the downloader: list, install, resume, remove
    tools/make_pack.py          builds a pack from a folder
    app/icons/*.png             192/512 plus maskable, drawn in code

**Installing needs https.** Android will not offer "Add to home screen" as a real
app over plain `http://192.168.x.x`, and a service worker will not register
there either — a secure context is required, and only `localhost` is exempt. The
another of my projects already solves exactly this: `Play.bat` opens a
**Cloudflare quick tunnel** and prints a QR code, which gives a real `https://`
origin with no account and no certificate work. Same trick works here.

Once it is on the home screen from an https origin, it is standalone, offline
and updates by itself when you edit a file (the worker is network-first).

---

## Route A — Trusted Web Activity (Bubblewrap)

**What it is.** A ~2 MB APK that is a thin Android wrapper around Chrome, opening
your https URL full screen with no address bar. Google's own recommended route
for shipping a PWA to Play. Not a WebView: it runs in real Chrome, so
performance is identical to the browser, which matters for a WebGL game.

**What you install**

| thing | size | note |
|---|---|---|
| JDK 17 | ~300 MB | Bubblewrap can fetch its own |
| Android SDK command-line tools + platform 34 + build-tools | ~4-6 GB | Bubblewrap can fetch its own |
| Node 18+ | already here | |
| `@bubblewrap/cli` | small | `npm i -g @bubblewrap/cli` |

**What you do**

1. Deploy `app/` to a stable https host. This is the one hard requirement: a TWA
   points at a URL, and a quick tunnel's URL changes every run. Cloudflare Pages
   or GitHub Pages, both free, both fine for a static folder.
2. `bubblewrap init --manifest https://your-host/manifest.webmanifest`
3. `bubblewrap build` — produces `app-release-signed.apk` and `.aab`.
4. Host `/.well-known/assetlinks.json` on the same origin with your signing
   key's SHA-256 fingerprint. **Skip this and the app opens with a browser
   address bar across the top**, which is the single most common TWA complaint
   and looks exactly like the wrapper not working.

**Cost.** A day the first time, mostly SDK download. Nothing recurring.

**Limits.** The app is only ever as available as the host. No offline first-run:
the very first launch must reach the network. Everything after that is the
service worker's.

---

## Route B — Capacitor

**What it is.** The web app is *copied inside* the APK and served from the app's
own storage, so there is no host and the first run works with no network at all.
Runs in a WebView rather than Chrome proper.

**What you install.** The same SDK (~4-6 GB), plus `@capacitor/core`,
`@capacitor/cli`, `@capacitor/android`.

**What you do**

    npm i @capacitor/core @capacitor/cli @capacitor/android
    npx cap init GRAFT com.tutut.graft --web-dir=app
    npx cap add android
    npx cap sync
    npx cap open android      # then Build > Generate Signed Bundle/APK

**Why you might prefer it.** No host, no assetlinks, and native APIs are
available if the game ever wants them (real filesystem storage for packs instead
of the Cache API, which would lift the browser's storage quota — see below).

**Why you might not.** WebView is a version behind Chrome on some devices, and
`file://`-origin quirks bite: the pack downloader would need its base URL
pointed at a real server, and Cache API behaviour under a custom scheme is worth
testing before trusting it. Roughly 100-200 MB heavier to build and slower to
iterate — every change is a rebuild and a reinstall, where the PWA is a save and
a pull-to-refresh.

---

## What a Play Store listing needs

- **A Google Play developer account: US$25, one time, forever.** Identity
  verification now takes days, and personal accounts created since 2023 need
  **12 testers running the app for 14 continuous days** before you may go public.
  That is the real gate, not the money.
- App icon 512x512, a feature graphic 1024x500, at least 2 phone screenshots.
- A privacy policy URL. Required even for a game that collects nothing; say so.
- A completed Data Safety form, a content rating questionnaire, and a target
  API level that is current (34+ as of writing, and it moves every August).
- An **.aab**, not an APK. Play does not accept APKs for new apps.

## What you can sideload with no account at all

A signed **APK** from either route above installs directly: send yourself the
file, tap it, allow "install unknown apps" once. No US$25, no review, no
14-day test. This is the sensible way to get it onto your own phone and a
friend's, and both routes produce one. Play is only for strangers.

**Or skip all of it.** The PWA on the home screen has an icon, a splash, no
browser bar, works offline, and updates when you save a file. For a game with
one player and a few friends it is strictly better than the store, and it is
what exists right now.

---

## The content packs, whichever route

Nothing above changes the packs. `tools/make_pack.py build <dir> --id <id>`
writes `app/packs/<id>/pack.json` and rebuilds `app/packs/packs.json`; the game
downloads from whatever origin it was served from, verifies every file's
SHA-256, and stores it in the Cache API under `graft-pack-<id>@<version>`.

One number to know: **browser storage is a quota, not a disk.** Chrome on
Android will grant roughly 60% of free storage to an origin, and it can evict
the lot if the device runs short — unless you have called
`navigator.storage.persist()`, which is worth doing before a large download and
which the browser grants readily to an installed PWA. A genuine 70 GB is past
what any browser will hold; that is where Route B and real native filesystem
storage stop being optional. For packs measured in hundreds of megabytes, the
Cache API is the right answer and no wrapper is needed.
