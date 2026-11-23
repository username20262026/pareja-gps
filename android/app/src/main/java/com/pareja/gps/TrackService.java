package com.pareja.gps;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.pm.PackageInfoCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Comparte la ubicacion SIN depender de la pantalla de la app:
 * servicio en primer plano + cola en disco + subida directa a Firestore (API REST).
 * Se vuelve a iniciar solo al reiniciar el celular (BootReceiver).
 */
public class TrackService extends Service {
    static final String PREFS = "pareja_track";
    private static final String CHANNEL = "pareja_track";
    private static final int NOTIF_ID = 4711;
    private static final long KEEPALIVE_MS = 120000L;
    private static final int MAX_QUEUE = 12000;
    private static final String RELEASES_URL = "https://api.github.com/repos/username20262026/pareja-gps/releases/latest";
    private static final String UPDATE_CHANNEL = "pareja_update";
    private static final int UPDATE_NOTIF_ID = 4900;
    private static final long UPDATE_CHECK_MS = 30L * 60L * 1000L;

    // ultima posicion, para que la pantalla la dibuje mientras esta abierta
    static volatile double lastLat = 0;
    static volatile double lastLng = 0;
    static volatile float lastAcc = 0;
    static volatile long lastTs = 0;
    // true mientras la pantalla de la app esta abierta (lo marca el plugin): ahi la propia app avisa
    static volatile boolean appVisible = false;

    private LocationManager lm;
    private LocationListener moveListener;
    private LocationListener aliveListener;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean haveKept = false;
    private double keptLat = 0;
    private double keptLng = 0;
    private long keptTs = 0;
    private String idToken = null;
    private long idTokenExp = 0;
    private boolean started = false;
    private int tick = 0;
    private boolean synced = false;
    // "En vivo": tu pareja tiene la app abierta mirando el mapa -> puntos mas seguidos (ver setLive)
    private static final long LIVE_WINDOW_MS = 90000L;
    private volatile boolean live = false;
    private String prov = LocationManager.GPS_PROVIDER;

