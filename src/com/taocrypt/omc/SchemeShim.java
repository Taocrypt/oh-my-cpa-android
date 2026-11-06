package com.taocrypt.omc;

import android.content.Context;
import android.content.res.Configuration;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The console's 「跟随系统」 asks matchMedia('(prefers-color-scheme: light)'). This WebView (targetSdk 28,
 * dark app theme) never answers "light", so the console stayed dark. We put a small matchMedia shim
 * into the console's HTML before any of its scripts run; it answers prefers-color-scheme from the
 * phone's night-mode setting, and {@link #js(boolean)} flips it live when the setting changes.
 */
final class SchemeShim {
    private SchemeShim() {}

    static boolean systemDark(Context c) {
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    static String shim(boolean dark) {
        return "(function(){if(window.__omcScheme)return;var dark=" + dark + ",ls=[],om=window.matchMedia?window.matchMedia.bind(window):null;"
                + "function mk(q,want){var m={media:q,onchange:null,get matches(){return dark===want;},"
                + "addListener:function(f){if(f)ls.push({m:m,f:f});},removeListener:function(f){ls=ls.filter(function(e){return !f||e.f!==f;});},"
                + "addEventListener:function(t,f){if(t==='change'&&f)ls.push({m:m,f:f});},removeEventListener:function(t,f){ls=ls.filter(function(e){return !f||e.f!==f;});},"
                + "dispatchEvent:function(){return true;}};ls.push({m:m,f:null});return m;}"
                + "window.matchMedia=function(q){var r=/prefers-color-scheme\\s*:\\s*(dark|light)/i.exec(String(q));"
                + "if(!r)return om?om(q):{matches:false,media:q,addListener:function(){},removeListener:function(){},addEventListener:function(){},removeEventListener:function(){}};"
                + "return mk(String(q),r[1].toLowerCase()==='dark');};"
                + "window.__omcScheme={set:function(d){d=!!d;if(d===dark)return;dark=d;var seen=[];ls.slice().forEach(function(e){"
                + "var ev={matches:e.m.matches,media:e.m.media};try{if(e.f)(typeof e.f==='function'?e.f.call(e.m,ev):e.f.handleEvent&&e.f.handleEvent(ev));"
                + "else if(seen.indexOf(e.m)<0){seen.push(e.m);if(typeof e.m.onchange==='function')e.m.onchange(ev);}}catch(x){}});}};})();";
    }

    static String js(boolean dark) {
        return "window.__omcScheme&&window.__omcScheme.set(" + dark + ");";
    }

    /** For the console's own HTML document: fetch it, put the shim first in head. null = let WebView load it. */
    static WebResourceResponse intercept(Context c, WebResourceRequest r, String consoleBase) {
        try {
            if (!r.isForMainFrame() || !"GET".equalsIgnoreCase(r.getMethod())) return null;
            String url = r.getUrl().toString();
            if (!url.startsWith(consoleBase)) return null;
            String path = r.getUrl().getPath();
            if (path != null && path.matches(".*\\.[A-Za-z0-9]{1,5}$") && !path.endsWith(".html")) return null;
            HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
            h.setConnectTimeout(5000);
            h.setReadTimeout(15000);
            h.setInstanceFollowRedirects(false);
            for (Map.Entry<String, String> e : r.getRequestHeaders().entrySet()) {
                String k = e.getKey().toLowerCase(Locale.US);
                if (k.equals("accept-encoding") || k.equals("cookie")) continue;
                h.setRequestProperty(e.getKey(), e.getValue());
            }
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) h.setRequestProperty("Cookie", cookie);
            int code = h.getResponseCode();
            String ct = h.getContentType();
            if (code != 200 || ct == null || !ct.toLowerCase(Locale.US).contains("text/html")) { h.disconnect(); return null; }
            List<String> set = h.getHeaderFields().get("Set-Cookie");
            if (set != null) for (String s : set) CookieManager.getInstance().setCookie(url, s);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = h.getInputStream()) {
                byte[] b = new byte[16384];
                int n;
                while ((n = in.read(b)) >= 0) out.write(b, 0, n);
            } finally {
                h.disconnect();
            }
            String html = out.toString("UTF-8");
            String tag = "<script>" + shim(systemDark(c)) + "</script>";
            int i = html.toLowerCase(Locale.US).indexOf("<head");
            int j = i < 0 ? -1 : html.indexOf('>', i);
            html = j < 0 ? tag + html : html.substring(0, j + 1) + tag + html.substring(j + 1);
            Map<String, String> hdr = new HashMap<>();
            hdr.put("Cache-Control", "no-store");
            String csp = h.getHeaderField("Content-Security-Policy");
            if (csp != null) hdr.put("Content-Security-Policy", csp);
            return new WebResourceResponse("text/html", "UTF-8", 200, "OK", hdr,
                    new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return null;
        }
    }
}
