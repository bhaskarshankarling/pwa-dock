package app.pwadock;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Thin native shell around the Dock web app.
 *
 * The web files are bundled into the APK and served from a virtual https origin
 * (appassets.androidplatform.net) via WebViewAssetLoader, so localStorage works
 * and the app runs fully offline with no hosting dependency.
 *
 * Any navigation away from that origin (tapping an app tile) is handed to Android
 * as an ACTION_VIEW intent, which lets the system route the URL to an installed
 * PWA (WebAPK) that claims it, or to the default browser otherwise.
 */
public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String HOME = "https://" + HOST + "/assets/www/index.html";
    private static final int REQ_IMPORT = 1;
    private static final int REQ_EXPORT = 2;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private String pendingBackup;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportMultipleWindows(false); // target=_blank navigates this view -> intercepted below

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (HOST.equals(uri.getHost())) return false;
                openExternal(uri);
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent pick = new Intent(Intent.ACTION_GET_CONTENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*");
                try {
                    startActivityForResult(pick, REQ_IMPORT);
                    return true;
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    return false;
                }
            }
        });

        web.addJavascriptInterface(new Bridge(), "DockAndroid");

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            load(getIntent());
        }
    }

    /** Loads the app, forwarding shared text/url as share-target query params. */
    private void load(Intent intent) {
        String url = HOME;
        if (intent != null && Intent.ACTION_SEND.equals(intent.getAction())) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            String title = intent.getStringExtra(Intent.EXTRA_SUBJECT);
            url = HOME + "?text=" + Uri.encode(text == null ? "" : text)
                    + "&title=" + Uri.encode(title == null ? "" : title);
        }
        web.loadUrl(url);
    }

    private void openExternal(Uri uri) {
        Intent view = new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE);
        try {
            startActivity(view);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (Intent.ACTION_SEND.equals(intent.getAction())) load(intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_IMPORT) {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                fileCallback = null;
            }
        } else if (requestCode == REQ_EXPORT) {
            String json = pendingBackup;
            pendingBackup = null;
            if (resultCode != RESULT_OK || data == null || data.getData() == null || json == null) return;
            try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
                Toast.makeText(this, "Backup saved", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Could not save backup", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }

    /** Exposed to the page as window.DockAndroid. Only bundled pages ever load in this WebView. */
    private class Bridge {
        /** Opens a bookmarked app inside Dock's own WebView screen (its own recents entry). */
        @JavascriptInterface
        public void openApp(final String url, final String name) {
            runOnUiThread(() -> {
                Uri uri = Uri.parse(url);
                String scheme = uri.getScheme();
                if (!"http".equals(scheme) && !"https".equals(scheme)) { openExternal(uri); return; }
                Intent open = new Intent(MainActivity.this, AppActivity.class)
                        .setData(uri)
                        .putExtra(AppActivity.EXTRA_NAME, name);
                startActivity(open);
            });
        }

        @JavascriptInterface
        public void saveBackup(final String json) {
            runOnUiThread(() -> {
                pendingBackup = json;
                Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json")
                        .putExtra(Intent.EXTRA_TITLE, "dock-backup.json");
                try {
                    startActivityForResult(save, REQ_EXPORT);
                } catch (ActivityNotFoundException e) {
                    pendingBackup = null;
                    Toast.makeText(MainActivity.this, "No file picker available", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }
}
