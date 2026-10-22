# Batch HTTP API — user stories & spec

Phase sau MVP. Contract: `contracts/bridge-api.yaml` (`/v1/http/send-batch`).

## User stories

1. **Verify nhiều biến thể một lỗ hổng trong một lượt.** Với tư cách agent, tôi gửi 20 biến thể
   IDOR (`user_id` 1..20) cho một endpoint và nhận metadata (statusCode, response size, sha256)
   để tự phân biệt response "bình thường" vs "rò rỉ dữ liệu", mà không cần 20 tool call riêng lẻ.
2. **So nhanh hành vi theo phương thức/method tampering.** Với tư cách agent, tôi gửi cùng một
   request qua GET/POST/PUT/DELETE/PATCH trong một batch để phát hiện endpoint không kiểm tra
   method đúng cách.
3. **Kiểm tra authentication/authorization ở quy mô.** Với tư cách agent, tôi gửi một bộ request
   thiếu token / token sai / token hết hạn / token user khác và so sánh nhanh kết quả.
4. **Tiết kiệm vòng lặp agent.** Với tư cách agent, tôi muốn một tool call cho toàn bộ bộ test
   để tránh độ trễ N lần gọi tuần tự qua MCP.
5. **Giữ kiểm soát an toàn.** Với tư cách operator, tôi muốn giới hạn cứng số request tối đa,
   tổng bytes tối đa, mức song song tối đa, và mỗi sub-request chỉ gửi đúng một lần (không retry)
   để batch không biến thành scanner vô hạn.

## Nguyên tắc thiết kế

- **Backward compatible**: endpoint `POST /v1/http/send` (single) không đổi.
- **Validate trước, gửi sau**: một item sai (schema, base64 non-canonical, vượt budget) → 400 cho
  toàn batch, **không gửi request nào**. Audit phản ánh đúng điều đó.
- **Per-item atomic, no retry**: mỗi sub-request gửi đúng 1 lần qua `BurpGateway.send`. Lỗi của một
  item không huỷ các item khác.
- **Giữ thứ tự**: `results[i]` ứng với `items[i]`.
- **Bounded concurrency**: thực thi trên executor riêng + semaphore `concurrency` (1..10, default 4);
  không giành thread với các bridge request thường.
- **Metadata first**: mặc định `summary` (không có body). `full` mới trả body, chịu budget 10 MiB/item
  và 8 MiB/batch (vượt → `omitted: true`).
- **Audit ghi 1 dòng/batch call**: `operation = POST /v1/http/send-batch`, `outcome ∈
  {OK, PARTIAL, REJECTED}`; count chi tiết nằm trong response, không spam log.

## Giới hạn (guardrails)

| Tham số | Giá trị | Vi phạm |
|---|---|---|
| Số items | 1..50 | 400 |
| Tổng decoded input | ≤ 8 MiB | 413 |
| Mỗi decoded request | ≤ 10 MiB | 413 |
| concurrency | 1..10 | 400 |
| Tổng response body (full mode) | ≤ 8 MiB | item vượt bị `omitted: true` |

## Request / Response (từ contract)

- Request: `{ items: SendRequest[], concurrency?: 1..10, responseMode?: "summary"|"full" }`
- Response: `{ results: BatchSendItemResult[], succeeded: int, failed: int }`
- `BatchSendItemResult`: `{ statusCode, request{byteLength,sha256,data?}, response{byteLength,sha256,data?,omitted} | null, hasResponse, truncated, omitted, durationMs, error?{code,requestId} }`

## HTTP status

- `200`: batch hoàn tất (có thể có item lỗi, xem `failed`).
- `400`: input sai (validate trước khi gửi bất cứ gì).
- `413`: vượt budget input.
- `503`: queue quá tải (`QUEUE_FULL`, giống pattern hiện tại).

## MCP tool

- Tên: `burp_send_http_batch`.
- Input zod strict khớp `BatchSendRequest` (đặc biệt: base64 canonical validator O(n) hiện có,
  `maxItems: 50`, `concurrency` 1..10, `responseMode` enum).
- Output: trả `results`/`succeeded`/`failed` cho model; `summary` mode không trả body.

## Tests

Java (`BridgeServerBatchTest`, hoặc mở rộng `BridgeServerTest`):
1. 51 items → 400, `gateway.sentRequest == null` (không gửi gì).
2. Tổng decoded > 8 MiB → 413, không gửi gì.
3. `concurrency = 0` và `11` → 400.
4. Batch 3 items hợp lệ → 200, `results` đúng thứ tự input, summary không có `data`.
5. Partial: gateway ném lỗi cho 1 host → 200, `failed = 1`, entry có `error.code` + `requestId`.
6. Song song thật: gateway block trên barrier → `concurrency = 4` chạy song song được;
   `concurrency = 1` chạy tuần tự (thứ tự gửi khớp input).
7. `full` mode: `data` hiện diện, `truncated`/`omitted` đúng budget.
8. Audit: đúng 1 dòng cho batch call.

TypeScript: schema strict tests (maxItems, concurrency bounds, responseMode enum, base64 canonical),
tool registration test (tồn tại `burp_send_http_batch` + description không hứa hẹn retry).

## Rủi ro đã biết / ngoài phạm vi v1

- Không có per-request timeout: độ dài batch = item chậm nhất (timeout MCP ở tầng opencode).
- Không có global rate limit trên bridge (chỉ có bound concurrency trong batch).
- `search`/history filtering không nằm trong scope của phase này.
