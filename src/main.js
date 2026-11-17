import { onSnapshot as fsOnSnapshot, addDoc as fsAddDoc, collection as fsCollection } from 'firebase/firestore';
import './style.css';
import 'leaflet/dist/leaflet.css';
import L from 'leaflet';
import { Capacitor, registerPlugin } from '@capacitor/core';
import {
  onAuthStateChanged, signInWithEmailAndPassword, createUserWithEmailAndPassword,
  sendPasswordResetEmail, signOut
} from 'firebase/auth';
import {
  doc, getDoc, collection, writeBatch, setDoc, addDoc, deleteDoc, onSnapshot, query, where, orderBy, limit, getDocs
} from 'firebase/firestore';
import { auth, db } from './firebase.js';

const BG = registerPlugin('BackgroundGeolocation');
const Native = registerPlugin('ParejaNative');
const IS_NATIVE = Capacitor.isNativePlatform();
const UPDATE_BASE = 'https://app-pareja-c1579.web.app';
const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const withTimeout = (p, ms) =>
  Promise.race([p, new Promise((_, rej) => setTimeout(() => rej(new Error('timeout')), ms))]);

const AVATARS = ['💗', '🐻', '🦊', '🐱', '🌹', '🌙', '☀️', '⭐'];
const MOODS = [['😍', 'Pensando en ti'], ['🚗', 'En camino'], ['🏠', 'En casa'], ['💼', 'Trabajando'],
  ['🍽️', 'Comiendo'], ['😴', 'Durmiendo'], ['😘', 'Te extraño'], ['🥰', 'Feliz']];

// ---------- Estado ----------
let cfg = null, started = false;
let queue = JSON.parse(localStorage.getItem('queue') || '[]');
let sharing = localStorage.getItem('sharing') !== 'off';
let styleName = localStorage.getItem('mapStyle') || 'calle';
let showLine = localStorage.getItem('showLine') !== 'off';
let map, baseLayer, histLayer, placesLayer, pairLine, pairLabel;
let wStyle = (() => { try { return { theme: 0, frame: 0, map: true, label: '', ...JSON.parse(localStorage.getItem('wstyle') || '{}') }; } catch { return { theme: 0, frame: 0, map: true, label: '' }; } })();
let mKey = localStorage.getItem('mkey') || '', gKey = localStorage.getItem('gkey') || '', gSession = '', gAttr = '', gAttrT, curStyle = '', styleSeq = 0;
let frameName = localStorage.getItem('mapFrame') || 'hearts';
let watcherId = null, webWatchId = null, flushing = false, trackStarting = false, histRun = 0, lastKept = null, histData = null;
const histOpts = { guess: true, dots: false };
let addingPlace = false, anniv = '', places = [], firstFit = true, centered = false, wasTogether = false;
const markers = {}, people = {};

const saveQ = () => localStorage.setItem('queue', JSON.stringify(queue));
const userPath = () => ['rooms', cfg.room, 'users', cfg.uid];
const saveMe = (data) =>
  setDoc(doc(db, ...userPath()), { uid: cfg.uid, name: cfg.name, avatar: cfg.avatar, ...data }, { merge: true }).catch(() => {});

// ---------- Utilidades ----------
const dist = (a, b) => {
  const R = 6371000, t = (x) => x * Math.PI / 180;
  const h = Math.sin(t(b.lat - a.lat) / 2) ** 2 +
    Math.cos(t(a.lat)) * Math.cos(t(b.lat)) * Math.sin(t(b.lng - a.lng) / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(h));
};
const fmtDist = (m) => (m < 1000 ? `${Math.round(m)} m` : `${(m / 1000).toFixed(1)} km`);
const ago = (ts) => {
  const m = Math.round((Date.now() - ts) / 60000);
  return m < 1 ? 'ahora mismo' : m < 60 ? `hace ${m} min` : m < 1440 ? `hace ${Math.round(m / 60)} h` : `hace ${Math.round(m / 1440)} d`;
};
const daysTogether = () => {
  if (!anniv) return null;
  const d = Math.floor((Date.now() - new Date(anniv + 'T00:00:00').getTime()) / 86400000);
  return d >= 0 ? d : null;
};
let toastT;
function toast(msg) {
  const t = $('toast'); t.textContent = msg; t.classList.remove('hidden');
  clearTimeout(toastT); toastT = setTimeout(() => t.classList.add('hidden'), 3500);
}
function floatHearts(n = 14) {
  const box = $('hearts');
  for (let i = 0; i < n; i++) {
    const s = document.createElement('span');
    s.textContent = ['💗', '💖', '💕', '❤️'][i % 4];
    s.style.left = Math.random() * 90 + '%';
    s.style.animationDelay = Math.random() * 0.8 + 's';
    box.appendChild(s);
    setTimeout(() => s.remove(), 3800);
  }
}
const navigate = (lat, lng) =>
  window.open(`https://www.google.com/maps/dir/?api=1&destination=${lat},${lng}`, '_blank');

function showScreen(name) {
  ['login', 'setup', 'app'].forEach((s) => $(s).classList.toggle('hidden', s !== name));
}

// ---------- Cuenta (correo y contraseña) ----------
const authError = (e) => ({
  'auth/invalid-email': 'El correo no es válido.',
  'auth/invalid-credential': 'Correo o contraseña incorrectos.',
  'auth/wrong-password': 'Correo o contraseña incorrectos.',
  'auth/user-not-found': 'Ese correo no tiene cuenta. Pulsa "Crear cuenta nueva".',
  'auth/email-already-in-use': 'Ese correo ya tiene cuenta. Pulsa "Entrar".',
  'auth/weak-password': 'La contraseña es muy corta (mínimo 6).',
  'auth/operation-not-allowed': 'Falta activar "Correo electrónico/contraseña" en Firebase → Authentication.',
  'auth/network-request-failed': 'Sin conexión. Revisa tu internet.',
  'auth/too-many-requests': 'Demasiados intentos. Espera un momento.'
}[e.code] || `Error: ${e.code || e.message}`);

function initLogin() {
  const msg = (t) => { $('lgMsg').textContent = t; };
  const run = async (isNew) => {
    const email = $('lgEmail').value.trim(), pass = $('lgPass').value;
    if (!email || pass.length < 6) return msg('Escribe tu correo y una contraseña de al menos 6 caracteres.');
    msg('Un momento… 💞');
    try {
      await (isNew ? createUserWithEmailAndPassword(auth, email, pass) : signInWithEmailAndPassword(auth, email, pass));
    } catch (e) { msg(authError(e)); }
  };
  $('btnLogin').onclick = () => run(false);
  $('btnSignup').onclick = () => run(true);
  $('btnReset').onclick = async () => {
    const email = $('lgEmail').value.trim();
    if (!email) return msg('Escribe tu correo arriba y vuelve a pulsar.');
    try { await sendPasswordResetEmail(auth, email); msg('Te enviamos un correo para cambiar la contraseña.'); }
    catch (e) { msg(authError(e)); }
  };
}

onAuthStateChanged(auth, async (user) => {
  if (user && user.isAnonymous) { await signOut(auth); return; } // sesiones viejas anónimas
  if (!user) { started = false; showScreen('login'); return; }
  if (started) return;
  await loadProfile(user);
});

async function loadProfile(user) {
  const key = 'cfg_' + user.uid;
  let p = null;
  try {
    const s = await withTimeout(getDoc(doc(db, 'profiles', user.uid)), 8000);
    p = s.exists() ? s.data() : null;
  } catch { p = JSON.parse(localStorage.getItem(key) || 'null'); }
  if (p && p.room && p.name) {
    localStorage.setItem(key, JSON.stringify(p));
    cfg = { ...p, uid: user.uid, email: user.email };
    anniv = p.anniv || '';
    started = true; showScreen('app'); startApp();
  } else showSetup(user, p);
}

function showSetup(user, p) {
  showScreen('setup');
  $('setupSub').textContent = user.email || '';
  $('inName').value = p?.name || (user.email || '').split('@')[0];
  $('inRoom').value = p?.room || '';
  $('inAnniv').value = p?.anniv || '';
  let avatar = p?.avatar || AVATARS[0];
  const box = $('avatars'); box.innerHTML = '';
  AVATARS.forEach((a) => {
    const b = document.createElement('button');
    b.textContent = a; if (a === avatar) b.className = 'sel';
    b.onclick = () => { avatar = a; [...box.children].forEach((c) => c.classList.toggle('sel', c === b)); };
    box.appendChild(b);
  });
  $('btnGen').onclick = () => { $('inRoom').value = crypto.randomUUID().replace(/-/g, '').slice(0, 16); };
  $('btnSetupOut').onclick = () => signOut(auth);
  $('btnSave').onclick = async () => {
    const name = $('inName').value.trim(), room = $('inRoom').value.trim();
    if (!name || room.length < 8) return alert('Pon tu nombre y un código de sala de al menos 8 caracteres.');
    const profile = { name, avatar, room, anniv: $('inAnniv').value };
    try { await withTimeout(setDoc(doc(db, 'profiles', user.uid), profile), 10000); }
    catch { return alert('No se pudo guardar. Revisa tu internet y las reglas de Firestore.'); }
    localStorage.setItem('cfg_' + user.uid, JSON.stringify(profile));
    cfg = { ...profile, uid: user.uid, email: user.email };
    anniv = profile.anniv || '';
    started = true; showScreen('app'); startApp();
  };
}

// ---------- App ----------
async function startApp() {
  $('hdrName').textContent = `${cfg.avatar} ${cfg.name}`;
  $('hdrSub').textContent = `sala ${cfg.room.slice(0, 4)}…`;
  $('accInfo').textContent = `${cfg.email || ''} · en todos tus celulares eres el mismo usuario`;
  $('inAnniv2').value = anniv;
  initMap(); initUI();
  if (cfg.anniv) setDoc(doc(db, 'rooms', cfg.room, 'meta', 'couple'), { anniv: cfg.anniv }, { merge: true }).catch(() => {});
  listenPeople(); listenPlaces(); listenMeta(); listenChat();
  lastKept = queue[queue.length - 1] || null;
  maybeStartTracking(); initNative(); startCallWatcher();
  window.addEventListener('online', flush);
  setInterval(flush, 30000);
  setInterval(() => { renderUs(); renderPlaces(); }, 30000);
  flush(); renderUs();
}
const ensureAuth = async () => !!auth.currentUser;

// ---------- Mapa ----------
const OSM = 'https://tile.openstreetmap.org/{z}/{x}/{y}.png';
const ESRI = 'https://server.arcgisonline.com/ArcGIS/rest/services';
const STYLES = {
  calle: () => L.layerGroup([L.tileLayer(OSM, { maxZoom: 19, attribution: '© OpenStreetMap' })]),
  satelite: () => L.layerGroup([
    L.tileLayer(`${ESRI}/World_Imagery/MapServer/tile/{z}/{y}/{x}`, { maxZoom: 19, attribution: 'Imágenes © Esri' }),
    L.tileLayer(`${ESRI}/Reference/World_Boundaries_and_Places/MapServer/tile/{z}/{y}/{x}`, { maxZoom: 19 })
  ]),
  oscuro: () => L.layerGroup([L.tileLayer(OSM, { maxZoom: 19, className: 'dark-tiles', attribution: '© OpenStreetMap' })])
};
// Mapbox: satélite con calles (mapbox.com, clave pública "pk.")
const mapboxLayer = () => {
  let fallos = 0;
  const tl = L.tileLayer(
    `https://api.mapbox.com/styles/v1/mapbox/satellite-streets-v12/tiles/{z}/{x}/{y}@2x?access_token=${encodeURIComponent(mKey)}`,
    { tileSize: 512, zoomOffset: -1, maxZoom: 21, attribution: '© Mapbox © OpenStreetMap' });
  // Si Mapbox no entrega las imágenes (clave sin permiso o restringida), vuelve a calles sin dejar el mapa en gris
  tl.on('tileerror', () => {
    if (++fallos === 3) { toast('Mapbox no carga: revisa la clave. Usando calles.'); setStyle('calle'); }
  });
  return L.layerGroup([tl]);
};
// Google Maps (satélite actual) por la API oficial Map Tiles
async function googleLayer() {
  const r = await fetch(`https://tile.googleapis.com/v1/createSession?key=${encodeURIComponent(gKey)}`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ mapType: 'satellite', language: 'es', region: 'PY', layerTypes: ['layerRoadmap'], overlay: false })
  });
  if (!r.ok) throw new Error('Google ' + r.status);
  const j = await r.json();
  gSession = typeof j.session === 'string' ? j.session : j.session?.token;
  if (!gSession) throw new Error('sin sesión');
  return L.layerGroup([L.tileLayer(
    `https://tile.googleapis.com/v1/2dtiles/{z}/{x}/{y}?session=${gSession}&key=${encodeURIComponent(gKey)}`, { maxZoom: 21 })]);
}
function setAttr(t) {
  const c = map.attributionControl;
  if (gAttr) c.removeAttribution(gAttr);
  gAttr = t; if (t) c.addAttribution(t);
}
async function refreshGAttr() {
  if (curStyle !== 'google' || !gSession) return;
  const bd = map.getBounds(), cl = (v) => Math.max(-180, Math.min(180, v));
  try {
    const r = await fetch(`https://tile.googleapis.com/tile/v1/viewport?session=${gSession}&key=${encodeURIComponent(gKey)}` +
      `&zoom=${Math.round(map.getZoom())}&north=${Math.min(89, bd.getNorth())}&south=${Math.max(-89, bd.getSouth())}&east=${cl(bd.getEast())}&west=${cl(bd.getWest())}`);
    const j = await r.json();
    setAttr(j.copyright ? `Google · ${j.copyright}` : 'Imágenes © Google');
  } catch { setAttr('Imágenes © Google'); }
}
async function setStyle(name) {
  const seq = ++styleSeq;
  let layer, shown = name;
  if (name === 'google') {
    if (!gKey) { toast('Pon tu clave de Google Maps en Ajustes'); shown = 'satelite'; }
    else {
      try { layer = await googleLayer(); }
      catch (e) { console.error(e); toast('Google Maps no respondió. Revisa la clave. Muestro el satélite normal.'); shown = 'satelite'; }
    }
  }
  if (name === 'mapbox') {
    if (!mKey) { toast('Pon tu clave de Mapbox en Ajustes'); shown = 'satelite'; } else layer = mapboxLayer();
  }
  if (seq !== styleSeq) return;
  if (!layer) layer = STYLES[shown]();
  if (baseLayer) map.removeLayer(baseLayer);
  baseLayer = layer.addTo(map);
  curStyle = shown; styleName = name; localStorage.setItem('mapStyle', name);
  $('btnLayer').textContent = { calle: '🗺️', satelite: '🛰️', google: '🌍', mapbox: '🌐', oscuro: '🌙' }[shown];
  document.querySelectorAll('#styleChips button').forEach((b) => b.classList.toggle('active', b.dataset.style === shown));
  if (shown === 'google') refreshGAttr(); else setAttr(shown === 'mapbox' ? '© Mapbox © OpenStreetMap' : '');
}

