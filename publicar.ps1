# Compila una versión nueva y la sube a GitHub Releases
# Ejecutar:  powershell -ExecutionPolicy Bypass -File .\publicar.ps1
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$utf8 = New-Object System.Text.UTF8Encoding($false)

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