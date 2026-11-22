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
foreach ($f in 'ParejaNativePlugin', 'MainActivity', 'TrackService', 'BootReceiver', 'ParejaWidget', 'ParejaWidgetMapa', 'ParejaWidgetCorazon', 'ParejaWidgetMini') {
  $src = [System.IO.File]::ReadAllText((Join-Path $PSScriptRoot "native\$f.java.txt")).Replace('__PKG__', $pkg)
  [System.IO.File]::WriteAllText((Join-Path $javaDir "$f.java"), $src, $utf8)
  Write-Host "  escrito $f.java"
}

# 1b) Recursos del widget (diseño, tamaño, fondo)
$resSrc = Join-Path $PSScriptRoot 'native\res'
if (Test-Path $resSrc) {
  Copy-Item (Join-Path $resSrc '*') (Join-Path $PSScriptRoot 'android\app\src\main\res') -Recurse -Force
  Write-Host '  recursos del widget copiados'
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
$perms = 'INTERNET','ACCESS_COARSE_LOCATION','ACCESS_FINE_LOCATION','ACCESS_BACKGROUND_LOCATION','FOREGROUND_SERVICE','FOREGROUND_SERVICE_LOCATION','POST_NOTIFICATIONS','REQUEST_IGNORE_BATTERY_OPTIMIZATIONS','REQUEST_INSTALL_PACKAGES','RECEIVE_BOOT_COMPLETED','RECORD_AUDIO','MODIFY_AUDIO_SETTINGS','CAMERA','READ_MEDIA_IMAGES'
$add = ''
foreach ($p in $perms) {
  if (-not $txt.Contains('android.permission.' + $p + '"')) {
    $add += '    <uses-permission android:name="android.permission.' + $p + '" />' + "`r`n"
  }
}
if ($add) { $txt = $txt.Replace('</manifest>', $add + '</manifest>') }

# Servicio de ubicacion + arranque automatico al encender el celular
if (-not $txt.Contains('.TrackService')) {
  $block = @'
        <service android:name=".TrackService" android:exported="false" android:foregroundServiceType="location" />
        <receiver android:name=".BootReceiver" android:exported="true" android:enabled="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
            </intent-filter>
        </receiver>
'@
  $txt = $txt.Replace('</application>', $block + "`n    </application>")
  Write-Host '  servicio TrackService y BootReceiver agregados al manifest'
}
if (-not $txt.Contains('.ParejaWidget')) {
  $wblock = @'
        <receiver android:name=".ParejaWidget" android:exported="true" android:label="Pareja GPS">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
            </intent-filter>
            <meta-data android:name="android.appwidget.provider" android:resource="@xml/pareja_widget_info" />
        </receiver>
'@
  $txt = $txt.Replace('</application>', $wblock + "`n    </application>")
  Write-Host '  widget agregado al manifest'
}
# nombres claros en la lista de widgets del celular
$txt = $txt.Replace('android:name=".ParejaWidget" android:exported="true" android:label="Pareja GPS"', 'android:name=".ParejaWidget" android:exported="true" android:label="Pareja · Tarjeta"')
$extra = @(
  @{ n = 'ParejaWidgetMapa';    l = 'Pareja · Mapa';    r = 'pareja_widget_mapa_info' },
  @{ n = 'ParejaWidgetCorazon'; l = 'Pareja · Corazón'; r = 'pareja_widget_corazon_info' },
  @{ n = 'ParejaWidgetMini';    l = 'Pareja · Mini';    r = 'pareja_widget_mini_info' }
)
foreach ($w in $extra) {
  if (-not $txt.Contains('.' + $w.n)) {
    $blk = '        <receiver android:name=".' + $w.n + '" android:exported="true" android:label="' + $w.l + '">' + "`n" +
           '            <intent-filter>' + "`n" +
           '                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />' + "`n" +
           '            </intent-filter>' + "`n" +
           '            <meta-data android:name="android.appwidget.provider" android:resource="@xml/' + $w.r + '" />' + "`n" +
           '        </receiver>'
    $txt = $txt.Replace('</application>', $blk + "`n    </application>")
    Write-Host ('  widget ' + $w.l + ' agregado al manifest')
  }
}
[System.IO.File]::WriteAllText($mf, $txt, $utf8)
Write-Host "  permisos agregados: $([bool]$add)"

Write-Host ''
Write-Host 'Listo. Ahora ejecuta:  powershell -ExecutionPolicy Bypass -File .\publicar.ps1' -ForegroundColor Green
