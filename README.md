# Dock — PWA Launcher

A tiny, installable Progressive Web App that works as a home screen for your other web apps. Bookmark any PWA or website, then launch it from one grid.

No backend, no build step, no dependencies: three static files and some icons.

## Features

- **App grid**: home-screen style tiles. Icons come from the site's favicon, or a custom icon URL you set, with a colored letter tile as a fallback.
- **Search and sort**: sort by Custom order, Recent, Most used, or A–Z. Taps are counted locally.
- **Edit and rearrange**: long-press a tile to edit or delete it. Tap ✎ to enter rearrange mode.
- **Android share target**: once Dock is installed, share any page from Chrome to Dock and the add dialog opens with the URL filled in.
- **App shortcut**: long-press the Dock icon and choose **Add app**.
- **Backup**: export and import your list as JSON from the ⋮ menu.
- **Offline**: a service worker caches the app shell.
- **Light and dark themes**: follows the system setting.

## Project structure

```
.
├── index.html             # UI, styles and app logic
├── manifest.webmanifest   # install metadata, share_target, shortcuts
├── sw.js                  # service worker (offline shell)
├── android/               # native WebView wrapper (APK)
├── .github/workflows/     # CI: builds and publishes the APK
└── icons/                 # 192, 512 and maskable 512 PNGs
```

## Deploy

PWAs must be served over HTTPS to be installable. Any static host works.

### GitHub Pages
1. Open **Settings → Pages** for this repo.
2. Under **Source**, choose **Deploy from a branch**, then pick `main` and `/ (root)`.
3. The site goes live at `https://<user>.github.io/<repo>/`.

All paths in the app are relative, so it works under a sub-path without any changes.

### Local testing
```bash
python3 -m http.server 8080
# open http://localhost:8080  (localhost counts as a secure context)
```

## Install on Android

1. Open the deployed URL in Chrome.
2. Tap **⋮ → Install app**, or use **Install Dock** in Dock's own ⋮ menu.
3. Chrome builds a WebAPK. Dock then appears in your app drawer and in the share sheet.

## Android APK (sideload)

If you'd rather not install through Chrome, the `android/` folder holds a small native wrapper. GitHub Actions builds it on every push to `main` and publishes the result to the **[`apk-latest` release](../../releases/tag/apk-latest)**.

- **Install**: download `pwa-dock.apk` on your phone and open it. Allow installs from your browser when Android asks.
- **Offline, no hosting needed**: the web files are bundled into the APK and served from a local `https://appassets.androidplatform.net` origin via `WebViewAssetLoader`.
- **In-app browser**: tapping a tile opens the app in Dock's own full-screen WebView, which gets its own entry in Android's recents, titled with the site's name and favicon. Tapping the same app again resumes it instead of reloading. The top bar has **Close**, the page title, **Share** (sends the current URL to any app, e.g. to open it later in a browser), and **⋮** for **Reload**, **Open in browser** and **Copy link**.
  - Supports logins and cookies, file uploads, downloads (via Android's download manager), fullscreen video, and per-site prompts for camera, microphone and location.
  - Links with non-web schemes (`mailto:`, `tel:`, `intent://`, UPI and so on) are handed to the app that handles them.
  - Prefer the old behaviour? Choose **⋮ → Open apps in browser instead** in Dock. Taps then fire an Android `ACTION_VIEW` intent, which routes to an installed PWA (WebAPK) if one claims the URL, or to your default browser.
  - **Limitation**: Google blocks "Sign in with Google" inside embedded WebViews. For apps that need it, use **⋮ → Open in browser**.
- **Share sheet**: an "Add to Dock" entry is registered for shared text and links (`ACTION_SEND text/plain`).
- **Backup**: export uses the system "Save to…" dialog, and import uses the system file picker.
- The APK keeps its own app list, separate from the Chrome-installed PWA. Use export and import to move apps between them.

### Signing (so updates install over the old version)

Without signing secrets, CI signs each build with a throwaway debug key. Android then refuses to install a newer build over the old one, and uninstalling wipes your list. To get stable signing, add these under **Settings → Secrets and variables → Actions**:

| Secret | Value |
|---|---|
| `DOCK_KEYSTORE_BASE64` | `base64 -w0 dock.keystore` |
| `DOCK_KEYSTORE_PASSWORD` | keystore password |
| `DOCK_KEY_ALIAS` | key alias |
| `DOCK_KEY_PASSWORD` | key password |

Then re-run the workflow from **Actions → Build Android APK → Run workflow**. Keep the keystore backed up, because losing it means the next update requires a reinstall.

### Build locally
```bash
cd android
gradle assembleDebug   # needs JDK 17 + Android SDK (ANDROID_HOME); Gradle 8.7+
```

## How it works

| Concern | Implementation |
|---|---|
| Storage | `localStorage` (`dock.apps.v1`, `dock.prefs.v1`), with a `navigator.storage.persist()` request |
| Launching | Each tile is a plain `<a target="_blank">` link |
| Share target | `share_target` makes a GET request to `./?title=&url=&text=`. The URL is taken from `url`, or parsed out of `text` (Chrome often puts it there) |
| Caching | Navigations try the network first and fall back to the cached shell. Same-origin assets come from the cache first |
| Icons | `google.com/s2/favicons`, unless you set a custom icon URL |

## Limitations

- **Opening installed PWAs**: a web page can't force another web app to launch. If the target app is installed as a WebAPK, Android *may* open it there, depending on the Chrome version and its link-capturing settings. Otherwise it opens in a Chrome tab.
- **Data lives on the device**: clearing Chrome's site data for Dock deletes your list. Export a backup now and then.
- **Favicon privacy**: the favicon service lets Google see the domains you've saved. Set custom icon URLs to avoid this.

## Updating

When you change any cached file, bump `CACHE` in `sw.js` (for example `dock-v2`) so installed clients pick up the new version.

## License

MIT
