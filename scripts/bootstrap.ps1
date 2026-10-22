[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$Tools = Join-Path $Root ".tools"
$Downloads = Join-Path $Tools "downloads"

$NodeVersion = "22.23.3"
$NodeArchive = "node-v$NodeVersion-win-x64.zip"
$NodeSha256 = "2b0ff57b049cda1bbcea2240eec20467018713c1efe1f7360c2681859b90ed71"
$NodeUrl = "https://nodejs.org/dist/v$NodeVersion/$NodeArchive"
$NodeHome = Join-Path $Tools "node-v$NodeVersion-win-x64"

$GradleVersion = "9.8.2"
$GradleArchive = "gradle-$GradleVersion-bin.zip"
$GradleSha256 = "21bd5da2de49b642904077f38c0efdc7778e92b3ed0889392dca61b97e389607"
$GradleUrl = "https://services.gradle.org/distributions/$GradleArchive"
$GradleHome = Join-Path $Tools "gradle-$GradleVersion"

function Get-VerifiedArchive {
    param(
        [Parameter(Mandatory)] [string] $Uri,
        [Parameter(Mandatory)] [string] $Destination,
        [Parameter(Mandatory)] [string] $ExpectedSha256
    )

    if (-not (Test-Path $Destination)) {
        $partial = "$Destination.partial"
        Remove-Item $partial -Force -ErrorAction SilentlyContinue
        Invoke-WebRequest -Uri $Uri -OutFile $partial
        Move-Item $partial $Destination
    }

    $actual = (Get-FileHash $Destination -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $ExpectedSha256) {
        throw "Checksum mismatch for $Destination. Expected $ExpectedSha256, got $actual"
    }
}

New-Item -ItemType Directory -Force -Path $Downloads | Out-Null

$nodeZip = Join-Path $Downloads $NodeArchive
Get-VerifiedArchive -Uri $NodeUrl -Destination $nodeZip -ExpectedSha256 $NodeSha256
if (-not (Test-Path (Join-Path $NodeHome "node.exe"))) {
    Expand-Archive -Path $nodeZip -DestinationPath $Tools -Force
}

$gradleZip = Join-Path $Downloads $GradleArchive
Get-VerifiedArchive -Uri $GradleUrl -Destination $gradleZip -ExpectedSha256 $GradleSha256
if (-not (Test-Path (Join-Path $GradleHome "bin\gradle.bat"))) {
    Expand-Archive -Path $gradleZip -DestinationPath $Tools -Force
}

$wrapper = Join-Path $Root "gradlew.bat"
if (-not (Test-Path $wrapper)) {
    & (Join-Path $GradleHome "bin\gradle.bat") --no-daemon wrapper --gradle-version $GradleVersion --distribution-type bin
    if ($LASTEXITCODE -ne 0) { throw "Gradle wrapper generation failed" }
}

$properties = Join-Path $Root "gradle\wrapper\gradle-wrapper.properties"
$content = Get-Content $properties -Raw
if ($content -notmatch "distributionSha256Sum=") {
    Add-Content -Path $properties -Value "distributionSha256Sum=$GradleSha256"
}

Write-Host "Portable toolchain ready."
Write-Host "Node:  $NodeHome"
Write-Host "Gradle: $GradleHome"
