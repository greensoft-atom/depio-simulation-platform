# Sets the project version in every POM (the only place it is defined).
# Usage:  powershell -ExecutionPolicy Bypass -File scripts\set-version.ps1 -Version 1.1.0
param([Parameter(Mandatory = $true)][string]$Version)
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$ParentPom = Join-Path $Root "pom.xml"
[xml]$Pom = Get-Content $ParentPom
$Old = $Pom.project.version
$Utf8 = New-Object System.Text.UTF8Encoding($false)

# the parent POM: replace only its own (first) <version> element
$Text = [System.IO.File]::ReadAllText($ParentPom)
$Regex = New-Object System.Text.RegularExpressions.Regex([regex]::Escape("<version>$Old</version>"))
$Text = $Regex.Replace($Text, "<version>$Version</version>", 1)
[System.IO.File]::WriteAllText($ParentPom, $Text, $Utf8)

# every module: the <parent> reference
Get-ChildItem -Path $Root -Directory -Filter "j-redis-*" | ForEach-Object {
    $ModulePom = Join-Path $_.FullName "pom.xml"
    if (Test-Path $ModulePom) {
        $T = [System.IO.File]::ReadAllText($ModulePom)
        $T = $T.Replace("<artifactId>j-redis-parent</artifactId><version>$Old</version>",
                        "<artifactId>j-redis-parent</artifactId><version>$Version</version>")
        [System.IO.File]::WriteAllText($ModulePom, $T, $Utf8)
    }
}
Write-Host "version $Old -> $Version"
