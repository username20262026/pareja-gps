// Cloudflare Worker: notificaciones push de Pareja GPS (gratis, sin el plan Blaze de Firebase).
//
// La app llama a  POST https://push.nnxsw-proyect.date/notify  cuando manda un mensaje,
// un corazón, un estado, una notita 💌 o empieza / corta una llamada.
//
//   Cabecera:  Authorization: Bearer <ID token de Firebase de quien envía>
//   Cuerpo:    { room, type: 'chat'|'heart'|'mood'|'msg'|'call'|'call_end'|'place', name, avatar, body, ts, callId, kind }
//   ('place' = llegó / salió de un Lugar; lo manda el servicio de ubicación del celular que se mueve)
//
// El Worker:
//   1. comprueba el ID token (que sea una cuenta real de tu proyecto Firebase),
//   2. comprueba que esa cuenta sea miembro de la sala (rooms/<sala>.members),
//   3. lee los tokens del celular de la pareja en profiles/<uid>.pushTokens
//      (la app no los puede leer: solo los ve su dueño; por eso los lee el Worker),
//   4. manda el aviso con la API HTTP v1 de Firebase Cloud Messaging (funciona en el plan gratis Spark).
//
// Secreto necesario en Cloudflare (Workers → tu Worker → Configuración → Variables y secretos):
//   SERVICE_ACCOUNT  = el contenido completo del archivo JSON de la cuenta de servicio de Firebase
//                      (Firebase console → ⚙️ Configuración del proyecto → Cuentas de servicio →
//                       "Generar nueva clave privada").
// No necesita ninguna librería: se pega este archivo tal cual en el editor del Worker.

const TYPES = new Set(['chat', 'heart', 'mood', 'msg', 'call', 'call_end', 'place']);
// Cuánto tiempo guarda FCM el aviso si el celular está apagado o sin internet (segundos)
const TTL = { chat: 86400, msg: 86400, heart: 21600, mood: 21600, call: 60, call_end: 60, place: 1800 };

const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
  'Access-Control-Allow-Headers': 'Authorization, Content-Type',
  'Access-Control-Max-Age': '86400'
};
const json = (obj, status = 200) =>
  new Response(JSON.stringify(obj), { status, headers: { 'Content-Type': 'application/json', ...CORS } });

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    if (req.method === 'OPTIONS') return new Response(null, { status: 204, headers: CORS });
    if (req.method === 'GET' && url.pathname === '/') {
      let sa = null;
      try { sa = serviceAccount(env); } catch (e) { /* se informa abajo */ }
      return json({ ok: !!sa, proyecto: sa ? sa.project_id : null, aviso: sa ? 'Listo' : 'Falta el secreto SERVICE_ACCOUNT' });
    }
    if (req.method !== 'POST' || url.pathname !== '/notify') return json({ error: 'no encontrado' }, 404);

    try {
      const sa = serviceAccount(env);
      const idToken = (req.headers.get('Authorization') || '').replace(/^Bearer\s+/i, '');
      const uid = await verifyIdToken(idToken, sa.project_id);
      if (!uid) return json({ error: 'sesión no válida' }, 401);

      const p = await req.json().catch(() => null);
      if (!p || typeof p.room !== 'string' || !/^[^/]{1,200}$/.test(p.room) || !TYPES.has(p.type)) {
        return json({ error: 'datos no válidos' }, 400);
      }

      const fs = new Firestore(sa, await accessToken(sa));
      const room = await fs.get(`rooms/${p.room}`);
      const members = (room && room.members) || [];
      if (!members.includes(uid)) return json({ error: 'no eres de esta sala' }, 403);

      const data = {
        type: p.type,
        name: short(p.name, 40) || 'Tu amor',
        avatar: short(p.avatar, 8),
        body: short(p.body, 180),
        ts: String(Number(p.ts) || Date.now()),
        callId: short(p.callId, 60),
        kind: p.kind === 'out' ? 'out' : (p.kind === 'in' ? 'in' : '')
      };

      let sent = 0;
      for (const other of members) {
        if (other === uid) continue;
        const prof = await fs.get(`profiles/${other}`);
        if (!prof || prof.room !== p.room || !prof.pushTokens) continue; // ya no está en esta sala
        for (const [token, t] of Object.entries(prof.pushTokens)) {
          if (!t || t.room !== p.room) continue;
          const r = await fs.sendPush(token, { ...data, to: other }, TTL[p.type]);
          if (r === 'ok') sent++;
          else if (r === 'gone') await fs.deleteToken(other, token); // app desinstalada o token viejo
        }
      }
      return json({ ok: true, sent });
    } catch (e) {
      console.error('notify', e && e.stack || e);
      return json({ error: String(e && e.message || e) }, 500);
    }
  }
};

const short = (s, n) => { s = String(s ?? ''); return s.length > n ? s.slice(0, n - 1) + '…' : s; };

function serviceAccount(env) {
  if (!env.SERVICE_ACCOUNT) throw new Error('Falta el secreto SERVICE_ACCOUNT');
  const sa = typeof env.SERVICE_ACCOUNT === 'string' ? JSON.parse(env.SERVICE_ACCOUNT) : env.SERVICE_ACCOUNT;
  if (!sa.project_id || !sa.client_email || !sa.private_key) throw new Error('SERVICE_ACCOUNT incompleto');
  return sa;
}

