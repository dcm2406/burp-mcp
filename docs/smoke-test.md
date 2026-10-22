# Burp MCP — Manual smoke test

Manual smoke test chạy trên Burp Suite Professional thật. Automated tests chỉ kiểm chứng contract với fake gateway và mock bridge; bước này kiểm chứng tích hợp thực: Montoya API, listener loopback, discovery descriptor, và MCP end-to-end.

## Điều kiện tiên quyết

- Đã build: `extension\build\libs\burp-mcp-extension-0.1.0-all.jar` và `mcp-server\dist\cli.js` (chạy `.\scripts\build.ps1` trước).
- Burp Suite Professional hỗ trợ Montoya API 2026.7.
- Một target bạn **được phép** kiểm thử cho bước gửi request (ví dụ HTTP server local của chính bạn).
- PowerShell chạy với `-ExecutionPolicy Bypass` cho script.

## Bổ sung cho Kali / Linux

Các bước bên dưới viết cho Windows PowerShell; trên Linux/Kali tương đương như sau. Build/test
bằng `./scripts/build.sh` và `./scripts/test.sh` (song ngữ với `build.ps1`/`test.ps1`, dùng
portable toolchain trong `.tools`).

**Descriptor** (bước 2): `~/.local/share/burp-mcp/active-session.json` (tôn trọng `XDG_DATA_HOME`
nếu có). Quyền owner-only được enforce qua POSIX `0600`:

```bash
FILE="${XDG_DATA_HOME:-$HOME/.local/share}/burp-mcp/active-session.json"
ls -l "$FILE"                       # kỳ vọng: -rw------- (600)
python3 -m json.tool "$FILE"        # schemaVersion=1, baseUrl http://127.0.0.1:<port>
```

**Gửi request** (bước 4) — dùng `curl`, token đọc từ descriptor, không in ra log:

```bash
FILE="${XDG_DATA_HOME:-$HOME/.local/share}/burp-mcp/active-session.json"
BASE=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["baseUrl"])' "$FILE")
TOKEN=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["token"])' "$FILE")
REQ=$(printf 'GET /smoke HTTP/1.1\r\nHost: 127.0.0.1:8080\r\nConnection: close\r\n\r\n' | base64 -w0)
curl -sS -X POST "$BASE/v1/http/send" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"service\":{\"host\":\"127.0.0.1\",\"port\":8080,\"secure\":false},\"request\":{\"encoding\":\"base64\",\"data\":\"$REQ\"}}"
```

**Repeater** (bước 5): cùng cách với `POST $BASE/v1/repeater`, kỳ vọng HTTP 202 + tab xuất hiện,
server local **không** nhận thêm request.

**Audit log** (bước 6): `~/.local/share/burp-mcp/audit/bridge.jsonl` (tương đương `%LOCALAPPDATA%`).

```bash
AUDIT="${XDG_DATA_HOME:-$HOME/.local/share}/burp-mcp/audit/bridge.jsonl"
tail -5 "$AUDIT"   # chỉ time/requestId/operation/outcome/durationMs
```

**Kill switch** (bước 8): sau khi Disable bridge trong tab, `test ! -e "$FILE"` phải true và tool
`burp_status` trả `DISCOVERY_NOT_FOUND`.

### Kết quả đã verify trên Kali (2026-10-21)

Burp Suite Professional 2026.9 + extension 0.1.0 (JAR build trên Kali, Montoya API 2026.7):
`burp_status`, `burp_proxy_history` (cursor pagination, `malformedItemCount: 0`),
`burp_get_proxy_message` (sha256 cho request+response), ref bị sửa bị từ chối
(`INVALID_REFERENCE`), descriptor `0600` tại `~/.local/share/burp-mcp/`, audit log metadata-only.

Kill switch/unload/re-enable cũng đã PASS: Disable → descriptor xóa + `DISCOVERY_NOT_FOUND`
(fail closed) + audit `BRIDGE_DISABLE`; reload → `sessionId`/port/token mới và ref của session
cũ bị từ chối `STALE_REFERENCE`. Tổng: **8/8 bước PASS**.

## Bước 1 — Nạp extension

