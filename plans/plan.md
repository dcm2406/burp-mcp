# Burp MCP MVP — Implementation Plan

## Trạng thái

- Status: Implementation xong; verification PASS trên cả Windows và Kali/Linux
- Target gốc: `D:\mbos\burp-mcp`; đã port sang `/home/kali/burp-mcp` (Kali) và verify lại end-to-end
- Repository: Không dùng Git theo yêu cầu
- Phiên bản MVP: `0.1.0`

### Bằng chứng xác minh (Windows, portable toolchain)

- `scripts\build.ps1` exit 0: `gradlew clean test fatJar`, `npm ci`, `tsc`, Vitest, `npm pack`.
- `scripts\test.ps1` exit 0: Java tests + `npm ci` + `npm test`.
- Java: 11 tests, 0 failures (auth + loopback bind, filter/paginate/raw ref, send + repeater, 413 cap, opaque token tampering, token rotation + kill switch, audit redaction, descriptor ACL owner-only + atomic write + delete).
- TypeScript: 21 tests, 5 files, 0 failures (discovery + unsafe URL rejection, bridge client envelope/no-retry/timeout, schema 10 MiB, tool registration + isError mapping, stdio JSON-RPC). Test sources are type-checked via `npm run typecheck:test`.
- `npm audit`: 0 vulnerabilities (dev `vitest` nâng 4.0.15 → 4.1.12 để thoát GHSA-5xrq-8626-4rwp).
- JAR `burp-mcp-extension-0.1.0-all.jar`: 2.48 MB, 0 entry `burp/api/montoya/**`, Jackson bundled, 21 lớp `io.github.burpmcp`.
- Tarball `burp-mcp-server-0.1.0.tgz`: chỉ `dist/`, `package.json`, `README.md` (không lẫn `src`, `test`, `node_modules`).

### Bằng chứng xác minh (Kali/Linux, 2026-10-21)

Port từ Windows sang Kali. Toolchain: JDK 21.0.12.1 (Oracle, trùng bản Windows), Gradle 9.8.2
dùng trực tiếp distribution local trong `.tools` (zip sha256 khớp wrapper), Node 22.23.3 +
npm 10.9.9 portable (system node 24 vi phạm `engines`).

- Build: `gradle clean test fatJar` BUILD SUCCESSFUL; `npm ci` (0 vulnerabilities), `npm run build`,
  `npm test` (build + typecheck:test + vitest), `npm pack` — tất cả exit 0.
- Java: **16 tests, 0 failures, 1 skipped** (skip là `@EnabledOnOs(OS.WINDOWS)` — test ACL Windows-only;
  bù lại bằng test POSIX mới `descriptorIsOwnerOnlyOnPosix` PASS trên Kali). Đây cũng là điểm sửa
  của bản Windows ghi "Java 8 tests" — thực tế 11 (docs/testing.md đã đúng).
- TypeScript: **26 tests, 5 files, 0 failures** (thêm 6 test cho path resolution mới).
- JAR: 2.48 MB, **19 lớp `io.github.burpmcp`** (đếm lại; bảnWindows ghi 21 là sai), 0 entry
  `burp/api/montoya/**`, Jackson bundled. JAR build trên Kali khác sha bản Windows (code thay đổi).
