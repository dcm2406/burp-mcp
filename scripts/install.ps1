#Requires -Version 5.1
<#
.SYNOPSIS
  Install a released burp-mcp build (Burp extension JAR + stdio MCP server).

.DESCRIPTION
  Downloads the latest (or requested) GitHub release, verifies SHA-256, installs
  it under %LOCALAPPDATA%\burp-mcp, and registers the server with detected
  OpenCode, Claude Code and Codex CLI clients.

.PARAMETER Version
  Release tag to install, e.g. v0.1.0. Defaults to the latest release.

.PARAMETER Prefix
  Install directory. Defaults to %LOCALAPPDATA%\burp-mcp.

.PARAMETER Local
  Use extension\build\libs and mcp-server\ from this checkout instead of a release.

.PARAMETER NoConfig
  Do not modify agent configuration.

.PARAMETER DryRun
  Print actions without changing anything.

.PARAMETER Uninstall
  Remove installed files and the Claude Code user entry.
#>
[CmdletBinding()]
param(
    [string]$Version = "",
    [string]$Prefix = "",
    [switch]$Local,
    [switch]$NoConfig,
    [switch]$DryRun,
    [switch]$Uninstall
)

$ErrorActionPreference = "Stop"

function Write-Say([string]$Message) {
    Write-Host $Message
}

function Stop-Install([string]$Message) {
    [Console]::Error.WriteLine("error: $Message")
    exit 1
}

function Join-Parts([string]$Base, [string[]]$Parts) {
    $path = $Base
    foreach ($part in $Parts) {
        $path = Join-Path $path $part
    }
    return $path
}

$Repo = $env:BURP_MCP_REPO
if (-not $Repo) { $Repo = "dcm2406/burp-mcp" }

if (-not $Prefix) {
    if ($PSScriptRoot -and (
            (Test-Path -LiteralPath (Join-Path $PSScriptRoot "server")) -or
            (Test-Path -LiteralPath (Join-Path $PSScriptRoot "extension")))) {
        # A copy stored at PREFIX\install.ps1 should upgrade/uninstall that prefix by default.
        $Prefix = $PSScriptRoot
    }
    elseif (-not $env:LOCALAPPDATA) {
        Stop-Install "LOCALAPPDATA is not set; pass -Prefix explicitly."
    }
    else {
        $Prefix = Join-Path $env:LOCALAPPDATA "burp-mcp"
    }
}

if (-not $Prefix -or $Prefix -eq "/" -or $Prefix -eq $env:USERPROFILE -or $Prefix -match '^[A-Za-z]:\\?$') {
    Stop-Install "refusing to use '$Prefix' as the install directory"
}
$Prefix = [System.IO.Path]::GetFullPath($Prefix)

if ($Uninstall) {
    if (Test-Path -LiteralPath $Prefix) {
        if (-not (Test-Path -LiteralPath (Join-Path $Prefix "server")) -and
            -not (Test-Path -LiteralPath (Join-Path $Prefix "extension"))) {
            Stop-Install "refusing to remove '$Prefix': no burp-mcp installation found"
        }
        if ($DryRun) {
            Write-Say "would remove the burp-mcp files from $Prefix"
            exit 0
        }
        foreach ($target in @("server", "extension", "bin")) {
            $path = Join-Path $Prefix $target
            if (Test-Path -LiteralPath $path) {
                Remove-Item -LiteralPath $path -Recurse -Force
            }
        }
        foreach ($target in @("install.ps1", "install.sh")) {
            $path = Join-Path $Prefix $target
            if (Test-Path -LiteralPath $path) {
                Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
            }
        }
        $remaining = @(Get-ChildItem -LiteralPath $Prefix -Force)
        if ($remaining.Count -eq 0) {
            Remove-Item -LiteralPath $Prefix -Force
            Write-Say "removed $Prefix"
        }
        else {
            Write-Say "removed the burp-mcp files from $Prefix"
            Write-Say "left other files there (session descriptor, audit log, index)"
        }
    }
    else {
        Write-Say "nothing to remove at $Prefix"
    }
    $claudeCommand = Get-Command claude -ErrorAction SilentlyContinue
    if ($claudeCommand) {
        $details = (& claude mcp get burp 2>$null | Out-String)
        if ($details -and $details.ToLowerInvariant().Contains($Prefix.ToLowerInvariant())) {
            & claude mcp remove --scope user burp 2>$null | Out-Null
            Write-Say "removed the Claude Code user entry for burp"
        }
        elseif ($details) {
            Write-Say "left the Claude Code entry for burp alone (it does not point to $Prefix)"
        }
    }
    Write-Say "Remove the OpenCode/Codex entry manually if it is still present."
    exit 0
}

