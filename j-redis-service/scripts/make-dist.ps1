# Builds the project offline and assembles the distribution on Windows:
#   target\dist\j-redis-<version>\   and   target\dist\j-redis-<version>.zip
#
# Usage (from the project directory, in PowerShell or cmd):
#   powershell -ExecutionPolicy Bypass -File scripts\make-dist.ps1 -MavenRepo C:\java21-offline\repository
#   powershell -ExecutionPolicy Bypass -File scripts\make-dist.ps1 -SkipBuild
param(
    [switch]$SkipBuild,
    [string]$MavenRepo = ""
)
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
[xml]$Pom = Get-Content (Join-Path $Root "pom.xml")
$Version = $Pom.project.version

if (-not $SkipBuild) {
    $MvnArgs = @("-B", "-o")
    if ($MavenRepo) { $MvnArgs += "-Dmaven.repo.local=$MavenRepo" }
    $MvnArgs += @("clean", "install")
    Push-Location $Root
    try {
        & mvn @MvnArgs
        if ($LASTEXITCODE -ne 0) { throw "Maven build failed (exit code $LASTEXITCODE)" }
    } finally {
        Pop-Location
    }
}

$Dist = Join-Path $Root "target\dist"
$Out = Join-Path $Dist "j-redis-$Version"
$Zip = Join-Path $Dist "j-redis-$Version.zip"
if (Test-Path $Out) { Remove-Item -Recurse -Force $Out }
if (Test-Path $Zip) { Remove-Item -Force $Zip }
New-Item -ItemType Directory -Force -Path (Join-Path $Out "lib") | Out-Null
Copy-Item -Recurse -Force (Join-Path $Root "dist\*") $Out
foreach ($m in "server", "cli", "tools", "examples") {
    Copy-Item (Join-Path $Root "j-redis-$m\target\j-redis-$m-$Version-all.jar") (Join-Path $Out "lib\j-redis-$m.jar")
}
Compress-Archive -Path $Out -DestinationPath $Zip
Write-Host "distribution: $Out"
Get-ChildItem $Dist
