# Bridge contract

The normative schema is `contracts/bridge-api.yaml`.

## Authentication

Every request requires:

```http
Authorization: Bearer <session-token>
```

The server authenticates before parsing a large JSON body and compares tokens in constant time. Browser `Origin` requests and non-loopback `Host` headers are rejected.

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/v1/status` | Bridge and capability status |
| `POST` | `/v1/proxy-history/query` | Filtered, cursor-paginated Proxy history |
| `GET` | `/v1/proxy-history/{ref}` | Binary-safe raw exchange retrieval |
| `POST` | `/v1/http/send` | Send exactly one HTTP request through Burp |
| `POST` | `/v1/repeater` | Create a Repeater tab without sending |

## Error behavior

Errors use stable machine-readable codes and never include stack traces:

```json
{
  "error": {
    "code": "STALE_REFERENCE",
    "message": "Reference belongs to another Burp session",
    "requestId": "..."
  }
}
```

Typical status codes are `400`, `401`, `404`, `405`, `410`, `413`, `415`, `429` and `500`.

## Action semantics

- `/v1/http/send` has at-most-once behavior from the MCP client's perspective; the client does not retry.
- `/v1/repeater` only creates a tab. It does not issue the request.
- The explicit `service` object determines the network destination. The raw `Host` header is preserved.
