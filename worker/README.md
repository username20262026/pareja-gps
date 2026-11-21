# Notificaciones push con Cloudflare Workers (gratis)

Firebase Cloud Functions pide el plan Blaze. Este Worker hace lo mismo gratis: la app le avisa
cuando mandas un mensaje, corazón, estado, notita 💌 o llamada, y el Worker manda la notificación
push al celular de tu pareja con Firebase Cloud Messaging (que sí es gratis en el plan Spark).

Plan gratis de Cloudflare: 100.000 pedidos por día. Una pareja usa unos pocos cientos.

## 1. Clave de la cuenta de servicio de Firebase
1. console.firebase.google.com → proyecto **app-pareja-c1579** → ⚙️ **Configuración del proyecto**.
2. Pestaña **Cuentas de servicio** → **Generar nueva clave privada** → **Generar clave**.
3. Se descarga un archivo `.json`. **No lo subas a GitHub ni lo mandes por chat**: con él se puede
   leer y escribir toda tu base de datos. (Está en `.gitignore` como `service-account*.json`.)

En la misma página de configuración, pestaña **Cloud Messaging**: “API de Firebase Cloud Messaging (V1)”
debe decir **Habilitada** (si dice inhabilitada, toca los tres puntos → administrar API → Habilitar).

## 2. Crear el Worker (desde el panel, sin instalar nada)
1. dash.cloudflare.com → **Workers y Pages** → **Crear** → **Crear Worker** (plantilla “Hello World”).
2. Nombre: `pareja-push` → **Implementar**.
3. **Editar código** → borra todo, pega el contenido de `push-worker.js` → **Implementar**.

## 3. Guardar la clave como secreto
1. En el Worker → **Configuración** → **Variables y secretos** → **Agregar**.
2. Tipo **Secreto**, nombre `SERVICE_ACCOUNT`, valor: abre el `.json` del paso 1 con el Bloc de notas,
   copia **todo** (desde `{` hasta `}`) y pégalo. → **Implementar**.

## 4. Dominio
1. En el Worker → **Configuración** → **Dominios y rutas** → **Agregar** → **Dominio personalizado**.
2. Escribe `push.nnxsw-proyect.date` → **Agregar dominio**. (Tarda unos minutos.)

Prueba: abre https://push.nnxsw-proyect.date/ en el navegador. Debe decir
`{"ok":true,"proyecto":"app-pareja-c1579","aviso":"Listo"}`.

## 5. App
La dirección está en `src/main.js` (`PUSH_URL`). Compila la versión nueva con `publicar.ps1`
e instálala en **los dos** celulares. Ábrela una vez en cada uno (así guarda su token).

## Desde la computadora (opcional, en vez de los pasos 2 a 4)
```
cd worker
npx wrangler login
npx wrangler deploy
npx wrangler secret put SERVICE_ACCOUNT   # pega el JSON completo y Enter
```

## Si no llegan
- Worker → **Registros** (Logs) → **Iniciar transmisión** y manda un corazón: ahí sale el error.
- `401 sesión no válida`: la app no tiene sesión. `403`: la cuenta no está en `rooms/<sala>.members`.
- `sent: 0`: el celular de tu pareja todavía no guardó su token (que abra la app nueva una vez).