    private final Runnable notifLoop = new Runnable() {
        @Override
        public void run() {
            io.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        pollNotifs();
                    } catch (Exception ignored) {
                    }
                    try {
                        checkCallNotif();
                    } catch (Exception ignored) {
                    }
                }
            });
            main.postDelayed(this, 10000L);
        }
    };

    // Revisa cada 30 min si hay una version nueva en GitHub y avisa con una notificacion
    private final Runnable updateLoop = new Runnable() {
        @Override
        public void run() {
            io.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        checkUpdateNotif();
                    } catch (Exception ignored) {
                    }
                }
            });
            main.postDelayed(this, UPDATE_CHECK_MS);
        }
    };

    private final Runnable retry = new Runnable() {
        @Override
        public void run() {
            io.execute(new Runnable() {
                @Override
                public void run() {
                    flush();
                    try {
                        sendPlaceEvents();
                    } catch (Exception ignored) {
                    }
                    tick++;
                    if (tick % 3 == 0) {
                        try {
                            ParejaWidget.refreshNow(TrackService.this);
                        } catch (Exception ignored) {
                        }
                    }
                }
            });
            main.postDelayed(this, 60000L);
        }
    };

    /** Inicia el servicio si el usuario dejo la ubicacion compartida activada. */
    static void startIfEnabled(Context c) {
        try {
            SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (!sp.getBoolean("enabled", false)) return;
            Intent i = new Intent(c, TrackService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception ignored) {
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Android exige llamar a startForeground enseguida
        try {
            goForeground();
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!sp.getBoolean("enabled", false) || sp.getString("uid", "").length() == 0) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!started) {
            started = true;
            startUpdates();
            main.postDelayed(retry, 15000L);
            main.postDelayed(notifLoop, 20000L);
            main.postDelayed(updateLoop, 60000L);
        }
        return START_STICKY;
    }

    private void goForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Compartir ubicacion", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = open == null ? null : PendingIntent.getActivity(this, 0, open, piFlags);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, CHANNEL);
        else b = new Notification.Builder(this);
        b.setContentTitle("Ubicaci\u00f3n compartida \uD83D\uDC95");
        b.setContentText("Compartiendo tu ubicaci\u00f3n con tu pareja");
        b.setSmallIcon(android.R.drawable.ic_menu_mylocation);
        b.setOngoing(true);
        if (pi != null) b.setContentIntent(pi);
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void startUpdates() {
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;
        prov = LocationManager.GPS_PROVIDER;
        try {
            if (Build.VERSION.SDK_INT >= 31 && lm.getAllProviders().contains("fused")) prov = "fused";
        } catch (Exception ignored) {
        }
        moveListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location l) {
                onLoc(l);
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
            }

            @Override
            public void onProviderEnabled(String provider) {
            }

            @Override
            public void onProviderDisabled(String provider) {
            }
        };
        aliveListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location l) {
                onLoc(l);
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
            }

            @Override
            public void onProviderEnabled(String provider) {
            }

            @Override
            public void onProviderDisabled(String provider) {
            }
        };
        try {
            // al moverse: cada 2 s o 4 m; "sigo aqui": una lectura cada 2 min aunque estes quieto
            lm.requestLocationUpdates(prov, 2000L, 4f, moveListener, Looper.getMainLooper());
            lm.requestLocationUpdates(prov, KEEPALIVE_MS, 0f, aliveListener, Looper.getMainLooper());
        } catch (SecurityException e) {
            stopSelf();
        } catch (Exception ignored) {
        }
    }

    private void onLoc(Location l) {
        if (l == null) return;
        final double lat = l.getLatitude();
        final double lng = l.getLongitude();
        final int acc = Math.round(l.getAccuracy());
        final long ts = l.getTime() > 0 ? l.getTime() : System.currentTimeMillis();
        lastLat = lat;
        lastLng = lng;
        lastAcc = l.getAccuracy();
        lastTs = ts;
        io.execute(new Runnable() {
            @Override
            public void run() {
                if (acc > 100) return; // lectura muy imprecisa
                try {
                    checkMyPlace(lat, lng, ts);
                } catch (Exception ignored) {
                }
                if (haveKept) {
                    long dt = ts - keptTs;
                    float[] r = new float[1];
                    Location.distanceBetween(keptLat, keptLng, lat, lng, r);
                    // en vivo: cada ~1 s si se movio 3 m (o mas si la lectura es imprecisa, para no
                    // "temblar" quieto); normal: cada 1,5 s si se movio 6 m
                    boolean keep;
                    if (live) keep = (r[0] >= Math.max(3f, acc * 0.5f) && dt >= 900L) || dt >= KEEPALIVE_MS;
                    else keep = (r[0] >= 6f && dt >= 1500L) || dt >= KEEPALIVE_MS;
                    if (!keep) return;
                }
                haveKept = true;
                keptLat = lat;
                keptLng = lng;
                keptTs = ts;
                try {
                    JSONObject o = new JSONObject();
                    o.put("lat", lat);
                    o.put("lng", lng);
                    o.put("acc", acc);
                    o.put("ts", ts);
                    FileOutputStream f = new FileOutputStream(queueFile(), true);
                    try {
                        f.write((o.toString() + "\n").getBytes("UTF-8"));
                    } finally {
                        f.close();
                    }
                } catch (Exception ignored) {
                }
                flush();
            }
        });
    }

    // ---------- Cola en disco + subida ----------
    private File queueFile() {
        return new File(getFilesDir(), "track_queue.jsonl");
    }

    private List<String> readQueue() {
        List<String> out = new ArrayList<String>();
        File f = queueFile();
        if (!f.exists()) return out;
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            try {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.length() > 2) out.add(line);
                }
            } finally {
                br.close();
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private void writeQueue(List<String> lines) {
        try {
            FileOutputStream f = new FileOutputStream(queueFile(), false);
            try {
                StringBuilder sb = new StringBuilder();
                for (String s : lines) sb.append(s).append("\n");
                f.write(sb.toString().getBytes("UTF-8"));
            } finally {
                f.close();
            }
        } catch (Exception ignored) {
        }
    }

    /** Siempre se llama desde el hilo "io". */
    private void flush() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        for (int round = 0; round < 40; round++) {
            List<String> lines = readQueue();
            if (lines.isEmpty()) return;
            if (lines.size() > MAX_QUEUE) {
                lines = new ArrayList<String>(lines.subList(lines.size() - MAX_QUEUE, lines.size()));
                writeQueue(lines);
            }
            String token = token(sp);
            if (token == null) return;
            int n = Math.min(lines.size(), 400);
            try {
                String pid = sp.getString("projectId", "");
                String room = sp.getString("room", "");
                String uid = sp.getString("uid", "");
                String userDoc = "projects/" + pid + "/databases/(default)/documents/rooms/" + room + "/users/" + uid;
                JSONArray writes = new JSONArray();
                JSONObject last = null;
                for (int i = 0; i < n; i++) {
                    JSONObject p = new JSONObject(lines.get(i));
                    last = p;
                    JSONObject fields = new JSONObject();
                    fields.put("lat", dbl(p.getDouble("lat")));
                    fields.put("lng", dbl(p.getDouble("lng")));
                    fields.put("acc", lng(p.getLong("acc")));
                    fields.put("ts", lng(p.getLong("ts")));
                    JSONObject upd = new JSONObject();
                    upd.put("name", userDoc + "/points/" + p.getLong("ts"));
                    upd.put("fields", fields);
                    JSONObject w = new JSONObject();
                    w.put("update", upd);
                    writes.put(w);
                }
                // ultima posicion en el documento de la persona (como hace la pantalla)
                JSONObject uf = new JSONObject();
                uf.put("uid", str(uid));
                uf.put("name", str(sp.getString("name", "")));
                uf.put("avatar", str(sp.getString("avatar", "")));
                uf.put("lat", dbl(last.getDouble("lat")));
                uf.put("lng", dbl(last.getDouble("lng")));
                uf.put("acc", lng(last.getLong("acc")));
                uf.put("ts", lng(last.getLong("ts")));
                uf.put("plv", lng(PLACE_EV_VERSION)); // "yo aviso mis llegadas/salidas" (ver checkMyPlace)
                JSONArray mask = new JSONArray();
                mask.put("uid");
                mask.put("name");
                mask.put("avatar");
                mask.put("lat");
                mask.put("lng");
                mask.put("acc");
                mask.put("ts");
                mask.put("plv");
                JSONObject uu = new JSONObject();
                uu.put("name", userDoc);
                uu.put("fields", uf);
                JSONObject uw = new JSONObject();
                uw.put("update", uu);
                uw.put("updateMask", new JSONObject().put("fieldPaths", mask));
                writes.put(uw);

                JSONObject body = new JSONObject();
                body.put("writes", writes);
                String url = "https://firestore.googleapis.com/v1/projects/" + pid + "/databases/(default)/documents:commit";
                int code = post(url, body.toString(), "application/json", token, new StringBuilder());
                if (code == 200) {
                    writeQueue(new ArrayList<String>(lines.subList(n, lines.size())));
                } else {
                    if (code == 401 || code == 403) idToken = null; // pide un token nuevo la proxima vez
                    return;
                }
            } catch (Exception e) {
                return; // sin internet: se reintenta
            }
        }
    }

    private JSONObject dbl(double v) throws Exception {
        return new JSONObject().put("doubleValue", v);
    }

    private JSONObject lng(long v) throws Exception {
        return new JSONObject().put("integerValue", String.valueOf(v));
    }

    private JSONObject str(String v) throws Exception {
        return new JSONObject().put("stringValue", v);
    }

    private String token(SharedPreferences sp) {
        if (idToken != null && System.currentTimeMillis() < idTokenExp - 60000L) return idToken;
        try {
            String rt = sp.getString("refreshToken", "");
            String key = sp.getString("apiKey", "");
            if (rt.length() == 0 || key.length() == 0) return null;
            String url = "https://securetoken.googleapis.com/v1/token?key=" + URLEncoder.encode(key, "UTF-8");
            String form = "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(rt, "UTF-8");
            StringBuilder out = new StringBuilder();
            int code = post(url, form, "application/x-www-form-urlencoded", null, out);
            if (code != 200) return null;
            JSONObject j = new JSONObject(out.toString());
            idToken = j.getString("id_token");
            long secs = Long.parseLong(j.optString("expires_in", "3600"));
            idTokenExp = System.currentTimeMillis() + secs * 1000L;
            String newRt = j.optString("refresh_token", "");
            if (newRt.length() > 0 && !newRt.equals(rt)) sp.edit().putString("refreshToken", newRt).apply();
            return idToken;
        } catch (Exception e) {
            return null;
        }
    }

    private int post(String url, String body, String contentType, String bearer, StringBuilder out) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", contentType);
            if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
            OutputStream os = c.getOutputStream();
            try {
                os.write(body.getBytes("UTF-8"));
            } finally {
                os.close();
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

    @Override
    public void onDestroy() {
        main.removeCallbacks(retry);
        main.removeCallbacks(notifLoop);
        main.removeCallbacks(updateLoop);
        try {
            if (lm != null) {
                if (moveListener != null) lm.removeUpdates(moveListener);
                if (aliveListener != null) lm.removeUpdates(aliveListener);
            }
        } catch (Exception ignored) {
        }
        io.shutdown();
        super.onDestroy();
    }

    // ---------- Avisos: corazones, estados, mensajitos y chat de tu pareja ----------
    private static long numL(JSONObject f, String k) {
        JSONObject v = f.optJSONObject(k);
        if (v == null) return 0L;
        try {
            if (v.has("integerValue")) return Long.parseLong(v.getString("integerValue"));
            if (v.has("doubleValue")) return (long) v.getDouble("doubleValue");
        } catch (Exception ignored) {
        }
        return 0L;
    }

    private static String strF(JSONObject f, String k) {
        JSONObject v = f.optJSONObject(k);
        return v == null ? "" : v.optString("stringValue", "");
    }

    private int httpGet(String url, String bearer, StringBuilder out) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestMethod("GET");
            c.setRequestProperty("Authorization", "Bearer " + bearer);
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


    // ---------- Aviso de version nueva (GitHub Releases) ----------
    /** Siempre desde el hilo "io". No manda el token de Firebase: es una consulta publica a GitHub. */
    private void checkUpdateNotif() throws Exception {
        if (appVisible) return; // con la app abierta, ella misma muestra el aviso
        HttpURLConnection c = (HttpURLConnection) new URL(RELEASES_URL).openConnection();
        String json;
        try {
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.setUseCaches(false);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("User-Agent", "ParejaGPS/1.0 (app privada de pareja)");
            if (c.getResponseCode() != 200) return;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            json = bo.toString("UTF-8");
        } finally {
            c.disconnect();
        }
        JSONObject r = new JSONObject(json);
        String tag = r.optString("tag_name", "");   // ej. "v1.20" -> versionCode 20
        int dot = tag.lastIndexOf('.');
        if (dot < 0) return;
        long latest = Long.parseLong(tag.substring(dot + 1).replaceAll("[^0-9]", ""));
        long cur = PackageInfoCompat.getLongVersionCode(getPackageManager().getPackageInfo(getPackageName(), 0));

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (latest <= cur) {
            if (nm != null) nm.cancel(UPDATE_NOTIF_ID); // ya esta al dia: quita el aviso viejo
            return;
        }
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return; // sin permiso: reintenta luego
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (sp.getLong("uNotified", 0L) >= latest) return; // esta version ya se aviso una vez
        notifyUpdate(tag, r.optString("body", ""));
        sp.edit().putLong("uNotified", latest).apply();
    }

    private void notifyUpdate(String tag, String notes) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(UPDATE_CHANNEL, "Actualizaciones de la app", NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(ch);
        }
        // abre la app y le avisa al plugin para que muestre la ventana de actualizar
        Intent open = new Intent(this, MainActivity.class);
        open.setAction("pareja.OPEN_UPDATE");
        open.putExtra("openUpdate", true);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, UPDATE_NOTIF_ID, open, piFlags);

        String text = notes == null ? "" : notes.trim();
        if (text.length() == 0) text = "Toca para actualizar la app";
        if (text.length() > 200) text = text.substring(0, 200) + "\u2026";

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, UPDATE_CHANNEL);
        else b = new Notification.Builder(this).setPriority(Notification.PRIORITY_HIGH);
        b.setContentTitle("\u2B07\uFE0F Nueva versi\u00f3n " + tag + " disponible");
        b.setContentText(text);
        b.setStyle(new Notification.BigTextStyle().bigText(text));
        b.setSmallIcon(android.R.drawable.stat_sys_download_done);
        b.setAutoCancel(true);
        b.setContentIntent(pi);
        b.setWhen(System.currentTimeMillis());
        b.setShowWhen(true);
        nm.notify(UPDATE_NOTIF_ID, b.build());
    }

    private void notifyUser(int id, String title, String text) {
        ParejaAvisos.notifyUser(this, id, title, text);
    }

    /** Cambia la frecuencia del GPS: 1 s / 1 m en vivo, 2 s / 4 m normal. Se llama desde cualquier hilo. */
    private void setLive(final boolean on) {
        if (on == live) return;
        live = on;
        main.post(new Runnable() {
            @Override
            public void run() {
                if (lm == null || moveListener == null) return;
                try {
                    lm.removeUpdates(moveListener);
                    lm.requestLocationUpdates(prov, on ? 1000L : 2000L, on ? 1f : 4f, moveListener, Looper.getMainLooper());
                } catch (Exception ignored) {
                }
            }
        });
    }

    /** Siempre desde el hilo "io". Solo avisa si la app NO esta abierta (pero siempre mira si tu pareja esta en vivo). */
    private void pollNotifs() throws Exception {
        if (appVisible) synced = false; // lo que pase con la app abierta ya lo vio la persona
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String uid = sp.getString("uid", "");
        String room = sp.getString("room", "");
        String pid = sp.getString("projectId", "");
        if (uid.length() == 0 || room.length() == 0 || pid.length() == 0) return;
        String tok = token(sp);
        if (tok == null) return;

        String base = "https://firestore.googleapis.com/v1/projects/" + pid + "/databases/(default)/documents/rooms/" + room;
        StringBuilder out = new StringBuilder();
        int code = httpGet(base + "/users?pageSize=10", tok, out);
        if (code == 401 || code == 403) {
            idToken = null;
            return;
        }
        if (code != 200) return;
        JSONArray docs = new JSONObject(out.toString()).optJSONArray("documents");
        if (docs == null) return;
        String pUid = "";
        String name = "";
        String avatar = "";
        String moodTxt = "";
        String msgTxt = "";
        long heart = 0L;
        long mood = 0L;
        long msg = 0L;
        double pLat = 0;
        double pLng = 0;
        long pTs = 0L;
        boolean pHasLoc = false;
        long pPlv = 0L;
        long pWatch = 0L;
        JSONObject pPlaceEv = null;
        for (int i = 0; i < docs.length(); i++) {
            JSONObject doc = docs.getJSONObject(i);
            String nm = doc.optString("name", "");
            String id = nm.substring(nm.lastIndexOf('/') + 1);
            JSONObject f = doc.optJSONObject("fields");
            if (f == null || id.equals(uid)) continue;
            pUid = id;
            name = strF(f, "name");
            avatar = strF(f, "avatar");
            moodTxt = strF(f, "mood");
            msgTxt = strF(f, "msg");
            heart = numL(f, "heartTs");
            mood = numL(f, "moodTs");
            msg = numL(f, "msgTs");
            pHasLoc = f.has("lat") && f.has("lng");
            pLat = numD(f, "lat");
            pLng = numD(f, "lng");
            pTs = numL(f, "ts");
            pPlv = numL(f, "plv");
            pWatch = numL(f, "watchTs");
            JSONObject pe = f.optJSONObject("placeEv");
            pPlaceEv = pe == null || pe.optJSONObject("mapValue") == null ? null
                : pe.getJSONObject("mapValue").optJSONObject("fields");
        }
        // tu pareja esta mirando el mapa ahora (su app avisa cada 45 s con watchTs)
        long age = System.currentTimeMillis() - pWatch;
        setLive(pWatch > 0 && age >= -60000L && age < LIVE_WINDOW_MS);
        if (appVisible) return;
        if (pUid.length() == 0) return;
        if (name.length() == 0) name = "Tu amor";

        // llegadas y salidas de los Lugares (en silencio si recien se pone al dia)
        boolean resync = !sp.getBoolean("nReady", false) || !synced;
        if (pPlv >= PLACE_EV_VERSION) {
            // APK nuevo en el otro celular: el mismo avisa sus llegadas/salidas (push + placeEv).
            // Aqui solo se usa placeEv como respaldo por si el push no llego.
            if (pPlaceEv != null) partnerPlaceEv(sp, name, avatar, pPlaceEv, resync);
        } else if (pHasLoc && pTs > 0) {
            try {
                checkPlaces(sp, tok, base, name, avatar, pLat, pLng, pTs, !sp.getBoolean("nReady", false) || !synced);
            } catch (Exception ignored) {
            }
        }

        // primera vez (o al volver de tener la app abierta): se ponen al dia los marcadores sin avisar
        if (!sp.getBoolean("nReady", false) || !synced) {
            SharedPreferences.Editor e0 = sp.edit();
            e0.putLong("nHeart", Math.max(heart, sp.getLong("nHeart", 0L)));
            e0.putLong("nMood", Math.max(mood, sp.getLong("nMood", 0L)));
            e0.putLong("nMsg", Math.max(msg, sp.getLong("nMsg", 0L)));
            e0.putLong("nChat", Math.max(System.currentTimeMillis(), sp.getLong("nChat", 0L)));
            e0.putBoolean("nReady", true);
            e0.apply();
            synced = true;
            return;
        }

        SharedPreferences.Editor e = sp.edit();
        if (heart > sp.getLong("nHeart", 0L)) {
            notifyUser(4801, "\uD83D\uDC97 " + name, "te envi\u00f3 un coraz\u00f3n");
            e.putLong("nHeart", heart);
        }
        if (mood > sp.getLong("nMood", 0L) && moodTxt.length() > 0) {
            notifyUser(4802, (avatar.length() > 0 ? avatar + " " : "") + name, moodTxt);
            e.putLong("nMood", mood);
        }
        if (msg > sp.getLong("nMsg", 0L) && msgTxt.length() > 0) {
            notifyUser(4803, name + " \uD83D\uDC8C", msgTxt);
            e.putLong("nMsg", msg);
        }

        // mensajes nuevos del chat
        long nChat = sp.getLong("nChat", 0L);
        JSONObject where = new JSONObject();
        JSONObject ff = new JSONObject();
        ff.put("field", new JSONObject().put("fieldPath", "ts"));
        ff.put("op", "GREATER_THAN");
        ff.put("value", new JSONObject().put("integerValue", String.valueOf(nChat)));
        where.put("fieldFilter", ff);
        JSONObject sq = new JSONObject();
        sq.put("from", new JSONArray().put(new JSONObject().put("collectionId", "chat")));
        sq.put("where", where);
        JSONObject ob = new JSONObject();
        ob.put("field", new JSONObject().put("fieldPath", "ts"));
        ob.put("direction", "ASCENDING");
        sq.put("orderBy", new JSONArray().put(ob));
        sq.put("limit", 20);
        JSONObject body = new JSONObject();
        body.put("structuredQuery", sq);
        StringBuilder qout = new StringBuilder();
        int qc = post(base + "/users/" + pUid + ":runQuery", body.toString(), "application/json", tok, qout);
        if (qc == 200) {
            JSONArray arr = new JSONArray(qout.toString());
            int count = 0;
            String lastText = "";
            long maxTs = nChat;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject d = arr.getJSONObject(i).optJSONObject("document");
                if (d == null) continue;
                JSONObject f = d.optJSONObject("fields");
                if (f == null) continue;
                count++;
                lastText = strF(f, "text");
                long t = numL(f, "ts");
                if (t > maxTs) maxTs = t;
            }
            if (count > 0) {
                String title = count > 1 ? name + " \u00b7 " + count + " mensajes" : name;
                notifyUser(4804, title, lastText);
                e.putLong("nChat", maxTs);
            }
        } else if (qc == 401 || qc == 403) {
            idToken = null;
        }
        e.apply();
    }

    private static double numD(JSONObject f, String k) {
        JSONObject v = f.optJSONObject(k);
        if (v == null) return 0;
        try {
            if (v.has("doubleValue")) return v.getDouble("doubleValue");
            if (v.has("integerValue")) return Double.parseDouble(v.getString("integerValue"));
        } catch (Exception ignored) {
        }
        return 0;
    }

    // ---------- Avisos de llegada / salida de los Lugares ----------
    // Mismo criterio que la pantalla (main.js): entra a menos de 150 m, sale a mas de 250 m.
    private static final float PLACE_IN = 150f;
    private static final float PLACE_OUT = 250f;
    private static final long PLACE_STALE_MS = 30L * 60L * 1000L;
    private static final long PLACES_REFRESH_MS = 5L * 60L * 1000L;
    private long placesTs = 0L;
    private JSONArray placesCache = null; // [{id,name,emoji,lat,lng}]

    /** Siempre desde el hilo "io". */
    private void checkPlaces(SharedPreferences sp, String tok, String base, String name, String avatar,
                             double lat, double lng, long ts, boolean silent) throws Exception {
        boolean changed = refreshPlaces(sp, tok, base);
        long now = System.currentTimeMillis();
        if (placesCache == null) return;
        if (!silent && !changed && ts == sp.getLong("plTs", 0L)) return; // misma posicion de antes

        boolean quiet = silent || changed || !sp.getBoolean("plAlerts", true) || now - ts > PLACE_STALE_MS;
        String in = sp.getString("plIn", "");
        String who = (avatar.length() > 0 ? avatar + " " : "") + name;
        SharedPreferences.Editor e = sp.edit();
        float[] r = new float[1];
        if (in.length() > 0) {
            JSONObject cur = null;
            for (int i = 0; i < placesCache.length(); i++) {
                if (in.equals(placesCache.getJSONObject(i).optString("id"))) cur = placesCache.getJSONObject(i);
            }
            boolean out = cur == null;
            if (cur != null) {
                Location.distanceBetween(lat, lng, cur.getDouble("lat"), cur.getDouble("lng"), r);
                out = r[0] > PLACE_OUT;
            }
            if (out) {
                if (cur != null && !quiet) notifyUser(ParejaAvisos.ID_PLACE, who, ParejaAvisos.placeText("out", cur.optString("emoji"), cur.optString("name"), 0L));
                in = "";
            }
        }
        if (in.length() == 0) {
            JSONObject best = null;
            float bd = PLACE_IN;
            for (int i = 0; i < placesCache.length(); i++) {
                JSONObject p = placesCache.getJSONObject(i);
                Location.distanceBetween(lat, lng, p.getDouble("lat"), p.getDouble("lng"), r);
                if (r[0] < bd) {
                    bd = r[0];
                    best = p;
                }
            }
            if (best != null) {
                in = best.optString("id");
                if (!quiet) notifyUser(ParejaAvisos.ID_PLACE, who, ParejaAvisos.placeText("in", best.optString("emoji"), best.optString("name"), 0L));
            }
        }
        e.putString("plIn", in);
        e.putLong("plTs", ts);
        e.apply();
    }

    /**
     * Lista de Lugares (rooms/<sala>/places), guardada en memoria y en el celular para poder
     * revisar llegadas/salidas aunque no haya internet. Se pide de nuevo cada 5 min.
     * Devuelve true si la lista cambio (lugar nuevo o borrado): ese momento se pone al dia sin avisar.
     * Siempre desde el hilo "io".
     */
    private boolean refreshPlaces(SharedPreferences sp, String tok, String base) throws Exception {
        long now = System.currentTimeMillis();
        if (placesCache == null) {
            try {
                String saved = sp.getString("plCache", "");
                if (saved.length() > 0) placesCache = new JSONArray(saved);
            } catch (Exception ignored) {
            }
        }
        if (placesCache != null && now - placesTs <= PLACES_REFRESH_MS) return false;
        if (tok == null) tok = token(sp);
        if (tok == null) return false;
        if (base == null) {
            String pid = sp.getString("projectId", "");
            String room = sp.getString("room", "");
            if (pid.length() == 0 || room.length() == 0) return false;
            base = "https://firestore.googleapis.com/v1/projects/" + pid + "/databases/(default)/documents/rooms/" + room;
        }
        StringBuilder out = new StringBuilder();
        int code = httpGet(base + "/places?pageSize=100", tok, out);
        if (code != 200) {
            if (code == 401 || code == 403) idToken = null;
            placesTs = now - PLACES_REFRESH_MS + 60000L; // reintenta en 1 min
            return false;
        }
        JSONArray docs = new JSONObject(out.toString()).optJSONArray("documents");
        JSONArray list = new JSONArray();
        if (docs != null) {
            for (int i = 0; i < docs.length(); i++) {
                JSONObject d = docs.getJSONObject(i);
                JSONObject f = d.optJSONObject("fields");
                if (f == null || !f.has("lat") || !f.has("lng")) continue;
                String n = d.optString("name", "");
                JSONObject p = new JSONObject();
                p.put("id", n.substring(n.lastIndexOf('/') + 1));
                p.put("name", strF(f, "name"));
                p.put("emoji", strF(f, "emoji"));
                p.put("lat", numD(f, "lat"));
                p.put("lng", numD(f, "lng"));
                list.put(p);
            }
        }
        boolean changed = placesCache == null || !placesCache.toString().equals(list.toString());
        placesCache = list;
        placesTs = now;
        if (changed) sp.edit().putString("plCache", list.toString()).apply();
        return changed;
    }

    private JSONObject findPlace(String id) throws Exception {
        if (placesCache == null) return null;
        for (int i = 0; i < placesCache.length(); i++) {
            if (id.equals(placesCache.getJSONObject(i).optString("id"))) return placesCache.getJSONObject(i);
        }
        return null;
    }

    // ---------- Mis llegadas / salidas (las detecta el celular que se mueve) ----------
    // Antes solo las buscaba el celular de la pareja, preguntando cada 10 s a Firestore. Con la
    // pantalla apagada Android duerme ese reloj y corta internet, asi que muchos avisos no salian.
    // Ahora este celular, que tiene su propio GPS al instante, detecta el cambio y:
    //   1) lo guarda en rooms/<sala>/users/<uid>.placeEv (respaldo que la pareja revisa), y
    //   2) manda un push de prioridad alta por el Worker, que despierta al otro celular aunque duerma.
    // Si no hay internet queda en cola (plEvQ) y se reintenta hasta 30 min.
    static final long PLACE_EV_VERSION = 2L;
    static final long PLACE_EV_MAX_AGE = 30L * 60L * 1000L;
    private static final String PUSH_URL = "https://push.nnxsw-proyect.date/notify";

    /** Siempre desde el hilo "io". Se llama con cada lectura del GPS de precision aceptable. */
    private void checkMyPlace(double lat, double lng, long ts) throws Exception {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean changed = refreshPlaces(sp, null, null);
        if (placesCache == null) return;
        // primera vez (APK recien instalado): solo aprende donde esta, sin avisar
        boolean silent = changed || !sp.contains("myIn");
        String in = sp.getString("myIn", "");
        int outN = sp.getInt("myOutN", 0);
        float[] r = new float[1];
        if (in.length() > 0) {
            JSONObject cur = findPlace(in);
            if (cur == null) {
                in = ""; // borraron el lugar
                outN = 0;
            } else {
                Location.distanceBetween(lat, lng, cur.getDouble("lat"), cur.getDouble("lng"), r);
                if (r[0] > PLACE_OUT) {
                    outN++;
                    // dos lecturas seguidas afuera (o una muy lejos): un salto suelto del GPS no cuenta
                    if (silent || outN >= 2 || r[0] > PLACE_OUT * 2) {
                        if (!silent) queuePlaceEv(sp, "out", cur, ts);
                        in = "";
                        outN = 0;
                    }
                } else {
                    outN = 0;
                }
            }
        }
        if (in.length() == 0) {
            JSONObject best = null;
            float bd = PLACE_IN;
            for (int i = 0; i < placesCache.length(); i++) {
                JSONObject p = placesCache.getJSONObject(i);
                Location.distanceBetween(lat, lng, p.getDouble("lat"), p.getDouble("lng"), r);
                if (r[0] < bd) {
                    bd = r[0];
                    best = p;
                }
            }
            if (best != null) {
                in = best.optString("id");
                outN = 0;
                if (!silent) queuePlaceEv(sp, "in", best, ts);
            }
        }
        if (!in.equals(sp.getString("myIn", null)) || outN != sp.getInt("myOutN", 0)) {
            sp.edit().putString("myIn", in).putInt("myOutN", outN).apply();
        }
    }

    private void queuePlaceEv(SharedPreferences sp, String kind, JSONObject place, long ts) throws Exception {
        JSONArray q;
        try {
            q = new JSONArray(sp.getString("plEvQ", "[]"));
        } catch (Exception e) {
            q = new JSONArray();
        }
        JSONObject ev = new JSONObject();
        ev.put("kind", kind);
        ev.put("id", place.optString("id"));
        ev.put("name", place.optString("name"));
        ev.put("emoji", place.optString("emoji"));
        ev.put("ts", ts);
        ev.put("fs", false);
        ev.put("push", false);
        q.put(ev);
        JSONArray keep = new JSONArray();
        for (int i = Math.max(0, q.length() - 6); i < q.length(); i++) keep.put(q.get(i));
        sp.edit().putString("plEvQ", keep.toString()).apply();
        sendPlaceEvents();
    }

    /** Siempre desde el hilo "io". Sube a Firestore y manda por push los avisos pendientes. */
    private void sendPlaceEvents() throws Exception {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String raw = sp.getString("plEvQ", "[]");
        if (raw.length() <= 2) return;
        JSONArray q = new JSONArray(raw);
        String pid = sp.getString("projectId", "");
        String room = sp.getString("room", "");
        String uid = sp.getString("uid", "");
        if (pid.length() == 0 || room.length() == 0 || uid.length() == 0) return;
        String tok = token(sp);
        if (tok == null) return;
        long now = System.currentTimeMillis();
        String name = sp.getString("name", "");
        String avatar = sp.getString("avatar", "");
        String userDoc = "projects/" + pid + "/databases/(default)/documents/rooms/" + room + "/users/" + uid;
        JSONArray keep = new JSONArray();
        for (int i = 0; i < q.length(); i++) {
            JSONObject ev = q.getJSONObject(i);
            long ts = ev.optLong("ts", 0L);
            if (now - ts > PLACE_EV_MAX_AGE) continue; // ya es tarde para avisar
            try {
                if (!ev.optBoolean("fs")) {
                    JSONObject pf = new JSONObject();
                    pf.put("kind", str(ev.optString("kind")));
                    pf.put("id", str(ev.optString("id")));
                    pf.put("name", str(ev.optString("name")));
                    pf.put("emoji", str(ev.optString("emoji")));
                    pf.put("ts", lng(ts));
                    JSONObject fields = new JSONObject();
                    fields.put("placeEv", new JSONObject().put("mapValue", new JSONObject().put("fields", pf)));
                    JSONObject upd = new JSONObject();
                    upd.put("name", userDoc);
                    upd.put("fields", fields);
                    JSONObject w = new JSONObject();
                    w.put("update", upd);
                    w.put("updateMask", new JSONObject().put("fieldPaths", new JSONArray().put("placeEv")));
                    JSONObject body = new JSONObject().put("writes", new JSONArray().put(w));
                    int code = post("https://firestore.googleapis.com/v1/projects/" + pid + "/databases/(default)/documents:commit",
                        body.toString(), "application/json", tok, new StringBuilder());
                    if (code == 200) ev.put("fs", true);
                    else if (code == 401 || code == 403) idToken = null;
                }
                if (!ev.optBoolean("push")) {
                    JSONObject p = new JSONObject();
                    p.put("room", room);
                    p.put("type", "place");
                    p.put("name", name);
                    p.put("avatar", avatar);
                    p.put("kind", ev.optString("kind"));
                    p.put("body", ParejaAvisos.placeText(ev.optString("kind"), ev.optString("emoji"), ev.optString("name"), 0L));
                    p.put("ts", ts);
                    int code = post(PUSH_URL, p.toString(), "application/json", tok, new StringBuilder());
                    // 4xx: el Worker no acepta el aviso (p. ej. todavia no se actualizo): no insiste,
                    // la pareja igual lo ve por placeEv. 5xx o sin internet: se reintenta.
                    if (code < 500) ev.put("push", true);
                    if (code == 401) idToken = null;
                }
            } catch (Exception ignored) {
                // sin internet: se reintenta en el proximo ciclo
            }
            if (!ev.optBoolean("fs") || !ev.optBoolean("push")) keep.put(ev);
        }
        sp.edit().putString("plEvQ", keep.toString()).apply();
    }

    /**
     * Respaldo del push: la ultima llegada/salida que la pareja guardo en placeEv.
     * A diferencia de antes, si el servicio estuvo dormido o se reinicio igual avisa
     * (hasta 30 min tarde, diciendo "hace N min"); solo calla lo que ya se vio con la app abierta.
     */
    private void partnerPlaceEv(SharedPreferences sp, String name, String avatar, JSONObject pe, boolean resync) {
        long evTs = numL(pe, "ts");
        if (evTs <= sp.getLong("nPlace", 0L)) return; // ya avisado (por push o por aqui)
        boolean show = sp.getBoolean("nReady", false) && sp.getBoolean("plAlerts", true)
            && System.currentTimeMillis() - evTs < PLACE_EV_MAX_AGE;
        // al volver de tener la app abierta: lo que paso mientras estaba abierta ya se vio en pantalla
        if (show && resync && evTs <= sp.getLong("pausedAt", 0L)) show = false;
        if (show) {
            String who = (avatar.length() > 0 ? avatar + " " : "") + name;
            notifyUser(ParejaAvisos.ID_PLACE, who, ParejaAvisos.placeText(strF(pe, "kind"), strF(pe, "emoji"), strF(pe, "name"), evTs));
        }
        sp.edit().putLong("nPlace", evTs).apply();
    }

    /** Siempre desde el hilo "io". Avisa de una llamada entrante aunque la app este cerrada. */
    private void checkCallNotif() throws Exception {
        if (appVisible) return; // con la app abierta, la propia app muestra la llamada
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String uid = sp.getString("uid", "");
        String room = sp.getString("room", "");
        String pid = sp.getString("projectId", "");
        if (uid.length() == 0 || room.length() == 0 || pid.length() == 0) return;
        String tok = token(sp);
        if (tok == null) return;
        String url = "https://firestore.googleapis.com/v1/projects/" + pid + "/databases/(default)/documents/rooms/" + room + "/call/current";
        StringBuilder out = new StringBuilder();
        int code = httpGet(url, tok, out);
        if (code == 401 || code == 403) { idToken = null; return; }
        if (code != 200) return;
        JSONObject f = new JSONObject(out.toString()).optJSONObject("fields");
        if (f == null) return;
        String status = strF(f, "status");
        String caller = strF(f, "caller");
        String callId = strF(f, "callId");
        long ts = numL(f, "ts");
        if (!"ringing".equals(status) || caller.equals(uid) || callId.length() == 0) return;
        if (System.currentTimeMillis() - ts > 60000L) return; // llamada vieja
        if (callId.equals(sp.getString("lastCallId", ""))) return; // ya avisada
        sp.edit().putString("lastCallId", callId).apply();
        notifyCall();
    }

    private void notifyCall() {
        ParejaAvisos.notifyCall(this, "");
    }
}
