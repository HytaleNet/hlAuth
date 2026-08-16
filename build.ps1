# Builds hlAuth.jar against the local HytaleServer.jar.
# JDBC drivers are downloaded at runtime into the plugin data folder (lib/).
# Usage: powershell -ExecutionPolicy Bypass -File build.ps1 [-ServerJar <path>]

param(
    [string]$ServerJar = "$env:APPDATA\HLauncher\install\release\package\game\latest\Server\HytaleServer.jar"
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$src = Join-Path $root "src\main\java"
$resources = Join-Path $root "src\main\resources"
$out = Join-Path $root "build\classes"
$dist = Join-Path $root "build\libs"
$stage = Join-Path $root "build\package"

if (-not (Test-Path $ServerJar)) {
    Write-Error "HytaleServer.jar not found at '$ServerJar'. Pass -ServerJar <path>."
}

if (Test-Path $out) { Remove-Item -Recurse -Force $out }
if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
New-Item -ItemType Directory -Force -Path $out, $dist, $stage | Out-Null

Write-Host "Compiling..." -ForegroundColor Cyan
$sources = Get-ChildItem -Path $src -Filter *.java -Recurse | ForEach-Object { '"' + ($_.FullName -replace '\\', '/') + '"' }
$sourceList = Join-Path $root "build\sources.txt"
[System.IO.File]::WriteAllLines($sourceList, $sources, (New-Object System.Text.UTF8Encoding($false)))

& javac -encoding UTF-8 -classpath $ServerJar -d $out "@$sourceList"
if ($LASTEXITCODE -ne 0) { Write-Error "Compilation failed." }

Write-Host "Packaging..." -ForegroundColor Cyan
Copy-Item -Path (Join-Path $out '*') -Destination $stage -Recurse -Force
Copy-Item -Path (Join-Path $resources '*') -Destination $stage -Recurse -Force

$jarPath = Join-Path $dist "hlAuth-1.1.0.jar"
if (Test-Path $jarPath) { Remove-Item -Force $jarPath }

& jar --create --file $jarPath -C $stage .
if ($LASTEXITCODE -ne 0) { Write-Error "Packaging failed." }

Write-Host "Done: $jarPath" -ForegroundColor Green
Write-Host "Install: copy the jar into your Hytale server's 'mods' folder."
