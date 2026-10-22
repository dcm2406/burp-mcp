[CmdletBinding()]
param()

# Native tools (npm, Gradle) write diagnostics to stderr. Under "Stop" those
# writes become terminating errors in Windows PowerShell 5.1, so failures are
# detected from $LASTEXITCODE after each call instead of the preference.
$ErrorActionPreference = "Continue"
$Root = Split-Path -Parent $PSScriptRoot
& (Join-Path $PSScriptRoot "bootstrap.ps1")
if (-not $?) { throw "Toolchain bootstrap failed" }

$NodeHome = Join-Path $Root ".tools\node-v22.23.3-win-x64"
$env:PATH = "$NodeHome;$env:PATH"

Push-Location $Root
try {
    & .\gradlew.bat --no-daemon clean test fatJar
    if ($LASTEXITCODE -ne 0) { throw "Java build failed" }

    Push-Location (Join-Path $Root "mcp-server")
    try {
        & npm.cmd ci
        if ($LASTEXITCODE -ne 0) { throw "npm ci failed" }
        & npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw "TypeScript build failed" }
        & npm.cmd test
        if ($LASTEXITCODE -ne 0) { throw "TypeScript tests failed" }
        & npm.cmd pack
        if ($LASTEXITCODE -ne 0) { throw "npm pack failed" }
    }
    finally { Pop-Location }
}
finally { Pop-Location }