- Bản quality issue portability đã sửa (2 bug thật): (1) `constants.ts` hardcode
  `"burp-mcp\\active-session.json"` → trên Linux tạo file literal có dấu `\`, discovery fail khi không
  set override; (2) Java + TS fallback về `~/AppData/Local` khi `LOCALAPPDATA` không tồn tại →
  giờ theo XDG (`~/.local/share/burp-mcp/`), thứ tự resolution đồng bộ hai bên.
- Thêm `scripts/build.sh`, `scripts/test.sh` (song ngữ với bản `.ps1`).

### Smoke test live trên Burp Suite Professional (Kali)

Burp Suite Professional 2026.9-53998 + extension 0.1.0 (Montoya API 2026.7):

- `burp_status`: connected, đủ capabilities/limits theo contract.
- `burp_proxy_history`: pagination cursor hoạt động, `malformedItemCount: 0`.
- `burp_get_proxy_message`: request/response trả canonical Base64 + sha256.
- Ref bị sửa: bị từ chối `INVALID_REFERENCE` (session-bound đúng).
- `burp_send_http_request` tới target local được phép `127.0.0.1:8888`: HTTP 404 từ server local,
  audit ghi đúng 1 entry cho operation (không retry).
- `burp_send_to_repeater`: tạo tab `smoke-test`, không gửi request.
- Audit log `~/.local/share/burp-mcp/audit/bridge.jsonl`: metadata-only, 0 leak token/header/URL.
- Descriptor `~/.local/share/burp-mcp/active-session.json` quyền `-rw-------` (600) — XDG path +
  POSIX owner-only hoạt động thật (hai fix nói trên được verify live).
- Kill switch / unload / re-enable: **PASS**. Disable → descriptor xóa, tool trả
  `DISCOVERY_NOT_FOUND` (fail closed), audit ghi `BRIDGE_DISABLE`. Reload extension →
  `sessionId` mới (`34f686b0…` → `9aaee927…` → `4552bb27…`), port mới, descriptor `0600`,
  audit ghi `BRIDGE_ENABLE`; ref của session cũ bị từ chối `STALE_REFERENCE` (session-bound
  đúng thiết kế).

**→ Smoke test 8/8 PASS. Dự án đóng được.**

### Không đạt trong MVP (đã biết, chấp nhận)

- `Proxy.history()` vẫn materialize toàn bộ lịch sử (rủi ro đã ghi nhận cho phase SQLite).

### Smoke test live bridge

Runbook đầy đủ ở `docs/smoke-test.md`. `scripts/smoke-test.ps1` đã được validate bằng
một `BridgeServer` thật (temporary harness đã xoá sau khi xong): **18/18 PASS, 0 SKIP**
bao gồm descriptor ACL owner-only, listener chỉ bind `127.0.0.1`, 401/403/400, status
contract, history + ref Base64, ref bị sửa, repeater 202, `/v1/http/send` tới target
local được phép, và audit metadata-only.

Các lỗi tìm ra khi chạy smoke script (đã sửa trong script, không phải production code):
`Encoding.Latin1` không tồn tại trong Windows PowerShell 5.1 (.NET Framework), mảng
1 phần tử bị PowerShell unwrap làm mất `Count`, và listener bị đóng phải FAIL chứ không SKIP.

Còn lại chỉ có bước GUI-only cần người vận hành làm trên Burp Suite Professional:
load JAR, kiểm tra tab Repeater, kill switch, unload, re-enable rotation, và 5 tool qua OpenCode.

## Kết quả bàn giao

1. Burp extension JAR tại `extension/build/libs/burp-mcp-extension-0.1.0-all.jar`.
2. MCP npm package tại `mcp-server/burp-mcp-server-0.1.0.tgz` và executable `burp-mcp`.
3. Java unit/integration tests và TypeScript unit/MCP integration tests chạy thành công.
4. OpenAPI contract, tài liệu cài extension và cấu hình OpenCode V2.
5. Script Windows bootstrap/build/test không yêu cầu Node.js hoặc Gradle cài system-wide.

## Kiến trúc và stack

```text
OpenCode V2
  └─ MCP 2026-07-28 qua stdio
       └─ TypeScript / Node.js 22 / @modelcontextprotocol/server v2
            └─ HTTP JSON + Bearer token
                 └─ 127.0.0.1:<random-port>
                      └─ Java 21 Burp extension / Montoya API 2026.7
