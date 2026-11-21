// Notificaciones push para Pareja GPS (Firebase Cloud Messaging).
// Avisa a la pareja de mensajes del chat, corazones, estados, notitas 💌 y llamadas,
// aunque su app esté cerrada. Se publica con:  firebase deploy --only functions
//
// Los tokens de cada celular están en profiles/<uid>.pushTokens = { <token>: { room, ts } }
// (los escribe la app al abrirse; las reglas de Firestore no cambian).
import { initializeApp } from 'firebase-admin/app';
import { getFirestore, FieldValue, FieldPath } from 'firebase-admin/firestore';
import { getMessaging } from 'firebase-admin/messaging';
import { onDocumentCreated, onDocumentUpdated, onDocumentWritten } from 'firebase-functions/v2/firestore';
import { setGlobalOptions } from 'firebase-functions/v2';
import { logger } from 'firebase-functions';

// ⚠️ SUPOSICIÓN: la región debe ser la misma de la base Firestore
// (Firebase console → Firestore → arriba dice la ubicación, p. ej. "nam5" = us-central1,
//  "southamerica-east1" = São Paulo). Cámbiala aquí si no coincide.
setGlobalOptions({ region: 'us-central1', maxInstances: 3 });

initializeApp();
const db = getFirestore();

// Personas de la sala que deben recibir el aviso (todas menos quien lo envió)
async function recipients(room, fromUid) {
  const users = await db.collection('rooms').doc(room).collection('users').listDocuments();
  const ids = users.map((d) => d.id).filter((id) => id !== fromUid);
  const out = [];
  for (const uid of ids) {
    const p = (await db.collection('profiles').doc(uid).get()).data();
    if (!p || p.room !== room || !p.pushTokens) continue; // ya no está en esta sala
    for (const [token, t] of Object.entries(p.pushTokens)) {
      if (t && t.room === room) out.push({ uid, token });
    }
  }
  return out;
}

// Mensajes "solo datos" con prioridad alta: los muestra ParejaPushService en el celular
// (así no salen repetidos con los avisos del servicio de ubicación).
async function send(room, fromUid, data, ttlSeconds) {
  const list = await recipients(room, fromUid);
  if (!list.length) return;
  const clean = Object.fromEntries(Object.entries(data).map(([k, v]) => [k, String(v ?? '')]));
  await Promise.all(list.map(async ({ uid, token }) => {
    logger.info('push', { type: clean.type, to: uid, token: token.slice(0, 12) });
    try {
      await getMessaging().send({
        token,
        data: { ...clean, to: uid },
        android: { priority: 'high', ttl: ttlSeconds * 1000 }
      });
    } catch (e) {
      const code = e?.errorInfo?.code || e?.code || '';
      if (code === 'messaging/registration-token-not-registered' || code === 'messaging/invalid-registration-token') {
        // app desinstalada o token viejo: se borra para no volver a intentarlo
        await db.collection('profiles').doc(uid).update(new FieldPath('pushTokens', token), FieldValue.delete()).catch(() => {});
      } else {
        logger.warn('push falló', { uid, code, msg: e?.message });
      }
    }
  }));
}

const short = (s, n = 180) => { s = String(s || ''); return s.length > n ? s.slice(0, n - 1) + '…' : s; };

// 1) Mensaje nuevo en el chat: rooms/<sala>/users/<uid>/chat/<id>
export const avisoChat = onDocumentCreated('rooms/{room}/users/{uid}/chat/{msgId}', async (ev) => {
  const m = ev.data?.data();
  if (!m) return;
  if (Date.now() - Number(m.ts || 0) > 10 * 60e3) return; // mensaje viejo que se subió tarde
  const body = m.replyTo ? `↩ ${short(m.text)}` : short(m.text) || 'Nuevo mensaje';
  await send(ev.params.room, ev.params.uid,
    { type: 'chat', name: m.name || 'Tu amor', avatar: m.avatar || '', body, ts: m.ts || Date.now() }, 24 * 3600);
});

// 2) Corazón, estado o notita: campos heartTs / moodTs / msgTs en rooms/<sala>/users/<uid>
//    (ese documento también cambia con cada ubicación; solo se avisa si cambió uno de esos campos)
export const avisoCorazon = onDocumentUpdated('rooms/{room}/users/{uid}', async (ev) => {
  const a = ev.data?.before.data() || {}, b = ev.data?.after.data() || {};
  const base = { name: b.name || 'Tu amor', avatar: b.avatar || '' };
  const { room, uid } = ev.params;
  const fresh = (t) => t && Date.now() - Number(t) < 10 * 60e3;
  const jobs = [];
  if (b.heartTs && b.heartTs !== a.heartTs && fresh(b.heartTs)) {
    jobs.push(send(room, uid, { ...base, type: 'heart', ts: b.heartTs }, 6 * 3600));
  }
  if (b.moodTs && b.moodTs !== a.moodTs && b.mood && fresh(b.moodTs)) {
    jobs.push(send(room, uid, { ...base, type: 'mood', body: short(b.mood), ts: b.moodTs }, 6 * 3600));
  }
  if (b.msgTs && b.msgTs !== a.msgTs && b.msg && fresh(b.msgTs)) {
    jobs.push(send(room, uid, { ...base, type: 'msg', body: short(b.msg), ts: b.msgTs }, 24 * 3600));
  }
  await Promise.all(jobs);
});

// 3) Llamada: rooms/<sala>/call/current (status ringing → avisa; ended → quita el aviso)
export const avisoLlamada = onDocumentWritten('rooms/{room}/call/current', async (ev) => {
  const a = ev.data?.before.data() || {}, b = ev.data?.after.data();
  if (!b || !b.callId) return;
  const { room } = ev.params;
  if (b.status === 'ringing' && (a.callId !== b.callId || a.status !== 'ringing')) {
    if (Date.now() - Number(b.ts || 0) > 60e3) return;
    const me = (await db.collection('rooms').doc(room).collection('users').doc(b.caller).get()).data() || {};
    await send(room, b.caller, { type: 'call', callId: b.callId, name: me.name || '', ts: b.ts || Date.now() }, 60);
  } else if (b.status === 'ended' && a.status !== 'ended') {
    // el callee también recibe esto; se manda a todos menos a quien llamó
    await send(room, a.caller || b.caller || '', { type: 'call_end', callId: b.callId }, 60);
  }
});
