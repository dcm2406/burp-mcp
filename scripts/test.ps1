[CmdletBinding()]
param()

# See scripts/build.ps1: native-tool stderr must not terminate this script;
# failures are detected from $LASTEXITCODE after each call.
$ErrorActionPreference = "Continue"
$Root = Split-Path -Parent $PSScriptRoot
& (Join-Path $PSScriptRoot "bootstrap.ps1")
if (-not $?) { throw "Toolchain bootstrap failed" }

$NodeHome = Join-Path $Root ".tools\node-v22.23.3-win-x64"
$env:PATH = "$NodeHome;$env:PATH"

Push-Location $Root
try {
    & .\gradlew.bat --no-daemon test
    if ($LASTEXITCODE -ne 0) { throw "Java tests failed" }
    Push-Location (Join-Path $Root "mcp-server")
    try {
        & npm.cmd ci
        if ($LASTEXITCODE -ne 0) { throw "npm ci failed" }
        & npm.cmd test
        if ($LASTEXITCODE -ne 0) { throw "TypeScript tests failed" }
    }
    finally { Pop-Location }
}
finally { Pop-Location }
