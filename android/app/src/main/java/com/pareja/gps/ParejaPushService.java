package com.pareja.gps;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

/**
 * Recibe las notificaciones push (Firebase Cloud Messaging) que manda la Cloud Function
 * el Worker de Cloudflare (worker/push-worker.js) cuando la pareja escribe, manda un corazon,
 * llama o llega / sale de un Lugar.
 * Llegan aunque la app y el servicio de ubicacion esten cerrados.
 *
 * Usa los mismos marcadores que TrackService (nHeart, nChat, lastCallId...) para que
 * el mismo aviso no salga dos veces si el servicio tambien lo encuentra.
 */
public class ParejaPushService extends FirebaseMessagingService {

    @Override
    public void onNewToken(String token) {
        // Se guarda aqui; la app lo sube a Firestore la proxima vez que se abre (pushToken en el plugin)
        getSharedPreferences(TrackService.PREFS, Context.MODE_PRIVATE).edit()
            .putString("fcmToken", token).putBoolean("fcmDirty", true).apply();
    }

    @Override
    public void onMessageReceived(RemoteMessage msg) {
        Map<String, String> d = msg.getData();
        if (d == null || d.isEmpty()) return;
        String type = get(d, "type");
        SharedPreferences sp = getSharedPreferences(TrackService.PREFS, Context.MODE_PRIVATE);

        // Fin de llamada: quita el aviso de "te esta llamando" (aunque la app este abierta)
        if ("call_end".equals(type)) {
            if (get(d, "callId").equals(sp.getString("lastCallId", ""))) ParejaAvisos.cancel(this, ParejaAvisos.ID_CALL);
            return;
        }
        if (TrackService.appVisible) return; // con la app abierta, la propia app avisa

        // La notificacion es para la cuenta que esta abierta en este celular
        String to = get(d, "to");
        String uid = sp.getString("uid", "");
        if (to.length() > 0 && uid.length() > 0 && !to.equals(uid)) return;

        String name = get(d, "name");
        if (name.length() == 0) name = "Tu amor";
        String avatar = get(d, "avatar");
        long ts = num(d, "ts");
        SharedPreferences.Editor e = sp.edit();

        switch (type) {
            case "heart":
                if (ts <= sp.getLong("nHeart", 0L)) return;
                ParejaAvisos.notifyUser(this, ParejaAvisos.ID_HEART, "💗 " + name, "te envió un corazón");
                e.putLong("nHeart", ts);
                break;
            case "mood":
                if (ts <= sp.getLong("nMood", 0L)) return;
                ParejaAvisos.notifyUser(this, ParejaAvisos.ID_MOOD, (avatar.length() > 0 ? avatar + " " : "") + name, get(d, "body"));
                e.putLong("nMood", ts);
                break;
            case "msg":
                if (ts <= sp.getLong("nMsg", 0L)) return;
                ParejaAvisos.notifyUser(this, ParejaAvisos.ID_MSG, name + " 💌", get(d, "body"));
                e.putLong("nMsg", ts);
                break;
            case "chat": {
                if (ts <= sp.getLong("nChat", 0L)) return;
                int count = sp.getInt("pChatCount", 0) + 1; // se reinicia al abrir la app
                String title = count > 1 ? name + " · " + count + " mensajes" : name;
                ParejaAvisos.notifyUser(this, ParejaAvisos.ID_CHAT, title, get(d, "body"));
                e.putLong("nChat", ts).putInt("pChatCount", count);
                break;
            }
            case "place": {
                // Llegada / salida de un Lugar: la manda el celular de la pareja apenas su GPS lo detecta
                if (ts <= 0 || ts <= sp.getLong("nPlace", 0L)) return;
                e.putLong("nPlace", ts);
                if (sp.getBoolean("plAlerts", true) && System.currentTimeMillis() - ts < TrackService.PLACE_EV_MAX_AGE) {
                    String who = (avatar.length() > 0 ? avatar + " " : "") + name;
                    String body = get(d, "body");
                    if (ts > 0 && System.currentTimeMillis() - ts >= 3L * 60000L) {
                        body += " \u00b7 hace " + ((System.currentTimeMillis() - ts) / 60000L) + " min";
                    }
                    ParejaAvisos.notifyUser(this, ParejaAvisos.ID_PLACE, who, body);
                }
                break;
            }
            case "call": {
                String callId = get(d, "callId");
                if (callId.length() == 0 || callId.equals(sp.getString("lastCallId", ""))) return;
                if (ts > 0 && System.currentTimeMillis() - ts > 60000L) return; // llamada vieja
                e.putString("lastCallId", callId);
                ParejaAvisos.notifyCall(this, name);
                break;
            }
            default:
                return;
        }
        e.apply();
    }

    private static String get(Map<String, String> d, String k) {
        String v = d.get(k);
        return v == null ? "" : v;
    }

    private static long num(Map<String, String> d, String k) {
        try {
            return Long.parseLong(get(d, k));
        } catch (Exception ex) {
            return 0L;
        }
    }
}
