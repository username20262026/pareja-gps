package com.pareja.gps;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;

/** Widgets de la pantalla de inicio (tarjeta, mapa, corazon, mini): donde esta tu pareja y a que distancia. */
public class ParejaWidget extends AppWidgetProvider {
    static final String ACTION_REFRESH = "com.pareja.gps.WIDGET_REFRESH";

    static class Data {
        int state; // 0 sin sesion, 1 listo, 2 cargando, 3 esperando pareja, 4 sin conexion
        String name = "";
        String avatar = "";
        String distShort = "";
        String distLong = "";
        String ago = "";
        String mood = "";
        Bitmap map = null;

        static Data of(int s) {
            Data d = new Data();
            d.state = s;
            return d;
        }
    }

    /** 0 tarjeta, 1 mapa cuadrado, 2 corazon, 3 mini. Las subclases lo cambian. */
    int kind() {
        return 0;
    }

    private static final Class<?>[] KINDS = {
        ParejaWidget.class, ParejaWidgetMapa.class, ParejaWidgetCorazon.class, ParejaWidgetMini.class
    };

    // temas: color inicial, color final
    private static final int[][] THEMES = {
        { 0xFFEC4899, 0xFFF43F5E }, // rosa
        { 0xFFFB923C, 0xFFEC4899 }, // atardecer
        { 0xFF38BDF8, 0xFF6366F1 }, // oceano
        { 0xFFA78BFA, 0xFFEC4899 }, // lavanda
        { 0xFF2A1033, 0xFF5B1D3C }  // noche
    };

