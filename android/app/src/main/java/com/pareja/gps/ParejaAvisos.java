package com.pareja.gps;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Notificaciones de mensajes, corazones y llamadas.
 * Las usan el servicio de ubicacion (TrackService, revisando cada 10 s) y las notificaciones push
 * (ParejaPushService, que llegan aunque la app y el servicio esten cerrados).
 */
final class ParejaAvisos {
    static final int ID_HEART = 4801;
    static final int ID_MOOD = 4802;
    static final int ID_MSG = 4803;
    static final int ID_CHAT = 4804;
    static final int ID_PLACE = 4805;
    static final int ID_CALL = 4950;

    private ParejaAvisos() { }

    private static PendingIntent openApp(Context c, int req) {
        Intent open = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
        if (open == null) return null;
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(c, req, open, piFlags);
    }

    static void notifyUser(Context c, int id, String title, String text) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel("pareja_msgs", "Mensajes y reacciones", NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(ch);
        }
        PendingIntent pi = openApp(c, 0);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(c, "pareja_msgs");
        else b = new Notification.Builder(c).setPriority(Notification.PRIORITY_HIGH);
        b.setContentTitle(title);
        b.setContentText(text);
        b.setStyle(new Notification.BigTextStyle().bigText(text));
        b.setSmallIcon(android.R.drawable.stat_notify_chat);
        b.setAutoCancel(true);
        if (pi != null) b.setContentIntent(pi);
        b.setWhen(System.currentTimeMillis());
        b.setShowWhen(true);
        nm.notify(id, b.build());
    }

    /** "llegó a 🏠 Casa" / "salió de 🏠 Casa"; si el aviso llega tarde agrega "· hace N min". */
    static String placeText(String kind, String emoji, String name, long ts) {
        String t = ("out".equals(kind) ? "sali\u00f3 de " : "lleg\u00f3 a ") + (emoji == null || emoji.length() == 0 ? "" : emoji + " ") + name;
        if (ts > 0) {
            long min = (System.currentTimeMillis() - ts) / 60000L;
            if (min >= 3) t += " \u00b7 hace " + min + " min";
        }
        return t;
    }

    static void notifyCall(Context c, String who) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        android.net.Uri snd = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel("pareja_llamada", "Llamadas", NotificationManager.IMPORTANCE_HIGH);
            ch.setSound(snd, new android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            ch.enableVibration(true);
            nm.createNotificationChannel(ch);
        }
        PendingIntent pi = openApp(c, ID_CALL);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(c, "pareja_llamada");
        else {
            b = new Notification.Builder(c).setPriority(Notification.PRIORITY_HIGH);
            b.setSound(snd);
            b.setDefaults(Notification.DEFAULT_VIBRATE);
        }
        String title = (who == null || who.length() == 0) ? "Te está llamando tu pareja" : "Te está llamando " + who;
        b.setContentTitle("📞 " + title);
        b.setContentText("Toca para contestar");
        b.setSmallIcon(android.R.drawable.stat_sys_phone_call);
        b.setCategory(Notification.CATEGORY_CALL);
        b.setAutoCancel(true);
        if (Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(60000L); // deja de sonar a los 60 s
        if (pi != null) b.setContentIntent(pi);
        b.setWhen(System.currentTimeMillis());
        b.setShowWhen(true);
        nm.notify(ID_CALL, b.build());
    }

    static void cancel(Context c, int id) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(id);
    }
}
