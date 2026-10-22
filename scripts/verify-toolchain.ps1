[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
& (Join-Path $PSScriptRoot "bootstrap.ps1")

$Node = Join-Path $Root ".tools\node-v22.23.3-win-x64\node.exe"
$Npm = Join-Path $Root ".tools\node-v22.23.3-win-x64\npm.cmd"
& java -version
& $Node --version
& $Npm --version
& (Join-Path $Root "gradlew.bat") --version --no-daemon
