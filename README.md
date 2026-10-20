> **Share copy:** sanitized for publishing — the package has been
> renamed to `com.example.fetch`, the signing keystore and its
> password are not included, and hosted download links were
> removed. Code is otherwise identical to the built v1.1.

# Fetch — Downloader alternative for Fire TV

A Downloader-style app for the Fire TV Stick: browse the web, download
APKs straight into the shared Downloads folder, and install them from
the Downloads tab. Built Oct 20, 2026.

## What it does

- **Browser tab** — WebView with JavaScript + DOM storage enabled,
  address bar that accepts a URL, a Downloader-style numeric code
  (resolved via aftv.news), or a Google search. Back/fwd/reload, zoom,
  page progress, long-press a link to open / download / copy it.
- **A. Remote mouse cursor** — toggle "Mouse" (or while browsing):
  D-pad moves an on-screen cursor, center button clicks the page,
  Back exits mouse mode. Hold Shift for fine movement.
- **B. Favorites / speed-dial** — Favorites tab with tiles; preloaded
  GitHub, APKMirror, Aptoide TV. Add the current page with "★ Add page"
  in the browser toolbar or the "+ Add favorite" tile; long-press a
  tile to remove it. Stored in SharedPreferences.
- **Smart downloads** — tapping an .apk link or any server download
  asks first (filename + size), then downloads to
  `/sdcard/Download` with a progress bar (percent + MB), resumable via
  a `.part` file (cancel keeps the partial). When an APK finishes, the
  system installer opens automatically.
- **Downloads tab** — lists the real Downloads folder, newest first,
  with size + date. Tap an APK to install; long-press for
  Rename / Delete / Details. Refreshed on open and on resume.
- **H. Ad blocking** — `shouldInterceptRequest` returns an empty 204
  for ~50 known ad/tracker hosts (doubleclick, googlesyndication,
  amazon-adsystem, criteo, outbrain, taboola, applovin, …).
- **J. Delete after install** — checkbox on the APK download prompt;
  `InstallReceiver` deletes that file on PACKAGE_ADDED/REPLACED, only
  if Fetch launched the installer within the last 10 minutes.
- One-time warning if Fire TV's "Apps from Unknown Sources" is off,
  with a shortcut into Settings.

Everything is plain Android framework code — no AndroidX/third-party
libraries — and downloads run on a background thread.

## Target device

- **Fire TV Stick running Fire OS 6.7.1.1 = Android 7.1.1, API 25**
- `minSdkVersion 25`, `targetSdkVersion 25`, compiled against android-34
- LAUNCHER + LEANBACK_LAUNCHER, 320×180 TV banner + launcher icon

## Project details

| | |
|---|---|
| App label | Fetch |
| Application ID | `com.example.fetch` |
| Version | 1.0 (versionCode 1) |
| Permissions | `INTERNET`, `ACCESS_NETWORK_STATE`, `READ/WRITE_EXTERNAL_STORAGE`, `REQUEST_INSTALL_PACKAGES` |
| Install serving | Custom read-only `FetchFileProvider` ContentProvider (framework has no FileProvider); serves only files inside the Downloads dir, path traversal rejected |

## Build

Manual SDK-tools build in `build.sh` (Gradle daemon can't run in the
sandbox): aapt2 → javac (source/target 8, android.jar on `-cp` — NOT
`-bootclasspath`, so JDK supplies `LambdaMetafactory` and d8 desugars
lambdas for API 25) → d8 `--min-api 25` → zipalign → apksigner.
Keystore: create your own `keystore.jks` in the project root and set
its password in `build.sh` (search for CHANGEME). No keystore is
included in this copy.

```bash
cd ~/workspace/fetch && bash build.sh   # → build/manual/apk/Fetch.apk
```

## v1.0 delivery (Oct 20, 2026)

- APK: 41,862 bytes, sha256 `99100812d4975f55892638fe5cb4fe85a1ba9ada946aa79f916dc863f2b3cab6`
- Public link: (link removed) (expires Oct 22, 2026 ~00:38 EDT; download verified byte-identical)
- Verified by aapt2 badging / apksigner / zip test only — **NOT yet
  tested on the physical Stick.**

## v1.1 (Oct 20, 2026) — cursor, long-press, settings, scrolling

- Cursor overlay now spans the whole window: tabs, URL bar, and toolbar
  are clickable in mouse mode; taps are dispatched to the window root.
- Hold the center button ~0.5 s = long-press: link menu on links
  (Open / Download / Copy / Add to favorites), app menu anywhere else.
- New app menu (also via the "≡ Menu" toolbar button or the remote Menu
  key): add current page to favorites, reload, mouse toggle, Downloads,
  Favorites, Settings, scroll to top/bottom.
- New Settings tab: Ad blocking, JavaScript, Desktop user agent,
  delete-after-install default, mouse cursor speed (Slow/Normal/Fast),
  clear cache + history. All persisted; JS/UA changes reload the page.
- Scroll fixes: PgUp/PgDn toolbar buttons; PAGE_UP/PAGE_DOWN and the
  remote's Fast-Forward/Rewind keys scroll the page; in mouse mode,
  pushing the cursor past the page edge scrolls the page (fixes long
  pages like GitHub); scrollbar no longer auto-fades.

## v1.1 delivery (Oct 20, 2026)

- APK: 45,958 bytes, sha256 `8c61a6c74e93e0c0ed574bdeb757334d2a88569a089217e0db4fd3cac959064a`
- Public link: (link removed) (expires Oct 22, 2026 ~00:54 EDT; download verified byte-identical)
- Verified by aapt2 badging / apksigner / zip test only — **NOT yet
  tested on the physical Stick.**
