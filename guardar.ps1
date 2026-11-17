param([string]$msg = "Actualización $(Get-Date -Format 'yyyy-MM-dd HH:mm')")
Set-Location $PSScriptRoot
git add -A
if (-not (git status --porcelain)) { Write-Host "Nada nuevo que guardar."; exit 0 }
git commit -m $msg
git push origin master
if ($LASTEXITCODE -ne 0) { Write-Host "No se pudo subir a GitHub." -ForegroundColor Red; exit 1 }
Write-Host "Guardado en GitHub." -ForegroundColor Green