// Marcos del mapa
const FRAMES = { hearts: ['💗', '💕', '💕', '💗'], polaroid: [], gold: ['✦', '✦', '✦', '✦'], none: [] };
function setFrame(name) {
  if (!FRAMES[name]) name = 'hearts';
  frameName = name; localStorage.setItem('mapFrame', name);
  $('mapWrap').className = 'f-' + name;
  $('frame').innerHTML = FRAMES[name].map((e) => `<i>${e}</i>`).join('');
  document.querySelectorAll('#frameChips button').forEach((b) => b.classList.toggle('active', b.dataset.frame === name));
  setTimeout(() => map && map.invalidateSize(), 60);
}
function initMap() {
  map = L.map('map', { zoomControl: false }).setView([0, 0], 2);
  L.control.zoom({ position: 'bottomleft' }).addTo(map);
  placesLayer = L.layerGroup().addTo(map);
  setStyle(styleName === 'google' || styleName === 'mapbox' || STYLES[styleName] ? styleName : 'calle');
  setFrame(frameName);
  map.on('moveend', () => { if (curStyle === 'google') { clearTimeout(gAttrT); gAttrT = setTimeout(refreshGAttr, 700); } });
  map.on('click', (e) => {
    if (!addingPlace) return;
    addingPlace = false; addPlace(e.latlng.lat, e.latlng.lng);
  });
}
const pinIcon = (avatar, name, isMe) => L.divIcon({
  className: '', iconSize: [44, 70], iconAnchor: [22, 44],
  html: `<div class="pin ${isMe ? 'me' : ''}"><div class="face" data-e="${esc(avatar || '💗')}"></div><em>${esc(name)}</em></div>`
});
function drawPerson(id, data, isMe) {
  people[id] = { ...people[id], ...data };
  const d = people[id];
  if (d.lat == null) return;
  const icon = pinIcon(d.avatar, d.name, isMe);
  if (!markers[id]) markers[id] = L.marker([d.lat, d.lng], { icon, zIndexOffset: isMe ? 0 : 500 }).addTo(map);
  else markers[id].setLatLng([d.lat, d.lng]).setIcon(icon);
  updatePair();
}
function fitAll() {
  const pts = Object.values(markers).map((m) => m.getLatLng());
  if (pts.length === 1) map.setView(pts[0], 16);
  else if (pts.length > 1) map.fitBounds(L.latLngBounds(pts), { padding: [60, 90] });
}

// Línea punteada con la distancia entre los dos
function clearPair() {
  if (pairLine) { map.removeLayer(pairLine); pairLine = null; }
  if (pairLabel) { map.removeLayer(pairLabel); pairLabel = null; }
}
function updatePair() {
  const me = people[cfg.uid], o = otherPeople()[0];
  if (!showLine || !me || me.lat == null || !o) return clearPair();
  const d = dist(me, o);
  if (d < 50) return clearPair();
  const a = [me.lat, me.lng], b = [o.lat, o.lng], mid = [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2];
  const icon = L.divIcon({ className: '', iconSize: [0, 0], html: `<div class="dist">📏 ${fmtDist(d)}</div>` });
  if (!pairLine) {
    pairLine = L.polyline([a, b], { color: '#f43f5e', weight: 4, dashArray: '8 10', opacity: 0.9 }).addTo(map);
    pairLabel = L.marker(mid, { icon, interactive: false, zIndexOffset: 900 }).addTo(map);
  } else { pairLine.setLatLngs([a, b]); pairLabel.setLatLng(mid).setIcon(icon); }
}

// ---------- Interfaz ----------
function initUI() {
  document.querySelectorAll('#nav button').forEach((b) => {
    b.onclick = () => {
      document.querySelectorAll('#nav button').forEach((x) => x.classList.toggle('active', x === b));
      document.querySelectorAll('.tab').forEach((t) => t.classList.toggle('hidden', t.id !== `tab-${b.dataset.tab}`));
      $('sheet').classList.remove('collapsed'); setTimeout(() => map.invalidateSize(), 250);
      $('sheet').classList.toggle('chatmode', b.dataset.tab === 'chat');
      if (b.dataset.tab === 'chat') { renderChat(true); markSeen(); }
    };
  });
  $('btnChat').onclick = sendChat;
  buildEmojis();
  $('btnEmoji').onclick = () => togglePanel();
  buildStickers();
  $('replyX').onclick = () => setReply(null);
  $('btnCall').onclick = startCall;
  $('callAccept').onclick = acceptCall;
  $('callHangup').onclick = hangupCall;
  $('menuDel').onclick = deleteMine;
  $('lbClose').onclick = closeLightbox;
  $('lightbox').onclick = (e) => { if (e.target.id === 'lightbox') closeLightbox(); };
  $('menuClose').onclick = closeMsgMenu;
  $('msgMenu').onclick = (e) => { if (e.target.id === 'msgMenu') closeMsgMenu(); };
  $('btnRec').onclick = toggleRec;
  $('recCancel').onclick = cancelRec;
  $('btnFile').onclick = async () => { const ok = fileClaveCache || await getFileClave(); if (ok) $('filePick').click(); };
  $('filePick').onchange = () => { const f = $('filePick').files[0]; $('filePick').value = ''; if (f) uploadFile(f); };
  $('inChat').onkeydown = (e) => { if (e.key === 'Enter') { e.preventDefault(); sendChat(); } };
  $('handle').onclick = () => { $('sheet').classList.toggle('collapsed'); setTimeout(() => map.invalidateSize(), 250); };
  $('btnLayer').onclick = () => {
    const order = ['calle', 'satelite'].concat(gKey ? ['google'] : [], mKey ? ['mapbox'] : [], ['oscuro']);
    setStyle(order[(order.indexOf(curStyle) + 1) % order.length]);
  };
  document.querySelectorAll('#styleChips button').forEach((b) => { b.onclick = () => setStyle(b.dataset.style); });
  document.querySelectorAll('#frameChips button').forEach((b) => { b.onclick = () => setFrame(b.dataset.frame); });
  const gStatus = () => { $('gStatus').textContent = gKey ? '✅ Clave guardada. Tu pareja la recibe sola.' : 'Sin clave: se usa el satélite gratuito (más antiguo).'; };
  gStatus(); window.refreshGStatus = gStatus;
  $('btnGKey').onclick = async () => {
    const k = $('inGKey').value.trim();
    if (!k) return toast('Pega primero tu clave');
    if (!/^AIza[\w-]{35}$/.test(k)) return toast('Esa clave no parece válida (empieza con AIza y mide 39).');
    gKey = k; localStorage.setItem('gkey', k); $('inGKey').value = '';
    setDoc(doc(db, 'rooms', cfg.room, 'meta', 'couple'), { gkey: k }, { merge: true }).catch(() => {});
    gStatus(); setStyle('google');
  };
  const mStatus = () => { $('mStatus').textContent = mKey ? '✅ Clave guardada. Mapa Mapbox y ajuste de rutas activos; tu pareja la recibe sola.' : 'Sin clave: las rutas se ajustan con el servicio gratuito.'; };
  mStatus(); window.refreshMStatus = mStatus;
  $('btnMKey').onclick = async () => {
    const k = $('inMKey').value.trim();
    if (!k) return toast('Pega primero tu clave');
    if (!/^pk\.[\w-]+\.[\w-]+$/.test(k)) return toast('Esa clave no parece válida (empieza con pk.)');
    mKey = k; localStorage.setItem('mkey', k); $('inMKey').value = '';
    if (IS_NATIVE) Native.setMapboxKey({ key: k }).catch(() => {});
    setDoc(doc(db, 'rooms', cfg.room, 'meta', 'couple'), { mkey: k }, { merge: true }).catch(() => {});
    mStatus(); setStyle('mapbox');
  };
  $('btnCenter').onclick = fitAll;

  const sendHeart = () => { saveMe({ heartTs: Date.now() }); floatHearts(); toast('Corazón enviado 💗'); };
  $('btnHeart').onclick = sendHeart; $('btnHeart2').onclick = sendHeart;

  MOODS.forEach(([e, t]) => {
    const b = document.createElement('button');
    b.textContent = `${e} ${t}`; b.dataset.mood = `${e} ${t}`;
    b.onclick = () => {
      saveMe({ mood: b.dataset.mood, moodTs: Date.now() });
      people[cfg.uid] = { ...people[cfg.uid], mood: b.dataset.mood }; renderUs(); toast('Estado actualizado');
    };
    $('moods').appendChild(b);
  });
  $('btnMsg').onclick = () => {
    const msg = $('inMsg').value.trim(); if (!msg) return;
    saveMe({ msg, msgTs: Date.now() }); $('inMsg').value = ''; toast('Mensaje enviado 💌');
  };
  $('btnGo').onclick = () => {
    const p = otherPeople()[0];
    if (!p) return toast('Tu pareja aún no comparte ubicación');
    navigate(p.lat, p.lng);
  };

  $('btnPlaceHere').onclick = () => {
    const me = people[cfg.uid];
    if (!me || me.lat == null) return toast('Aún no tengo tu ubicación');
    addPlace(me.lat, me.lng);
  };
  $('btnPlaceMap').onclick = () => {
    addingPlace = true; $('sheet').classList.add('collapsed'); setTimeout(() => map.invalidateSize(), 250);
    toast('Toca en el mapa el lugar que quieres guardar');
  };

  $('histDate').valueAsDate = new Date();
  $('btnHist').onclick = showHistory;
  $('btnHistClear').onclick = () => { histRun++; histData = null; stopPlay(); if (histLayer) map.removeLayer(histLayer); $('histInfo').textContent = ''; };
  initPlayer();
  $('btnGuess').onclick = () => { histOpts.guess = !histOpts.guess; $('btnGuess').classList.toggle('active', histOpts.guess); paintHist(); };
  $('btnDots').onclick = () => { histOpts.dots = !histOpts.dots; $('btnDots').classList.toggle('active', histOpts.dots); paintHist(); };

  $('btnShare').onclick = toggleShare; updateShareBtn();
  const lineBtn = () => { $('btnLine').textContent = `📏 Línea de distancia: ${showLine ? 'sí' : 'no'}`; };
  $('btnLine').onclick = () => {
    showLine = !showLine; localStorage.setItem('showLine', showLine ? 'on' : 'off'); lineBtn(); updatePair();
  };
  lineBtn();
  $('btnAnniv').onclick = () => {
    const v = $('inAnniv2').value; if (!v) return;
    setDoc(doc(db, 'rooms', cfg.room, 'meta', 'couple'), { anniv: v }, { merge: true }).catch(() => {});
    setDoc(doc(db, 'profiles', cfg.uid), { anniv: v }, { merge: true }).catch(() => {});
    anniv = v; renderUs(); toast('Aniversario guardado 💕');
  };
  $('btnCopy').onclick = () => {
    navigator.clipboard?.writeText(cfg.room).then(() => toast('Código copiado'), () => prompt('Tu código de sala:', cfg.room));
  };
  $('btnRoom').onclick = async () => {
    if (!confirm('¿Cambiar de sala? Tu cuenta se queda; solo eliges otro código.')) return;
    await stopTracking();
    await setDoc(doc(db, 'profiles', cfg.uid), { room: '' }, { merge: true }).catch(() => {});
    localStorage.removeItem('cfg_' + cfg.uid); localStorage.removeItem('queue'); location.reload();
  };
  $('btnLogout').onclick = async () => {
    if (!confirm('¿Cerrar sesión en este celular?')) return;
    await stopTracking();
    localStorage.removeItem('queue');
    await signOut(auth); location.reload();
  };
}