$nodeCommand = Get-Command node -ErrorAction SilentlyContinue
if (-not $nodeCommand) {
    Stop-Install "Node.js >= 22.12 is required: https://nodejs.org"
}
$nodePath = $nodeCommand.Source
$nodeVersion = (& node -p "process.versions.node").Trim()
$nodeParts = $nodeVersion.Split(".")
$nodeMajor = [int]$nodeParts[0]
$nodeMinor = [int]$nodeParts[1]
if ($nodeMajor -lt 22 -or ($nodeMajor -eq 22 -and $nodeMinor -lt 12)) {
    Stop-Install "Node.js >= 22.12 is required (found $nodeVersion)"
}

$root = ""
if ($PSScriptRoot) {
    $root = Split-Path -Parent $PSScriptRoot
}

$serverSource = $null
$jarSource = $null
$tag = ""
$versionNumber = ""

if ($Local) {
    if (-not $root) {
        Stop-Install "-Local requires running this script from a checkout"
    }
    $serverSource = Join-Path $root "mcp-server"
    $localCli = Join-Parts $serverSource @("dist", "cli.js")
    if (-not (Test-Path -LiteralPath $localCli)) {
        Stop-Install "local build missing: run .\scripts\build.ps1 first"
    }
    if (-not (Test-Path -LiteralPath (Join-Path $serverSource "node_modules"))) {
        Stop-Install "local node_modules missing: run .\scripts\build.ps1 first"
    }
    $libs = Join-Parts $root @("extension", "build", "libs")
    $jarSource = Get-ChildItem -LiteralPath $libs -Filter "*-all.jar" -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not $jarSource) {
        Stop-Install "local JAR missing: run .\scripts\build.ps1 first"
    }
    $package = Get-Content -LiteralPath (Join-Path $serverSource "package.json") -Raw | ConvertFrom-Json
    $versionNumber = $package.version
    $tag = "local"
}
else {
    if ($Version) {
        $tag = $Version.Trim()
        if (-not $tag.StartsWith("v")) {
            $tag = "v$tag"
        }
    }
    else {
        $tag = ""
        $ghCommand = Get-Command gh -ErrorAction SilentlyContinue
        if ($ghCommand) {
            $tag = (& gh api "repos/$Repo/releases/latest" --jq .tag_name 2>$null | Select-Object -First 1)
        }
        if (-not $tag) {
            try {
                $release = Invoke-RestMethod -UseBasicParsing -Uri "https://api.github.com/repos/$Repo/releases/latest"
                $tag = $release.tag_name
            }
            catch {
                $tag = ""
            }
        }
        if (-not $tag) {
            Stop-Install "cannot resolve the latest release for $Repo (run 'gh auth login', pass -Version, or check the repository name)"
        }
        $tag = $tag.Trim()
    }
    $versionNumber = $tag -replace '^v', ''
}

if ($DryRun) {
    Write-Say "would install burp-mcp $versionNumber"
    Write-Say "  source: $tag"
    Write-Say "  prefix: $Prefix"
    Write-Say "  node:   $nodePath"
    Write-Say "  server: $(Join-Parts $Prefix @('server', 'dist', 'cli.js'))"
    Write-Say "  jar:    $(Join-Parts $Prefix @('extension', 'burp-mcp-extension.jar'))"
    exit 0
}

