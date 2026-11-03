# Instala el módulo nativo (permisos + actualizaciones) en el proyecto Android.
# Ejecutar UNA vez:  powershell -ExecutionPolicy Bypass -File .\instalar-nativo.ps1
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$utf8 = New-Object System.Text.UTF8Encoding($false)

if (-not (Test-Path (Join-Path $PSScriptRoot 'android'))) { throw 'No existe la carpeta android. Ejecuta primero: npx cap add android' }
$pkg = (Get-Content (Join-Path $PSScriptRoot 'capacitor.config.json') -Raw | ConvertFrom-Json).appId
Write-Host "Paquete: $pkg"

# 1) Archivos Java
$javaDir = Join-Path $PSScriptRoot ('android\app\src\main\java\' + ($pkg -replace '\.', '\'))
New-Item -ItemType Directory -Force $javaDir | Out-Null
foreach ($f in 'ParejaNativePlugin', 'MainActivity') {
  $src = [System.IO.File]::ReadAllText((Join-Path $PSScriptRoot "native\$f.java.txt")).Replace('__PKG__', $pkg)
  [System.IO.File]::WriteAllText((Join-Path $javaDir "$f.java"), $src, $utf8)
  Write-Host "  escrito $f.java"
}

# 2) Carpeta permitida para el instalador de APK
$xmlDir = Join-Path $PSScriptRoot 'android\app\src\main\res\xml'
New-Item -ItemType Directory -Force $xmlDir | Out-Null
$paths = @'
<?xml version="1.0" encoding="utf-8"?>
<paths xmlns:android="http://schemas.android.com/apk/res/android">
    <external-path name="my_images" path="." />
    <cache-path name="my_cache_images" path="." />
</paths>
'@
[System.IO.File]::WriteAllText((Join-Path $xmlDir 'file_paths.xml'), $paths, $utf8)

# 3) Permisos en el AndroidManifest
$mf = Join-Path $PSScriptRoot 'android\app\src\main\AndroidManifest.xml'
$txt = [System.IO.File]::ReadAllText($mf)
$perms = 'INTERNET','ACCESS_COARSE_LOCATION','ACCESS_FINE_LOCATION','ACCESS_BACKGROUND_LOCATION','FOREGROUND_SERVICE','FOREGROUND_SERVICE_LOCATION','POST_NOTIFICATIONS','REQUEST_IGNORE_BATTERY_OPTIMIZATIONS','REQUEST_INSTALL_PACKAGES'
$add = ''
foreach ($p in $perms) {
  if (-not $txt.Contains('android.permission.' + $p + '"')) {
    $add += '    <uses-permission android:name="android.permission.' + $p + '" />' + "`r`n"
  }
}
if ($add) { $txt = $txt.Replace('</manifest>', $add + '</manifest>') }
[System.IO.File]::WriteAllText($mf, $txt, $utf8)
Write-Host "  permisos agregados: $([bool]$add)"

Write-Host ''
Write-Host 'Listo. Ahora ejecuta:  powershell -ExecutionPolicy Bypass -File .\publicar.ps1' -ForegroundColor Green