// ---------- Rastreo ----------
async function startTracking() {
  if (watcherId || webWatchId != null || trackStarting || nativeTrack) return;
  trackStarting = true;
  try {
  if (Capacitor.isNativePlatform()) {
    if (await startNativeTrack()) return; // servicio propio: sigue con la app cerrada y al reiniciar
    watcherId = await BG.addWatcher(
      {
        backgroundTitle: 'Ubicación compartida 💕',
        backgroundMessage: 'Compartiendo tu ubicación con tu pareja',
        requestPermissions: true, stale: false, distanceFilter: 4
      },
      (loc, err) => {
        if (err) {
          if (err.code === 'NOT_AUTHORIZED' &&
              confirm('Falta el permiso de ubicación "Permitir todo el tiempo". ¿Abrir ajustes?')) BG.openSettings();
          return;
        }
        onPoint({ latitude: loc.latitude, longitude: loc.longitude, accuracy: loc.accuracy, time: loc.time });
      }
    );
  } else {
    webWatchId = navigator.geolocation.watchPosition(
      (p) => onPoint({ latitude: p.coords.latitude, longitude: p.coords.longitude, accuracy: p.coords.accuracy, time: p.timestamp }),
      console.error, { enableHighAccuracy: true }
    );
  }
  } finally { trackStarting = false; }
}
// Servicio nativo (TrackService): comparte la ubicacion aunque la app este cerrada o se reinicie el celular
let nativeTrack = false, uiPollTimer = 0, lastUiTs = 0;
async function startNativeTrack() {
  try {
    const u = auth.currentUser;
    if (!u || !u.refreshToken) return false;
    await Native.startTracking({
      apiKey: auth.app.options.apiKey, projectId: auth.app.options.projectId,
      room: cfg.room, uid: cfg.uid, name: cfg.name || '', avatar: cfg.avatar || '', refreshToken: u.refreshToken
    });
    nativeTrack = true;
    if (mKey) Native.setMapboxKey({ key: mKey }).catch(() => {});
    clearInterval(uiPollTimer);
    uiPollTimer = setInterval(pollNative, 2000); pollNative();
    return true;
  } catch { return false; } // si el celular aun no tiene el modulo nuevo, usa el metodo de antes
}
async function pollNative() {
  if (document.visibilityState === 'hidden') return;
  try {
    const l = await Native.lastLocation();
    if (l && l.time && l.time !== lastUiTs) { lastUiTs = l.time; uiPoint(l); }
  } catch { /* nada */ }
}
function uiPoint(p) { // solo dibuja: el servicio nativo es quien guarda y sube los puntos
  const pt = { lat: p.latitude, lng: p.longitude, acc: Math.round(p.accuracy || 0), ts: p.time || Date.now() };
  drawPerson(cfg.uid, { name: cfg.name, avatar: cfg.avatar, ...pt }, true);
  if (!centered) { centered = true; map.setView([pt.lat, pt.lng], 16); }
  renderUs();
}
async function stopTracking() {
  if (nativeTrack) { try { await Native.stopTracking(); } catch {} nativeTrack = false; clearInterval(uiPollTimer); }
  if (watcherId) { try { await BG.removeWatcher({ id: watcherId }); } catch {} watcherId = null; }
  if (webWatchId != null) { navigator.geolocation.clearWatch(webWatchId); webWatchId = null; }
}
function toggleShare() {
  sharing = !sharing;
  localStorage.setItem('sharing', sharing ? 'on' : 'off');
  sharing ? maybeStartTracking() : stopTracking();
  updateShareBtn();
}
function updateShareBtn() {
  $('btnShare').textContent = sharing ? '⏸ Pausar compartir ubicación' : '▶ Reanudar compartir ubicación';
  $('btnShare').className = sharing ? '' : 'ghost';
}

// ---------- Permisos y actualizaciones (solo en el celular) ----------
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const nStatus = () => Native.status().catch(() => ({}));
async function maybeStartTracking() {
  if (!sharing) return;
  if (IS_NATIVE) { const st = await nStatus(); if (st.sdk !== undefined && !st.location) return; }
  startTracking();
}
async function waitFor(key, ms) {
  const t0 = Date.now();
  while (Date.now() - t0 < ms) { if ((await nStatus())[key]) return true; await sleep(800); }
  return false;
}
const SLOW = ['battery', 'install'];
const PERMS = [
  { key: 'location', icon: '📍', title: 'Ubicación precisa', why: 'Para verte en el mapa', ask: () => Native.requestLocation() },
  { key: 'background', icon: '🔄', title: 'Ubicación todo el tiempo', why: 'Para compartir con la app cerrada. En la pantalla que se abre elige "Permitir todo el tiempo".', ask: () => Native.requestBackground() },
  { key: 'notifications', icon: '🔔', title: 'Notificaciones', why: 'Para el aviso fijo y los corazones', ask: () => Native.requestNotifications() },
  { key: 'battery', icon: '🔋', title: 'Sin límites de batería', why: 'Para que el celular no cierre la app', ask: () => Native.requestBattery() },
  { key: 'install', icon: '⬇️', title: 'Instalar actualizaciones', why: 'Para actualizar desde la misma app', ask: () => Native.requestInstall() }
];
let permTimer;
async function renderPerms() {
  const st = await nStatus(), box = $('permList');
  box.innerHTML = '';
  PERMS.forEach((p) => {
    const ok = !!st[p.key];
    const row = document.createElement('div'); row.className = 'perm' + (ok ? ' ok' : '');
    row.innerHTML = `<span class="pi">${p.icon}</span><div class="t"><b>${esc(p.title)}</b><small>${esc(p.why)}</small></div>`;
    const b = document.createElement('button');
    b.textContent = ok ? '✅' : 'Conceder'; b.disabled = ok;
    if (!ok) b.onclick = () => askOne(p);
    row.appendChild(b); box.appendChild(row);
  });
  if (st.location) maybeStartTracking();
  return st;
}
async function askOne(p) {
  try { await withTimeout(p.ask(), 60000); } catch { toast('No se pudo abrir esa opción'); }
  const ok = await waitFor(p.key, SLOW.includes(p.key) ? 45000 : 2000);
  if (!ok) toast('No se concedió. Usa "Abrir ajustes de la app" para activarlo a mano.');
  renderPerms();
}
async function grantAll() {
  $('btnPermAll').disabled = true;
  for (const p of PERMS) {
    if ((await nStatus())[p.key]) continue;
    try { await withTimeout(p.ask(), 60000); } catch { /* sigue */ }
    const ok = await waitFor(p.key, SLOW.includes(p.key) ? 60000 : 3000);
    await renderPerms();
    if (!ok) { toast(`Falta: ${p.title}`); break; }
  }
  $('btnPermAll').disabled = false;
  const st = await renderPerms();
  if (PERMS.every((p) => st[p.key])) { toast('¡Todo listo! 💖'); closePerms(); }
}
function openPerms() {
  $('permModal').classList.remove('hidden'); renderPerms();
  clearInterval(permTimer); permTimer = setInterval(renderPerms, 2500);
}
function closePerms() { $('permModal').classList.add('hidden'); clearInterval(permTimer); }

let pendingUpdate = null;
async function checkUpdate(manual) {
  if (!IS_NATIVE) return;
  try {
    const info = await Native.appInfo();
    $('verInfo').textContent = `Versión instalada: ${info.versionName} (${info.versionCode})`;
    
    const res = await withTimeout(Native.httpGet({ url: `https://api.github.com/repos/username20262026/pareja-gps/releases/latest?t=${Date.now()}` }), 15000);
    const datos = JSON.parse(res.text);
    
    // El tag viene como "v1.10". Rompemos por el punto y sacamos el "10"
    const versionNubeCode = parseInt(datos.tag_name.split('.')[1]);
    
    if (versionNubeCode > parseInt(info.versionCode)) {
      pendingUpdate = { 
        url: datos.assets[0].browser_download_url, 
        versionName: datos.tag_name, 
        notes: datos.body || '' 
      };
      $('updInfo').innerHTML = `Versión nueva <b>${esc(pendingUpdate.versionName)}</b> (tienes ${esc(info.versionName)}).${pendingUpdate.notes ? `<br><i>${esc(pendingUpdate.notes)}</i>` : ''}`;
      $('updBar').style.width = '0%'; $('updState').textContent = '';
      $('updModal').classList.remove('hidden');
    } else if (manual) toast('Ya tienes la última versión 💖');
  } catch (e) { 
    if (manual) toast('No pude conectar con GitHub para buscar actualizaciones.'); 
  }
}

async function doUpdate() {
  if (!pendingUpdate) return;
  $('btnUpdNow').disabled = true;
  try {
    if (!(await nStatus()).install) {
      $('updState').textContent = 'Activa "Permitir de esta fuente" y vuelve a la app…';
      await Native.requestInstall();
      if (!(await waitFor('install', 90000))) throw new Error('Falta permitir instalar desde esta app');
    }
    $('updState').textContent = 'Descargando…';
    await Native.downloadAndInstall({ url: pendingUpdate.url });
    $('updState').textContent = 'Abriendo el instalador… pulsa "Instalar".';
  } catch (e) { $('updState').textContent = '⚠️ ' + (e.message || e); }
  $('btnUpdNow').disabled = false;
}
async function initNative() {
  const info = IS_NATIVE ? await Native.appInfo().catch(() => null) : null;
  if (!info) { $('nativeBox').classList.add('hidden'); return; } // en PC o sin el módulo nativo
  $('verInfo').textContent = `Versión instalada: ${info.versionName} (${info.versionCode})`;
  Native.addListener('updateProgress', (d) => {
    $('updBar').style.width = d.percent + '%'; $('updState').textContent = `Descargando… ${d.percent}%`;
  });
  Native.addListener('updateTap', () => checkUpdate(false)); // tocó la notificación de versión nueva
  $('btnPerms').onclick = openPerms; $('btnPermAll').onclick = grantAll; $('btnPermClose').onclick = closePerms;
  $('btnPermSettings').onclick = () => Native.openAppSettings();
  const wSave = () => {
    localStorage.setItem('wstyle', JSON.stringify(wStyle));
    document.querySelectorAll('#wThemeChips button').forEach((b) => b.classList.toggle('active', +b.dataset.t === wStyle.theme));
    document.querySelectorAll('#wFrameChips button').forEach((b) => b.classList.toggle('active', +b.dataset.f === wStyle.frame));
    $('btnWMap').classList.toggle('active', wStyle.map);
    if (IS_NATIVE) Native.setWidgetStyle(wStyle).catch(() => {});
  };
  document.querySelectorAll('#wThemeChips button').forEach((b) => { b.onclick = () => { wStyle.theme = +b.dataset.t; wSave(); }; });
  document.querySelectorAll('#wFrameChips button').forEach((b) => { b.onclick = () => { wStyle.frame = +b.dataset.f; wSave(); }; });
  $('btnWMap').onclick = () => { wStyle.map = !wStyle.map; wSave(); };
  $('inWLabel').value = wStyle.label;
  $('btnWLabel').onclick = () => { wStyle.label = $('inWLabel').value.trim().slice(0, 24); wSave(); toast('Mensaje guardado'); };
  wSave();
  $('btnWidget').onclick = async () => {
    let ok = false;
    try { ok = (await Native.pinWidget()).supported; } catch {}
    if (!ok) toast('Mantén presionada la pantalla de inicio → Widgets → Pareja GPS');
  };
  $('btnAuto').onclick = async () => { try { await Native.openAutostart(); } catch {} toast('Activa "Inicio automático" o "Permitir en segundo plano" para Pareja GPS'); };
  $('btnUpd').onclick = () => checkUpdate(true);
  $('btnUpdNow').onclick = doUpdate; $('btnUpdLater').onclick = () => $('updModal').classList.add('hidden');
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible' && !$('permModal').classList.contains('hidden')) renderPerms();
  });
  setTimeout(async () => {
    const st = await nStatus();
    if (!(st.location && st.background && st.notifications && st.battery)) openPerms();
    setTimeout(() => checkUpdate(false), 2500);
  }, 1000);
}