1. Burp → **Extensions → Installed → Add**.
2. Extension type: **Java**.
3. Chọn `extension\build\libs\burp-mcp-extension-0.1.0-all.jar`.
4. Mở tab **Burp MCP**.
5. Kỳ vọng: `Status: enabled on 127.0.0.1:<random-port>`, `Session: xxxxxxxx… (token hidden)`.

Nếu tab báo `Bridge startup failed`, xem **Extensions → Errors/Output** để lấy class lỗi (thường là port hoặc quyền ghi descriptor).

## Bước 2 — Kiểm tra discovery descriptor

```powershell
$file = "$env:LOCALAPPDATA\burp-mcp\active-session.json"
Test-Path $file
(Get-Content $file -Raw) -join "`n" | ConvertFrom-Json | Select-Object schemaVersion, sessionId, baseUrl, extensionVersion
```

Kỳ vọng: tồn tại, `schemaVersion = 1`, `baseUrl` dạng `http://127.0.0.1:<port>`.

Xác nhận quyền chỉ thuộc user hiện tại (Windows ACL):

```powershell
(Get-Acl $file).Access | Format-Table IdentityReference, FileSystemRights, AccessControlType
```

Kỳ vọng: chỉ có user hiện tại (và SYSTEM); không có `Users` hoặc `Everyone`.

## Bước 3 — Kiểm tra bridge contract tự động

Bridge đang enabled (từ bước 1). Chạy:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\smoke-test.ps1
```

Script tự đọc descriptor, không cần nhập token, và in bảng PASS/FAIL. Nếu muốn kiểm tra luôn bước gửi request:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\smoke-test.ps1 `
  -AuthorizedSendHost 127.0.0.1 -AuthorizedSendPort 8080
```

Mặc định script **không** gửi request ra ngoài nếu bạn không truyền `-AuthorizedSendHost`/`-AuthorizedSendPort`.

## Bước 4 — Kiểm tra gửi request thật (target được phép)

1. Tạo local target (ví dụ `python -m http.server 8080` hoặc bất kỳ server local nào bạn kiểm soát).
2. Chạy script Bước 3 với `-AuthorizedSendHost` / `-AuthorizedSendPort`.
3. Hoặc tự gọi:

```powershell
$session = Get-Content "$env:LOCALAPPDATA\burp-mcp\active-session.json" -Raw | ConvertFrom-Json
$body = @{
    service = @{ host = "127.0.0.1"; port = 8080; secure = $false }
    request = @{ encoding = "base64"; data = "R0VUIC8gc21va2UgSFRUUC8xLjENCkhvc3Q6IDEyNy4wLjAuMTo4MDgwDQoNCg==" }
} | ConvertTo-Json -Compress
$response = Invoke-WebRequest -Uri "$($session.baseUrl)/v1/http/send" `
  -Method Post -ContentType "application/json" -Body $body `
  -Headers @{ Authorization = "Bearer $($session.token)" }
```

Kỳ vọng: HTTP 200, JSON có `service`, `request`, `response` với `encoding = base64`, `hasResponse = true`, `statusCode` khớp server local. Xác nhận trong Burp **HTTP history** xuất hiện message do extension tạo (tool source là extension), và server local có log một request duy nhất (không retry).

## Bước 5 — Kiểm tra Repeater không tự gửi

```powershell
$body = @{
    service = @{ host = "127.0.0.1"; port = 8080; secure = $false }
    request = @{ encoding = "base64"; data = "R0VUIC8gcmVwZWF0ZXIgSFRUUC8xLjENCkhvc3Q6IDEyNy4wLjAuMTo4MDgwDQoNCg==" }
    tabName = "smoke-test"
} | ConvertTo-Json -Compress
Invoke-WebRequest -Uri "$($session.baseUrl)/v1/repeater" -Method Post `
  -ContentType "application/json" -Body $body `
  -Headers @{ Authorization = "Bearer $($session.token)" }