```

- Build extension: Gradle 9.8.2 wrapper, Java toolchain 21.
- JSON: Jackson.
- UI: Swing suite tab.
- MCP schema: Zod v4.
- Test: JUnit 5, AssertJ, Vitest và MCP client v2.
- Portable toolchain downloads phải pin version và verify SHA-256 trước khi chạy.

## Giới hạn bảo mật bắt buộc

- Bridge chỉ bind literal `127.0.0.1` trên random port; không bind `localhost`, IPv6 hoặc `0.0.0.0`.
- Tạo token bằng `SecureRandom` 32 bytes, Base64URL; rotate mỗi lần enable/restart.
- Token chỉ nằm trong session descriptor thuộc current Windows user, không xuất hiện trong log, MCP output hoặc UI đầy đủ.
- Có kill switch trong Burp: dừng listener, xóa queue, descriptor và token. Request đã vào `MontoyaApi.http().sendRequest()` có thể không cancel được.
- Không retry tự động action gửi request.
- Không kiểm tra Burp scope theo lựa chọn của người dùng; docs phải cảnh báo chỉ dùng với target được phép kiểm thử.
- Audit log không ghi raw payload, header, token hoặc full URL.
- Mỗi raw input hoặc tổng raw output của một tool call giới hạn 10 MiB decoded. JSON/Base64 envelope có giới hạn riêng đủ chứa 10 MiB raw; output quá giới hạn được truncate và gắn metadata rõ ràng.
- Bridge từ chối CORS/browser origin, Host header sai, content type sai, token sai, queue đầy và payload quá giới hạn.

## Public contract — Bridge API v1

Mọi endpoint yêu cầu `Authorization: Bearer <token>`. Error thống nhất:

```json
{
  "error": {
    "code": "ERROR_CODE",
    "message": "Safe message",
    "requestId": "opaque-id"
  }
}
```

| Endpoint | Chức năng |
|---|---|
| `GET /v1/status` | Phiên bản, session, Burp version, capability và limits |
| `POST /v1/proxy-history/query` | Filter và cursor pagination, mặc định 50/tối đa 200 |
| `GET /v1/proxy-history/{ref}` | Lấy request/response binary-safe bằng Base64 |
| `POST /v1/http/send` | Gửi một raw HTTP request qua Burp và trả response |
| `POST /v1/repeater` | Đưa request vào Repeater, không tự gửi |

### History filter

- `host`, `method`, `statusCodes`, `hasResponse`, `search`, `from`, `to`.
- `search` tối đa 256 ký tự.
- Reference và cursor là opaque, có session binding và HMAC; reference cũ trả `STALE_REFERENCE`.
- Dùng API không deprecated: `finalRequest()`, `httpService()`, `id()`, `time()`.
- Một history item malformed không làm hỏng toàn bộ page.

### Raw request schema

```text
service: { host, port, secure }
request: { encoding: "base64", data }
```

`service` quyết định destination; header `Host` trong raw request được giữ nguyên để phục vụ pentest.

## MCP tools

| Tool | Loại | Hành vi |
|---|---|---|
| `burp_status` | Read | Kiểm tra discovery và bridge status |
| `burp_proxy_history` | Read | Search/filter/paginate Proxy history |
| `burp_get_proxy_message` | Read | Lấy raw request/response theo opaque ref |
| `burp_send_http_request` | Destructive/open-world | Gửi raw request, không retry |
| `burp_send_to_repeater` | Write/non-destructive | Tạo Repeater tab, không gửi request |

- MCP logs chỉ ghi `stderr`; `stdout` dành riêng cho JSON-RPC.
- Tool input không được override bridge URL hoặc token.
- Bridge discovery mặc định qua `%LOCALAPPDATA%\burp-mcp\active-session.json`, có thể override bằng `BURP_MCP_SESSION_FILE`.

## Phases

### Phase 1 — Contracts và toolchain

- Tạo multi-project Gradle, npm package, OpenAPI 3.1 contract và scripts.
- Bootstrap portable Node.js 22.23.3 và Gradle 9.8.2 bằng official checksum.
- Sinh Gradle wrapper có `distributionSha256Sum`.

Gate: Java 21, wrapper, portable Node/npm chạy; contract lint/parse thành công.

### Phase 2 — Java bridge read path

- Tạo `BurpGateway` interface và fake test adapter.
- Implement auth, error mapping, request ID, payload limits và bounded executor.
- Implement status, history query, opaque reference/cursor và raw message retrieval.

Gate: Java read-path unit/integration tests pass trên real loopback server.

### Phase 3 — Java action path, lifecycle và UI

- Implement `sendRequest` và `sendToRepeater` qua Montoya adapter.
- Implement descriptor lifecycle, token rotation, ACL/owner check, audit rotation và unload cleanup.
- Implement Swing tab: connection status, partial session/port, recent audit, enable/kill switch.
- Build fat JAR nhưng không bundle Montoya API.

Gate: action/lifecycle/UI view-model tests pass; JAR structure đúng.

### Phase 4 — TypeScript MCP server

- Implement discovery, typed bridge client, năm MCP tools và error mapping.
- Add executable `burp-mcp`, package metadata, exact lockfile và OpenCode config example.
- Không retry tool có side effect.

Gate: TypeScript unit tests và mock bridge tests pass; stdout sạch.

### Phase 5 — Packaging và verification

- Spawn compiled MCP server qua stdio và test initialize/tools/list/tools/call với MCP client v2.
- Clean build, test, `npm pack`, kiểm tra JAR/tarball contents.
- Hoàn thiện README, architecture, bridge contract, threat model và OpenCode V2 setup.

## Acceptance criteria

- `scripts/build.ps1` tạo JAR, compiled MCP server và npm tarball trên máy hiện tại mà không cài Node/Gradle system-wide.
- Tất cả Java và TypeScript tests pass.
- Năm MCP tools được list/call thành công với mock bridge.
- Có test cho auth, loopback binding, 10 MiB cap, cursor/reference tampering, token rotation, kill switch, audit redaction và no-retry.
- Không đọc hoặc sửa bất kỳ dữ liệu pentest nào bên ngoài `D:\mbos\burp-mcp`.
- Không cần live Burp test để đạt Done; bước cài JAR thủ công được ghi rõ trong README.

## Ngoài phạm vi MVP

- Burp Scanner/active scan.
- Mutation orchestration (Intruder API có sẵn trong Montoya 2026.7, chỉ cần lớp mapping khi làm).
- Installer Windows, remote/team mode và multi-user service.
- Tự động sửa global OpenCode config hoặc cài extension vào Burp.

## SQLite index (phase sau, đã triển khai)

Montoya không có API history metadata-only (`proxy().history()` materialize toàn bộ bytes;
`history(ProxyHistoryFilter)` chỉ đưa predicate về Java), nên mỗi lần query/fetch lặp lại chi phí đó.

- `HistoryIndex` (SQLite, `org.xerial:sqlite-jdbc:3.50.3.0` bundled): chỉ metadata, owner-only,
  xoá theo session tại `<data>/burp-mcp/index/history-<sessionId>.db`.
- TTL refresh mặc định 15s (`burp.mcp.index.ttl.ms`), empty-guard buộc fresh scan khi kết quả rỗng.
- `search` (raw bytes) luôn đi live scan; mọi lỗi index fallback về live scan (cache không bao giờ
  là nguồn sự thật).
- Java: 24 tests (16 cũ + `HistoryIndexTest` 6 + index parity/refresh trong `BridgeServerTest`
  + dispose trong `BridgeRuntimeTest`), 0 failures, 1 skip (ACL Windows-only).
- Fat JAR: 16.8 MB (SQLite native bundled), 0 entry `burp/api/montoya/**`, 24 lớp
  `io.github.burpmcp`.
- Known limitation: Montoya không có event "history entry added" (response handler chỉ fire khi
  intercept), nên freshness bị giới hạn bởi TTL thay vì push.

### Batch API (đã triển khai + verify live)

`POST /v1/http/send-batch` + MCP tool `burp_send_http_batch`: 1..50 request/lượt, concurrency
1..10 (default 4), mỗi item gửi đúng 1 lần, không retry. Validate toàn bộ trước khi gửi (400 →
không gửi gì); results giữ thứ tự input; `summary` (default) chỉ metadata, `full` trả body trong
budget 10 MiB/item + 8 MiB/batch (`omitted: true` khi vượt); per-item `SEND_FAILED` không làm hỏng
batch; audit 1 dòng/batch call. Java 33 tests, TS 31 tests.

Verify live (Burp 2026.9, target `127.0.0.1:8888` được phép): summary 4 request → `succeeded=4`,
không có body; full 2 request → có body + `omitted: false`; audit đúng 1 dòng/batch.

Lưu ý vận hành: extension và MCP server phải nâng **đồng thời** — schema `.strict()` khiến client cũ
từ chối response mới (`BRIDGE_INVALID_RESPONSE`) thay vì parse sai; reload JAR thì phải restart
session OpenCode để spawn lại `dist/cli.js`.

### Collaborator/OAST (đã triển khai + automated verify)

`POST /v1/collaborator/payload` + `POST /v1/collaborator/interactions`, MCP tools
`burp_collaborator_payload` / `burp_collaborator_interactions`. Tái dụng `api.collaborator()`;
tối đa 20 payload/session; `customData` tag phân biệt vị trí tiêm; ref opaque session-bound;
`full` (default) trả DNS/HTTP/SMTP detail + Base64 body, `summary` chỉ metadata; `newCount` chỉ
đếm hit mới so với lần poll trước (seen-set per session). Java 41 tests, TS 35 tests.

Known limitation: payload của session cũ không tra được sau re-enable (session-bound, có chủ
đích - không lưu secret ra disk); âm tính giả nếu Burp không ra được internet tới Collaborator
cloud (phải verify channel bằng `dig +short <payload-domain>` từ chính máy trước khi kết luận).

## Rủi ro đã chấp nhận

- `Proxy.history()` vẫn materialize danh sách đầy đủ; với 10k–100k requests có thể chậm cho đến phase SQLite.
- Cloud model nhận raw traffic khi tool được gọi; MCP local không ngăn dữ liệu gửi tới provider.
- Kill switch không đảm bảo cancel request đang chạy bên trong Burp.
- Chưa có live Burp smoke test trong acceptance criteria của MVP.