// ---------- Guardado + cola offline ----------
function keepPoint(pt) {
  if (pt.acc > 100) return false;            // lectura muy imprecisa
  if (!lastKept) return true;
  const dt = pt.ts - lastKept.ts;
  return (dist(lastKept, pt) >= 6 && dt >= 1500) || dt >= 120000; // movimiento real o "sigo aquí"
}
function onPoint(p) {
  const pt = { lat: p.latitude, lng: p.longitude, acc: Math.round(p.accuracy || 0), ts: p.time || Date.now() };
  if (keepPoint(pt)) { lastKept = pt; queue.push(pt); saveQ(); }
  drawPerson(cfg.uid, { name: cfg.name, avatar: cfg.avatar, ...pt }, true);
  if (!centered) { centered = true; map.setView([pt.lat, pt.lng], 16); }
  renderUs(); flush();
}
async function flush() {
  if (flushing || !queue.length) return updateStatus();
  flushing = true;
  try {
    if (!(await ensureAuth())) throw new Error('sin sesión');
    while (queue.length) {
      const chunk = queue.slice(0, 400);
      const wb = writeBatch(db);
      const base = userPath();
      chunk.forEach((pt) => wb.set(doc(db, ...base, 'points', String(pt.ts)), pt));
      wb.set(doc(db, ...base), { uid: cfg.uid, name: cfg.name, avatar: cfg.avatar, ...chunk[chunk.length - 1] }, { merge: true });
      await withTimeout(wb.commit(), 15000);
      queue = queue.slice(chunk.length); saveQ();
    }
  } catch (e) { /* sin internet: se reintenta luego */ }
  flushing = false; updateStatus();
}
function updateStatus() {
  $('status').textContent = queue.length ? `📴 ${queue.length} pendientes` : (navigator.onLine ? '🟢 al día' : '📴 sin conexión');
}

// ---------- Pareja en vivo ----------
function listenPeople() {
  onSnapshot(collection(db, 'rooms', cfg.room, 'users'), (snap) => {
    snap.forEach((d) => {
      const data = d.data();
      if (data.uid !== d.id) return; // ignora registros viejos de dispositivos sueltos
      drawPerson(d.id, data, d.id === cfg.uid);
      if (d.id !== cfg.uid) {
        attachChatPartner(d.id);
        const react = (key, ts, text) => {
          const k = key + d.id;
          if (ts > Number(localStorage.getItem(k) || 0)) {
            localStorage.setItem(k, ts);
            if (Date.now() - ts < 6 * 3600e3) { toast(text); navigator.vibrate?.(60); }
          }
        };
        if (data.moodTs && data.mood) react('seenMood_', data.moodTs, `${data.avatar || '💗'} ${data.name}: ${data.mood}`);
        if (data.msgTs && data.msg) react('seenMsg_', data.msgTs, `💌 ${data.name}: “${data.msg}”`);
      }
      if (d.id !== cfg.uid && data.heartTs) {
        const k = 'seenHeart_' + d.id;
        if (data.heartTs > Number(localStorage.getItem(k) || 0)) {
          localStorage.setItem(k, data.heartTs);
          if (Date.now() - data.heartTs < 6 * 3600e3) { toast(`${data.avatar || '💗'} ${data.name} te envió un corazón`); floatHearts(18); }
        }
      }
    });
    renderUs(); refreshHistUsers();
    if (firstFit && Object.keys(markers).length > 1) { fitAll(); firstFit = false; }
  }, (e) => console.error('users', e));
}
// ---------- Chat ----------
// Cada persona guarda sus mensajes en su propio lugar (rooms/.../users/<uid>/chat) y marca como "visto"
// los de la otra en users/<uid>/reads. Así funciona con las reglas de Firebase que ya tienes.
const chat = { mine: [], theirs: {}, seen: {}, myReads: new Set(), readsReady: false, partners: new Set(), first: {} };
const chatCol = (uid, sub) => collection(db, 'rooms', cfg.room, 'users', uid, sub);
const chatOpen = () => !$('tab-chat').classList.contains('hidden') && !$('sheet').classList.contains('collapsed') && document.visibilityState === 'visible';

function listenChat() {
  onSnapshot(query(chatCol(cfg.uid, 'chat'), orderBy('ts', 'desc'), limit(150)), (snap) => {
    chat.mine = snap.docs.map((d) => ({ id: d.id, pending: d.metadata.hasPendingWrites, ...d.data() }));
    renderChat();
  }, (e) => console.error('chat', e));
  onSnapshot(query(chatCol(cfg.uid, 'reads'), orderBy('at', 'desc'), limit(300)), (snap) => {
    chat.myReads = new Set(snap.docs.map((d) => d.id)); chat.readsReady = true; renderChat();
  }, (e) => console.error('reads', e));
  document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') markSeen(); });
}
function attachChatPartner(pid) {
  if (chat.partners.has(pid)) return;
  chat.partners.add(pid);
  onSnapshot(query(chatCol(pid, 'chat'), orderBy('ts', 'desc'), limit(150)), (snap) => {
    chat.theirs[pid] = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
    const fresh = chat.first[pid] ? snap.docChanges().filter((c) => c.type === 'added').map((c) => c.doc.data()) : [];
    chat.first[pid] = true;
    renderChat();
    if (fresh.length && !chatOpen()) {
      const m = fresh[fresh.length - 1];
      toast(`💬 ${m.name || 'Tu amor'}: ${String(m.text).slice(0, 60)}`); navigator.vibrate?.(60);
    }
  }, (e) => console.error('chat pareja', e));
  onSnapshot(query(chatCol(pid, 'reads'), orderBy('at', 'desc'), limit(300)), (snap) => {
    snap.docs.forEach((d) => { chat.seen[d.id] = d.data().at; });
    renderChat();
  }, (e) => console.error('reads pareja', e));
}
function sendChat() {
  const text = $('inChat').value.trim(); if (!text) return;
  sendMsg({ text: text.slice(0, 500) });
  $('inChat').value = '';
}

// ---- Chat: respuestas deslizando y emojis ----
const EMOJIS = '😀 😍 🥰 😘 😂 🤣 😊 😉 😎 🤗 🤔 😴 😭 😡 🥺 😅 🙈 💕 💖 💗 💘 💞 💌 💋 ❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🔥 ✨ 🌹 🌸 🌈 ⭐ 🎉 🎶 🍕 🍫 ☕ 🌙 ☀️ 👍 👏 🙏 💪 🫶 😇 🥳 😋 🤤 👀 💯'.split(' ');
let replyTo = null;
let recorder = null, recChunks = [], recTimer = null, recStart = 0, recCancel = false;

