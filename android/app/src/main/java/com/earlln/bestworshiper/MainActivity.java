package com.earlln.bestworshiper;

import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.webkit.WebViewAssetLoader;

import java.io.OutputStream;

/**
 * Hosts the bestWorshiper web app inside a WebView.
 *
 * Assets are served through WebViewAssetLoader on https://appassets.androidplatform.net
 * rather than file://, because a file:// page is an opaque origin where IndexedDB —
 * which stores the entire roster, history and media — is unavailable.
 */
public class MainActivity extends AppCompatActivity {

    private static final String APP_URL =
            "https://appassets.androidplatform.net/assets/bestWorshiper.html";

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private ActivityResultLauncher<String> fileChooserLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        fileChooserLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (filePathCallback == null) return;
                    filePathCallback.onReceiveValue(uri == null ? null : new Uri[]{uri});
                    filePathCallback = null;
                });

        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view,
                                             ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;

                String mime = "*/*";
                String[] accept = params.getAcceptTypes();
                if (accept != null && accept.length > 0 && !accept[0].isEmpty()) {
                    // A single accept entry maps cleanly; mixed lists fall back to any file.
                    mime = accept.length == 1 ? accept[0] : "*/*";
                }

                try {
                    fileChooserLauncher.launch(mime);
                    return true;
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
            }
        });

        webView.addJavascriptInterface(new SaveFileBridge(), "AndroidBridge");

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    finish();
                }
            }
        });

        if (savedInstanceState == null) {
            webView.loadUrl(APP_URL);
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    /**
     * Lets the web app hand a backup file to Android. A WebView cannot follow the
     * blob: download the browser build uses, so exports are routed here instead.
     */
    private class SaveFileBridge {
        @JavascriptInterface
        public void saveFile(String fileName, String base64Data, String mimeType) {
            try {
                byte[] bytes = Base64.decode(base64Data, Base64.DEFAULT);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                    values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
                    values.put(MediaStore.Downloads.IS_PENDING, 1);

                    Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                    Uri item = getContentResolver().insert(collection, values);
                    if (item == null) throw new IllegalStateException("insert failed");

                    try (OutputStream out = getContentResolver().openOutputStream(item)) {
                        if (out == null) throw new IllegalStateException("stream failed");
                        out.write(bytes);
                    }

                    values.clear();
                    values.put(MediaStore.Downloads.IS_PENDING, 0);
                    getContentResolver().update(item, values, null, null);
                } else {
                    java.io.File dir = Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS);
                    if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("mkdir failed");
                    java.io.File target = new java.io.File(dir, fileName);
                    try (OutputStream out = new java.io.FileOutputStream(target)) {
                        out.write(bytes);
                    }

                    DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                    if (dm != null) {
                        dm.addCompletedDownload(fileName, fileName, true,
                                mimeType, target.getAbsolutePath(), bytes.length, true);
                    }
                }

                toast("다운로드 폴더에 저장되었습니다: " + fileName);
            } catch (Exception e) {
                toast("저장 실패: " + e.getMessage());
            }
        }

        private void toast(final String msg) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
        }
    }
}
