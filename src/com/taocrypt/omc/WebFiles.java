package com.taocrypt.omc;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.Toast;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Locale;

/** File upload (<input type=file>) and downloads (http, data:, blob:) for the console WebViews. */
final class WebFiles {
    private static ValueCallback<Uri[]> pending;
    private static Runnable pendingDone;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private WebFiles() {}

    /** Keeps blob: URLs alive long enough for the download hook to read them. */
    static final String HOOK_JS = "(function(){if(window.__omcFiles)return;window.__omcFiles=1;"
            + "var r=URL.revokeObjectURL.bind(URL);URL.revokeObjectURL=function(u){setTimeout(function(){r(u)},120000)};"
            + "document.addEventListener('click',function(e){var a=e.target&&e.target.closest?e.target.closest('a[download]'):null;"
            + "if(!a||!window.OmcFiles||!/^(blob|data):/.test(a.href))return;e.preventDefault();e.stopPropagation();"
            + "var n=a.getAttribute('download')||'';fetch(a.href).then(function(x){return x.blob()}).then(function(b){"
            + "var f=new FileReader();f.onload=function(){OmcFiles.save(f.result,n,b.type||'')};f.readAsDataURL(b)})"
            + ".catch(function(err){OmcFiles.fail(String(err))})},true)})();";

    static boolean choose(Context c, ValueCallback<Uri[]> cb, WebChromeClient.FileChooserParams p, Runnable done) {
        if (pending != null) {
            pending.onReceiveValue(null);
        }
        pending = cb;
        pendingDone = done;
        Intent i = new Intent(c, PickActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        String[] types = p == null ? null : p.getAcceptTypes();
        ArrayList<String> mimes = new ArrayList<>();
        if (types != null) {
            for (String t : types) {
                for (String one : t.split(",")) {
                    String m = one.trim().toLowerCase(Locale.ROOT);
                    if (m.isEmpty()) continue;
                    if (m.startsWith(".")) {
                        String g = MimeTypeMap.getSingleton().getMimeTypeFromExtension(m.substring(1));
                        if (g != null) mimes.add(g);
                    } else {
                        mimes.add(m);
                    }
                }
            }
        }
        i.putExtra("mimes", mimes.toArray(new String[0]));
        i.putExtra("multiple", p != null && p.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE);
        try {
            c.startActivity(i);
            return true;
        } catch (RuntimeException e) {
            deliver(null);
            return false;
        }
    }

    static void deliver(Uri[] result) {
        ValueCallback<Uri[]> cb = pending;
        Runnable done = pendingDone;
        pending = null;
        pendingDone = null;
        if (cb != null) cb.onReceiveValue(result);
        if (done != null) done.run();
    }

    static void attach(final Context ctx, final WebView web) {
        final Context c = ctx.getApplicationContext();
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void save(String dataUrl, String name, String mime) {
                saveDataUrl(c, dataUrl, name, mime);
            }

            @JavascriptInterface
            public void fail(String why) {
                toast(c, "下载失败：" + why);
            }
        }, "OmcFiles");
        web.setDownloadListener((url, ua, disposition, mime, length) -> {
            if (url.startsWith("data:")) {
                new Thread(() -> saveDataUrl(c, url, URLUtil.guessFileName(url, disposition, mime), mime), "omc-dl").start();
            } else if (url.startsWith("blob:")) {
                String js = "fetch(" + quote(url) + ").then(function(x){return x.blob()}).then(function(b){var f=new FileReader();"
                        + "f.onload=function(){OmcFiles.save(f.result," + quote(URLUtil.guessFileName(url, disposition, mime)) + ",b.type||'')};"
                        + "f.readAsDataURL(b)}).catch(function(e){OmcFiles.fail(String(e))})";
                web.evaluateJavascript(js, null);
            } else {
                new Thread(() -> fetch(c, url, ua, disposition, mime), "omc-dl").start();
            }
        });
    }

    private static String quote(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private static void saveDataUrl(Context c, String dataUrl, String name, String mime) {
        try {
            int comma = dataUrl.indexOf(',');
            String head = dataUrl.substring(5, comma);
            String m = head.split(";")[0];
            if (mime == null || mime.isEmpty()) mime = m.isEmpty() ? "application/octet-stream" : m;
            byte[] data = head.endsWith(";base64") ? Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
                    : Uri.decode(dataUrl.substring(comma + 1)).getBytes("UTF-8");
            store(c, new ByteArrayInputStream(data), fixName(name, mime), mime);
        } catch (Exception e) {
            toast(c, "下载失败：" + e.getMessage());
        }
    }

    private static void fetch(Context c, String url, String ua, String disposition, String mime) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) conn.setRequestProperty("Cookie", cookie);
            if (ua != null) conn.setRequestProperty("User-Agent", ua);
            int code = conn.getResponseCode();
            if (code / 100 != 2) throw new IOException("HTTP " + code);
            String m = conn.getContentType() != null ? conn.getContentType().split(";")[0] : mime;
            String d = conn.getHeaderField("Content-Disposition");
            String name = URLUtil.guessFileName(url, d != null ? d : disposition, m);
            try (InputStream in = conn.getInputStream()) {
                store(c, in, fixName(name, m), m);
            }
        } catch (Exception e) {
            toast(c, "下载失败：" + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String fixName(String name, String mime) {
        if (name == null || name.trim().isEmpty() || name.equals("downloadfile.bin")) {
            String ext = mime == null ? null : MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
            name = "oh-my-cpa-" + System.currentTimeMillis() + (ext != null ? "." + ext : "");
        }
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    /** Writes to Download/OhMyCPA and returns the content uri, or null. */
    static Uri store(Context c, InputStream in, String name, String mime) throws IOException {
        ContentResolver cr = c.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime == null || mime.isEmpty() ? "application/octet-stream" : mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/OhMyCPA");
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("无法写入下载目录");
        try (OutputStream os = cr.openOutputStream(uri)) {
            if (os == null) throw new IOException("无法写入下载目录");
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) >= 0) os.write(b, 0, n);
        } catch (IOException e) {
            cr.delete(uri, null, null);
            throw e;
        }
        v.clear();
        v.put(MediaStore.MediaColumns.IS_PENDING, 0);
        cr.update(uri, v, null, null);
        toast(c, "已保存到 下载/OhMyCPA/" + name);
        return uri;
    }

    static void toast(Context c, String s) {
        MAIN.post(() -> Toast.makeText(c, s, Toast.LENGTH_LONG).show());
    }
}
