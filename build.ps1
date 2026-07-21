# Builds hlAuth.jar against the local HytaleServer.jar.
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

if (-not (Test-Path $ServerJar)) {
    Write-Error "HytaleServer.jar not found at '$ServerJar'. Pass -ServerJar <path>."
}

if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force -Path $out, $dist | Out-Null

Write-Host "Compiling..." -ForegroundColor Cyan
# Quote paths (they may contain spaces); write without BOM for javac @argfile
$sources = Get-ChildItem -Path $src -Filter *.java -Recurse | ForEach-Object { '"' + ($_.FullName -replace '\\', '/') + '"' }
$sourceList = Join-Path $root "build\sources.txt"
[System.IO.File]::WriteAllLines($sourceList, $sources, (New-Object System.Text.UTF8Encoding($false)))

& javac -encoding UTF-8 -classpath $ServerJar -d $out "@$sourceList"
if ($LASTEXITCODE -ne 0) { Write-Error "Compilation failed." }

Write-Host "Packaging..." -ForegroundColor Cyan
$jarPath = Join-Path $dist "hlAuth-1.0.0.jar"
if (Test-Path $jarPath) { Remove-Item -Force $jarPath }

& jar --create --file $jarPath -C $out . -C $resources .
if ($LASTEXITCODE -ne 0) { Write-Error "Packaging failed." }

Write-Host "Done: $jarPath" -ForegroundColor Green
Write-Host "Install: copy the jar into your Hytale server's 'mods' folder."