```

Kỳ vọng: **HTTP 202**, tab `smoke-test` xuất hiện trong **Repeater**, và server local **không** nhận thêm request nào.

## Bước 6 — Kiểm tra audit log

```powershell
Get-ChildItem "$env:LOCALAPPDATA\burp-mcp\audit\bridge.jsonl" | Select-Object Name, Length
Get-Content "$env:LOCALAPPDATA\burp-mcp\audit\bridge.jsonl" -Tail 5
```

Kỳ vọng: chỉ có `time`, `requestId` (UUID), `operation` (vd. `POST /v1/http/send`), `outcome`, `durationMs`. **Không có** token, header, body, hay full URL.

## Bước 7 — Kiểm tra MCP end-to-end qua OpenCode

1. Copy block MCP server từ `docs\mcp-setup.md` vào config agent, trỏ `command` tới Node + `mcp-server\dist\cli.js`.
2. `opencode mcp list` → `burp` connected.
3. Gọi `burp_status` → trả `burpVersion`, `capabilities`, `limits`.
4. Gọi `burp_proxy_history` → danh sách `ref`.
5. Gọi `burp_get_proxy_message` với một `ref` → raw request/response dạng Base64.
6. Gọi `burp_send_http_request` với target được phép → response.
7. Gọi `burp_send_to_repeater` → tab mới, không gửi.

## Bước 8 — Kiểm tra kill switch và unload

Kill switch:

1. Trong tab **Burp MCP**, nhấn **Disable bridge**.
2. `Test-Path "$env:LOCALAPPDATA\burp-mcp\active-session.json"` → `False`.
3. Gọi lại tool `burp_status` → lỗi `DISCOVERY_NOT_FOUND` (fail closed).

Unload extension:

1. Burp → **Extensions → Installed → Burp MCP → Remove** (hoặc tắt Burp).
2. Kỳ vọng: descriptor vẫn không tồn tại, port không còn listen.

Re-enable:

1. Nhấn **Enable bridge** trong tab (nếu còn) hoặc reload extension.
2. Kỳ vọng: port mới, `sessionId` mới, token mới; ref/cursor cũ trả `STALE_REFERENCE`.

## Kết quả kỳ vọng tổng hợp

| Kiểm tra                         | Kỳ vọng                                               |
| --------------------------------- | ------------------------------------------------------- |
| Load extension                    | Tab hiển thị enabled trên `127.0.0.1:<port>`       |
| Descriptor                        | `schemaVersion=1`, chỉ user hiện tại đọc được |
| `GET /v1/status` không token   | 401 `UNAUTHORIZED`                                    |
| `GET /v1/status` có `Origin` | 403 `ORIGIN_REJECTED`                                 |
| `GET /v1/status` sai `Host`   | 400 `INVALID_HOST`                                    |
| `GET /v1/status` hợp lệ       | 200 + đủ field contract                               |
| Query history + ref               | 200, Base64 decode đúng nội dung                     |
| Ref bị sửa                      | 400 `INVALID_REFERENCE`                               |
| `/v1/repeater`                  | 202, tab tạo, không gửi                              |
| `/v1/http/send`                 | 200, một request duy nhất ở target                   |
| Audit log                         | Metadata only                                           |
| Kill switch                       | Descriptor xóa, tool fail closed                       |
| Unload                            | Listener đóng, descriptor sạch                       |
| Re-enable                         | Session/token mới, ref cũ stale                       |

## Xử lý sự cố

| Hiện tượng                       | Nguyên nhân thường gặp                                                                     |
| ----------------------------------- | ----------------------------------------------------------------------------------------------- |
| `Bridge startup failed` trong tab | Không ghi được `%LOCALAPPDATA%` hoặc port bị chiếm; xem Burp **Errors**          |
| `DISCOVERY_NOT_FOUND` từ MCP     | Bridge chưa enable, hoặc descriptor ở path khác; set `BURP_MCP_SESSION_FILE` cho OpenCode |
| `BRIDGE_AUTH_FAILED`              | Bridge đã re-enable, token mới; gọi lại tool để đọc descriptor mới                    |
| `INVALID_HOST`                    | Gọi bridge bằng hostname khác `127.0.0.1`                                                  |
| Tool trả raw data nhưng JSON lỗi | Phiên bản extension và MCP server lệch; rebuild cả hai                                     |
| Request vừa gửi bị treo          | Kill switch không cancel request đã vào Burp (rủi ro đã biết)                           |
