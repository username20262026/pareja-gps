# Pareja GPS — guía rápida (Android)

## 0. Instala una vez
- Node.js 20+ (nodejs.org)
- Android Studio (incluye SDK y JDK) → developer.android.com/studio

## 1. Firebase (gratis)
1. console.firebase.google.com → Crear proyecto.
2. Agregar app **Web** (</>) → copia el objeto `firebaseConfig` en `src/firebase.js`.
3. Build → Authentication → Comenzar → habilita **Anónimo**.
4. Build → Firestore Database → Crear (modo producción) → pestaña Reglas → pega `firestore.rules` → Publicar.

## 2. Instalar dependencias
```
npm install firebase leaflet @capacitor/core @capacitor/android @capacitor-community/background-geolocation
npm install -D vite @capacitor/cli
```

## 3. Probar en el PC (opcional)
```
npx vite
```
Abre la URL que muestra. Sirve para ver el mapa y la sincronización.

## 4. Crear el proyecto Android
```
npm run build
npx cap add android
```

## 5. Permisos: edita android/app/src/main/AndroidManifest.xml
Dentro de `<manifest>` (fuera de `<application>`) agrega:
```
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.INTERNET" />
```

## 6. Compilar e instalar
```
npx cap sync android
npx cap open android      # abre Android Studio → botón ▶ con el celular por USB
```
O genera el APK: `npm run apk` → android/app/build/outputs/apk/debug/app-debug.apk
(pásalo al celular de tu pareja y ábrelo; activa "instalar apps desconocidas").

## 7. En AMBOS celulares (importante para que no se corte)
- Permiso de ubicación: **"Permitir todo el tiempo"** + ubicación precisa.
- Ajustes > Apps > Pareja GPS > Batería > **Sin restricciones** (en Xiaomi/Samsung/Huawei también desactiva el ahorro agresivo).
- Usen el **mismo código de sala**, cada uno con su nombre.

## Cómo funciona offline
Cada punto se guarda primero en el teléfono. Una cola lo sube a Firebase cuando hay internet
(al reconectar y cada 30 s). Los puntos usan su hora como ID, así que nunca se duplican.
