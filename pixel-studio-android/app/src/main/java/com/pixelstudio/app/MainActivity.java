package com.pixelstudio.app;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Pixel Studio for Android: the same web app as the site, bundled in the APK and served from
 * https://appassets.androidplatform.net (a secure origin, so the video engine works offline).
 */
public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String START = "https://" + HOST + "/assets/studio/index.html";
    private static final int PICK_FILE = 1;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);   // exports keep running

        web = new WebView(this);
        web.setBackgroundColor(0xFF0D0B1A);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                WebResourceResponse r = loader.shouldInterceptRequest(url);
                if (r != null) {
                    String p = url.getPath() == null ? "" : url.getPath();
                    // JavaScript modules are only run with a JavaScript type
                    if (p.endsWith(".mjs") || p.endsWith(".js")) r.setMimeType("text/javascript");
                    else if (p.endsWith(".webmanifest")) r.setMimeType("application/manifest+json");
                }
                return r;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (HOST.equals(url.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, url)); } catch (Exception ignored) { }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
                try {
                    startActivityForResult(Intent.createChooser(i, "Choisir une image ou une vidéo"), PICK_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        web.addJavascriptInterface(new SaveBridge(), "PixelStudioAndroid");

        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 2);
        }

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(START);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCallback = null;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    /** Receives a finished file from the page in base64 pieces and saves it in the gallery. */
    private class SaveBridge {
        private final Map<Integer, Sink> sinks = new HashMap<>();
        private int nextId = 1;

        private class Sink {
            OutputStream out;
            Uri uri;       // Android 10+
            File file;     // older Android
            String where;
        }

        @JavascriptInterface
        public synchronized int begin(String name, String mime) {
            try {
                boolean video = mime != null && mime.startsWith("video/");
                String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
                Sink k = new Sink();
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentResolver cr = getContentResolver();
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.DISPLAY_NAME, safe);
                    v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                    String dir = video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES;
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, dir + "/Pixel Studio");
                    v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                    Uri base = video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                    k.uri = cr.insert(base, v);
                    if (k.uri == null) return -1;
                    k.out = cr.openOutputStream(k.uri);
                    k.where = (video ? "Films" : "Images") + " › Pixel Studio (Galerie)";
                } else {
                    File dir;
                    if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                        dir = new File(Environment.getExternalStoragePublicDirectory(
                                video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES), "Pixel Studio");
                    } else {
                        dir = new File(getExternalFilesDir(null), "Pixel Studio");
                    }
                    if (!dir.exists() && !dir.mkdirs()) return -1;
                    k.file = new File(dir, safe);
                    k.out = new FileOutputStream(k.file);
                    k.where = dir.getAbsolutePath();
                }
                int id = nextId++;
                sinks.put(id, k);
                return id;
            } catch (Exception e) {
                return -1;
            }
        }

        @JavascriptInterface
        public synchronized boolean chunk(int id, String base64) {
            Sink k = sinks.get(id);
            if (k == null) return false;
            try {
                k.out.write(Base64.decode(base64, Base64.DEFAULT));
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public synchronized String end(int id) {
            Sink k = sinks.remove(id);
            if (k == null) return "ERR:fichier inconnu";
            try {
                k.out.close();
                if (k.uri != null) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    getContentResolver().update(k.uri, v, null, null);
                } else if (k.file != null) {
                    MediaScannerConnection.scanFile(MainActivity.this, new String[]{k.file.getAbsolutePath()}, null, null);
                }
                return k.where;
            } catch (Exception e) {
                return "ERR:" + e.getMessage();
            }
        }
    }
}
