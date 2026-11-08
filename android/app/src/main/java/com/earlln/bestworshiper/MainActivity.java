package com.earlln.bestworshiper;

import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.media.MediaScannerConnection;
import android.os.ParcelFileDescriptor;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
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

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
    // An optional folder offered among the export targets.
    private static final String KEY_DEST = "destTreeUri";
    private static final String PPTX_MIME =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    private ActivityResultLauncher<Uri> pickFolderLauncher;

    // A USB drive plugged into the phone is reached through the document
    // picker like any other volume, but the picker can be opened straight at
    // that volume, so the user only has to confirm. The grant is kept so the
    // same drive is written without asking again.
    private static final String KEY_USB = "usbTreeUri";
    private static final String PHONE_DIR = "bestWorshiper";   // under Download/
    private ActivityResultLauncher<Intent> usbTreeLauncher;
    private byte[] pendingBytes; private String pendingName, pendingMime, pendingSub;

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

        // Exports are dated, so a folder can simply be written into by name;
        // nothing has to be looked up or replaced there.
        pickFolderLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
            if (uri == null) { notifyWeb('"' + "destCancelled" + '"'); return; }
            persist(uri);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_DEST, uri.toString()).apply();
            toast("Exports can go to " + describeTree(uri));
            notifyWeb(jsonString(describeTree(uri)));
        });

        usbTreeLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
            Uri tree = result.getData() != null ? result.getData().getData() : null;
            byte[] bytes = pendingBytes; String name = pendingName, mime = pendingMime, sub = pendingSub;
            pendingBytes = null; pendingName = null; pendingMime = null; pendingSub = null;
            if (tree == null || bytes == null) { notifyExport(jsonString("cancelled")); return; }
            persist(tree);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_USB, tree.toString()).apply();
            try {
                String saved = writeIntoTree(tree, sub, name, mime, bytes);
                toast("Saved to USB: " + saved + " — eject the drive before unplugging it");
                notifyExport(jsonString("saved:" + saved));
            } catch (Exception e) {
                notifyExport(jsonString("error:" + e.getMessage()));
            }
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

    private void notifyExport(String jsonArg) {
        runOnUiThread(() -> webView.evaluateJavascript(
                "window.onExportResult && window.onExportResult(" + jsonArg + ")", null));
    }

    /**
     * Rewrites fileName under subPath ("EM", or "" for the folder itself) inside
     * a folder we hold a grant on, creating folders and file as needed.
     */
    private String writeIntoTree(Uri tree, String subPath, String fileName, String mime, byte[] bytes) throws Exception {
        DocumentFile dir = DocumentFile.fromTreeUri(this, tree);
        if (dir == null || !dir.canWrite()) throw new IllegalStateException("folder not writable");
        for (String part : (subPath == null ? "" : subPath).split("/")) {
            if (part.isEmpty()) continue;
            DocumentFile next = dir.findFile(part);
            if (next == null || !next.isDirectory()) next = dir.createDirectory(part);
            if (next == null) throw new IllegalStateException("could not create folder " + part);
            dir = next;
        }
        if (bytes.length == 0) throw new IllegalStateException("nothing to write (0 bytes received)");
        DocumentFile file = dir.findFile(fileName);
        boolean created = false;
        if (file == null || !file.isFile()) {
            file = dir.createFile(mime, fileName);
            created = true;
        }
        if (file == null) throw new IllegalStateException("could not create file");
        try {
            writeTruncating(file.getUri(), bytes);
            // A deck that opens with no slides is a short write; check the size
            // on disk rather than report a success. Read it back through the
            // descriptor: DocumentFile.length() can be a stale cached value.
            long onDisk = sizeOnDisk(file.getUri());
            if (onDisk < 0) onDisk = file.length();
            if (onDisk != bytes.length) {
                throw new IllegalStateException("wrote " + onDisk + " of " + bytes.length + " bytes");
            }
        } catch (Exception e) {
            // Leave no empty file behind to be mistaken for the deck.
            if (created || file.length() == 0) file.delete();
            throw e;
        }
        String name = file.getName();
        return (name != null ? name : fileName) + " (" + sizeLabel(bytes.length) + ")";
    }

    /**
     * Writes the whole file, truncating first. "wt" is the mode that truncates;
     * a provider that rejects it gets "rwt", and failing that plain "w" on a
     * file that is already empty.
     */
    private void writeTruncating(Uri uri, byte[] bytes) throws Exception {
        Exception last = null;
        for (String mode : new String[]{"wt", "rwt", "w"}) {
            try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, mode)) {
                if (pfd == null) throw new IllegalStateException("no descriptor (" + mode + ")");
                try (FileOutputStream out = new FileOutputStream(pfd.getFileDescriptor())) {
                    out.write(bytes);
                    out.flush();
                    // A USB stick pulled out before the kernel flushes its cache
                    // keeps a 0-byte directory entry; force the data down first.
                    try { pfd.getFileDescriptor().sync(); } catch (Exception ignored) { /* provider without fsync */ }
                }
                return;
            } catch (Exception e) {
                last = e;
            }
        }
        throw new IllegalStateException("write failed: " + (last != null ? last.getMessage() : "?"));
    }

    /** The size the file really has now, read back through the provider; -1 if unreadable. */
    private long sizeOnDisk(Uri uri) {
        try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r")) {
            return pfd == null ? -1 : pfd.getStatSize();
        } catch (Exception e) {
            return -1;
        }
    }

    private static String sizeLabel(long bytes) {
        return bytes >= 1_000_000 ? String.format(java.util.Locale.US, "%.1f MB", bytes / 1_000_000.0)
             : bytes >= 1_000 ? (bytes / 1_000) + " KB" : bytes + " B";
    }

    /** The mounted, removable volume — the USB drive — or null. */
    private StorageVolume usbVolume() {
        StorageManager sm = (StorageManager) getSystemService(Context.STORAGE_SERVICE);
        if (sm == null) return null;
        for (StorageVolume v : sm.getStorageVolumes()) {
            if (v.isRemovable() && !v.isPrimary() && Environment.MEDIA_MOUNTED.equals(v.getState())) return v;
        }
        return null;
    }

    private static String jsonString(String s) {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private void persist(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception e) { /* some providers grant only for this session */ }
    }

    private String describeTree(Uri tree) {
        try {
            DocumentFile dir = DocumentFile.fromTreeUri(this, tree);
            String name = dir != null ? dir.getName() : null;
            if (name != null) return name;
        } catch (Exception ignored) { /* fall through to the raw path */ }
        String last = tree.getLastPathSegment();
        return last != null ? last : tree.toString();
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

        /**
         * Starts a Downloads file the page writes piece by piece. A backup carries
         * every photo and video, and handing it over as one base64 string runs the
         * WebView out of string length long before the phone runs out of space.
         * Returns a handle for appendText/finishFile, or "" if the file can't be made.
         */
        @JavascriptInterface
        public String beginFile(String fileName, String mimeType) {
            PendingFile pf = new PendingFile();
            pf.name = fileName;
            pf.mime = mimeType;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                    values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
                    values.put(MediaStore.Downloads.IS_PENDING, 1);
                    pf.uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (pf.uri == null) throw new IllegalStateException("insert failed");
                    OutputStream out = getContentResolver().openOutputStream(pf.uri);
                    if (out == null) throw new IllegalStateException("stream failed");
                    pf.out = new BufferedOutputStream(out, 1 << 16);
                } else {
                    File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("mkdir failed");
                    pf.file = new File(dir, fileName);
                    pf.out = new BufferedOutputStream(new FileOutputStream(pf.file), 1 << 16);
                }
            } catch (Exception e) {
                discard(pf);
                toast("저장 실패: " + e.getMessage());
                return "";
            }
            String id = UUID.randomUUID().toString();
            pendingFiles.put(id, pf);
            return id;
        }

        /** Appends text, as UTF-8, to a file from beginFile. False means it was abandoned. */
        @JavascriptInterface
        public boolean appendText(String id, String text) {
            PendingFile pf = pendingFiles.get(id);
            if (pf == null) return false;
            try {
                pf.out.write(text.getBytes(StandardCharsets.UTF_8));
                return true;
            } catch (Exception | OutOfMemoryError e) {
                pendingFiles.remove(id);
                discard(pf);
                toast("저장 실패: " + e.getMessage());
                return false;
            }
        }

        /** Closes a file from beginFile and makes it visible in Downloads. */
        @JavascriptInterface
        public boolean finishFile(String id) {
            PendingFile pf = pendingFiles.remove(id);
            if (pf == null) return false;
            try {
                pf.out.close();
                pf.out = null;
                if (pf.uri != null) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.IS_PENDING, 0);
                    getContentResolver().update(pf.uri, values, null, null);
                } else {
                    DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                    if (dm != null) {
                        dm.addCompletedDownload(pf.name, pf.name, true, pf.mime,
                                pf.file.getAbsolutePath(), pf.file.length(), true);
                    }
                }
                toast("다운로드 폴더에 저장되었습니다: " + pf.name);
                return true;
            } catch (Exception e) {
                discard(pf);
                toast("저장 실패: " + e.getMessage());
                return false;
            }
        }

        /** Drops a half-written file from beginFile, so no broken backup is left behind. */
        @JavascriptInterface
        public void abortFile(String id) {
            PendingFile pf = pendingFiles.remove(id);
            if (pf != null) discard(pf);
        }

        /** Asks for a folder to offer among the export targets. */
        @JavascriptInterface
        public void pickDestinationFolder() {
            runOnUiThread(() -> {
                try {
                    pickFolderLauncher.launch(null);
                } catch (Exception e) {
                    toast("Could not open the folder picker: " + e.getMessage());
                }
            });
        }

        /** The chosen folder's name, or "" when none is set. */
        @JavascriptInterface
        public String destinationName() {
            String dir = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_DEST, null);
            if (dir == null) return "";
            try { return describeTree(Uri.parse(dir)); } catch (Exception e) { return ""; }
        }

        @JavascriptInterface
        public void clearDestination() {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_DEST).apply();
            toast("Export folder cleared.");
        }

        /**
         * Writes a file under subPath inside the chosen folder. Returns "" on
         * success or a reason, so the page can say what happened.
         */
        @JavascriptInterface
        public String saveToDestination(String fileName, String base64Data,
                                        String mimeType, String subPath) {
            String tree = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_DEST, null);
            if (tree == null) return "no-destination";
            try {
                String saved = writeIntoTree(Uri.parse(tree), subPath, fileName, mimeType,
                        Base64.decode(base64Data, Base64.DEFAULT));
                toast("Saved " + saved);
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

        /**
         * Writes the deck to the phone itself, under Download/bestWorshiper,
         * replacing the previous week's. Returns "" or a reason.
         */
        @JavascriptInterface
        public String saveToPhone(String fileName, String base64Data, String mimeType, String subPath) {
            try {
                byte[] bytes = Base64.decode(base64Data, Base64.DEFAULT);
                String sub = subPath == null ? "" : subPath.replaceAll("^/+|/+$", "");
                String where = "Download/" + PHONE_DIR + "/" + (sub.isEmpty() ? "" : sub + "/");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                    // Our earlier copy can be deleted; one left by a previous install
                    // cannot, and then the new one gets a "(1)" — reported below.
                    try {
                        getContentResolver().delete(collection,
                                MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                                new String[]{where, fileName});
                    } catch (Exception ignored) { /* not ours to delete */ }

                    ContentValues values = new ContentValues();
                    values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                    values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH, where);
                    values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                    if (bytes.length == 0) return "error:0 bytes received from the page";
                    Uri item = getContentResolver().insert(collection, values);
                    if (item == null) return "insert-failed";
                    try {
                        writeTruncating(item, bytes);
                        values.clear();
                        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                        getContentResolver().update(item, values, null, null);
                    } catch (Exception e) {
                        getContentResolver().delete(item, null, null);   // no empty file left behind
                        return "error:" + e.getMessage();
                    }

                    String name = fileName; long size = -1; String path = null;
                    try (android.database.Cursor c = getContentResolver().query(item,
                            new String[]{MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATA}, null, null, null)) {
                        if (c != null && c.moveToFirst()) { name = c.getString(0); size = c.getLong(1); path = c.getString(2); }
                    }
                    long onDisk = sizeOnDisk(item);
                    if (onDisk >= 0 && onDisk != bytes.length) {
                        getContentResolver().delete(item, null, null);
                        return "short-write:" + onDisk + "/" + bytes.length;
                    }
                    if (size != bytes.length && path != null) {
                        // The file is whole but the media index still says otherwise —
                        // and a PC over the USB cable copies the size from that index,
                        // which is how a good deck arrives as 0 bytes. Rescan it.
                        MediaScannerConnection.scanFile(this_(), new String[]{path}, new String[]{mimeType}, null);
                    }
                    toast("Saved to " + where + name + " (" + sizeLabel(bytes.length) + ")");
                    return fileName.equals(name) ? "saved:" + name + " (" + sizeLabel(bytes.length) + ")" : "renamed:" + name;
                } else {
                    File dir = new File(Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS), PHONE_DIR + (sub.isEmpty() ? "" : "/" + sub));
                    if (!dir.exists() && !dir.mkdirs()) return "mkdir-failed";
                    try (OutputStream out = new FileOutputStream(new File(dir, fileName))) {
                        out.write(bytes);
                    }
                    toast("Saved to " + where + fileName);
                    return "";
                }
            } catch (Exception e) {
                return "error:" + e.getMessage();
            }
        }

        /**
         * Writes the deck to the USB drive. With a grant on that drive already
         * held this finishes here and returns "saved:<name>"; otherwise it opens
         * the picker at the drive and returns "picking", and the result arrives
         * through window.onExportResult. "no-usb" when nothing is plugged in.
         */
        @JavascriptInterface
        public String exportToUsb(String fileName, String base64Data, String mimeType, String subPath) {
            StorageVolume usb = usbVolume();
            if (usb == null) return "no-usb";
            byte[] bytes = Base64.decode(base64Data, Base64.DEFAULT);

            if (bytes.length == 0) return "error:0 bytes received from the page";
            String held = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_USB, null);
            if (held != null) {
                try {
                    String saved = writeIntoTree(Uri.parse(held), subPath, fileName, mimeType, bytes);
                    toast("Saved to USB: " + saved + " — eject the drive before unplugging it");
                    return "saved:" + saved;
                } catch (Exception e) {
                    // The grant may have lapsed or the drive changed — ask for the
                    // drive again — but say what went wrong, since a failed write
                    // on the right drive would otherwise go unexplained.
                    toast("USB write failed (" + e.getMessage() + ") — choose the drive again");
                }
            }

            pendingBytes = bytes; pendingName = fileName; pendingMime = mimeType; pendingSub = subPath;
            runOnUiThread(() -> {
                try {
                    Intent intent = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                            ? usb.createOpenDocumentTreeIntent()
                            : new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    usbTreeLauncher.launch(intent);
                } catch (Exception e) {
                    pendingBytes = null;
                    notifyExport(jsonString("error:" + e.getMessage()));
                }
            });
            return "picking";
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

        private void discard(PendingFile pf) {
            try { if (pf.out != null) pf.out.close(); } catch (Exception ignored) {}
            try { if (pf.uri != null) getContentResolver().delete(pf.uri, null, null); } catch (Exception ignored) {}
            if (pf.file != null) pf.file.delete();
        }
    }

    /** A Downloads file the page is still writing through beginFile/appendText. */
    private static class PendingFile {
        String name;
        String mime;
        Uri uri;
        File file;
        OutputStream out;
    }

    private final Map<String, PendingFile> pendingFiles = new ConcurrentHashMap<>();
}
