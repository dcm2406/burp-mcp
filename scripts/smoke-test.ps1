[CmdletBinding()]
param(
    # Defaults to $env:BURP_MCP_SESSION_FILE or the standard descriptor location.
    [string]$SessionFile,
    # Sending an HTTP request is skipped unless an authorized target is supplied.
    [string]$AuthorizedSendHost = "",
    [int]$AuthorizedSendPort = 0
)

# Native tools write to stderr; mirror the other scripts and key failures off
# $LASTEXITCODE so stderr noise never terminates this run.
$ErrorActionPreference = "Continue"

$results = New-Object System.Collections.ArrayList

function Add-Result {
    param([string]$Name, [string]$Status, [string]$Detail)
    [void]$results.Add([pscustomobject]@{ Name = $Name; Status = $Status; Detail = $Detail })
    $color = switch ($Status) {
        "PASS" { "Green" }
        "FAIL" { "Red" }
        "SKIP" { "Yellow" }
        default { "Gray" }
    }
    Write-Host ("[{0,-4}] {1}" -f $Status, $Name) -ForegroundColor $color
    if ($Detail) { Write-Host ("        " + $Detail) -ForegroundColor DarkGray }
}

function Resolve-SessionFile {
    if ($SessionFile) { return $SessionFile }
    if ($env:BURP_MCP_SESSION_FILE) { return $env:BURP_MCP_SESSION_FILE }
    if ($env:LOCALAPPDATA) { return (Join-Path $env:LOCALAPPDATA "burp-mcp\active-session.json") }
    return (Join-Path $env:USERPROFILE "AppData\Local\burp-mcp\active-session.json")
}

function Get-Field {
    param($Object, [string]$Name)
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { return $null }
    return $property.Value
}

# curl wrapper: returns a parsed response without exposing the token in output.
function Invoke-Bridge {
    param(
        [string]$Method,
        [string]$Path,
        [string]$Token,
        [hashtable]$Headers,
        [string]$Body
    )
    $arguments = @("-s", "-S", "--max-time", "15", "-o", "$env:TEMP\burp-smoke-body.json",
                   "-w", "%{http_code}", "-X", $Method)
    foreach ($header in $Headers.GetEnumerator()) {
        $arguments += @("-H", ("{0}: {1}" -f $header.Key, $header.Value))
    }
    if ($Token) { $arguments += @("-H", ("Authorization: Bearer {0}" -f $Token)) }
    if ($Body) {
        $bodyFile = "$env:TEMP\burp-smoke-request.json"
        [System.IO.File]::WriteAllText($bodyFile, $Body)
        $arguments += @("-H", "Content-Type: application/json", "--data-binary", "@$bodyFile")
    }
    $arguments += $Path

    $bodyFile = "$env:TEMP\burp-smoke-body.json"
    Remove-Item $bodyFile -ErrorAction SilentlyContinue
    $status = & curl.exe @arguments 2>$null
    $raw = ""
    if (Test-Path $bodyFile) {
        $raw = [System.IO.File]::ReadAllText($bodyFile)
    }
    [pscustomobject]@{ Status = [int]($status -replace "[^0-9]", ""); Body = $raw }
}

function Test-JsonField {
    param($Node, [string]$Name)
    if ($null -eq $Node) { return $false }
    $property = $Node.PSObject.Properties[$Name]
    return ($null -ne $property -and $null -ne $property.Value)
}

function Get-ErrorCode {
    param([string]$Json)
    try { return (Get-Field (ConvertFrom-Json $Json).error "code") } catch { return $null }
}

Write-Host ""
Write-Host "Burp MCP live bridge smoke test" -ForegroundColor Cyan
Write-Host "===============================" -ForegroundColor Cyan

# --- 1. Discovery descriptor -------------------------------------------------
$file = Resolve-SessionFile
if (-not (Test-Path $file)) {
    Add-Result "descriptor present" "FAIL" "Not found: $file. Load the extension and enable the bridge first."
    Write-Host ""
    Write-Host "1 test failed." -ForegroundColor Red
    exit 1
}
Add-Result "descriptor present" "PASS" "Found at $file"

