package com.pareja.gps;

import android.Manifest;
import android.webkit.WebView;
import androidx.activity.OnBackPressedCallback;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.content.pm.PackageInfoCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import android.util.Base64;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

@CapacitorPlugin(
    name = "ParejaNative",
    permissions = {
        @Permission(alias = "location", strings = { Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION }),
        @Permission(alias = "background", strings = { Manifest.permission.ACCESS_BACKGROUND_LOCATION }),
        @Permission(alias = "notifications", strings = { "android.permission.POST_NOTIFICATIONS" }),
        @Permission(alias = "mic", strings = { Manifest.permission.RECORD_AUDIO }),
        @Permission(alias = "photos", strings = { "android.permission.READ_MEDIA_IMAGES" }),
        @Permission(alias = "photosOld", strings = { Manifest.permission.READ_EXTERNAL_STORAGE })
    }
)
public class ParejaNativePlugin extends Plugin {

    private boolean has(String perm) {
        return ContextCompat.checkSelfPermission(getContext(), perm) == PackageManager.PERMISSION_GRANTED;
    }

    private JSObject buildStatus() {
        Context c = getContext();
        boolean fine = has(Manifest.permission.ACCESS_FINE_LOCATION);
        JSObject r = new JSObject();
        r.put("sdk", Build.VERSION.SDK_INT);
        r.put("location", fine);
        r.put("background", Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ? fine : has(Manifest.permission.ACCESS_BACKGROUND_LOCATION));
        r.put("notifications", NotificationManagerCompat.from(c).areNotificationsEnabled());
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        r.put("battery", pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName()));
        r.put("install", Build.VERSION.SDK_INT < Build.VERSION_CODES.O || c.getPackageManager().canRequestPackageInstalls());
        return r;
    }

    private void launch(Intent i) {
        try {
            getActivity().startActivity(i);
        } catch (Exception e) {
            try {
                Intent s = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getContext().getPackageName()));
                getActivity().startActivity(s);
            } catch (Exception ignored) { }
        }
    }

    // ---------- Estado ----------
    @PluginMethod
    public void status(PluginCall call) {
        call.resolve(buildStatus());
    }

    @PluginMethod
    public void appInfo(PluginCall call) {
        try {
            PackageInfo pi = getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0);
            JSObject r = new JSObject();
            r.put("versionName", pi.versionName == null ? "" : pi.versionName);
            r.put("versionCode", PackageInfoCompat.getLongVersionCode(pi));
            call.resolve(r);
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    // ---------- Permisos ----------
    @PluginMethod
    public void requestLocation(PluginCall call) {
        if (has(Manifest.permission.ACCESS_FINE_LOCATION)) { call.resolve(buildStatus()); return; }
        requestPermissionForAlias("location", call, "permCallback");
    }

    @PluginMethod
    public void requestBackground(PluginCall call) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            call.resolve(buildStatus());
            return;
        }
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) {
            call.reject("Primero concede la ubicación precisa");
            return;
        }
        requestPermissionForAlias("background", call, "permCallback");
    }

    @PluginMethod
    public void requestNotifications(PluginCall call) {
        if (NotificationManagerCompat.from(getContext()).areNotificationsEnabled()) { call.resolve(buildStatus()); return; }
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissionForAlias("notifications", call, "permCallback");
        } else {
            openNotificationSettings(call);
        }
    }

    @PluginMethod
    public void requestMic(PluginCall call) {
        if (has(Manifest.permission.RECORD_AUDIO)) { call.resolve(); return; }
        requestPermissionForAlias("mic", call, "permCallback");
    }

    @PermissionCallback
    private void permCallback(PluginCall call) {
        call.resolve(buildStatus());
    }

    @PluginMethod
    public void requestBattery(PluginCall call) {
        Context c = getContext();
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        if (pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName())) { call.resolve(buildStatus()); return; }
        try {
            getActivity().startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + c.getPackageName())));
        } catch (Exception e) {
            launch(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
        call.resolve(buildStatus());
    }

    @PluginMethod
    public void requestInstall(PluginCall call) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getContext().getPackageManager().canRequestPackageInstalls()) {
            launch(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getContext().getPackageName())));
        }
        call.resolve(buildStatus());
    }

    @PluginMethod
    public void openAppSettings(PluginCall call) {
        launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getContext().getPackageName())));
        call.resolve();
    }

    @PluginMethod
    public void openNotificationSettings(PluginCall call) {
        Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
        i.putExtra(Settings.EXTRA_APP_PACKAGE, getContext().getPackageName());
        launch(i);
        call.resolve(buildStatus());
    }

    // ---------- Actualizaciones ----------
    @PluginMethod
    public void httpGet(final PluginCall call) {
        final String u = call.getString("url");
        if (u == null) { call.reject("Falta la dirección"); return; }
        new Thread(() -> {
            HttpURLConnection con = null;
            try {
                con = (HttpURLConnection) new URL(u).openConnection();
                con.setConnectTimeout(10000);
                con.setReadTimeout(10000);
                con.setUseCaches(false);
                con.setRequestProperty("Cache-Control", "no-cache");
                con.setRequestProperty("User-Agent", "ParejaGPS/1.0 (app privada de pareja)");
                if (con.getResponseCode() != 200) throw new Exception("HTTP " + con.getResponseCode());
                InputStream in = con.getInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close();
                JSObject r = new JSObject();
                r.put("text", out.toString("UTF-8"));
                call.resolve(r);
            } catch (Exception e) {
                call.reject(String.valueOf(e.getMessage()));
            } finally {
                if (con != null) con.disconnect();
            }
        }).start();
    }

    @PluginMethod
    public void downloadAndInstall(final PluginCall call) {
        final String u = call.getString("url");
        if (u == null) { call.reject("Falta la dirección"); return; }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getContext().getPackageManager().canRequestPackageInstalls()) {
            call.reject("Falta permitir instalar desde esta app");
            return;
        }
        new Thread(() -> {
            HttpURLConnection con = null;
            try {
                File dir = new File(getContext().getCacheDir(), "update");
                if (!dir.exists()) dir.mkdirs();
                File apk = new File(dir, "app.apk");
                if (apk.exists()) apk.delete();

                con = (HttpURLConnection) new URL(u).openConnection();
                con.setConnectTimeout(15000);
                con.setReadTimeout(30000);
                con.setUseCaches(false);
                if (con.getResponseCode() != 200) throw new Exception("HTTP " + con.getResponseCode());
                long total = con.getContentLengthLong();
                InputStream in = con.getInputStream();
                OutputStream out = new FileOutputStream(apk);
                byte[] buf = new byte[16384];
                int n;
                long done = 0;
                int last = -1;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (total > 0) {
                        int pct = (int) (done * 100 / total);
                        if (pct != last) {
                            last = pct;
                            JSObject p = new JSObject();
                            p.put("percent", pct);
                            notifyListeners("updateProgress", p);
                        }
                    }
                }
                out.close();
                in.close();
                if (apk.length() < 100000) throw new Exception("El archivo descargado es muy pequeño");

                Uri uri = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", apk);
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(uri, "application/vnd.android.package-archive");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(i);
                call.resolve();
            } catch (Exception e) {
                call.reject(String.valueOf(e.getMessage()));
            } finally {
                if (con != null) con.disconnect();
            }
        }).start();
    }

    // ---------- Ubicacion que sigue activa con la app cerrada ----------
    @PluginMethod
    public void startTracking(PluginCall call) {
        SharedPreferences.Editor e = getContext().getSharedPreferences(TrackService.PREFS, Context.MODE_PRIVATE).edit();
        e.putString("apiKey", call.getString("apiKey", ""));
        e.putString("projectId", call.getString("projectId", ""));
        e.putString("room", call.getString("room", ""));
        e.putString("uid", call.getString("uid", ""));
        e.putString("name", call.getString("name", ""));
        e.putString("avatar", call.getString("avatar", ""));
        e.putString("refreshToken", call.getString("refreshToken", ""));
        e.putBoolean("enabled", true);
        e.apply();
        TrackService.startIfEnabled(getContext());
        call.resolve();
    }

    @PluginMethod
    public void stopTracking(PluginCall call) {
        getContext().getSharedPreferences(TrackService.PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", false).apply();
        try {
            getContext().stopService(new Intent(getContext(), TrackService.class));
        } catch (Exception ignored) { }
        call.resolve();
    }

    @PluginMethod
    public void lastLocation(PluginCall call) {
        JSObject r = new JSObject();
        if (TrackService.lastTs > 0) {
            r.put("latitude", TrackService.lastLat);
            r.put("longitude", TrackService.lastLng);
            r.put("accuracy", (double) TrackService.lastAcc);
            r.put("time", TrackService.lastTs);
        }
        call.resolve(r);
    }

    // Pantallas de "inicio automatico" / "segundo plano" de cada marca (Xiaomi, Huawei, Oppo, Vivo...)
    private static final String[][] AUTOSTART = {
        { "com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity" },
        { "com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity" },
        { "com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity" },
        { "com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity" },
        { "com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity" },
        { "com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity" },
        { "com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity" },
        { "com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity" },
        { "com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity" },
        { "com.asus.mobilemanager", "com.asus.mobilemanager.powersaver.PowerSaverSettings" }
    };

    @PluginMethod
    public void openAutostart(PluginCall call) {
        for (String[] a : AUTOSTART) {
            try {
                Intent i = new Intent();
                i.setComponent(new ComponentName(a[0], a[1]));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getActivity().startActivity(i);
                JSObject r = new JSObject();
                r.put("opened", true);
                call.resolve(r);
                return;
            } catch (Exception ignored) { }
        }
        launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getContext().getPackageName())));
        JSObject r = new JSObject();
        r.put("opened", false);
        call.resolve(r);
    }

    // ---------- Notificaciones push (Firebase Cloud Messaging) ----------
    // Devuelve el token de este celular para que la app lo guarde en Firestore (profiles/<uid>.push).
    // Si falta google-services.json en la compilacion, responde sin token en vez de fallar.
    @PluginMethod
    public void pushToken(PluginCall call) {
        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().getToken()
                .addOnCompleteListener(t -> {
                    JSObject r = new JSObject();
                    if (t.isSuccessful() && t.getResult() != null) {
                        r.put("token", t.getResult());
                        getContext().getSharedPreferences(TrackService.PREFS, Context.MODE_PRIVATE).edit()
                            .putString("fcmToken", t.getResult()).putBoolean("fcmDirty", false).apply();
                    } else {
                        r.put("error", t.getException() == null ? "sin token" : String.valueOf(t.getException().getMessage()));
                    }
                    call.resolve(r);
                });
        } catch (Exception e) { // FirebaseApp sin configurar (no hay google-services.json)
            JSObject r = new JSObject();
            r.put("error", String.valueOf(e.getMessage()));
            call.resolve(r);
        }
    }

    // ---------- Widget de la pantalla de inicio ----------
    @PluginMethod
    public void setMapboxKey(PluginCall call) {
        getContext().getSharedPreferences(TrackService.PREFS, Context.MODE_PRIVATE).edit()
            .putString("mapboxKey", call.getString("key", "")).apply();
        call.resolve();
    }

    @PluginMethod
    public void pinWidget(PluginCall call) {
        JSObject r = new JSObject();
        boolean ok = false;
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                AppWidgetManager m = AppWidgetManager.getInstance(getContext());
                if (m.isRequestPinAppWidgetSupported()) {
                    ok = m.requestPinAppWidget(new ComponentName(getContext(), ParejaWidget.class), null, null);
                }
            }
        } catch (Exception ignored) { }
        r.put("supported", ok);
        call.resolve(r);
    }

    @PluginMethod
    public void setWidgetStyle(PluginCall call) {
        SharedPreferences.Editor e = getContext().getSharedPreferences(TrackService.PREFS, Context.MODE_PRIVATE).edit();
        e.putInt("wTheme", call.getInt("theme", 0));
        e.putInt("wFrame", call.getInt("frame", 0));
        e.putBoolean("wMap", call.getBoolean("map", true));
        e.putString("wLabel", call.getString("label", ""));
        e.apply();
        final Context app = getContext().getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ParejaWidget.refreshNow(app);
                } catch (Exception ignored) { }
            }
        }).start();
        call.resolve();
    }

    // Avisos de llegada/salida de los Lugares (los revisa TrackService con la app cerrada)
    @PluginMethod
    public void setPlaceAlerts(PluginCall call) {
        getContext().getSharedPreferences(TrackService.PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("plAlerts", call.getBoolean("on", true)).apply();
        call.resolve();
    }

    // Mientras la pantalla esta abierta la app avisa sola; en segundo plano avisa el servicio
    @Override
    protected void handleOnResume() {
        TrackService.appVisible = true;
        getContext().getSharedPreferences(TrackService.PREFS, Context.MODE_PRIVATE).edit().putInt("pChatCount", 0).apply();
    }

    @Override
    protected void handleOnPause() {
        TrackService.appVisible = false;
    }

    // Al tocar la notificacion "Nueva version" con la app ya abierta en segundo plano
    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        if (intent != null && intent.getBooleanExtra("openUpdate", false)) {
            notifyListeners("updateTap", new JSObject());
        }
    }

    // Botón atrás: primero deja que el chat cierre lo que tiene abierto (visor de fotos, menú)
    @Override
    public void load() {
        getActivity().getOnBackPressedDispatcher().addCallback(getActivity(), new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                final WebView wv = getBridge().getWebView();
                wv.evaluateJavascript("(window.__handleBack && window.__handleBack()) ? 1 : 0", value -> {
                    if ("1".equals(value)) return;
                    if (wv.canGoBack()) { wv.goBack(); return; }
                    setEnabled(false);
                    getActivity().getOnBackPressedDispatcher().onBackPressed();
                });
            }
        });
    }

    // ---------- Fotos del celular para la cámara del chat (tira de recientes y galería) ----------
    private String photosAlias() { return Build.VERSION.SDK_INT >= 33 ? "photos" : "photosOld"; }

    @PluginMethod
    public void recentPhotos(PluginCall call) {
        if (getPermissionState(photosAlias()) == com.getcapacitor.PermissionState.GRANTED) { loadPhotos(call); return; }
        if (!call.getBoolean("ask", true)) {
            JSObject r = new JSObject();
            r.put("denied", true);
            r.put("items", new com.getcapacitor.JSArray());
            call.resolve(r);
            return;
        }
        requestPermissionForAlias(photosAlias(), call, "photosPermCallback");
    }

    @PermissionCallback
    private void photosPermCallback(PluginCall call) {
        if (getPermissionState(photosAlias()) == com.getcapacitor.PermissionState.GRANTED) { loadPhotos(call); return; }
        JSObject r = new JSObject();
        r.put("denied", true);
        r.put("items", new com.getcapacitor.JSArray());
        call.resolve(r);
    }

    private void loadPhotos(final PluginCall call) {
        final int offset = Math.max(0, call.getInt("offset", 0));
        final int limit = Math.max(1, Math.min(100, call.getInt("limit", 30)));
        new Thread(() -> {
            com.getcapacitor.JSArray items = new com.getcapacitor.JSArray();
            android.content.ContentResolver cr = getContext().getContentResolver();
            Uri col = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            String[] proj = { android.provider.MediaStore.Images.Media._ID };
            String sort = android.provider.MediaStore.Images.Media.DATE_ADDED;
            android.database.Cursor c = null;
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    android.os.Bundle q = new android.os.Bundle();
                    q.putStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS, new String[] { sort });
                    q.putInt(android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION, android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING);
                    q.putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, limit);
                    q.putInt(android.content.ContentResolver.QUERY_ARG_OFFSET, offset);
                    c = cr.query(col, proj, q, null);
                } else {
                    c = cr.query(col, proj, null, null, sort + " DESC LIMIT " + limit + " OFFSET " + offset);
                }
                while (c != null && c.moveToNext()) {
                    long id = c.getLong(0);
                    Uri u = android.content.ContentUris.withAppendedId(col, id);
                    android.graphics.Bitmap bm = null;
                    try {
                        if (Build.VERSION.SDK_INT >= 29) bm = cr.loadThumbnail(u, new android.util.Size(220, 220), null);
                        else bm = android.provider.MediaStore.Images.Thumbnails.getThumbnail(cr, id, android.provider.MediaStore.Images.Thumbnails.MINI_KIND, null);
                    } catch (Exception ignored) { }
                    if (bm == null) continue;
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    bm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, bo);
                    bm.recycle();
                    JSObject it = new JSObject();
                    it.put("id", String.valueOf(id));
                    it.put("thumb", "data:image/jpeg;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP));
                    items.put(it);
                }
                JSObject r = new JSObject();
                r.put("items", items);
                call.resolve(r);
            } catch (Exception e) {
                call.reject(String.valueOf(e.getMessage()));
            } finally {
                if (c != null) c.close();
            }
        }).start();
    }

    // Copia la foto elegida a la caché de la app para que la web la lea (Capacitor.convertFileSrc)
    @PluginMethod
    public void photoFile(final PluginCall call) {
        final String sid = call.getString("id", "");
        new Thread(() -> {
            try {
                long id = Long.parseLong(sid);
                Uri u = android.content.ContentUris.withAppendedId(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                android.content.ContentResolver cr = getContext().getContentResolver();
                File dir = new File(getContext().getCacheDir(), "fotos");
                if (!dir.exists()) dir.mkdirs();
                File[] old = dir.listFiles();
                if (old != null) for (File f : old) f.delete();
                File out = new File(dir, "foto_" + id + "_" + System.currentTimeMillis());
                try (InputStream in = cr.openInputStream(u); OutputStream os = new FileOutputStream(out)) {
                    if (in == null) throw new Exception("no se pudo leer la foto");
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
                JSObject r = new JSObject();
                r.put("path", out.getAbsolutePath());
                String mime = cr.getType(u);
                r.put("mime", mime == null ? "image/jpeg" : mime);
                call.resolve(r);
            } catch (Exception e) {
                call.reject(String.valueOf(e.getMessage()));
            }
        }).start();
    }

    // ---------- Grabación de audio nativa (no depende del WebView) ----------
    private android.media.MediaRecorder recorder;
    private File recFile;
    private long recStart;

    @PluginMethod
    public void startRec(PluginCall call) {
        if (recorder != null) { call.reject("Ya estás grabando"); return; }
        if (!has(Manifest.permission.RECORD_AUDIO)) {
            requestPermissionForAlias("mic", call, "recPerm");
            return;
        }
        beginRec(call);
    }

    @PermissionCallback
    private void recPerm(PluginCall call) {
        if (has(Manifest.permission.RECORD_AUDIO)) beginRec(call);
        else call.reject("Permite el micrófono en los ajustes de la app");
    }

    private void beginRec(PluginCall call) {
        try {
            recFile = new File(getContext().getCacheDir(), "rec.m4a");
            if (recFile.exists()) recFile.delete();
            recorder = new android.media.MediaRecorder();
            recorder.setAudioSource(android.media.MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(24000);
            recorder.setAudioSamplingRate(22050);
            recorder.setOutputFile(recFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            recStart = System.currentTimeMillis();
            call.resolve();
        } catch (Exception e) {
            releaseRec();
            call.reject("No se pudo iniciar la grabación: " + e.getMessage());
        }
    }

    @PluginMethod
    public void stopRec(PluginCall call) {
        if (recorder == null) { call.reject("No estás grabando"); return; }
        try {
            recorder.stop();
        } catch (Exception e) {
            releaseRec();
            call.reject("Audio muy corto, intenta de nuevo");
            return;
        }
        releaseRec();
        try {
            FileInputStream in = new FileInputStream(recFile);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            byte[] bytes = out.toByteArray();
            if (bytes.length < 1000) { call.reject("Audio muy corto"); return; }
            if (bytes.length > 900 * 1024) { call.reject("Audio muy largo, graba algo más corto"); return; }
            JSObject r = new JSObject();
            r.put("data", "data:audio/mp4;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP));
            r.put("dur", Math.round((System.currentTimeMillis() - recStart) / 1000.0));
            call.resolve(r);
        } catch (Exception e) {
            call.reject("No se pudo leer el audio: " + e.getMessage());
        }
    }

    @PluginMethod
    public void cancelRec(PluginCall call) {
        releaseRec();
        call.resolve();
    }

    private void releaseRec() {
        if (recorder != null) {
            try { recorder.release(); } catch (Exception ignored) {}
            recorder = null;
        }
    }

    // ---------- Llamadas de voz (WebRTC nativo; la señalización la hace el chat por Firestore) ----------
    private PeerConnectionFactory callFactory;
    private PeerConnection callPc;
    private AudioSource callAudioSrc;
    private AudioTrack callAudio;
    private boolean callRemoteSet = false;
    private final java.util.List<IceCandidate> callPendingIce = new java.util.ArrayList<>();

    private void ensureCallFactory() {
        if (callFactory != null) return;
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(getContext()).createInitializationOptions());
        callFactory = PeerConnectionFactory.builder().createPeerConnectionFactory();
    }

    private void emit(String name, JSObject data) {
        getActivity().runOnUiThread(() -> notifyListeners(name, data));
    }

    private void emitCallError(String msg) {
        JSObject o = new JSObject();
        o.put("state", "ERROR");
        o.put("msg", msg == null ? "" : msg);
        emit("callState", o);
    }

    private void setLocalAndEmit(SessionDescription d) {
        callPc.setLocalDescription(new SdpObserver() {
            public void onCreateSuccess(SessionDescription x) {}
            public void onSetSuccess() {}
            public void onCreateFailure(String e) {}
            public void onSetFailure(String e) { emitCallError(e); }
        }, d);
        JSObject o = new JSObject();
        o.put("type", d.type.canonicalForm());
        o.put("sdp", d.description);
        emit("callLocalSdp", o);
    }

    private void endCallNative() {
        try { if (callPc != null) { callPc.close(); callPc.dispose(); } } catch (Exception ignored) {}
        callPc = null; callAudio = null; callAudioSrc = null;
        callRemoteSet = false; callPendingIce.clear();
        try {
            setSpeaker(false);
            android.media.AudioManager am = (android.media.AudioManager) getContext().getSystemService(android.content.Context.AUDIO_SERVICE);
            am.setMode(android.media.AudioManager.MODE_NORMAL);
        } catch (Exception ignored) {}
    }

    // Altavoz: en Android 12+ se elige el dispositivo de comunicacion; en versiones viejas, setSpeakerphoneOn.
    private boolean setSpeaker(boolean on) {
        android.media.AudioManager am = (android.media.AudioManager) getContext().getSystemService(android.content.Context.AUDIO_SERVICE);
        if (am == null) return false;
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            if (!on) { am.clearCommunicationDevice(); return true; }
            for (android.media.AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                if (d.getType() == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) return am.setCommunicationDevice(d);
            }
            return false;
        }
        am.setSpeakerphoneOn(on);
        return true;
    }

    @PluginMethod
    public void callSpeaker(PluginCall call) {
        boolean on = Boolean.TRUE.equals(call.getBoolean("on", false));
        try {
            boolean ok = setSpeaker(on);
            JSObject r = new JSObject();
            r.put("on", ok ? on : !on);
            call.resolve(r);
        } catch (Exception e) {
            call.reject("No se pudo cambiar el altavoz: " + e.getMessage());
        }
    }

    @PluginMethod
    public void callStartCall(PluginCall call) {
        if (!has(Manifest.permission.RECORD_AUDIO)) {
            requestPermissionForAlias("mic", call, "callPerm");
            return;
        }
        beginCall(call);
    }

    @PermissionCallback
    private void callPerm(PluginCall call) {
        if (has(Manifest.permission.RECORD_AUDIO)) beginCall(call);
        else call.reject("Permite el micrófono para llamar");
    }

    private void beginCall(PluginCall call) {
        try {
            boolean caller = Boolean.TRUE.equals(call.getBoolean("caller", false));
            endCallNative();
            ensureCallFactory();
            java.util.List<PeerConnection.IceServer> servers = new java.util.ArrayList<>();
            servers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
            PeerConnection.RTCConfiguration rtc = new PeerConnection.RTCConfiguration(servers);
            callPc = callFactory.createPeerConnection(rtc, new PeerConnection.Observer() {
                public void onSignalingChange(PeerConnection.SignalingState s) {}
                public void onIceConnectionChange(PeerConnection.IceConnectionState s) {
                    JSObject o = new JSObject();
                    o.put("state", s.name());
                    emit("callState", o);
                }
                public void onIceConnectionReceivingChange(boolean b) {}
                public void onIceGatheringChange(PeerConnection.IceGatheringState s) {}
                public void onIceCandidate(IceCandidate c) {
                    JSObject o = new JSObject();
                    o.put("candidate", c.sdp);
                    o.put("sdpMid", c.sdpMid);
                    o.put("sdpMLineIndex", c.sdpMLineIndex);
                    emit("callIce", o);
                }
                public void onIceCandidatesRemoved(IceCandidate[] cs) {}
                public void onAddStream(MediaStream s) {}
                public void onRemoveStream(MediaStream s) {}
                public void onDataChannel(DataChannel d) {}
                public void onRenegotiationNeeded() {}
                public void onAddTrack(RtpReceiver r, MediaStream[] s) {}
            });
            if (callPc == null) { endCallNative(); call.reject("No se pudo crear la conexión de llamada"); return; }
            callAudioSrc = callFactory.createAudioSource(new MediaConstraints());
            callAudio = callFactory.createAudioTrack("audio0", callAudioSrc);
            callPc.addTrack(callAudio, java.util.Collections.singletonList("stream0"));
            android.media.AudioManager am = (android.media.AudioManager) getContext().getSystemService(android.content.Context.AUDIO_SERVICE);
            am.setMode(android.media.AudioManager.MODE_IN_COMMUNICATION);
            setSpeaker(false); // cada llamada empieza por el auricular
            if (caller) {
                callPc.createOffer(new SdpObserver() {
                    public void onCreateSuccess(SessionDescription d) { setLocalAndEmit(d); }
                    public void onSetSuccess() {}
                    public void onCreateFailure(String e) { emitCallError(e); }
                    public void onSetFailure(String e) { emitCallError(e); }
                }, new MediaConstraints());
            }
            call.resolve();
        } catch (Exception e) {
            endCallNative();
            call.reject("No se pudo iniciar la llamada: " + e.getMessage());
        }
    }

    @PluginMethod
    public void callSetRemote(PluginCall call) {
        if (callPc == null) { call.reject("No hay llamada activa"); return; }
        String type = call.getString("type", "");
        String sdp = call.getString("sdp", "");
        final SessionDescription.Type t = "offer".equals(type) ? SessionDescription.Type.OFFER : SessionDescription.Type.ANSWER;
        callPc.setRemoteDescription(new SdpObserver() {
            public void onCreateSuccess(SessionDescription d) {}
            public void onSetSuccess() {
                callRemoteSet = true;
                for (IceCandidate c : callPendingIce) callPc.addIceCandidate(c);
                callPendingIce.clear();
                if (t == SessionDescription.Type.OFFER) {
                    callPc.createAnswer(new SdpObserver() {
                        public void onCreateSuccess(SessionDescription d) { setLocalAndEmit(d); }
                        public void onSetSuccess() {}
                        public void onCreateFailure(String e) { emitCallError(e); }
                        public void onSetFailure(String e) { emitCallError(e); }
                    }, new MediaConstraints());
                }
            }
            public void onCreateFailure(String e) {}
            public void onSetFailure(String e) { emitCallError(e); }
        }, new SessionDescription(t, sdp));
        call.resolve();
    }

    @PluginMethod
    public void callAddIce(PluginCall call) {
        if (callPc == null) { call.resolve(); return; }
        IceCandidate c = new IceCandidate(
            call.getString("sdpMid", ""),
            call.getInt("sdpMLineIndex", 0),
            call.getString("candidate", ""));
        if (callRemoteSet) callPc.addIceCandidate(c);
        else callPendingIce.add(c);
        call.resolve();
    }

    @PluginMethod
    public void callEnd(PluginCall call) {
        endCallNative();
        call.resolve();
    }
}