$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("burp-mcp-install-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

try {
    $extract = Join-Path $tmp "extract"
    $jarFile = $null

    if ($Local) {
        $staged = Join-Path $extract "server"
        New-Item -ItemType Directory -Force -Path $staged | Out-Null
        Copy-Item -Path (Join-Path $serverSource "dist") -Destination (Join-Path $staged "dist") -Recurse -Force
        Copy-Item -LiteralPath (Join-Path $serverSource "package.json") -Destination (Join-Path $staged "package.json") -Force
        $readme = Join-Path $serverSource "README.md"
        if (Test-Path -LiteralPath $readme) {
            Copy-Item -LiteralPath $readme -Destination (Join-Path $staged "README.md") -Force
        }
        $license = Join-Path $root "LICENSE"
        if (Test-Path -LiteralPath $license) {
            Copy-Item -LiteralPath $license -Destination (Join-Path $staged "LICENSE") -Force
        }
        Copy-Item -Path (Join-Path $serverSource "node_modules") -Destination (Join-Path $staged "node_modules") -Recurse -Force
        $binDir = Join-Parts $staged @("node_modules", ".bin")
        if (Test-Path -LiteralPath $binDir) {
            Remove-Item -LiteralPath $binDir -Recurse -Force
        }
        $jarFile = $jarSource.FullName
    }
    else {
        $ghCommand = Get-Command gh -ErrorAction SilentlyContinue
        $downloaded = $false
        if ($ghCommand) {
            & gh release download $tag --repo $Repo --dir $tmp --clobber `
                --pattern "burp-mcp-extension-*.jar" `
                --pattern "burp-mcp-server-*.zip" `
                --pattern "install.ps1" `
                --pattern "SHA256SUMS" | Out-Null
            if ($LASTEXITCODE -eq 0) {
                $downloaded = $true
            }
        }
        if (-not $downloaded) {
            $assets = @(
                "burp-mcp-extension-$versionNumber.jar",
                "burp-mcp-server-$versionNumber.zip",
                "install.ps1",
                "SHA256SUMS"
            )
            foreach ($asset in $assets) {
                $url = "https://github.com/$Repo/releases/download/$tag/$asset"
                try {
                    Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile (Join-Path $tmp $asset)
                }
                catch {
                    Stop-Install "cannot download $asset from $Repo@$tag (for a private repo run 'gh auth login')"
                }
            }
        }

        $sumsPath = Join-Path $tmp "SHA256SUMS"
        if (-not (Test-Path -LiteralPath $sumsPath)) {
            Stop-Install "release is missing SHA256SUMS"
        }
        $verified = 0
        foreach ($line in Get-Content -LiteralPath $sumsPath) {
            if ($line -match '^\s*([0-9a-fA-F]{64})\s+\*?(.+?)\s*$') {
                $expected = $Matches[1].ToLowerInvariant()
                $name = $Matches[2]
                $file = Join-Path $tmp $name
                if (Test-Path -LiteralPath $file) {
                    $actual = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
                    if ($actual -ne $expected) {
                        Stop-Install "checksum mismatch for $name"
                    }
                    $verified++
                }
            }
        }
        if ($verified -lt 3) {
            Stop-Install "SHA256SUMS is missing entries for the release assets"
        }

        $zipPath = Join-Path $tmp "burp-mcp-server-$versionNumber.zip"
        if (-not (Test-Path -LiteralPath $zipPath)) {
            Stop-Install "release is missing burp-mcp-server-$versionNumber.zip"
        }
        Expand-Archive -LiteralPath $zipPath -DestinationPath $extract -Force
        if (-not (Test-Path -LiteralPath (Join-Parts $extract @("server", "dist", "cli.js")))) {
            Stop-Install "server bundle is malformed (missing dist\cli.js)"
        }

        $jarSource = Get-ChildItem -LiteralPath $tmp -Filter "burp-mcp-extension-*.jar" |
            Select-Object -First 1
        if (-not $jarSource) {
            Stop-Install "release is missing the extension JAR"
        }
        $jarFile = $jarSource.FullName
    }

    $serverDir = Join-Path $Prefix "server"
    $extDir = Join-Path $Prefix "extension"
    $binOut = Join-Path $Prefix "bin"
    $cli = Join-Parts $serverDir @("dist", "cli.js")

    New-Item -ItemType Directory -Force -Path $Prefix, $extDir, $binOut | Out-Null
    if (Test-Path -LiteralPath $serverDir) {
        Remove-Item -LiteralPath $serverDir -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $serverDir | Out-Null
    Copy-Item -Path (Join-Parts $extract @("server", "*")) -Destination $serverDir -Recurse -Force
    Set-Content -LiteralPath (Join-Path $serverDir "VERSION") -Value $versionNumber -Encoding ASCII

    Copy-Item -LiteralPath $jarFile -Destination (Join-Path $extDir "burp-mcp-extension-$versionNumber.jar") -Force
    Copy-Item -LiteralPath $jarFile -Destination (Join-Path $extDir "burp-mcp-extension.jar") -Force

    $installerSource = Join-Path $tmp "install.ps1"
    if (-not (Test-Path -LiteralPath $installerSource) -and $PSScriptRoot) {
        $installerSource = Join-Path $PSScriptRoot "install.ps1"
    }
    if (Test-Path -LiteralPath $installerSource) {
        Copy-Item -LiteralPath $installerSource -Destination (Join-Path $Prefix "install.ps1") -Force
    }

    $cmdContent = "@echo off`r`n`"$nodePath`" `"$cli`" %*`r`n"
    Set-Content -LiteralPath (Join-Path $binOut "burp-mcp.cmd") -Value $cmdContent -Encoding ASCII

    $configNotes = @()
    if (-not $NoConfig) {
        $opencodeCommand = Get-Command opencode -ErrorAction SilentlyContinue
        if ($opencodeCommand) {
            & opencode mcp add --global burp -- $nodePath $cli | Out-Null
            if ($LASTEXITCODE -eq 0) {
                $configNotes += "OpenCode (global)"
            }
            else {
                Write-Warning "could not update the OpenCode config automatically"
            }
        }

        $claudeCommand = Get-Command claude -ErrorAction SilentlyContinue
        if ($claudeCommand) {
            & claude mcp remove --scope user burp 2>$null | Out-Null
            & claude mcp add --scope user burp -- $nodePath $cli | Out-Null
            if ($LASTEXITCODE -eq 0) {
                $configNotes += "Claude Code (user)"
            }
            else {
                Write-Warning "could not update the Claude Code config automatically"
            }
        }

        $codexCommand = Get-Command codex -ErrorAction SilentlyContinue
        if ($codexCommand) {
            & codex mcp add --help 2>$null | Out-Null
            if ($LASTEXITCODE -eq 0) {
                & codex mcp add burp -- $nodePath $cli | Out-Null
                if ($LASTEXITCODE -eq 0) {
                    $configNotes += "Codex"
                }
                else {
                    Write-Warning "could not update the Codex config automatically"
                }
            }
        }
    }

    Write-Say ""
    Write-Say "burp-mcp $versionNumber installed"
    Write-Say "  server : $cli"
    Write-Say "  jar    : $(Join-Path $extDir 'burp-mcp-extension.jar')"
    if ($configNotes.Count -gt 0) {
        Write-Say ("  config : " + ($configNotes -join ", "))
    }
    Write-Say ""
    Write-Say "Next:"
    Write-Say "  1. Burp -> Extensions -> Installed -> Add -> Java"
    Write-Say "     select $(Join-Path $extDir 'burp-mcp-extension.jar')"
    Write-Say "  2. Open the Burp MCP tab and enable the bridge."
    Write-Say "  3. Restart your agent session so it spawns the new server."

    if ($NoConfig -or $configNotes.Count -eq 0) {
        Write-Say ""
        Write-Say "Manual registration:"
        Write-Say "  command: $nodePath"
        Write-Say "  args:    [`"$cli`"]"
        Write-Say ""
        Write-Say "  Claude Code: claude mcp add --scope user burp -- `"$nodePath`" `"$cli`""
        Write-Say "  OpenCode:    opencode mcp add --global burp -- `"$nodePath`" `"$cli`""
        Write-Say "  Codex:       codex mcp add burp -- `"$nodePath`" `"$cli`""
    }
}
finally {
    if (Test-Path -LiteralPath $tmp) {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}
