package com.earlln.bestworshiper;

import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.documentfile.provider.DocumentFile;
import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileOutputStream;
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
    private ActivityResultLauncher<Uri> takePictureLauncher;
    private ActivityResultLauncher<Uri> takeVideoLauncher;
    /** Where the camera app is writing; also what we hand back on success. */
    private Uri pendingCaptureUri;

    private static final String PREFS = "bestWorshiper";
    // The deck file the user picked once; every export rewrites that very file.
    private static final String KEY_FILE = "exportFileUri";
    private static final String PPTX_MIME =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    private ActivityResultLauncher<String[]> pickFileLauncher;
    private ActivityResultLauncher<String> createFileLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        fileChooserLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> deliverToWeb(uri));

        takePictureLauncher = registerForActivityResult(
                new ActivityResultContracts.TakePicture(),
                ok -> deliverToWeb(Boolean.TRUE.equals(ok) ? pendingCaptureUri : null));

        takeVideoLauncher = registerForActivityResult(
                new ActivityResultContracts.CaptureVideo(),
                ok -> deliverToWeb(Boolean.TRUE.equals(ok) ? pendingCaptureUri : null));

        // The export target is one file the user picks once, and every week's
        // deck is written into it. Picking the file rather than a folder keeps
        // the app out of the provider's name lookup: OneDrive answers that from
        // a stale listing, and the create that follows lands on the server as
        // "BestWorshiper 1.pptx" while the phone still reports the name asked for.
        ActivityResultCallback<Uri> remember = uri -> {
            if (uri == null) { notifyWeb('"' + "destCancelled" + '"'); return; }
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception e) { /* some providers grant only for this session */ }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_FILE, uri.toString()).apply();
            toast("Exports will overwrite " + describeFile(uri));
            notifyWeb(jsonString(describeFile(uri)));
        };
        pickFileLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), remember);
        createFileLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument(PPTX_MIME), remember);

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

                String[] accept = params.getAcceptTypes();
                String want = (accept != null && accept.length == 1 && accept[0] != null)
                        ? accept[0].trim() : "";

                // capture="environment" on the input means the page wants the
                // camera itself, not a file already on the device.
                if (params.isCaptureEnabled() && (want.startsWith("image/") || want.startsWith("video/"))) {
                    if (launchCamera(want.startsWith("video/"))) return true;
                    // Fall through to the picker if no camera app answered.
                }

                // Only media filters are narrowed. A backup file is requested as
                // application/json, but pickers often report .json files as
                // octet-stream and would grey them out, so those stay unfiltered —
                // the web app validates the contents on import anyway.
                String mime = (want.startsWith("image/") || want.startsWith("video/")) ? want : "*/*";

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
     * Hands the result back to the waiting <input type="file">. The callback must
     * be answered exactly once — a WebView whose chooser never replies will
     * refuse to open another one for the rest of the session.
     */
    private void deliverToWeb(Uri uri) {
        if (filePathCallback == null) return;
        filePathCallback.onReceiveValue(uri == null ? null : new Uri[]{uri});
        filePathCallback = null;
        pendingCaptureUri = null;
    }

    /** Tells the page how a folder pick turned out. */
    private void notifyWeb(String jsonArg) {
        runOnUiThread(() -> webView.evaluateJavascript(
                "window.onDestinationPicked && window.onDestinationPicked(" + jsonArg + ")", null));
    }

    private static String jsonString(String s) {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private String describeFile(Uri doc) {
        try {
            DocumentFile f = DocumentFile.fromSingleUri(this, doc);
            String name = f != null ? f.getName() : null;
            if (name != null) return name;
        } catch (Exception ignored) { /* fall through to the raw path */ }
        String last = doc.getLastPathSegment();
        return last != null ? last : doc.toString();
    }

    private void toast(final String msg) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
    }

    /** Opens the camera, writing into a file this app can share back. */
    private boolean launchCamera(boolean video) {
        try {
            File dir = new File(getCacheDir(), "capture");
            if (!dir.exists() && !dir.mkdirs()) return false;

            File target = new File(dir, (video ? "video_" : "photo_")
                    + System.currentTimeMillis() + (video ? ".mp4" : ".jpg"));
            pendingCaptureUri = FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", target);

            if (video) takeVideoLauncher.launch(pendingCaptureUri);
            else takePictureLauncher.launch(pendingCaptureUri);
            return true;
        } catch (Exception e) {
            pendingCaptureUri = null;
            return false;
        }
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

        /** Asks for the existing deck file that every export should overwrite. */
        @JavascriptInterface
        public void pickDestination() {
            runOnUiThread(() -> {
                try {
                    pickFileLauncher.launch(new String[]{"*/*"});
                } catch (Exception e) {
                    toast("Could not open the file picker: " + e.getMessage());
                }
            });
        }

        /** Creates the deck file where the user chooses, and remembers it. */
        @JavascriptInterface
        public void createDestination(String suggestedName) {
            runOnUiThread(() -> {
                try {
                    createFileLauncher.launch(suggestedName);
                } catch (Exception e) {
                    toast("Could not open the file picker: " + e.getMessage());
                }
            });
        }

        /** The chosen file's display name, or "" when none is set yet. */
        @JavascriptInterface
        public String destinationName() {
            String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_FILE, null);
            if (saved == null) return "";
            try { return describeFile(Uri.parse(saved)); } catch (Exception e) { return ""; }
        }

        @JavascriptInterface
        public void clearDestination() {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_FILE).apply();
            toast("Export file cleared.");
        }

        /**
         * Rewrites the chosen file in place. Returns "" on success or a reason, so
         * the page can fall back to sharing rather than leave the user with
         * nothing. fileName and subPath stay in the signature for the page's sake;
         * the target is the file the user picked.
         */
        @JavascriptInterface
        public String saveToDestination(String fileName, String base64Data,
                                        String mimeType, String subPath) {
            String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_FILE, null);
            if (saved == null) return "no-destination";
            try {
                Uri uri = Uri.parse(saved);
                byte[] bytes = Base64.decode(base64Data, Base64.DEFAULT);
                if (!writeAll(uri, bytes)) return "stream-failed";
                toast("Saved " + describeFile(uri));
                return "";
            } catch (Exception e) {
                return "error:" + e.getMessage();
            }
        }

        /**
         * Truncates the target and writes the whole file. "wt" is what makes an
         * existing file shorter than the old one come out right; providers that
         * reject the mode fall back to a plain write.
         */
        private boolean writeAll(Uri uri, byte[] bytes) {
            try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) return false;
                out.write(bytes);
                return true;
            } catch (Exception truncateUnsupported) {
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) return false;
                    out.write(bytes);
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }
        }

        /** Hands the file to the share sheet — the route when no folder is set. */
        @JavascriptInterface
        public void shareFile(String fileName, String base64Data, String mimeType) {
            try {
                File dir = new File(getCacheDir(), "share");
                if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("mkdir failed");
                File target = new File(dir, fileName);
                try (OutputStream out = new FileOutputStream(target)) {
                    out.write(Base64.decode(base64Data, Base64.DEFAULT));
                }
                Uri uri = FileProvider.getUriForFile(this_(), getPackageName() + ".fileprovider", target);

                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType(mimeType);
                send.putExtra(Intent.EXTRA_STREAM, uri);
                send.putExtra(Intent.EXTRA_SUBJECT, fileName);
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                runOnUiThread(() -> startActivity(Intent.createChooser(send, "PPT 보내기")));
            } catch (Exception e) {
                toast("공유 실패: " + e.getMessage());
            }
        }

        private MainActivity this_() { return MainActivity.this; }

        private void toast(final String msg) {
            MainActivity.this.toast(msg);
        }
    }
}