    private static String idTok = null;
    private static long idExp = 0;

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent != null && ACTION_REFRESH.equals(intent.getAction())) {
            async(ctx);
            return;
        }
        super.onReceive(ctx, intent);
    }

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        for (int i = 0; i < ids.length; i++) {
            int[] sz = size(mgr, ids[i]);
            mgr.updateAppWidget(ids[i], build(ctx, Data.of(2), kind(), sz[0], sz[1]));
        }
        async(ctx);
    }

    private void async(final Context ctx) {
        final PendingResult pr = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    refreshNow(ctx);
                } catch (Exception ignored) {
                } finally {
                    pr.finish();
                }
            }
        }).start();
    }

    private static int[] size(AppWidgetManager mgr, int id) {
        int w = 160, h = 160;
        try {
            Bundle o = mgr.getAppWidgetOptions(id);
            if (o != null) {
                int ow = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
                int oh = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0);
                if (ow > 0) w = ow;
                if (oh > 0) h = oh;
            }
        } catch (Exception ignored) {
        }
        return new int[] { w, h };
    }

    /** Descarga los datos y repinta todos los widgets. Llamar desde un hilo de fondo. */
    static void refreshNow(Context ctx) {
        Context app = ctx.getApplicationContext();
        AppWidgetManager mgr = AppWidgetManager.getInstance(app);
        int[][] all = new int[KINDS.length][];
        boolean any = false;
        boolean needMap = false;
        for (int k = 0; k < KINDS.length; k++) {
            all[k] = mgr.getAppWidgetIds(new ComponentName(app, KINDS[k]));
            if (all[k] != null && all[k].length > 0) {
                any = true;
                if (k <= 1) needMap = true;
            }
        }
        if (!any) return;
        Data d;
        try {
            d = load(app, needMap);
        } catch (Exception e) {
            d = Data.of(4);
        }
        for (int k = 0; k < KINDS.length; k++) {
            if (all[k] == null) continue;
            for (int i = 0; i < all[k].length; i++) {
                int[] sz = size(mgr, all[k][i]);
                mgr.updateAppWidget(all[k][i], build(app, d, k, sz[0], sz[1]));
            }
        }
    }

    static RemoteViews build(Context ctx, Data d, int kind, int wDp, int hDp) {
        SharedPreferences sp = ctx.getSharedPreferences(TrackService.PREFS, Context.MODE_PRIVATE);
        int theme = sp.getInt("wTheme", 0);
        int frame = sp.getInt("wFrame", 0);
        boolean useMap = sp.getBoolean("wMap", true);
        String label = sp.getString("wLabel", "");
        int layout = R.layout.widget_pareja;
        if (kind == 1) layout = R.layout.widget_mapa;
        else if (kind == 2) layout = R.layout.widget_corazon;
        else if (kind == 3) layout = R.layout.widget_mini;
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), layout);

        String av = "\uD83D\uDC9E";
        String name = "Pareja GPS";
        String sub = "Toca para abrir";
        String big = "\u2014";
        if (d.state == 0) {
            sub = "Abre la app e inicia sesi\u00f3n";
        } else if (d.state == 2) {
            sub = "Cargando\u2026";
        } else if (d.state == 3) {
            name = "Esperando a tu pareja";
            sub = "A\u00fan no comparte";
        } else if (d.state == 4) {
            sub = "Sin conexi\u00f3n \u00b7 toca \uD83D\uDD04";
        } else if (d.state == 1) {
            if (d.avatar.length() > 0) av = d.avatar;
            name = d.name.length() > 0 ? d.name : "Tu pareja";
            big = d.distShort.length() > 0 ? d.distShort : "\u2014";
            sub = d.distLong.length() > 0 ? d.distLong + " \u00b7 " + d.ago : d.ago;
        }
        rv.setTextViewText(R.id.w_avatar, av);
        if (kind == 0 || kind == 3) {
            rv.setTextViewText(R.id.w_name, name);
            rv.setTextViewText(R.id.w_sub, sub);
        } else if (kind == 1) {
            rv.setTextViewText(R.id.w_name, d.state == 1 ? big : name);
            rv.setTextViewText(R.id.w_sub, d.state == 1 ? d.ago : sub);
        } else {
            rv.setTextViewText(R.id.w_name, name);
            rv.setTextViewText(R.id.w_dist, big);
            rv.setTextViewText(R.id.w_sub, d.state == 1 ? (d.mood + " \u00b7 " + d.ago) : sub);
        }
        if (kind != 3) {
            if (label.length() > 0) {
                rv.setTextViewText(R.id.w_label, label);
                rv.setViewVisibility(R.id.w_label, View.VISIBLE);
            } else {
                rv.setViewVisibility(R.id.w_label, View.GONE);
            }
        }
        Bitmap mapBmp = (useMap && kind <= 1) ? d.map : null;
        rv.setImageViewBitmap(R.id.w_bg, art(ctx, wDp, hDp, theme, frame, mapBmp, mapBmp == null && kind != 3));

        int fl = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) fl |= PendingIntent.FLAG_IMMUTABLE;
        Intent open = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
        if (open != null) {
            rv.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(ctx, 0, open, fl));
        }
        Intent ref = new Intent(ctx, ParejaWidget.class);
        ref.setAction(ACTION_REFRESH);
        rv.setOnClickPendingIntent(R.id.w_refresh, PendingIntent.getBroadcast(ctx, 1, ref, fl));
        return rv;
    }

    // ---------- Fondo dibujado: degradado + marco + mapa ----------
    private static Bitmap art(Context ctx, int wDp, int hDp, int theme, int frame, Bitmap map, boolean decor) {
        float d = ctx.getResources().getDisplayMetrics().density;
        int w = Math.round(wDp * d);
        int h = Math.round(hDp * d);
        int maxSide = 720;
        if (Math.max(w, h) > maxSide) {
            float k = maxSide / (float) Math.max(w, h);
            w = Math.round(w * k);
            h = Math.round(h * k);
            d = d * k;
        }
        if (w < 48) w = 48;
        if (h < 32) h = 32;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        float rad = Math.min(22f * d, Math.min(w, h) / 2f);
        int[] col = THEMES[Math.abs(theme) % THEMES.length];
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        // fondo con degradado
        p.setShader(new LinearGradient(0, 0, w, h, col[0], col[1], Shader.TileMode.CLAMP));
        c.drawRoundRect(new RectF(0, 0, w, h), rad, rad, p);
        p.setShader(null);

        // corazones suaves de fondo
        if (decor) {
            Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
            t.setTextAlign(Paint.Align.CENTER);
            t.setColor(0x26FFFFFF);
            t.setTextSize(h * 0.55f);
            c.drawText("\u2665", w * 0.84f, h * 0.70f, t);
            t.setTextSize(h * 0.28f);
            c.drawText("\u2665", w * 0.12f, h * 0.95f, t);
            t.setColor(0x33FFFFFF);
            t.setTextSize(h * 0.16f);
            c.drawText("\u2665", w * 0.62f, h * 0.22f, t);
        }

        // mapa dentro del marco
        float mi = 0;
        if (map != null) {
            mi = 8f * d;
            RectF mr = new RectF(mi, mi, w - mi, h - mi);
            float mrad = Math.max(4f, rad - mi * 0.8f);
            Path clip = new Path();
            clip.addRoundRect(mr, mrad, mrad, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            float sw = map.getWidth();
            float sh = map.getHeight();
            float tr = mr.width() / mr.height();
            Rect src;
            if (sw / sh > tr) {
                int nw = Math.round(sh * tr);
                int x0 = (int) ((sw - nw) / 2f);
                src = new Rect(x0, 0, x0 + nw, (int) sh);
            } else {
                int nh = Math.round(sw / tr);
                int y0 = (int) ((sh - nh) / 2f);
                src = new Rect(0, y0, (int) sw, y0 + nh);
            }
            c.drawBitmap(map, src, mr, new Paint(Paint.FILTER_BITMAP_FLAG));
            c.restore();
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.6f * d);
            p.setColor(0xCCFFFFFF);
            c.drawRoundRect(mr, mrad, mrad, p);
        }

        // marco exterior blanco
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3f * d);
        p.setColor(0xFFFFFFFF);
        c.drawRoundRect(new RectF(1.5f * d, 1.5f * d, w - 1.5f * d, h - 1.5f * d), rad, rad, p);

        // adorno interior segun el marco elegido
        float gi = (map != null ? 4.5f : 7f) * d;
        RectF ir = new RectF(gi, gi, w - gi, h - gi);
        float irad = Math.max(4f, rad - gi + 1.5f * d);
        if (frame == 0) { // doble
            p.setStrokeWidth(1.2f * d);
            p.setColor(0xB3FFFFFF);
            c.drawRoundRect(ir, irad, irad, p);
        } else if (frame == 1) { // puntos
            p.setStrokeWidth(2.4f * d);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(0xE6FFFFFF);
            p.setPathEffect(new DashPathEffect(new float[] { 0.1f, 6f * d }, 0f));
            c.drawRoundRect(ir, irad, irad, p);
            p.setPathEffect(null);
        } else if (frame == 2) { // corazones
            Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
            t.setTextAlign(Paint.Align.CENTER);
            t.setColor(0xE6FFFFFF);
            float ts = 9f * d;
            t.setTextSize(ts);
            float off = ts * 0.35f;
            float step = 17f * d;
            float x0 = ir.left + irad * 0.9f;
            float x1 = ir.right - irad * 0.9f;
            float y0 = ir.top + irad * 0.9f;
            float y1 = ir.bottom - irad * 0.9f;
            for (float x = x0; x <= x1; x += step) {
                c.drawText("\u2665", x, ir.top + off, t);
                c.drawText("\u2665", x, ir.bottom + off, t);
            }
            for (float y = y0; y <= y1; y += step) {
                c.drawText("\u2665", ir.left, y + off, t);
                c.drawText("\u2665", ir.right, y + off, t);
            }
            float cr = irad * 0.293f; // esquina: punto a 45 grados del arco
            c.drawText("\u2665", ir.left + cr, ir.top + cr + off, t);
            c.drawText("\u2665", ir.right - cr, ir.top + cr + off, t);
            c.drawText("\u2665", ir.left + cr, ir.bottom - cr + off, t);
            c.drawText("\u2665", ir.right - cr, ir.bottom - cr + off, t);
        }
        return out;
    }

    // ---------- Datos ----------
    private static Data load(Context ctx, boolean needMap) throws Exception {
        SharedPreferences sp = ctx.getSharedPreferences(TrackService.PREFS, Context.MODE_PRIVATE);
        String uid = sp.getString("uid", "");
        String room = sp.getString("room", "");
        String pid = sp.getString("projectId", "");
        if (uid.length() == 0 || room.length() == 0 || pid.length() == 0) return Data.of(0);
        String tok = token(sp);
        if (tok == null) return Data.of(4);

        StringBuilder out = new StringBuilder();
        String url = "https://firestore.googleapis.com/v1/projects/" + pid
                + "/databases/(default)/documents/rooms/" + room + "/users?pageSize=10";
        int code = http("GET", url, null, null, tok, out);
        if (code == 401 || code == 403) {
            idTok = null;
            return Data.of(4);
        }
        if (code != 200) return Data.of(4);

        JSONArray docs = new JSONObject(out.toString()).optJSONArray("documents");
        double myLat = Double.NaN, myLng = Double.NaN;
        double pLat = Double.NaN, pLng = Double.NaN;
        long pTs = 0;
        String pName = "", pAvatar = "";
        boolean havePartner = false;
        if (docs != null) {
            for (int i = 0; i < docs.length(); i++) {
                JSONObject doc = docs.getJSONObject(i);
                JSONObject f = doc.optJSONObject("fields");
                if (f == null) continue;
                String nm = doc.optString("name", "");
                String id = nm.substring(nm.lastIndexOf('/') + 1);
                if (id.equals(uid)) {
                    myLat = num(f, "lat");
                    myLng = num(f, "lng");
                } else {
                    havePartner = true;
                    pLat = num(f, "lat");
                    pLng = num(f, "lng");
                    double t = num(f, "ts");
                    pTs = Double.isNaN(t) ? 0 : (long) t;
                    pName = txt(f, "name");
                    pAvatar = txt(f, "avatar");
                }
            }
        }
        if (!havePartner || Double.isNaN(pLat) || Double.isNaN(pLng)) {
            Data w = Data.of(3);
            if (havePartner) w.name = pName;
            return w;
        }
        if (Double.isNaN(myLat) && TrackService.lastTs > 0) {
            myLat = TrackService.lastLat;
            myLng = TrackService.lastLng;
        }
        Data d = Data.of(1);
        d.name = pName;
        d.avatar = pAvatar;
        d.ago = ago(pTs);
        if (!Double.isNaN(myLat) && !Double.isNaN(myLng)) {
            double dk = km(myLat, myLng, pLat, pLng);
            d.distShort = distShort(dk);
            d.distLong = distLong(dk);
            d.mood = mood(dk);
        } else {
            d.mood = "\uD83D\uDC97";
        }
        String mk = sp.getString("mapboxKey", "");
        if (needMap && mk.length() > 0 && sp.getBoolean("wMap", true)) {
            try {
                d.map = staticMap(mk, pLat, pLng);
            } catch (Exception ignored) {
            }
        }
        return d;
    }

    private static double num(JSONObject f, String k) {
        JSONObject v = f.optJSONObject(k);
        if (v == null) return Double.NaN;
        try {
            if (v.has("doubleValue")) return v.getDouble("doubleValue");
            if (v.has("integerValue")) return Double.parseDouble(v.getString("integerValue"));
        } catch (Exception ignored) {
        }
        return Double.NaN;
    }

    private static String txt(JSONObject f, String k) {
        JSONObject v = f.optJSONObject(k);
        return v == null ? "" : v.optString("stringValue", "");
    }

    private static double km(double la1, double lo1, double la2, double lo2) {
        double r = 6371.0;
        double dLa = Math.toRadians(la2 - la1);
        double dLo = Math.toRadians(lo2 - lo1);
        double a = Math.sin(dLa / 2) * Math.sin(dLa / 2)
                + Math.cos(Math.toRadians(la1)) * Math.cos(Math.toRadians(la2)) * Math.sin(dLo / 2) * Math.sin(dLo / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }

    private static String distShort(double km) {
        if (km < 0.05) return "\u00a1Juntos!";
        if (km < 1) return (Math.round(km * 100.0) * 10) + " m";
        return String.format(Locale.getDefault(), "%.1f km", km);
    }

    private static String distLong(double km) {
        if (km < 0.05) return "est\u00e1n juntos";
        return "a " + distShort(km) + " de ti";
    }

    private static String mood(double km) {
        if (km < 0.05) return "\uD83E\uDD70 Juntos";
        if (km < 1) return "\uD83D\uDC97 Muy cerca";
        if (km < 10) return "\uD83D\uDC95 Cerca";
        return "\uD83C\uDF0D Lejos";
    }

    private static String ago(long ts) {
        if (ts <= 0) return "sin datos";
        long m = (System.currentTimeMillis() - ts) / 60000L;
        if (m < 1) return "ahora";
        if (m < 60) return "hace " + m + " min";
        long h = m / 60;
        if (h < 24) return "hace " + h + " h";
        return "hace " + (h / 24) + " d";
    }

    // ---------- Mapa (Mapbox Static Images) ----------
    private static Bitmap staticMap(String key, double lat, double lng) throws Exception {
        String ll = String.format(Locale.US, "%.6f,%.6f", lng, lat);
        String url = "https://api.mapbox.com/styles/v1/mapbox/satellite-streets-v12/static/pin-l+f43f5e("
                + ll + ")/" + ll + ",15,0/400x400@2x?access_token=" + URLEncoder.encode(key, "UTF-8");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            if (c.getResponseCode() != 200) return null;
            InputStream is = c.getInputStream();
            Bitmap src = BitmapFactory.decodeStream(is);
            is.close();
            return src;
        } finally {
            c.disconnect();
        }
    }

    // ---------- Red ----------
    private static String token(SharedPreferences sp) {
        if (idTok != null && System.currentTimeMillis() < idExp - 60000L) return idTok;
        try {
            String rt = sp.getString("refreshToken", "");
            String key = sp.getString("apiKey", "");
            if (rt.length() == 0 || key.length() == 0) return null;
            String url = "https://securetoken.googleapis.com/v1/token?key=" + URLEncoder.encode(key, "UTF-8");
            String form = "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(rt, "UTF-8");
            StringBuilder out = new StringBuilder();
            int code = http("POST", url, form, "application/x-www-form-urlencoded", null, out);
            if (code != 200) return null;
            JSONObject j = new JSONObject(out.toString());
            idTok = j.getString("id_token");
            idExp = System.currentTimeMillis() + Long.parseLong(j.optString("expires_in", "3600")) * 1000L;
            String nrt = j.optString("refresh_token", "");
            if (nrt.length() > 0 && !nrt.equals(rt)) sp.edit().putString("refreshToken", nrt).apply();
            return idTok;
        } catch (Exception e) {
            return null;
        }
    }

    private static int http(String method, String url, String body, String ctype, String bearer, StringBuilder out) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestMethod(method);
            if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", ctype);
                OutputStream os = c.getOutputStream();
                try {
                    os.write(body.getBytes("UTF-8"));
                } finally {
                    os.close();
                }
            }
            int code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (is != null) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int r;
                while ((r = is.read(buf)) != -1) bo.write(buf, 0, r);
                is.close();
                out.append(bo.toString("UTF-8"));
            }
            return code;
        } finally {
            c.disconnect();
        }
    }
}