$descriptor = $null
try { $descriptor = Get-Content $file -Raw | ConvertFrom-Json } catch { }
if ($null -eq $descriptor) {
    Add-Result "descriptor JSON" "FAIL" "Descriptor is not valid JSON"
    Write-Host ""
    Write-Host "Descriptor could not be parsed, stopping." -ForegroundColor Red
    exit 1
}

$schemaVersion = Get-Field $descriptor "schemaVersion"
if ($schemaVersion -eq 1) {
    Add-Result "descriptor schemaVersion" "PASS" "schemaVersion=1"
} else {
    Add-Result "descriptor schemaVersion" "FAIL" "Expected 1, got $schemaVersion. Extension and MCP server are out of sync."
}

$baseUrl = Get-Field $descriptor "baseUrl"
$token = Get-Field $descriptor "token"
$sessionId = Get-Field $descriptor "sessionId"

if ($baseUrl -match "^http://127\.0\.0\.1:\d+$") {
    Add-Result "descriptor loopback baseUrl" "PASS" ("host is literal 127.0.0.1")
} else {
    Add-Result "descriptor loopback baseUrl" "FAIL" "Expected http://127.0.0.1:<port>, got $baseUrl"
}
if ($token -and $token.Length -ge 16) {
    Add-Result "descriptor token shape" "PASS" "Bearer token present (value hidden)"
} else {
    Add-Result "descriptor token shape" "FAIL" "Token missing or too short"
}

# --- Descriptor permissions --------------------------------------------------
try {
    $acl = Get-Acl $file
    $worldReadable = $acl.Access | Where-Object {
        ($_.IdentityReference -match "Everyone|Authenticated Users|BUILTIN\\Users|S-1-1-0") -and
        ($_.FileSystemRights -match "Read") -and
        ($_.AccessControlType -eq "Allow")
    }
    if ($worldReadable) {
        Add-Result "descriptor ACL" "FAIL" "Descriptor is readable by a group other than the current user"
    } else {
        Add-Result "descriptor ACL" "PASS" "No broad read access granted"
    }
} catch {
    Add-Result "descriptor ACL" "SKIP" "Could not inspect ACL: $($_.Exception.Message)"
}

if (-not ($baseUrl -match "^http://127\.0\.0\.1:\d+$") -or -not $token) {
    Write-Host ""
    Write-Host "Descriptor is not usable, stopping before bridge calls." -ForegroundColor Red
    exit 1
}

$port = [int]($baseUrl -replace "^http://127\.0\.0\.1:", "")

# --- 2. Listener binding -----------------------------------------------------
# Distinguish "cmdlet exists" from "nothing is listening": an absent listener is a FAIL, not a SKIP.
$netAvailable = $null -ne (Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue)
$netstatAvailable = $null -ne (Get-Command netstat.exe -ErrorAction SilentlyContinue)
if ($netAvailable) {
    try {
        $all = @(Get-NetTCPConnection -LocalPort $port -ErrorAction Stop)
        $listeners = @($all | Where-Object { $_.State -eq "Listen" })
        if ($listeners.Count -eq 0) {
            Add-Result "loopback-only listener" "FAIL" "No listener on port $port. Is the bridge still enabled?"
        } else {
            $loopback = @($listeners | Where-Object { $_.LocalAddress -eq "127.0.0.1" })
            if ($loopback.Count -eq $listeners.Count) {
                Add-Result "loopback-only listener" "PASS" "Port $port listens on 127.0.0.1 only"
            } else {
                Add-Result "loopback-only listener" "FAIL" "Port $port is bound to $($listeners.LocalAddress -join ","); it must be 127.0.0.1 only"
            }
        }
    } catch {
        Add-Result "loopback-only listener" "FAIL" "Could not inspect the listener on port ${port}: $($_.Exception.Message)"
    }
} elseif ($netstatAvailable) {
    $netstatOutput = (& netstat.exe -ano -p TCP) -join "`n"
    $bound = @($netstatOutput -split "`n" | Where-Object { $_ -match ":$port\s" -and $_ -match "\sLISTENING\s" })
    $nonLoopback = @($bound | Where-Object { $_ -notmatch "127\.0\.0\.1:$port" })
    if ($bound.Count -eq 0) {
        Add-Result "loopback-only listener" "FAIL" "No listener on port $port. Is the bridge still enabled?"
    } elseif ($nonLoopback.Count -gt 0) {
        Add-Result "loopback-only listener" "FAIL" "Port $port is also bound outside 127.0.0.1"
    } else {
        Add-Result "loopback-only listener" "PASS" "Port $port listens on 127.0.0.1 only (netstat)"
    }
} else {
    Add-Result "loopback-only listener" "SKIP" "Neither Get-NetTCPConnection nor netstat is available"
}
$noAuth = Invoke-Bridge -Method "GET" -Path "$baseUrl/v1/status" -Headers @{ "Host" = "127.0.0.1:$port" }
if ($noAuth.Status -eq 401 -and (Get-ErrorCode $noAuth.Body) -eq "UNAUTHORIZED") {
    Add-Result "rejects missing bearer token" "PASS" "401 UNAUTHORIZED"
} else {
    Add-Result "rejects missing bearer token" "FAIL" "Expected 401 UNAUTHORIZED, got $($noAuth.Status): $($noAuth.Body)"
}

