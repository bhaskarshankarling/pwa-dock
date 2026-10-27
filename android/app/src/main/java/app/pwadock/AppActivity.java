package app.pwadock;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Opens one bookmarked web app inside Dock.
 *
 * Declared with documentLaunchMode="intoExisting", so each URL gets its own entry in
 * Android's recents (titled and iconed from the site), and re-opening the same app
 * returns to its existing task instead of reloading it.
 */
public class AppActivity extends Activity {
    public static final String EXTRA_NAME = "name";

    private static final int REQ_FILE = 10;
    private static final int REQ_MEDIA_PERMS = 11;
    private static final int REQ_GEO_PERMS = 12;
    private static final int REQ_STORAGE_PERMS = 13;

    private WebView web;
    private FrameLayout content;
    private View toolbar;
    private TextView titleView, hostView;
    private ProgressBar progress;

    private String appName;
    private Bitmap siteIcon;
    private ValueCallback<Uri[]> fileCallback;

    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    private PermissionRequest pendingMedia;
    private String pendingGeoOrigin;
    private GeolocationPermissions.Callback pendingGeoCallback;
    private DownloadManager.Request pendingDownload;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app);

        Uri start = getIntent().getData();
        if (start == null) { finish(); return; }
        appName = getIntent().getStringExtra(EXTRA_NAME);
        if (appName == null || appName.isEmpty()) appName = start.getHost();

        toolbar = findViewById(R.id.toolbar);
        content = findViewById(R.id.content);
        titleView = findViewById(R.id.title);
        hostView = findViewById(R.id.host);
        progress = findViewById(R.id.progress);
        web = findViewById(R.id.web);

        titleView.setText(appName);
        hostView.setText(start.getHost());
        updateTaskDescription();

        findViewById(R.id.btnClose).setOnClickListener(v -> finishAndRemoveTask());
        findViewById(R.id.btnShare).setOnClickListener(v -> share());
        ImageButton more = findViewById(R.id.btnMore);
        more.setOnClickListener(this::showMenu);

        setupWebView();

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(start.toString());
    }

    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setSupportMultipleWindows(false); // target=_blank / window.open stay in this view

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true); // embedded logins, SSO iframes

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) return false;
                handleNonWebScheme(request.getUrl());
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                Uri u = Uri.parse(url);
                if (u.getHost() != null) hostView.setText(u.getHost());
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                // The renderer crashed or was killed for memory; drop this view rather than crash Dock.
                Toast.makeText(AppActivity.this, appName + " stopped. Reopen it from Dock.", Toast.LENGTH_SHORT).show();
                content.removeView(web);
                web.destroy();
                web = null;
                finishAndRemoveTask();
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                if (title != null && !title.isEmpty() && !title.startsWith("http")) titleView.setText(title);
            }

            @Override
            public void onReceivedIcon(WebView view, Bitmap icon) {
                siteIcon = icon;
                updateTaskDescription();
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                } catch (ActivityNotFoundException e) {
                    try {
                        Intent any = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                        if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) any.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                        startActivityForResult(any, REQ_FILE);
                    } catch (ActivityNotFoundException e2) {
                        fileCallback = null;
                        return false;
                    }
                }
                return true;
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> askMediaPermission(request));
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest request) {
                if (pendingMedia == request) pendingMedia = null;
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                askGeoPermission(origin, callback);
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) { callback.onCustomViewHidden(); return; }
                customView = view;
                customViewCallback = callback;
                content.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                web.setVisibility(View.GONE);
                toolbar.setVisibility(View.GONE);
                setFullscreenUi(true);
            }

            @Override
            public void onHideCustomView() {
                exitFullscreen();
            }
        });

        web.setDownloadListener((url, userAgent, disposition, mimeType, length) -> download(url, userAgent, disposition, mimeType));
    }

    // ---- toolbar actions ----

    private String currentUrl() {
        String u = web != null ? web.getUrl() : null;
        return u != null ? u : getIntent().getDataString();
    }

    private void share() {
        String url = currentUrl();
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, url)
                .putExtra(Intent.EXTRA_SUBJECT, titleView.getText().toString());
        startActivity(Intent.createChooser(send, "Share " + appName));
    }

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "Reload");
        menu.getMenu().add(0, 2, 1, "Open in browser");
        menu.getMenu().add(0, 3, 2, "Copy link");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: if (web != null) web.reload(); return true;
                case 2: openInBrowser(Uri.parse(currentUrl())); return true;
                case 3: copyLink(); return true;
            }
            return false;
        });
        menu.show();
    }

    /** Forces a real browser (not an installed WebAPK or another app that claims the URL). */
    private void openInBrowser(Uri uri) {
        Intent view = new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE);
        view.setSelector(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER));
        try {
            startActivity(view);
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (ActivityNotFoundException e2) {
                Toast.makeText(this, "No browser found", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void copyLink() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("link", currentUrl()));
        Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show();
    }

    /** mailto:, tel:, intent://, whatsapp:, upi: ... go to whichever app handles them. */
    private void handleNonWebScheme(Uri uri) {
        try {
            Intent intent;
            if ("intent".equals(uri.getScheme())) {
                intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
                intent.addCategory(Intent.CATEGORY_BROWSABLE).setComponent(null).setSelector(null);
                try {
                    startActivity(intent);
                } catch (ActivityNotFoundException e) {
                    String fallback = intent.getStringExtra("browser_fallback_url");
                    if (fallback != null && web != null) web.loadUrl(fallback);
                }
                return;
            }
            intent = new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show();
        }
    }

    // ---- downloads ----

    private void download(String url, String userAgent, String disposition, String mimeType) {
        String scheme = Uri.parse(url).getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            Toast.makeText(this, "This download only works in a browser. Use ⋮ → Open in browser.", Toast.LENGTH_LONG).show();
            return;
        }
        String fileName = URLUtil.guessFileName(url, disposition, mimeType);
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url))
                .setMimeType(mimeType)
                .addRequestHeader("User-Agent", userAgent)
                .setTitle(fileName)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
        String cookie = CookieManager.getInstance().getCookie(url);
        if (cookie != null) req.addRequestHeader("Cookie", cookie);

        if (android.os.Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingDownload = req;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE_PERMS);
            return;
        }
        enqueue(req, fileName);
    }

    private void enqueue(DownloadManager.Request req, String fileName) {
        ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(req);
        Toast.makeText(this, "Downloading " + (fileName != null ? fileName : "file"), Toast.LENGTH_SHORT).show();
    }

    // ---- site permissions (camera / mic / location) ----
    // Each request is confirmed with the user per site, then backed by the Android runtime permission.

    private void askMediaPermission(PermissionRequest request) {
        List<String> resources = new ArrayList<>();
        List<String> label = new ArrayList<>();
        for (String r : request.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) { resources.add(r); label.add("camera"); }
            else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) { resources.add(r); label.add("microphone"); }
        }
        if (resources.isEmpty()) { request.deny(); return; }
        String host = request.getOrigin().getHost();
        new AlertDialog.Builder(this)
                .setTitle("Allow " + TextUtils.join(" and ", label) + "?")
                .setMessage(host + " wants to use your " + TextUtils.join(" and ", label) + ".")
                .setNegativeButton("Block", (d, w) -> request.deny())
                .setPositiveButton("Allow", (d, w) -> {
                    List<String> need = new ArrayList<>();
                    for (String r : resources) {
                        String p = PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) ? Manifest.permission.CAMERA : Manifest.permission.RECORD_AUDIO;
                        if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
                    }
                    if (need.isEmpty()) { request.grant(resources.toArray(new String[0])); return; }
                    pendingMedia = request;
                    requestPermissions(need.toArray(new String[0]), REQ_MEDIA_PERMS);
                })
                .setOnCancelListener(d -> request.deny())
                .show();
    }

    private void askGeoPermission(String origin, GeolocationPermissions.Callback callback) {
        new AlertDialog.Builder(this)
                .setTitle("Allow location?")
                .setMessage(Uri.parse(origin).getHost() + " wants to know your location.")
                .setNegativeButton("Block", (d, w) -> callback.invoke(origin, false, false))
                .setPositiveButton("Allow", (d, w) -> {
                    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                            || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        callback.invoke(origin, true, false);
                        return;
                    }
                    pendingGeoOrigin = origin;
                    pendingGeoCallback = callback;
                    requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_GEO_PERMS);
                })
                .setOnCancelListener(d -> callback.invoke(origin, false, false))
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_MEDIA_PERMS && pendingMedia != null) {
            List<String> granted = new ArrayList<>();
            for (String r : pendingMedia.getResources()) {
                String p = PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) ? Manifest.permission.CAMERA
                        : PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) ? Manifest.permission.RECORD_AUDIO : null;
                if (p != null && checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) granted.add(r);
            }
            if (granted.isEmpty()) pendingMedia.deny();
            else pendingMedia.grant(granted.toArray(new String[0]));
            pendingMedia = null;
        } else if (requestCode == REQ_GEO_PERMS && pendingGeoCallback != null) {
            boolean ok = false;
            for (int r : results) ok |= r == PackageManager.PERMISSION_GRANTED;
            pendingGeoCallback.invoke(pendingGeoOrigin, ok, false);
            pendingGeoCallback = null;
            pendingGeoOrigin = null;
        } else if (requestCode == REQ_STORAGE_PERMS && pendingDownload != null) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) enqueue(pendingDownload, null);
            else Toast.makeText(this, "Storage permission needed to download", Toast.LENGTH_SHORT).show();
            pendingDownload = null;
        }
    }

    // ---- lifecycle ----

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE && fileCallback != null) {
            Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            if (result == null && data != null && data.getClipData() != null) {
                ClipData clip = data.getClipData();
                result = new Uri[clip.getItemCount()];
                for (int i = 0; i < clip.getItemCount(); i++) result[i] = clip.getItemAt(i).getUri();
            }
            fileCallback.onReceiveValue(result);
            fileCallback = null;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        // Same app tapped again in Dock: keep the existing session, just bring it forward.
        super.onNewIntent(intent);
    }

    @Override
    public void onBackPressed() {
        if (customView != null) { exitFullscreen(); return; }
        if (web != null && web.canGoBack()) { web.goBack(); return; }
        finishAndRemoveTask();
    }

    private void exitFullscreen() {
        if (customView == null) return;
        content.removeView(customView);
        customView = null;
        if (customViewCallback != null) customViewCallback.onCustomViewHidden();
        customViewCallback = null;
        web.setVisibility(View.VISIBLE);
        toolbar.setVisibility(View.VISIBLE);
        setFullscreenUi(false);
    }

    @SuppressWarnings("deprecation")
    private void setFullscreenUi(boolean on) {
        getWindow().getDecorView().setSystemUiVisibility(on
                ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                : View.SYSTEM_UI_FLAG_VISIBLE);
    }

    @SuppressWarnings("deprecation")
    private void updateTaskDescription() {
        setTaskDescription(new ActivityManager.TaskDescription(appName, siteIcon));
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (web != null) web.saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush(); // persist logins
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            content.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