function sendMsg(extra) {
  const ts = Date.now();
  const data = { uid: cfg.uid, name: cfg.name || '', avatar: cfg.avatar || '', text: '', ts, ...extra };
  data.text = String(data.text || '').slice(0, 500);
  if (replyTo) data.replyTo = replyTo;
  setDoc(doc(db, 'rooms', cfg.room, 'users', cfg.uid, 'chat', `${ts}${Math.random().toString(36).slice(2, 6)}`), data)
    .catch(() => toast('No se pudo enviar el mensaje'));
  if (replyTo) setReply(null);
}
function setReply(m) {
  replyTo = m ? { uid: m.uid, name: m.name || 'Tu amor', text: String(m.text || '').slice(0, 120) } : null;
  $('replyBar').classList.toggle('hidden', !replyTo);
  if (replyTo) { $('replyTxt').textContent = `↩ ${replyTo.uid === cfg.uid ? 'Tú' : replyTo.name}: ${replyTo.text}`; $('inChat').focus(); }
}
function addSwipe(el, m) {
  let x0 = 0, y0 = 0, dx = 0, drag = false;
  el.addEventListener('touchstart', (e) => { x0 = e.touches[0].clientX; y0 = e.touches[0].clientY; dx = 0; drag = true; el.style.transition = 'none'; }, { passive: true });
  el.addEventListener('touchmove', (e) => {
    if (!drag) return;
    const t = e.touches[0], ddx = t.clientX - x0, ddy = t.clientY - y0;
    if (Math.abs(ddy) > Math.abs(ddx)) return;
    dx = Math.max(0, Math.min(ddx, 80));
    el.style.transform = `translateX(${dx}px)`;
  }, { passive: true });
  const end = () => {
    if (!drag) return;
    drag = false; el.style.transition = 'transform .2s'; el.style.transform = '';
    if (dx >= 55) { setReply(m); navigator.vibrate?.(30); }
  };
  el.addEventListener('touchend', end);
  el.addEventListener('touchcancel', end);
}
function buildEmojis() {
  $('emojiGrid').innerHTML = EMOJIS.map((e) => `<button type="button" data-e="${e}">${e}</button>`).join('');
  $('emojiGrid').onclick = (ev) => {
    const e = ev.target.dataset.e; if (!e) return;
    const inp = $('inChat'), p = inp.selectionStart ?? inp.value.length, q = inp.selectionEnd ?? p;
    inp.value = inp.value.slice(0, p) + e + inp.value.slice(q);
    inp.focus();
  };
}
let recNativeOn = false, recNativeTimer = null;
async function toggleRecNative() {
  if (recNativeOn) {
    clearInterval(recNativeTimer); recNativeOn = false;
    $('recBar').classList.add('hidden'); $('btnRec').classList.remove('rec');
    try {
      const r = await Native.stopRec();
      if (r && r.data) sendMsg({ type: 'audio', data: r.data, dur: r.dur, text: '🎤 Audio' });
    } catch (e) { toast(e.message || 'No se pudo enviar el audio'); }
    return;
  }
  try { await Native.startRec(); }
  catch (e) { return toast(e.message || 'Permite el micrófono en los ajustes de la app'); }
  recNativeOn = true;
  recStart = Date.now();
  $('btnRec').classList.add('rec'); $('recBar').classList.remove('hidden');
  const tick = () => {
    const s = Math.floor((Date.now() - recStart) / 1000);
    $('recTime').textContent = `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
    if (s >= 60 && recNativeOn) toggleRecNative();
  };
  tick(); recNativeTimer = setInterval(tick, 500);
}
async function toggleRec() {
  if (IS_NATIVE) return toggleRecNative();
  if (recorder) { recorder.stop(); return; }
  if (IS_NATIVE) { try { await Native.requestMic(); } catch {} }
  if (!navigator.mediaDevices?.getUserMedia || !window.MediaRecorder) return toast('Tu navegador no puede grabar audio');
  let stream;
  try { stream = await navigator.mediaDevices.getUserMedia({ audio: true }); }
  catch { return toast('Permite el micrófono para grabar 🎤'); }
  const mime = ['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4'].find((x) => MediaRecorder.isTypeSupported(x)) || '';
  const rec = new MediaRecorder(stream, { ...(mime ? { mimeType: mime } : {}), audioBitsPerSecond: 24000 });
  recorder = rec; recChunks = []; recCancel = false;
  rec.ondataavailable = (e) => { if (e.data.size) recChunks.push(e.data); };
  rec.onstop = async () => {
    stream.getTracks().forEach((t) => t.stop());
    clearInterval(recTimer); $('recBar').classList.add('hidden'); $('btnRec').classList.remove('rec');
    const type = rec.mimeType || 'audio/webm';
    const dur = Math.round((Date.now() - recStart) / 1000);
    recorder = null;
    if (recCancel) return;
    const blob = new Blob(recChunks, { type });
    if (blob.size < 1000) return toast('Audio muy corto');
    if (blob.size > 900 * 1024) return toast('Audio muy largo, graba algo más corto');
    const data = await new Promise((res, rej) => { const r = new FileReader(); r.onload = () => res(r.result); r.onerror = rej; r.readAsDataURL(blob); });
    sendMsg({ type: 'audio', data, dur, text: '🎤 Audio' });
  };
  recStart = Date.now();
  rec.start();
  $('btnRec').classList.add('rec'); $('recBar').classList.remove('hidden');
  const tick = () => {
    const s = Math.floor((Date.now() - recStart) / 1000);
    $('recTime').textContent = `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
    if (s >= 60 && recorder) recorder.stop();
  };
  tick(); recTimer = setInterval(tick, 500);
}
function cancelRec() {
  if (IS_NATIVE && recNativeOn) {
    clearInterval(recNativeTimer); recNativeOn = false;
    $('recBar').classList.add('hidden'); $('btnRec').classList.remove('rec');
    Native.cancelRec().catch(() => {});
    return;
  }
  if (!recorder) return; recCancel = true; recorder.stop();
}
// ---- Archivos y fotos (servidor propio de la pareja) ----
const MAX_FILE = 100 * 1024 * 1024;
const mediaCache = new Map(), mediaPending = new Map(), mediaFailed = new Map();
const FILE_BASE = 'https://pareja-mia.duckdns.org';
let fileClaveCache = null;
// La clave vive en la sala de Firebase (rooms/<sala>/meta/server): la escribes una vez y la leen los dos celulares.
async function getFileClave(forceAsk) {
  if (!forceAsk && fileClaveCache) return fileClaveCache;
  if (!forceAsk) {
    try {
      const s = await getDoc(doc(db, 'rooms', cfg.room, 'meta', 'server'));
      if (s.exists() && s.data().clave) { fileClaveCache = s.data().clave; return fileClaveCache; }
    } catch {}
  }
  const c = (prompt('Clave del servidor de archivos (se guardará para los dos celulares):') || '').trim();
  if (!c) return null;
  await setDoc(doc(db, 'rooms', cfg.room, 'meta', 'server'), { clave: c });
  fileClaveCache = c;
  return c;
}
async function srvFetch(path, opts = {}) {
  let clave = await getFileClave();
  if (!clave) throw new Error('Sin clave');
  const send = () => fetch(FILE_BASE + path, { ...opts, headers: { ...(opts.headers || {}), 'x-clave': clave } });
  let r = await send();
  if (r.status === 401) {
    // clave incorrecta o cambiada: se pide de nuevo una vez
    fileClaveCache = null;
    clave = await getFileClave(true);
    if (!clave) throw new Error('Clave incorrecta');
    r = await send();
  }
  if (r.status === 401) throw new Error('Clave incorrecta');
  if (r.status === 429) throw new Error('Demasiados intentos, espera 15 min');
  if (!r.ok) throw new Error('Error ' + r.status);
  return r;
}
async function uploadFile(f) {
  if (!(await getFileClave())) return;
  if (f.size > MAX_FILE) return toast('Máximo 100 MB');
  toast('Subiendo…');
  try {
    const type = f.type || 'application/octet-stream';
    const r = await srvFetch('/upload', { method: 'POST', headers: { 'x-filename': encodeURIComponent(f.name), 'content-type': type }, body: f });
    const j = await r.json();
    const isImg = type.startsWith('image/');
    sendMsg({ type: isImg ? 'image' : 'file', fid: j.id, fname: f.name, text: isImg ? '📷 Foto' : `📎 ${f.name}` });
  } catch (e) {
    toast('No se pudo subir: ' + e.message + (e.message === 'Failed to fetch' ? ' (¿laptop encendida?)' : ''));
  }
}
function loadBlob(fid) {
  if (mediaCache.has(fid)) return Promise.resolve(mediaCache.get(fid));
  if (mediaPending.has(fid)) return mediaPending.get(fid);
  const failedAt = mediaFailed.get(fid);
  if (failedAt && Date.now() - failedAt < 60000) return Promise.reject(new Error('reciente'));
  const p = srvFetch(`/f/${fid}`).then((r) => r.blob()).then((bl) => {
    const u = URL.createObjectURL(bl); mediaCache.set(fid, u); mediaPending.delete(fid); return u;
  }).catch((e) => { mediaPending.delete(fid); mediaFailed.set(fid, Date.now()); throw e; });
  mediaPending.set(fid, p);
  return p;
}
// Visor de fotos dentro de la app (no abre otra aplicación)
function openLightbox(u) {
  $('lbImg').src = u;
  $('lightbox').classList.remove('hidden');
}
function closeLightbox() {
  $('lightbox').classList.add('hidden');
  $('lbImg').src = '';
}
// Llamado por el botón atrás nativo: cierra lo abierto y devuelve true; si no hay nada, devuelve false
window.__handleBack = () => {
  if (!$('lightbox').classList.contains('hidden')) { closeLightbox(); return true; }
  if (!$('msgMenu').classList.contains('hidden')) { closeMsgMenu(); return true; }
  return false;
};
function mediaBubble(b, m) {
  if (m.type === 'image') {
    const im = document.createElement('img'); im.className = 'mimg'; im.alt = 'Foto';
    loadBlob(m.fid).then((u) => { im.src = u; im.onclick = () => openLightbox(u); })
      .catch(() => { const s = document.createElement('small'); s.textContent = '📷 Foto no disponible (¿laptop encendida?)'; im.replaceWith(s); });
    b.appendChild(im);
  } else {
    const a = document.createElement('a'); a.className = 'fl'; a.href = '#';
    a.textContent = '📎 ' + (m.fname || 'archivo');
    a.onclick = (e) => {
      e.preventDefault();
      loadBlob(m.fid).then((u) => { const d = document.createElement('a'); d.href = u; d.download = m.fname || 'archivo'; d.click(); })
        .catch(() => toast('No se pudo abrir (¿laptop encendida?)'));
    };
    b.appendChild(a);
  }
}
// Mantener presionado (0.5 s) para abrir el menú; si el dedo se mueve, no se abre
function addLongPress(el, fn) {
  let t = null, x0 = 0, y0 = 0;
  const cancel = () => { clearTimeout(t); t = null; };
  el.addEventListener('touchstart', (e) => {
    x0 = e.touches[0].clientX; y0 = e.touches[0].clientY;
    cancel();
    t = setTimeout(() => { t = null; navigator.vibrate?.(40); fn(); }, 500);
  }, { passive: true });
  el.addEventListener('touchmove', (e) => {
    const d = e.touches[0];
    if (Math.abs(d.clientX - x0) > 10 || Math.abs(d.clientY - y0) > 10) cancel();
  }, { passive: true });
  el.addEventListener('touchend', cancel);
  el.addEventListener('touchcancel', cancel);
  el.addEventListener('contextmenu', (e) => e.preventDefault());
}
let menuMsg = null;
function openMsgMenu(m) {
  menuMsg = m;
  $('menuTxt').textContent = String(m.text || '').slice(0, 80);
  $('menuDel').textContent = m.pending ? '✖ Cancelar envío' : '🗑 Eliminar para todos';
  $('msgMenu').classList.remove('hidden');
}
function closeMsgMenu() { $('msgMenu').classList.add('hidden'); menuMsg = null; }
async function deleteMine() {
  const m = menuMsg; closeMsgMenu();
  if (!m) return;
  try {
    if (m.fid) { try { await srvFetch(`/f/${m.fid}`, { method: 'DELETE' }); } catch {} }
    await deleteDoc(doc(db, 'rooms', cfg.room, 'users', cfg.uid, 'chat', m.id));
    toast(m.pending ? 'Envío cancelado' : 'Mensaje eliminado para todos');
  } catch (e) {
    toast('No se pudo eliminar el mensaje');
  }
}
// ---- Llamadas de voz: señalización por Firestore + audio WebRTC nativo ----
let callActive = false, callRole = null, callId = null, callIncomingId = null;
let callWatcherOn = false, callAnswerApplied = false, callUnsubs = [];
const callRef = () => doc(db, 'rooms', cfg.room, 'call', 'current');
const callCandCol = () => fsCollection(db, 'rooms', cfg.room, 'call', 'current', 'cand');
function showCall(title, status, incoming) {
  $('callTitle').textContent = title;
  $('callStatus').textContent = status;
  $('callAccept').classList.toggle('hidden', !incoming);
  $('callOverlay').classList.remove('hidden');
}
function endCallLocal() {
  callUnsubs.forEach((f) => f()); callUnsubs = [];
  callActive = false; callRole = null; callId = null; callIncomingId = null; callAnswerApplied = false;
  Native.callEnd().catch(() => {});
  $('callOverlay').classList.add('hidden');
}
function listenCandidates() {
  callUnsubs.push(fsOnSnapshot(callCandCol(), (snap) => {
    snap.docChanges().forEach((ch) => {
      if (ch.type !== 'added') return;
      const c = ch.doc.data();
      if (callId && c.callId === callId && c.from !== cfg.uid) Native.callAddIce(c).catch(() => {});
    });
  }));
}
async function startCall() {
  if (callActive || callIncomingId) return toast('Ya hay una llamada en curso');
  callId = `${Date.now()}${Math.random().toString(36).slice(2, 6)}`;
  callRole = 'caller'; callActive = true;
  try {
    await setDoc(callRef(), { callId, caller: cfg.uid, status: 'ringing', ts: Date.now() });
    await Native.callStartCall({ caller: true });
  } catch (e) {
    toast(e.message || 'No se pudo llamar');
    setDoc(callRef(), { status: 'ended', callId }, { merge: true }).catch(() => {});
    endCallLocal();
    return;
  }
  listenCandidates();
  showCall('Llamando…', 'Esperando que conteste', false);
}
async function acceptCall() {
  const id = callIncomingId;
  if (!id) return;
  const snap = await getDoc(callRef()).catch(() => null);
  const d = snap && snap.data();
  if (!d || d.callId !== id || !d.offer) return toast('La llamada ya no está disponible');
  callId = id; callIncomingId = null; callRole = 'callee'; callActive = true;
  try {
    await Native.callStartCall({ caller: false });
    listenCandidates();
    await Native.callSetRemote({ type: 'offer', sdp: d.offer.sdp });
    await setDoc(callRef(), { status: 'active' }, { merge: true });
  } catch (e) {
    toast(e.message || 'No se pudo contestar');
    endCallLocal();
    return;
  }
  showCall('En llamada', 'Conectando…', false);
}
async function hangupCall() {
  const id = callId || callIncomingId;
  endCallLocal();
  if (id) setDoc(callRef(), { status: 'ended', callId: id }, { merge: true }).catch(() => {});
}
function startCallWatcher() {
  if (callWatcherOn) return;
  callWatcherOn = true;
  // Señales nativas hacia Firestore
  Native.addListener('callLocalSdp', (o) => {
    if (!callId) return;
    setDoc(callRef(), { [o.type]: { type: o.type, sdp: o.sdp } }, { merge: true })
      .catch(() => toast('Error al enviar la llamada'));
  });
  Native.addListener('callIce', (c) => {
    if (!callId) return;
    fsAddDoc(callCandCol(), { callId, from: cfg.uid, candidate: c.candidate, sdpMid: c.sdpMid, sdpMLineIndex: c.sdpMLineIndex })
      .catch(() => {});
  });
  Native.addListener('callState', (s) => {
    if (!callActive) return;
    if (s.state === 'ERROR') { toast('Error en la llamada'); hangupCall(); }
    else if (s.state === 'CONNECTED' || s.state === 'COMPLETED') $('callStatus').textContent = 'En llamada 🎙️';
    else if (s.state === 'FAILED') { toast('La llamada no pudo conectar'); hangupCall(); }
  });
  // Escucha la llamada de la sala: entrante, respuesta o fin
  fsOnSnapshot(callRef(), (snap) => {
    const d = snap.data();
    if (!d) return;
    const fresh = Date.now() - (d.ts || 0) < 60000;
    if (d.status === 'ringing' && d.caller !== cfg.uid && !callActive && !callIncomingId && fresh) {
      callIncomingId = d.callId;
      showCall('Te está llamando tu pareja', 'Llamada de voz', true);
      navigator.vibrate?.([300, 200, 300]);
      return;
    }
    if (callActive && callRole === 'caller' && d.callId === callId && d.answer && !callAnswerApplied) {
      callAnswerApplied = true;
      $('callStatus').textContent = 'Conectando…';
      Native.callSetRemote({ type: 'answer', sdp: d.answer.sdp }).catch(() => {});
    }
    if (d.status === 'ended' && (callActive || callIncomingId) && d.callId === (callId || callIncomingId)) endCallLocal();
  }, () => {});
}
// ---- Stickers propios (dibujos incluidos en la app) y GIFs de Giphy ----
// Para tus propios GIFs: pega aquí tu clave de Giphy (developers.giphy.com). Sin clave, la pestaña GIF avisa.
const GIPHY_KEY = 'KpvgBKCWbvgFax0EsuNtNROyc4jLVcGs';
const sv = (body) => `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">${body}</svg>`;
const STICKER_CAT = {
  corazon: sv('<path d="M50 88C15 62 8 42 18 28C28 14 46 16 50 30C54 16 72 14 82 28C92 42 85 62 50 88Z" fill="#f43f5e"/>'),
  osito: sv('<circle cx="22" cy="22" r="13" fill="#b45309"/><circle cx="78" cy="22" r="13" fill="#b45309"/><circle cx="50" cy="55" r="36" fill="#b45309"/><circle cx="38" cy="50" r="5" fill="#111"/><circle cx="62" cy="50" r="5" fill="#111"/><ellipse cx="50" cy="66" rx="9" ry="6" fill="#fde7c8"/>'),
  gato: sv('<path d="M20 90V40L38 14L50 34L62 14L80 40V90Z" fill="#f59e0b"/><circle cx="38" cy="58" r="5" fill="#111"/><circle cx="62" cy="58" r="5" fill="#111"/><path d="M46 70Q50 74 54 70" stroke="#111" stroke-width="3" fill="none"/>'),
  beso: sv('<circle cx="50" cy="50" r="40" fill="#fde047"/><circle cx="36" cy="42" r="5" fill="#111"/><path d="M60 40L70 46L60 52" stroke="#111" stroke-width="4" fill="none"/><path d="M42 66Q50 72 58 66" stroke="#111" stroke-width="3" fill="none"/><path d="M78 20L84 30L74 30Z" fill="#f43f5e"/>'),
  estrella: sv('<path d="M50 6L62 36L94 38L68 58L78 90L50 72L22 90L32 58L6 38L38 36Z" fill="#facc15"/>'),
  flor: sv('<circle cx="50" cy="28" r="18" fill="#f9a8d4"/><circle cx="72" cy="46" r="18" fill="#f9a8d4"/><circle cx="62" cy="72" r="18" fill="#f9a8d4"/><circle cx="38" cy="72" r="18" fill="#f9a8d4"/><circle cx="28" cy="46" r="18" fill="#f9a8d4"/><circle cx="50" cy="50" r="12" fill="#fde047"/>'),
  luna: sv('<path d="M60 10A40 40 0 1 0 90 70A32 32 0 1 1 60 10Z" fill="#818cf8"/>'),
  fuego: sv('<path d="M50 6C60 28 80 40 78 64C76 84 62 94 50 94C36 94 22 84 22 64C22 48 34 40 40 26C46 36 46 46 50 52C54 40 52 22 50 6Z" fill="#f97316"/>'),
};
const stickerUrl = (sid) => 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(STICKER_CAT[sid] || '');
function buildStickers() {
  $('stickerGrid').innerHTML = Object.keys(STICKER_CAT).map((sid) => `<button type="button" data-sid="${sid}"><img src="${stickerUrl(sid)}" alt="${sid}"></button>`).join('');
  $('stickerGrid').onclick = (ev) => {
    const b = ev.target.closest('[data-sid]'); if (!b) return;
    sendMsg({ type: 'sticker', sid: b.dataset.sid, text: '🧸 Sticker' });
    togglePanel(false);
  };
  $('gifGrid').onclick = (ev) => {
    const b = ev.target.closest('[data-gif]'); if (!b) return;
    sendMsg({ type: 'gif', url: b.dataset.gif, text: '🎞️ GIF' });
    togglePanel(false);
  };
  $('gifGo').onclick = searchGifs;
  $('gifQ').onkeydown = (e) => { if (e.key === 'Enter') searchGifs(); };
  document.querySelectorAll('[data-pt]').forEach((b) => {
    b.onclick = () => {
      document.querySelectorAll('[data-pt]').forEach((x) => x.classList.toggle('active', x === b));
      $('emojiGrid').classList.toggle('hidden', b.dataset.pt !== 'emoji');
      $('stickerGrid').classList.toggle('hidden', b.dataset.pt !== 'sticker');
      $('gifBox').classList.toggle('hidden', b.dataset.pt !== 'gif');
    };
  });
}
async function searchGifs() {
  const q = $('gifQ').value.trim();
  if (!GIPHY_KEY) { $('gifNote').textContent = 'Falta la clave de Giphy en la app.'; return; }
  if (!q) return;
  $('gifNote').textContent = 'Buscando…';
  try {
    const r = await fetch(`https://api.giphy.com/v1/gifs/search?api_key=${encodeURIComponent(GIPHY_KEY)}&q=${encodeURIComponent(q)}&limit=18&rating=pg`);
    const list = ((await r.json()).data) || [];
    $('gifNote').textContent = list.length ? '' : 'Sin resultados';
    $('gifGrid').innerHTML = list.map((g) => {
      const u = g.images.fixed_height_small.url;
      return `<button type="button" data-gif="${u}"><img src="${u}" loading="lazy" alt="GIF"></button>`;
    }).join('');
  } catch (e) {
    $('gifNote').textContent = 'No se pudo buscar. Revisa la conexión.';
  }
}
function togglePanel(force) {
  const p = $('chatPanel');
  const show = force ?? p.classList.contains('hidden');
  p.classList.toggle('hidden', !show);
  placePanel();
}
// Coloca el panel de emojis/stickers justo encima de la barra de escribir, completo, como el teclado
function placePanel() {
  const p = $('chatPanel'), bar = document.querySelector('.chatbar');
  if (!p || !bar) return;
  if (p.parentElement !== document.body) document.body.appendChild(p); // fuera de la zona con desplazamiento
  if (p.classList.contains('hidden')) return;
  const r = bar.getBoundingClientRect();
  p.style.position = 'fixed';
  p.style.left = r.left + 'px';
  p.style.width = r.width + 'px';
  p.style.bottom = (window.innerHeight - r.top + 6) + 'px';
  p.style.maxHeight = Math.max(160, Math.min(window.innerHeight * 0.5, r.top - 90)) + 'px';
  p.style.overflowY = 'auto';
  p.style.zIndex = '65';
}
window.addEventListener('resize', placePanel);
  $('panelClose').onclick = () => togglePanel(false);
