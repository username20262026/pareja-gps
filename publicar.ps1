# Compila una versión nueva, la sube a GitHub Releases y actualiza el Worker de Cloudflare
# Ejecutar:  powershell -ExecutionPolicy Bypass -File .\publicar.ps1
# Solo el Worker (sin compilar APK):  powershell -ExecutionPolicy Bypass -File .\publicar.ps1 -SoloWorker
param([switch]$SoloWorker)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$utf8 = New-Object System.Text.UTF8Encoding($false)

# Sube worker\push-worker.js a Cloudflare con Wrangler, solo si cambió desde la última vez.
# La primera vez se abre el navegador para iniciar sesión en Cloudflare (una sola vez).
function Publicar-Worker {
  $dir = Join-Path $PSScriptRoot 'worker'
  $marca = Join-Path $dir '.ultimo-deploy'
  $hash = (Get-FileHash (Join-Path $dir 'push-worker.js') -Algorithm SHA256).Hash + `
          (Get-FileHash (Join-Path $dir 'wrangler.toml') -Algorithm SHA256).Hash
  if ((Test-Path $marca) -and ((Get-Content $marca -Raw).Trim() -eq $hash)) {
    Write-Host 'El Worker no cambió, no hace falta subirlo.' -ForegroundColor DarkGray
    return
  }
  Write-Host '=== Actualizando el Worker en Cloudflare ===' -ForegroundColor Cyan
  Push-Location $dir
  try {
    npx --yes wrangler@4 deploy
    if ($LASTEXITCODE -ne 0) {
      Write-Host 'Parece que falta iniciar sesión en Cloudflare. Se abre el navegador...' -ForegroundColor Yellow
      npx --yes wrangler@4 login
      npx --yes wrangler@4 deploy
    }
    if ($LASTEXITCODE -ne 0) {
      Write-Warning 'No se pudo subir el Worker. El APK ya está publicado; revisá el error de arriba y volvé a correr: .\publicar.ps1 -SoloWorker'
      return
    }
    [System.IO.File]::WriteAllText($marca, $hash, $utf8)
    Write-Host 'Worker actualizado en Cloudflare.' -ForegroundColor Green
  } finally { Pop-Location }
}

if ($SoloWorker) { Publicar-Worker; return }

if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
  throw 'Falta GitHub CLI. Ejecuta: winget install --id GitHub.cli y luego: gh auth login'
}

# 1) Subir el número de versión
$gradle = Join-Path $PSScriptRoot 'android\app\build.gradle'
$g = [System.IO.File]::ReadAllText($gradle)
$m = [regex]::Match($g, 'versionCode\s+(\d+)')
if (-not $m.Success) { throw 'No encontré versionCode en android\app\build.gradle' }
$code = [int]$m.Groups[1].Value + 1
$name = "1.$code"
$g = [regex]::Replace($g, 'versionCode\s+\d+', "versionCode $code")
$g = [regex]::Replace($g, 'versionName\s+"[^"]*"', "versionName `"$name`"")
[System.IO.File]::WriteAllText($gradle, $g, $utf8)
Write-Host "Versión nueva: $name (código $code)" -ForegroundColor Cyan

# 2) Compilar
npm run build
npx cap sync android
Push-Location (Join-Path $PSScriptRoot 'android')
.\gradlew.bat assembleDebug
if ($LASTEXITCODE -ne 0) { Pop-Location; throw 'Falló la compilación de Gradle' }
Pop-Location

# 3) Publicar en GitHub
$apkPath = Join-Path $PSScriptRoot 'android\app\build\outputs\apk\debug\app-debug.apk'
$notes = Read-Host '¿Qué trae esta versión? (opcional, Enter para omitir)'
if ([string]::IsNullOrWhiteSpace($notes)) { $notes = "Actualización automática v$name para la tóxica" }

Write-Host "=== Alzando el APK a GitHub Releases ===" -ForegroundColor Cyan
gh release create "v$name" $apkPath --title "Actualización v$name" --notes $notes

if ($LASTEXITCODE -ne 0) { throw 'Nderakore, falló la subida a GitHub. Revisá el error de arriba kp.' }

Write-Host ''
Write-Host "¡Coronaste! Publicada la versión v$name en GitHub." -ForegroundColor Green

# 4) Actualizar el Worker de Cloudflare (notificaciones push)
Publicar-Worker