$wrongToken = Invoke-Bridge -Method "GET" -Path "$baseUrl/v1/status" -Token ("X" * 43) -Headers @{ "Host" = "127.0.0.1:$port" }
if ($wrongToken.Status -eq 401) {
    Add-Result "rejects wrong bearer token" "PASS" "401"
} else {
    Add-Result "rejects wrong bearer token" "FAIL" "Expected 401, got $($wrongToken.Status)"
}

$browserOrigin = Invoke-Bridge -Method "GET" -Path "$baseUrl/v1/status" -Token $token -Headers @{
    "Host"   = "127.0.0.1:$port"
    "Origin" = "http://evil.example"
}
if ($browserOrigin.Status -eq 403 -and (Get-ErrorCode $browserOrigin.Body) -eq "ORIGIN_REJECTED") {
    Add-Result "rejects browser origin" "PASS" "403 ORIGIN_REJECTED"
} else {
    Add-Result "rejects browser origin" "FAIL" "Expected 403 ORIGIN_REJECTED, got $($browserOrigin.Status)"
}

$wrongHost = Invoke-Bridge -Method "GET" -Path "$baseUrl/v1/status" -Token $token -Headers @{ "Host" = "evil.example" }
if ($wrongHost.Status -eq 400 -and (Get-ErrorCode $wrongHost.Body) -eq "INVALID_HOST") {
    Add-Result "rejects non-loopback Host" "PASS" "400 INVALID_HOST"
} else {
    Add-Result "rejects non-loopback Host" "FAIL" "Expected 400 INVALID_HOST, got $($wrongHost.Status)"
}

# --- 4. Status ---------------------------------------------------------------
$status = Invoke-Bridge -Method "GET" -Path "$baseUrl/v1/status" -Token $token -Headers @{ "Host" = "127.0.0.1:$port" }
$statusOk = $false
$statusDetail = "HTTP $($status.Status)"
if ($status.Status -eq 200) {
    try {
        $statusJson = ConvertFrom-Json $status.Body
        $missing = @()
        foreach ($field in @("connected", "sessionId", "extensionVersion", "burpVersion", "startedAt", "capabilities", "limits")) {
            if (-not (Test-JsonField $statusJson $field)) { $missing += $field }
        }
        if ($missing.Count -gt 0) {
            $statusDetail = "200 but missing fields: $($missing -join ', ')"
        } elseif ((Get-Field $statusJson "sessionId") -ne $sessionId) {
            $statusDetail = "200 but sessionId does not match the descriptor"
        } else {
            $statusOk = $true
            $statusDetail = "200 OK, Burp version $((Get-Field $statusJson 'burpVersion')), sessionId matches descriptor"
        }
    } catch {
        $statusDetail = "200 but body is not valid JSON"
    }
}
Add-Result "GET /v1/status contract" $(if ($statusOk) { "PASS" } else { "FAIL" }) $statusDetail

if ($statusOk -and $status.Body -match [regex]::Escape($token)) {
    Add-Result "token never leaves the bridge" "FAIL" "Bearer token appeared in a bridge response"
} elseif ($statusOk) {
    Add-Result "token never leaves the bridge" "PASS" "Not present in status response"
}