function markSeen() {
  if (!chat.readsReady || !chatOpen()) return;
  const unread = Object.values(chat.theirs).flat().filter((m) => !chat.myReads.has(m.id));
  if (!unread.length) return;
  const wb = writeBatch(db), at = Date.now();
  unread.slice(0, 400).forEach((m) => wb.set(doc(db, 'rooms', cfg.room, 'users', cfg.uid, 'reads', m.id), { at }));
  wb.commit().catch(() => {});
  unread.forEach((m) => chat.myReads.add(m.id));
}
const sameDay = (a, b) => new Date(a).toDateString() === new Date(b).toDateString();
const hm = (ts) => new Date(ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
const whenLbl = (ts) => (sameDay(ts, Date.now()) ? hm(ts) : `${new Date(ts).toLocaleDateString([], { day: 'numeric', month: 'short' })} ${hm(ts)}`);
const dayLbl = (ts) => {
  if (sameDay(ts, Date.now())) return 'Hoy';
  if (sameDay(ts, Date.now() - 86400000)) return 'Ayer';
  return new Date(ts).toLocaleDateString([], { weekday: 'long', day: 'numeric', month: 'long' });
};
function renderChat(forceBottom) {
  const list = $('chatList'); if (!list) return;
  const msgs = chat.mine.concat(...Object.values(chat.theirs)).sort((a, b) => a.ts - b.ts);
  const stick = forceBottom || list.scrollHeight - list.scrollTop - list.clientHeight < 90;
  list.innerHTML = '';
  if (!msgs.length) { const e = document.createElement('p'); e.className = 'muted'; e.textContent = 'Aún no hay mensajes. ¡Escribe el primero! 💌'; list.appendChild(e); }
  let day = null;
  msgs.forEach((m) => {
    if (day == null || !sameDay(day, m.ts)) {
      day = m.ts; const s = document.createElement('div'); s.className = 'day'; s.textContent = dayLbl(m.ts); list.appendChild(s);
    }
    const mine = m.uid === cfg.uid;
    const b = document.createElement('div'); b.className = `bub ${mine ? 'me' : 'them'}`;
    if (m.type === 'sticker' || m.type === 'gif') b.classList.add('stkbub');
    if (m.replyTo) {
      const q = document.createElement('div'); q.className = 'quote';
      const who = m.replyTo.uid === cfg.uid ? 'Tú' : m.replyTo.name;
      q.innerHTML = `<b>${esc(who)}</b>${esc(String(m.replyTo.text || '').slice(0, 120))}`;
      b.appendChild(q);
    }
    if (m.type === 'audio' && m.data) {
      const a = document.createElement('audio'); a.controls = true; a.preload = 'metadata'; a.src = m.data; b.appendChild(a);
    } else if (m.type === 'sticker' && STICKER_CAT[m.sid]) {
      const im = document.createElement('img'); im.className = 'stkimg'; im.alt = 'Sticker'; im.src = stickerUrl(m.sid); b.appendChild(im);
    } else if (m.type === 'gif' && m.url) {
      const im = document.createElement('img'); im.className = 'gifimg'; im.alt = 'GIF'; im.loading = 'lazy'; im.src = m.url; b.appendChild(im);
    } else if ((m.type === 'image' || m.type === 'file') && m.fid) {
      mediaBubble(b, m);
    } else {
      const t = document.createElement('span'); t.textContent = m.text; b.appendChild(t);
    }
    const sm = document.createElement('small');
    if (mine) {
      const at = chat.seen[m.id];
      sm.textContent = m.pending ? `${hm(m.ts)} · 🕓 enviando` : at ? `${hm(m.ts)} · ✓✓ Visto ${whenLbl(at)}` : `${hm(m.ts)} · ✓ Enviado`;
      if (at) sm.className = 'seen';
    } else sm.textContent = hm(m.ts);
    b.appendChild(sm); list.appendChild(b);
    addSwipe(b, m);
    if (mine) addLongPress(b, () => openMsgMenu(m));
  });
  if (stick) list.scrollTop = list.scrollHeight;
  const unread = chat.readsReady ? Object.values(chat.theirs).flat().filter((m) => !chat.myReads.has(m.id)).length : 0;
  const badge = $('chatBadge');
  badge.textContent = unread > 9 ? '9+' : String(unread);
  badge.classList.toggle('hidden', !unread || chatOpen());
  markSeen();
}

function listenMeta() {
  onSnapshot(doc(db, 'rooms', cfg.room, 'meta', 'couple'), (s) => {
    if (s.exists() && s.data().anniv) { anniv = s.data().anniv; $('inAnniv2').value = anniv; }
    const g = s.exists() ? s.data().gkey : '';
    if (g && g !== gKey) {
      gKey = g; localStorage.setItem('gkey', g); window.refreshGStatus?.();
      if (styleName === 'google') setStyle('google');
    }
    const m = s.exists() ? s.data().mkey : '';
    if (m && m !== mKey) {
      mKey = m; localStorage.setItem('mkey', m); window.refreshMStatus?.();
      if (IS_NATIVE) Native.setMapboxKey({ key: m }).catch(() => {});
      if (styleName === 'mapbox') setStyle('mapbox');
    }
    renderUs();
  }, () => {});
}
const otherPeople = () => Object.entries(people)
  .filter(([id, p]) => id !== cfg.uid && p.lat != null)
  .map(([id, p]) => ({ id, ...p })).sort((a, b) => b.ts - a.ts);

function renderBanner() {
  const b = $('banner'); if (!b) return;
  const me = people[cfg.uid], o = otherPeople()[0];
  if (!o) {
    b.className = 'banner wait';
    b.innerHTML = `<span class="beat">💞</span><div><b>Esperando a tu pareja…</b><small>Que entre con tu mismo código de sala</small></div>`;
    return;
  }
  const [n1, n2] = [me?.name || cfg.name, o.name].sort((a, c) => String(a).localeCompare(String(c), 'es', { sensitivity: 'base' }));
  const names = `${esc(n1)} y ${esc(n2)}`;
  const days = daysTogether();
  if (me && me.lat != null) {
    const d = dist(me, o);
    if (d < 50) {
      b.className = 'banner together';
      b.innerHTML = `<span class="beat">💞</span><div><b>¡${names} están juntos!</b><small>🥰 ${days != null ? days + ' días de amor' : 'Disfrútenlo'}</small></div>`;
      if (!wasTogether) { wasTogether = true; floatHearts(22); }
      return;
    }
    wasTogether = false;
    b.className = 'banner';
    b.innerHTML = `<span class="beat">💗</span><div><b>${names}</b><small>${days != null ? '💕 ' + days + ' días juntos · ' : ''}a ${fmtDist(d)} de distancia</small></div>`;
  } else {
    b.className = 'banner';
    b.innerHTML = `<span class="beat">💗</span><div><b>${names}</b><small>Buscando tu ubicación…</small></div>`;
  }
}

function renderUs() {
  if (!$('usInfo') || !cfg) return;
  renderBanner(); updatePair();
  const me = people[cfg.uid], others = otherPeople();
  let h = '';
  const days = daysTogether();
  if (days != null) h += `<div class="days">💕 <b>${days}</b> días juntos</div>`;
  if (me && me.lat != null && others[0]) {
    const d = dist(me, others[0]);
    h += `<div class="distance">${d < 50 ? '🥰 ¡Están juntos!' : `💗 Están a <b>${fmtDist(d)}</b> de distancia`}</div>`;
  } else if (!others.length) {
    h += `<div class="distance">⏳ Esperando a que tu pareja entre con el mismo código…</div>`;
  }
  [me && { id: cfg.uid, ...me }, ...others].filter(Boolean).forEach((p) => {
    h += `<div class="person"><div class="av">${esc(p.avatar || '💗')}</div><div>
      <b>${esc(p.name)}${p.id === cfg.uid ? ' (tú)' : ''}</b><br>
      ${p.mood ? `<span>${esc(p.mood)}</span><br>` : ''}
      ${p.msg ? `<i>“${esc(p.msg)}”</i><br>` : ''}
      <small>${p.ts ? ago(p.ts) : ''}${p.acc ? ` · ±${p.acc} m` : ''}</small></div></div>`;
  });
  $('usInfo').innerHTML = h;
  document.querySelectorAll('#moods button').forEach((b) => b.classList.toggle('active', !!me && me.mood === b.dataset.mood));
}

// ---------- Lugares ----------
function listenPlaces() {
  onSnapshot(collection(db, 'rooms', cfg.room, 'places'), (snap) => {
    places = snap.docs.map((d) => ({ id: d.id, ...d.data() }));
    renderPlaces();
  }, () => {});
}
function addPlace(lat, lng) {
  const name = (prompt('¿Cómo se llama este lugar?') || '').trim();
  if (!name) return;
  addDoc(collection(db, 'rooms', cfg.room, 'places'),
    { name: name.slice(0, 40), emoji: $('placeEmoji').value, lat, lng, by: cfg.name, ts: Date.now() }).catch(() => {});
  toast('Lugar guardado ⭐');
}
function renderPlaces() {
  if (!placesLayer) return;
  placesLayer.clearLayers();
  const me = people[cfg.uid];
  const list = $('placesList');
  list.innerHTML = places.length ? '' : '<p class="muted">Aún no hay lugares. Guarda su casa, su restaurante favorito… 💕</p>';
  places.forEach((p) => {
    L.marker([p.lat, p.lng], { icon: L.divIcon({ className: '', html: `<div class="pl">${esc(p.emoji)}</div>`, iconSize: [30, 30], iconAnchor: [15, 15] }) })
      .bindTooltip(p.name).addTo(placesLayer);
    const row = document.createElement('div'); row.className = 'place';
    row.innerHTML = `<span style="font-size:1.5rem">${esc(p.emoji)}</span><div class="t"><b>${esc(p.name)}</b><br><small>${me && me.lat != null ? fmtDist(dist(me, p)) + ' de ti · ' : ''}por ${esc(p.by)}</small></div>`;
    [['👁', () => map.flyTo([p.lat, p.lng], 17)], ['🧭', () => navigate(p.lat, p.lng)],
      ['🗑', () => confirm(`¿Borrar "${p.name}"?`) && deleteDoc(doc(db, 'rooms', cfg.room, 'places', p.id))]]
      .forEach(([t, fn]) => { const b = document.createElement('button'); b.textContent = t; b.onclick = fn; row.appendChild(b); });
    list.appendChild(row);
  });
}

// ---------- Historial ----------
function refreshHistUsers() {
  const sel = $('histUser'), prev = sel.value; sel.innerHTML = '';
  Object.entries(people).forEach(([id, p]) => sel.add(new Option(id === cfg.uid ? `${p.name} (yo)` : p.name, id)));
  if (prev) sel.value = prev;
}
// <<snap
// Ajusta el recorrido a las calles (OSRM público de OpenStreetMap, uso ligero)
const OSRM = 'https://routing.openstreetmap.de';
let lastReq = 0;
async function getJSON(url) {
  if (IS_NATIVE) {
    try { return JSON.parse((await withTimeout(Native.httpGet({ url }), 25000)).text); }
    catch (e) { if (!/not implemented|UNIMPLEMENTED/i.test(String(e?.message || e))) throw e; }
  }
  return (await withTimeout(fetch(url), 25000)).json();
}
async function osrm(mode, service, pts, params) {
  const wait = 1100 - (Date.now() - lastReq);
  if (wait > 0) await sleep(wait);
  lastReq = Date.now();
  const path = pts.map((c) => `${c.lng.toFixed(6)},${c.lat.toFixed(6)}`).join(';');
  return getJSON(`${OSRM}/routed-${mode}/${service}/v1/driving/${path}?${params}`);
}
const DRIFT_MAX_MS = 45; // más de ~160 km/h entre dos puntos = salto falso del GPS
function cleanPoints(pts) {
  const out = []; let jumps = 0;
  for (const p of pts.slice().sort((a, b) => a.ts - b.ts)) {
    if ((p.acc || 0) > 150) continue;
    const l = out[out.length - 1];
    if (l) {
      const d = dist(l, p), dt = Math.max(1, (p.ts - l.ts) / 1000);
      if (d < 4) continue;
      if (d > 80 && d / dt > DRIFT_MAX_MS && ++jumps < 4) continue; // filtro de deriva
    }
    jumps = 0;
    out.push(p);
  }
  // picos: el GPS salta lejos y vuelve casi al mismo sitio
  const res = [];
  for (let i = 0; i < out.length; i++) {
    const a = res[res.length - 1], b = out[i], c = out[i + 1];
    if (a && c) {
      const ab = dist(a, b), bc = dist(b, c), ac = dist(a, c);
      if (ab > 100 && bc > 100 && ac < Math.min(ab, bc) * 0.5) continue;
    }
    res.push(b);
  }
  return res;
}
// Paradas: varios minutos en el mismo sitio (la "P" del mapa). route = puntos sin el ruido de estar quieto
const STOP_MIN_MS = 180000, STOP_RADIUS_M = 40;
function findStops(pts) {
  const stops = [], route = [];
  let i = 0;
  while (i < pts.length) {
    let j = i;
    while (j + 1 < pts.length && dist(pts[i], pts[j + 1]) <= STOP_RADIUS_M) j++;
    if (j > i && pts[j].ts - pts[i].ts >= STOP_MIN_MS) {
      let la = 0, ln = 0;
      for (let k = i; k <= j; k++) { la += pts[k].lat; ln += pts[k].lng; }
      stops.push({ lat: la / (j - i + 1), lng: ln / (j - i + 1), from: pts[i].ts, to: pts[j].ts });
      route.push(pts[i], pts[j]);
      i = j + 1;
    } else { route.push(pts[i]); i++; }
  }
  return { stops, route };
}
function splitRuns(pts) {
  const runs = []; let cur = [];
  for (const p of pts) {
    const l = cur[cur.length - 1];
    if (l && (dist(l, p) > 500 || p.ts - l.ts > 300000)) { runs.push(cur); cur = []; }
    cur.push(p);
  }
  if (cur.length) runs.push(cur);
  return runs;
}
function chunk(run, n) {
  const out = [];
  for (let i = 0; i < run.length - 1; i += n - 1) out.push(run.slice(i, i + n));
  return out;
}
const speedMode = (d, dtMs) => (dtMs > 0 && d / (dtMs / 1000) > 2.8 ? 'car' : 'foot');
function runMode(run) {
  let d = 0; for (let i = 1; i < run.length; i++) d += dist(run[i - 1], run[i]);
  return speedMode(d, run[run.length - 1].ts - run[0].ts);
}
const radius = (p) => Math.round(Math.min(60, Math.max(20, (p.acc || 20) * 1.2)));
// distancia rápida en metros entre dos [lat, lng] cercanos
function mDist(a, b) {
  const dy = (a[0] - b[0]) * 111320, dx = (a[1] - b[1]) * 111320 * Math.cos(a[0] * Math.PI / 180);
  return Math.hypot(dx, dy);
}
function pathLen(c) {
  let s = 0;
  for (let i = 1; i < c.length; i++) s += mDist(c[i - 1], c[i]);
  return s;
}
// Mapbox Map Matching (hasta 100 puntos por pedido). Devuelve trozos [lat,lng] o null.
async function mapboxMatch(pts, mode) {
  const coords = pts.map((p) => `${p.lng.toFixed(6)},${p.lat.toFixed(6)}`).join(';');
  const rad = pts.map((p) => Math.min(50, radius(p))).join(';');
  const url = `https://api.mapbox.com/matching/v5/mapbox/${mode === 'car' ? 'driving' : 'walking'}/${coords}` +
    `?access_token=${encodeURIComponent(mKey)}&geometries=geojson&overview=full&radiuses=${rad}`;
  const j = await getJSON(url);
  return j.code === 'Ok' && j.matchings?.length ? j.matchings.map((m) => toLL(m.geometry)) : null;
}
const toLL = (g) => g.coordinates.map(([lng, lat]) => [lat, lng]);
const asPt = (c) => ({ lat: c[0], lng: c[1] });
// Ruta por las calles pasando por todos los puntos (plan B cuando el ajuste directo falla)
async function routeVia(pts, mode) {
  const j = await osrm(mode, 'route', pts, 'overview=full&geometries=geojson&continue_straight=false');
  return j.code === 'Ok' && j.routes?.length ? toLL(j.routes[0].geometry) : null;
}
// segment(coords, estimado, fallo): fallo = true si no se pudo seguir las calles
async function snapRoute(pts, { segment, progress, cancelled }) {
  const runs = splitRuns(pts);
  const chunks = runs.map((r) => (r.length < 2 ? [] : chunk(r, 80)));
  const total = chunks.reduce((a, c) => a + c.length, 0) + Math.max(0, runs.length - 1);
  let done = 0, calls = 0;
  const MAX_CALLS = 220; // para no abusar del servicio público
  const DENSE_MAX_GAP_M = 40; // con puntos así de juntos, los puntos reales ya siguen la calle
  const raw = (arr) => arr.map((p) => [p.lat, p.lng]);
  const attempt = async (fn) => {
    if (calls >= MAX_CALLS) return null;
    calls++;
    try { return await fn(); } catch { return null; }
  };
  for (let i = 0; i < runs.length; i++) {
    if (cancelled()) return;
    const mode = runMode(runs[i]);
    for (const ch of chunks[i]) {
      if (cancelled()) return;
      if (ch.every((p, k) => k === 0 || dist(ch[k - 1], p) <= DENSE_MAX_GAP_M)) { // puntos juntos: sin servicio
        segment(raw(ch), false, false); progress(++done, total); continue;
      }
      // 1) ajuste a las calles (map matching)
      const segs = await attempt(async () => {
        if (mKey) { try { const m = await mapboxMatch(ch, mode); if (m) return m; } catch { /* sigue con el gratuito */ } }
        const j = await osrm(mode, 'match', ch, `overview=full&geometries=geojson&radiuses=${ch.map(radius).join(';')}`);
        return j.code === 'Ok' && j.matchings?.length ? j.matchings.map((m) => toLL(m.geometry)) : null;
      });
      if (segs) {
        let prev = null;
        for (const c of segs) {
          if (prev && mDist(prev[prev.length - 1], c[0]) > 15) { // trozos sueltos: se unen por las calles
            const a = prev[prev.length - 1], b = c[0];
            const bridge = await attempt(() => routeVia([asPt(a), asPt(b)], mode));
            segment(bridge || [a, b], false, !bridge);
          }
          segment(c, false, false);
          prev = c;
        }
      } else {
        // 2) plan B: ruta por las calles pasando por los puntos; 3) plan C: línea recta
        const via = await attempt(() => routeVia(ch, mode));
        if (via) segment(via, false, false); else segment(raw(ch), true, true);
      }
      progress(++done, total);
    }
    if (i < runs.length - 1) { // hueco entre dos tramos: se rellena siguiendo las calles
      if (cancelled()) return;
      const a = runs[i][runs[i].length - 1], b = runs[i + 1][0];
      if (dist(a, b) <= 60) segment(raw([a, b]), false, false); // misma parada
      else {
        const coords = await attempt(() => routeVia([a, b], speedMode(dist(a, b), b.ts - a.ts)));
        segment(coords || raw([a, b]), true, !coords);
      }
      progress(++done, total);
    }
  }
}
function findGaps(pts) {
  const out = [];
  for (let i = 1; i < pts.length; i++) {
    const dt = pts[i].ts - pts[i - 1].ts;
    if ((dt > 180000 && dist(pts[i - 1], pts[i]) > 100) || dist(pts[i - 1], pts[i]) > 500) out.push(Math.round(dt / 60000));
  }
  return out;
}
// snap>>

// <<play
// Reproducción: el marcador recorre las calles ya ajustadas, con la hora real de cada punto del GPS
const GAP_MS = 180000, GAP_PLAY_MS = 90000; // un hueco sin datos se recorre rápido
function buildPlayback(clean, segs) {
  const path = [];
  for (const { c } of segs) for (const p of c) {
    const l = path[path.length - 1];
    if (!l || l[0] !== p[0] || l[1] !== p[1]) path.push(p);
  }
  if (path.length < 2) return null;
  const cum = [0];
  for (let i = 1; i < path.length; i++) cum.push(cum[i - 1] + mDist(path[i - 1], path[i]));
  // cada punto del GPS se ubica sobre el camino, siempre hacia adelante
  const anchors = []; let j = 0, vt = 0;
  clean.forEach((p, k) => {
    if (k === 0) j = 0;
    else if (k === clean.length - 1) j = path.length - 1;
    else {
      const allow = dist(clean[k - 1], p) * 3 + 400, here = [p.lat, p.lng];
      let best = j, bd = Infinity;
      for (let m = j; m < path.length && cum[m] - cum[j] <= allow; m++) {
        const d = mDist(path[m], here);
        if (d < bd) { bd = d; best = m; }
      }
      j = best;
    }
    let gap = false;
    if (k > 0) {
      const prev = clean[k - 1], dt = Math.max(1, p.ts - prev.ts);
      const dd = dist(prev, p);
      gap = (dt > GAP_MS && dd > 100) || dd > 500;
      vt += dt > GAP_MS ? GAP_PLAY_MS : dt;
    }
    anchors.push({ d: cum[j], ts: p.ts, vt, gap });
  });
  const totalVt = anchors[anchors.length - 1].vt;
  return totalVt > 0 ? { path, cum, anchors, totalVt, length: cum[cum.length - 1] } : null;
}
function pointAt(pb, d) {
  const cum = pb.cum;
  if (d <= 0) return { pos: pb.path[0], i: 0 };
  if (d >= pb.length) return { pos: pb.path[pb.path.length - 1], i: pb.path.length - 1 };
  let lo = 0, hi = cum.length - 1;
  while (hi - lo > 1) { const m = (lo + hi) >> 1; if (cum[m] <= d) lo = m; else hi = m; }
  const f = cum[hi] > cum[lo] ? (d - cum[lo]) / (cum[hi] - cum[lo]) : 0;
  const a = pb.path[lo], b = pb.path[hi];
  return { pos: [a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f], i: lo };
}
function bearing(a, b) {
  const r = (x) => x * Math.PI / 180, y = Math.sin(r(b[1] - a[1])) * Math.cos(r(b[0]));
  const x = Math.cos(r(a[0])) * Math.sin(r(b[0])) - Math.sin(r(a[0])) * Math.cos(r(b[0])) * Math.cos(r(b[1] - a[1]));
  return (Math.atan2(y, x) * 180 / Math.PI + 360) % 360;
}
const CARDINALS = ['Norte', 'Noreste', 'Este', 'Sureste', 'Sur', 'Suroeste', 'Oeste', 'Noroeste'];
const cardinal = (deg) => CARDINALS[Math.round(deg / 45) % 8];
function playState(pb, vt) {
  const A = pb.anchors; let lo = 0, hi = A.length - 1;
  while (hi - lo > 1) { const m = (lo + hi) >> 1; if (A[m].vt <= vt) lo = m; else hi = m; }
  const a = A[lo], b = A[hi];
  const f = b.vt > a.vt ? Math.min(1, Math.max(0, (vt - a.vt) / (b.vt - a.vt))) : 1;
  const d = a.d + (b.d - a.d) * f, ts = a.ts + (b.ts - a.ts) * f, dtS = (b.ts - a.ts) / 1000;
  const kmh = b.gap || dtS <= 0 ? null : Math.min(250, (b.d - a.d) / dtS * 3.6);
  const cur = pointAt(pb, d);
  const p1 = pointAt(pb, Math.max(0, d - 15)).pos, p2 = pointAt(pb, Math.min(pb.length, d + 15)).pos;
  return { pos: cur.pos, idx: cur.i, d, ts, kmh, course: mDist(p1, p2) < 1 ? null : bearing(p1, p2) };
}
// play>>

const ROUTE_COLOR = '#f43f5e', ROUTE_SOFT = '#f9a8c4';
const CASING_STYLE = { color: '#fff', weight: 10, opacity: 0.92, lineCap: 'round', lineJoin: 'round' };
const SOFT_STYLE = { color: ROUTE_SOFT, weight: 5, opacity: 0.95, lineCap: 'round', lineJoin: 'round' };
const DONE_STYLE = { color: ROUTE_COLOR, weight: 5, opacity: 1, lineCap: 'round', lineJoin: 'round' };
const GUESS_STYLE = { color: '#a78b95', weight: 3, opacity: 0.9, dashArray: '2 8' };
const hhmm = (ts) => new Date(ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
const hhmmss = (ts) => new Date(ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
const dayShort = (ts) => new Date(ts).toLocaleDateString([], { day: 'numeric', month: 'short' });
const flagIcon = (e) => L.divIcon({ className: '', html: `<div class="pl">${e}</div>`, iconSize: [28, 28], iconAnchor: [8, 26] });
const durTxt = (ms) => { const m = Math.round(ms / 60000); return m < 60 ? `${m} min` : `${Math.floor(m / 60)} h ${m % 60} min`; };
const parkIcon = () => L.divIcon({ className: '', html: '<div class="pk">P</div>', iconSize: [24, 24], iconAnchor: [12, 12] });
function histStops(layer, stops) {
  stops.forEach((s) => L.marker([s.lat, s.lng], { icon: parkIcon(), zIndexOffset: 150 })
    .bindTooltip(`Parada ${hhmm(s.from)} – ${hhmm(s.to)} · ${durTxt(s.to - s.from)}`).addTo(layer));
}
function histMarks(layer, clean) {
  const f = clean[0], l = clean[clean.length - 1];
  L.marker([f.lat, f.lng], { icon: flagIcon('🚩'), zIndexOffset: 200 }).bindTooltip(`Inicio ${hhmm(f.ts)}`).addTo(layer);
  L.marker([l.lat, l.lng], { icon: flagIcon('🏁'), zIndexOffset: 200 }).bindTooltip(`Fin ${hhmm(l.ts)}`).addTo(layer);
}
function addSeg(layer, c, inferred) {
  if (inferred) { L.polyline(c, GUESS_STYLE).addTo(layer); return; }
  L.polyline(c, CASING_STYLE).addTo(layer);
  L.polyline(c, SOFT_STYLE).addTo(layer);
}

// ---- Reproductor ----
const SPEEDS = [10, 30, 60, 120, 300];
const play = { pb: null, vt: 0, playing: false, touched: false, speed: 30, follow: true, raf: 0, last: 0,
  marker: null, done: null, course: 0, tUI: 0, tLine: 0 };
const hmIcon = (avatar) => L.divIcon({
  className: '', iconSize: [46, 46], iconAnchor: [23, 23],
  html: `<div class="hm"><i class="hm-dir"></i><div class="hm-face">${esc(avatar || '💗')}</div></div>`
});
function bubbleHTML(s) {
  const course = s.course ?? play.course;
  return `<b>${hhmmss(s.ts)}</b> <small>${dayShort(s.ts)}</small><br>` +
    `⚡ ${s.kmh == null ? 'sin datos' : Math.round(s.kmh) + ' km/h'}<br>` +
    `🧭 ${cardinal(course)} ${Math.round(course)}°<br>` +
    `📏 ${(s.d / 1000).toFixed(2)} km`;
}
function renderPlay(force) {
  const pb = play.pb; if (!pb || !play.marker) return;
  const s = playState(pb, play.vt), now = performance.now();
  if (s.course != null) play.course = s.course;
  play.marker.setLatLng(s.pos);
  const el = play.marker.getElement(), dir = el && el.querySelector('.hm-dir');
  if (dir) dir.style.transform = `rotate(${play.course}deg)`;
  if (force || now - play.tUI > 120) {
    play.tUI = now;
    play.marker.setTooltipContent(bubbleHTML(s));
    $('hpSeek').value = Math.round(play.vt / pb.totalVt * 1000);
  }
  if (force || now - play.tLine > 150) {
    play.tLine = now;
    if (play.done) play.done.setLatLngs(play.touched ? pb.path.slice(0, s.idx + 1).concat([s.pos]) : pb.path);
  }
  if (play.follow && play.playing && !map.getBounds().pad(-0.3).contains(s.pos)) map.panTo(s.pos, { animate: true, duration: 0.4 });
}
function setPlaying(on) {
  play.playing = on;
  $('hpPlay').textContent = on ? '⏸' : '▶';
  cancelAnimationFrame(play.raf);
  if (!on) return;
  if (play.vt >= play.pb.totalVt) play.vt = 0;
  play.touched = true; play.last = performance.now();
  const tick = (now) => {
    if (!play.playing) return;
    play.vt = Math.min(play.pb.totalVt, play.vt + Math.min(100, now - play.last) * play.speed);
    play.last = now;
    if (play.vt >= play.pb.totalVt) { play.playing = false; $('hpPlay').textContent = '▶'; renderPlay(true); return; }
    renderPlay();
    play.raf = requestAnimationFrame(tick);
  };
  play.raf = requestAnimationFrame(tick);
}
function stopPlay() {
  play.playing = false; cancelAnimationFrame(play.raf);
  play.pb = null; play.marker = null; play.done = null; play.vt = 0; play.touched = false;
  $('histPlayer').classList.add('hidden'); $('hpPlay').textContent = '▶';
}
function initPlayer() {
  $('hpPlay').onclick = () => { if (play.pb) setPlaying(!play.playing); };
  $('hpSeek').oninput = () => {
    if (!play.pb) return;
    play.touched = true;
    play.vt = $('hpSeek').value / 1000 * play.pb.totalVt;
    play.last = performance.now(); renderPlay(true);
  };
  $('hpSpeed').onclick = () => {
    play.speed = SPEEDS[(SPEEDS.indexOf(play.speed) + 1) % SPEEDS.length];
    $('hpSpeed').textContent = `×${play.speed}`;
  };
  $('hpFollow').onclick = () => {
    play.follow = !play.follow; $('hpFollow').classList.toggle('active', play.follow);
    if (play.follow && play.pb && play.marker) map.panTo(play.marker.getLatLng());
  };
  map.on('dragstart', () => { if (play.playing && play.follow) { play.follow = false; $('hpFollow').classList.remove('active'); } });
}

function paintHist() {
  if (!histData) return;
  if (histLayer) map.removeLayer(histLayer);
  histLayer = L.layerGroup().addTo(map);
  histData.segs.forEach(({ c, inf }) => { if (!inf || histOpts.guess) addSeg(histLayer, c, inf); });
  play.done = L.polyline([], DONE_STYLE).addTo(histLayer);
  if (histOpts.dots) histData.clean.forEach((p) =>
    L.circleMarker([p.lat, p.lng], { radius: 3, color: '#be123c', weight: 1, fillColor: '#fff', fillOpacity: 1 }).bindTooltip(hhmm(p.ts)).addTo(histLayer));
  histStops(histLayer, histData.stops);
  histMarks(histLayer, histData.clean);
  if (play.pb) {
    const who = people[histData.id] || {};
    play.marker = L.marker(playState(play.pb, play.vt).pos, { icon: hmIcon(who.avatar), zIndexOffset: 1000, interactive: false })
      .bindTooltip('', { permanent: true, direction: 'top', offset: [0, -26], className: 'hbubble', opacity: 1 })
      .addTo(histLayer);
    renderPlay(true);
  }
}

// ---- Caché del recorrido ya ajustado (para no volver a pedirlo) ----
const HCACHE = 'hist2:';
function cacheGet(key) { try { return JSON.parse(localStorage.getItem(key)); } catch { return null; } }
function cachePut(key, segs) {
  try {
    Object.keys(localStorage).filter((k) => k.startsWith('hist:')).forEach((k) => localStorage.removeItem(k)); // versión anterior
    const keys = Object.keys(localStorage).filter((k) => k.startsWith(HCACHE) && k !== key);
    keys.slice(0, Math.max(0, keys.length - 7)).forEach((k) => localStorage.removeItem(k));
    const r = (c) => c.map(([a, b]) => [+a.toFixed(5), +b.toFixed(5)]);
    localStorage.setItem(key, JSON.stringify(segs.map(({ c, inf }) => ({ c: r(c), inf }))));
  } catch { /* sin espacio: no pasa nada */ }
}

async function showHistory() {
  const id = $('histUser').value, day = $('histDate').value;
  if (!id || !day) return;
  const [y, m, d] = day.split('-').map(Number);
  const start = new Date(y, m - 1, d).getTime(), end = start + 86400000;
  let pts = [];
  try {
    const q = query(collection(db, 'rooms', cfg.room, 'users', id, 'points'),
      where('ts', '>=', start), where('ts', '<', end), orderBy('ts'));
    pts = (await withTimeout(getDocs(q), 10000)).docs.map((x) => x.data());
  } catch { return toast('No se pudo cargar (¿sin internet?)'); }
  if (id === cfg.uid) {
    const seen = new Set(pts.map((p) => p.ts));
    pts = pts.concat(queue.filter((p) => p.ts >= start && p.ts < end && !seen.has(p.ts))).sort((a, b) => a.ts - b.ts);
  }
  if (histLayer) map.removeLayer(histLayer);
  stopPlay();
  const my = ++histRun, cancelled = () => my !== histRun;
  const clean = cleanPoints(pts);
  if (clean.length < 2) { histData = null; $('histInfo').textContent = pts.length ? 'Muy pocos puntos ese día.' : 'Sin puntos ese día.'; return; }

  const { stops, route } = findStops(clean);
  const data = { id, clean, route, stops, segs: [] }; histData = data;
  histLayer = L.layerGroup().addTo(map);
  const raw = clean.map((p) => [p.lat, p.lng]);
  const rough = L.polyline(raw, { color: '#d9a3b3', weight: 2, opacity: 0.5, dashArray: '2 6' }).addTo(histLayer);
  histMarks(histLayer, clean);
  map.fitBounds(rough.getBounds(), { padding: [40, 40] });

  let total = 0, failed = 0; const all = [];
  const key = `${HCACHE}${mKey ? 'm' : 'o'}:${id}:${day}:${clean.length}:${clean[clean.length - 1].ts}`;
  const cached = cacheGet(key);
  const take = (coords, inferred) => {
    data.segs.push({ c: coords, inf: inferred });
    if (!inferred || histOpts.guess) addSeg(histLayer, coords, inferred);
    total += pathLen(coords);
    for (const c of coords) all.push(c);
  };
  if (cached && cached.length) cached.forEach(({ c, inf }) => take(c, inf));
  else {
    await snapRoute(route, {
      cancelled,
      progress: (a, b) => { if (!cancelled()) $('histInfo').textContent = `Ajustando a las calles… ${a}/${b}`; },
      segment: (coords, inferred, fail) => { if (cancelled()) return; if (fail) failed++; take(coords, inferred); }
    });
    if (cancelled()) return;
    if (!failed) cachePut(key, data.segs);
  }
  play.pb = buildPlayback(route, data.segs);
  play.vt = 0; play.touched = false;
  paintHist();
  if (play.pb) $('histPlayer').classList.remove('hidden');
  if (all.length) map.fitBounds(L.latLngBounds(all), { padding: [40, 40] });
  const gaps = findGaps(clean);
  $('histInfo').innerHTML = `${clean.length} puntos del GPS · recorrido ≈ ${fmtDist(total)}` +
    (stops.length ? ` · ${stops.length} parada(s)` : '') +
    (gaps.length ? `<br>⚠️ ${gaps.length} hueco(s) sin datos (el mayor de ${Math.max(...gaps)} min). Ahí el GPS no reportó.` : '') +
    (failed ? `<br>⚠️ ${failed} tramo(s) no se pudieron ajustar a las calles (servicio ocupado). Vuelve a tocar «Ver ruta».` : '') +
    `<br>🌸 recorrido · 🅿️ paradas · ⚪ estimado (faltan datos)` +
    (play.pb ? `<br>▶ Usa el reproductor del mapa para verlo en movimiento.` : '');
}

// ---------- Inicio ----------
localStorage.removeItem('cfg'); // configuración de la versión anterior
initLogin();