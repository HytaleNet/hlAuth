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
$deps = Join-Path $root "build\deps"
$stage = Join-Path $root "build\package"

if (-not (Test-Path $ServerJar)) {
    Write-Error "HytaleServer.jar not found at '$ServerJar'. Pass -ServerJar <path>."
}

function Get-MavenJar([string]$Group, [string]$Artifact, [string]$Version) {
    New-Item -ItemType Directory -Force -Path $deps | Out-Null
    $jarFile = Join-Path $deps "$Artifact-$Version.jar"
    if (Test-Path $jarFile) {
        return $jarFile
    }
    $groupPath = $Group.Replace('.', '/')
    $url = "https://repo1.maven.org/maven2/$groupPath/$Artifact/$Version/$Artifact-$Version.jar"
    Write-Host "Downloading $Artifact $Version..." -ForegroundColor Cyan
    Invoke-WebRequest -Uri $url -OutFile $jarFile -UseBasicParsing
    return $jarFile
}

$h2Jar = Get-MavenJar "com.h2database" "h2" "2.3.232"
$mysqlJar = Get-MavenJar "com.mysql" "mysql-connector-j" "8.4.0"

if (Test-Path $out) { Remove-Item -Recurse -Force $out }
if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
New-Item -ItemType Directory -Force -Path $out, $dist, $stage | Out-Null

Write-Host "Compiling..." -ForegroundColor Cyan
$sources = Get-ChildItem -Path $src -Filter *.java -Recurse | ForEach-Object { '"' + ($_.FullName -replace '\\', '/') + '"' }
$sourceList = Join-Path $root "build\sources.txt"
[System.IO.File]::WriteAllLines($sourceList, $sources, (New-Object System.Text.UTF8Encoding($false)))

$cp = "$ServerJar;$h2Jar;$mysqlJar"
& javac -encoding UTF-8 -classpath $cp -d $out "@$sourceList"
if ($LASTEXITCODE -ne 0) { Write-Error "Compilation failed." }

Write-Host "Packaging..." -ForegroundColor Cyan
Copy-Item -Path (Join-Path $out '*') -Destination $stage -Recurse -Force
Copy-Item -Path (Join-Path $resources '*') -Destination $stage -Recurse -Force

function Expand-DepIntoStage([string]$JarPath) {
    $tmp = Join-Path $root ("build\dep-" + [IO.Path]::GetFileNameWithoutExtension($JarPath))
    if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    Push-Location $tmp
    try {
        & jar xf $JarPath
    } finally {
        Pop-Location
    }
    Get-ChildItem -Path $tmp -Recurse -File | ForEach-Object {
        $rel = $_.FullName.Substring($tmp.Length).TrimStart('\', '/')
        $name = $_.Name
        if ($rel -ieq "META-INF\MANIFEST.MF" -or $rel -ieq "META-INF/MANIFEST.MF") { return }
        if ($name -match '\.(SF|DSA|RSA)$') { return }
        if ($name -eq "module-info.class") { return }
        if ($rel -replace '\\','/' -ieq "META-INF/services/java.sql.Driver") { return }
        $dest = Join-Path $stage $rel
        $dir = Split-Path $dest -Parent
        if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
        Copy-Item $_.FullName $dest -Force
    }
    Remove-Item -Recurse -Force $tmp
}

Expand-DepIntoStage $h2Jar
Expand-DepIntoStage $mysqlJar

# Keep our merged JDBC Driver SPI
$spiSrc = Join-Path $resources "META-INF\services\java.sql.Driver"
$spiDst = Join-Path $stage "META-INF\services\java.sql.Driver"
New-Item -ItemType Directory -Force -Path (Split-Path $spiDst) | Out-Null
Copy-Item $spiSrc $spiDst -Force

$jarPath = Join-Path $dist "hlAuth-1.0.1.jar"
if (Test-Path $jarPath) { Remove-Item -Force $jarPath }

& jar --create --file $jarPath -C $stage .
if ($LASTEXITCODE -ne 0) { Write-Error "Packaging failed." }

Write-Host "Done: $jarPath" -ForegroundColor Green
Write-Host "Install: copy the jar into your Hytale server's 'mods' folder."