# --- 5. Proxy history --------------------------------------------------------
$historyBody = @{ pageSize = 10 } | ConvertTo-Json -Compress
$history = Invoke-Bridge -Method "POST" -Path "$baseUrl/v1/proxy-history/query" -Token $token -Body $historyBody -Headers @{ "Host" = "127.0.0.1:$port" }
$reference = $null
if ($history.Status -eq 200) {
    try {
        $historyJson = ConvertFrom-Json $history.Body
        $items = Get-Field $historyJson "items"
        if ($null -eq $items) {
            Add-Result "proxy history query" "FAIL" "200 but response has no items array"
        } elseif ($items.Count -eq 0) {
            Add-Result "proxy history query" "PASS" "200 OK with an empty page; send some traffic through Burp Proxy to exercise this fully"
        } else {
            $reference = Get-Field ($items[0]) "ref"
            Add-Result "proxy history query" "PASS" "200 OK, $(@($items).Count) item(s), opaque ref returned"
        }
    } catch {
        Add-Result "proxy history query" "FAIL" "200 but body is not valid JSON"
    }
} else {
    Add-Result "proxy history query" "FAIL" "Expected 200, got $($history.Status): $($history.Body)"
}

if ($reference) {
    $first = Invoke-Bridge -Method "GET" -Path "$baseUrl/v1/proxy-history/$reference" -Token $token -Headers @{ "Host" = "127.0.0.1:$port" }
    if ($first.Status -eq 200) {
        try {
            $messageJson = ConvertFrom-Json $first.Body
            $requestPart = Get-Field $messageJson "request"
            $encoding = Get-Field $requestPart "encoding"
            $data = Get-Field $requestPart "data"
            if ($encoding -ne "base64" -or -not $data) {
                Add-Result "get proxy message" "FAIL" "200 but request is not Base64 raw data"
            } else {
                $bytes = [Convert]::FromBase64String($data)
                $latin1 = [System.Text.Encoding]::GetEncoding("ISO-8859-1")
                $text = $latin1.GetString($bytes)
                if ($text -match "^[A-Z]{3,7} ") {
                    Add-Result "get proxy message" "PASS" "200 OK, raw request decodes to an HTTP request line"
                } else {
                    Add-Result "get proxy message" "PASS" "200 OK, Base64 decoded but content is not a recognisable request line"
                }
            }
        } catch {
            Add-Result "get proxy message" "FAIL" "200 but body could not be parsed as Base64 contract data"
        }
    } else {
        Add-Result "get proxy message" "FAIL" "Expected 200, got $($first.Status): $($first.Body)"
    }

    $dot = $reference.IndexOf(".")
    $tampered = $reference
    if ($dot -ge 0 -and $dot -lt $reference.Length - 1) {
        $position = $dot + 1
        $replacement = if ($reference[$position] -eq "A") { "B" } else { "A" }
        $tampered = $reference.Substring(0, $position) + $replacement + $reference.Substring($position + 1)
    }
    $rejected = Invoke-Bridge -Method "GET" -Path "$baseUrl/v1/proxy-history/$tampered" -Token $token -Headers @{ "Host" = "127.0.0.1:$port" }
    if ($rejected.Status -eq 400 -and (Get-ErrorCode $rejected.Body) -eq "INVALID_REFERENCE") {
        Add-Result "rejects tampered ref" "PASS" "400 INVALID_REFERENCE"
    } else {
        Add-Result "rejects tampered ref" "FAIL" "Expected 400 INVALID_REFERENCE, got $($rejected.Status)"
    }
}