// ---------- base64url ----------
const b64url = (buf) => {
  const bytes = buf instanceof Uint8Array ? buf : new Uint8Array(buf);
  let s = ''; for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
};
const b64urlJson = (o) => b64url(new TextEncoder().encode(JSON.stringify(o)));
const fromB64url = (s) => {
  s = s.replace(/-/g, '+').replace(/_/g, '/'); while (s.length % 4) s += '=';
  return Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
};
const parseJson = (s) => JSON.parse(new TextDecoder().decode(fromB64url(s)));
const RS256 = { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' };

// ---------- 1) Comprobar el ID token de Firebase Auth ----------
// Las claves públicas de Google cambian cada pocas horas; se guardan mientras el Worker siga vivo.
let jwks = null, jwksExp = 0;
async function googleKeys() {
  if (jwks && Date.now() < jwksExp) return jwks;
  const r = await fetch('https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com');
  if (!r.ok) throw new Error('No pude leer las claves de Google');
  const m = /max-age=(\d+)/.exec(r.headers.get('Cache-Control') || '');
  jwks = (await r.json()).keys; jwksExp = Date.now() + (m ? Number(m[1]) : 3600) * 1000;
  return jwks;
}
async function verifyIdToken(tok, projectId) {
  const parts = (tok || '').split('.');
  if (parts.length !== 3) return null;
  let head, claims;
  try { head = parseJson(parts[0]); claims = parseJson(parts[1]); } catch { return null; }
  if (head.alg !== 'RS256') return null;
  const now = Math.floor(Date.now() / 1000);
  if (claims.aud !== projectId || claims.iss !== `https://securetoken.google.com/${projectId}`) return null;
  if (!claims.sub || claims.exp < now - 60 || claims.iat > now + 300) return null;
  const jwk = (await googleKeys()).find((k) => k.kid === head.kid);
  if (!jwk) return null;
  const key = await crypto.subtle.importKey('jwk', jwk, RS256, false, ['verify']);
  const ok = await crypto.subtle.verify(RS256, key, fromB64url(parts[2]), new TextEncoder().encode(`${parts[0]}.${parts[1]}`));
  return ok ? claims.sub : null;
}

// ---------- 2) Token de acceso de la cuenta de servicio (dura 1 hora) ----------
let access = null, accessExp = 0;
async function accessToken(sa) {
  if (access && Date.now() < accessExp) return access;
  const now = Math.floor(Date.now() / 1000);
  const unsigned = `${b64urlJson({ alg: 'RS256', typ: 'JWT' })}.${b64urlJson({
    iss: sa.client_email,
    scope: 'https://www.googleapis.com/auth/datastore https://www.googleapis.com/auth/firebase.messaging',
    aud: 'https://oauth2.googleapis.com/token',
    iat: now, exp: now + 3600
  })}`;
  const pem = sa.private_key.replace(/-----[^-]+-----/g, '').replace(/\s+/g, '');
  const key = await crypto.subtle.importKey('pkcs8', Uint8Array.from(atob(pem), (c) => c.charCodeAt(0)), RS256, false, ['sign']);
  const sig = await crypto.subtle.sign(RS256, key, new TextEncoder().encode(unsigned));
  const r = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion: `${unsigned}.${b64url(sig)}` })
  });
  const j = await r.json();
  if (!r.ok || !j.access_token) throw new Error('Google no aceptó la cuenta de servicio: ' + (j.error_description || j.error || r.status));
  access = j.access_token; accessExp = Date.now() + (Number(j.expires_in || 3600) - 300) * 1000;
  return access;
}

// ---------- 3) Firestore (REST) y 4) FCM HTTP v1 ----------
class Firestore {
  constructor(sa, token) {
    this.pid = sa.project_id;
    this.base = `https://firestore.googleapis.com/v1/projects/${sa.project_id}/databases/(default)/documents`;
    this.h = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
  }
  async get(path) {
    const r = await fetch(`${this.base}/${path.split('/').map(encodeURIComponent).join('/')}`, { headers: this.h });
    if (r.status === 404) return null;
    if (!r.ok) throw new Error(`Firestore ${r.status} al leer ${path.split('/')[0]}`);
    const d = await r.json();
    return decodeFields(d.fields || {});
  }
  async deleteToken(uid, token) {
    // Quita solo pushTokens.<token> del perfil (el token lleva ":" y "-", por eso va entre `comillas`)
    const field = `pushTokens.\`${token.replace(/\\/g, '\\\\').replace(/`/g, '\\`')}\``;
    const u = `${this.base}/profiles/${encodeURIComponent(uid)}?updateMask.fieldPaths=${encodeURIComponent(field)}&currentDocument.exists=true`;
    await fetch(u, { method: 'PATCH', headers: this.h, body: JSON.stringify({ fields: {} }) }).catch(() => {});
  }
  async sendPush(token, data, ttlSeconds) {
    // Mensaje "solo datos" con prioridad alta: lo muestra ParejaPushService en el celular
    const r = await fetch(`https://fcm.googleapis.com/v1/projects/${this.pid}/messages:send`, {
      method: 'POST', headers: this.h,
      body: JSON.stringify({ message: { token, data, android: { priority: 'HIGH', ttl: `${ttlSeconds}s` } } })
    });
    if (r.ok) return 'ok';
    const t = await r.text();
    if (r.status === 404 || /UNREGISTERED|registration-token-not-registered/.test(t)) return 'gone';
    console.warn('FCM', r.status, t.slice(0, 300));
    return 'error';
  }
}
function decodeValue(v) {
  if ('stringValue' in v) return v.stringValue;
  if ('integerValue' in v) return Number(v.integerValue);
  if ('doubleValue' in v) return v.doubleValue;
  if ('booleanValue' in v) return v.booleanValue;
  if ('timestampValue' in v) return v.timestampValue;
  if ('nullValue' in v) return null;
  if ('arrayValue' in v) return (v.arrayValue.values || []).map(decodeValue);
  if ('mapValue' in v) return decodeFields(v.mapValue.fields || {});
  return null;
}
function decodeFields(f) {
  const o = {};
  for (const [k, v] of Object.entries(f)) o[k] = decodeValue(v);
  return o;
}
