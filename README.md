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