# --- 6. Repeater (creates a tab, never sends) --------------------------------
$raw = [Convert]::ToBase64String([System.Text.Encoding]::ASCII.GetBytes("GET /smoke HTTP/1.1`r`nHost: 127.0.0.1`r`n`r`n"))
$repeaterBody = @{
    service = @{ host = "127.0.0.1"; port = $port; secure = $false }
    request = @{ encoding = "base64"; data = $raw }
    tabName = "smoke-test"
} | ConvertTo-Json -Compress
$repeater = Invoke-Bridge -Method "POST" -Path "$baseUrl/v1/repeater" -Token $token -Body $repeaterBody -Headers @{ "Host" = "127.0.0.1:$port" }
if ($repeater.Status -eq 202) {
    try {
        $repeaterJson = ConvertFrom-Json $repeater.Body
        if ((Get-Field $repeaterJson "accepted") -eq $true) {
            Add-Result "repeater tab creation" "PASS" "202 accepted:true. Confirm the tab appears in Burp Repeater and that no request was sent."
        } else {
            Add-Result "repeater tab creation" "FAIL" "202 but accepted is not true"
        }
    } catch {
        Add-Result "repeater tab creation" "FAIL" "202 but body is not valid JSON"
    }
} else {
    Add-Result "repeater tab creation" "FAIL" "Expected 202, got $($repeater.Status): $($repeater.Body)"
}

# --- 7. Optional HTTP send ---------------------------------------------------
if ($AuthorizedSendHost -and $AuthorizedSendPort -gt 0) {
    $sendRaw = [Convert]::ToBase64String(
        [System.Text.Encoding]::ASCII.GetBytes(
            "GET /burp-mcp-smoke HTTP/1.1`r`nHost: ${AuthorizedSendHost}`r`n`r`n"))
    $sendBody = @{
        service = @{ host = $AuthorizedSendHost; port = $AuthorizedSendPort; secure = $false }
        request = @{ encoding = "base64"; data = $sendRaw }
    } | ConvertTo-Json -Compress
    $sent = Invoke-Bridge -Method "POST" -Path "$baseUrl/v1/http/send" -Token $token -Body $sendBody -Headers @{ "Host" = "127.0.0.1:$port" }
    if ($sent.Status -eq 200) {
        try {
            $sentJson = ConvertFrom-Json $sent.Body
            Add-Result "send one HTTP request" "PASS" "200, statusCode=$(Get-Field $sentJson 'statusCode'), response present=$([bool](Get-Field $sentJson 'response'))"
        } catch {
            Add-Result "send one HTTP request" "FAIL" "200 but body is not valid JSON"
        }
    } else {
        Add-Result "send one HTTP request" "FAIL" "Expected 200, got $($sent.Status): $($sent.Body)"
    }
} else {
    Add-Result "send one HTTP request" "SKIP" "No -AuthorizedSendHost/-AuthorizedSendPort supplied; no request was sent"
}

# --- 8. Audit log ------------------------------------------------------------
$auditPath = Join-Path (Split-Path $file -Parent) "audit\bridge.jsonl"
if (Test-Path $auditPath) {
    $auditContent = [System.IO.File]::ReadAllText($auditPath)
    $leaks = @()
    if ($auditContent -match [regex]::Escape($token)) { $leaks += "bearer token" }
    if ($auditContent -match "Authorization|Bearer ") { $leaks += "authorization header" }
    if ($auditContent -match "[A-Za-z0-9+/]{200,}={0,2}") { $leaks += "large Base64 payload" }
    if ($auditContent -match "https?://(?!127\.0\.0\.1)") { $leaks += "non-loopback URL" }
    if ($leaks.Count -gt 0) {
        Add-Result "audit log redaction" "FAIL" "Audit log contains: $($leaks -join ', ')"
    } else {
        Add-Result "audit log redaction" "PASS" "Metadata-only records confirmed"
    }
} else {
    Add-Result "audit log redaction" "SKIP" "No audit file yet at $auditPath"
}

# --- Summary -----------------------------------------------------------------
Write-Host ""
$passed = @($results | Where-Object { $_.Status -eq "PASS" }).Count
$failed = @($results | Where-Object { $_.Status -eq "FAIL" }).Count
$skipped = @($results | Where-Object { $_.Status -eq "SKIP" }).Count
Write-Host ("Summary: {0} passed, {1} failed, {2} skipped" -f $passed, $failed, $skipped) -ForegroundColor Cyan

Remove-Item "$env:TEMP\burp-smoke-body.json" -ErrorAction SilentlyContinue
Remove-Item "$env:TEMP\burp-smoke-request.json" -ErrorAction SilentlyContinue

if ($failed -gt 0) {
    Write-Host "Live bridge smoke test FAILED." -ForegroundColor Red
    exit 1
}
Write-Host "Live bridge smoke test passed." -ForegroundColor Green
exit 0
