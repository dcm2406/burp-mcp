package io.github.burpmcp.extension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class BridgeServerTest {
    private static final String TOKEN = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);

    @TempDir
    Path temporaryDirectory;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();
    private FakeBurpGateway gateway;
    private BridgeServer server;

    @BeforeEach
    void start() throws Exception {
        gateway = new FakeBurpGateway();
        AuditLog audit = new AuditLog(temporaryDirectory.resolve("audit.jsonl"), mapper);
        server = new BridgeServer(gateway, mapper, audit, "session-a", TOKEN, new byte[32]);
        server.start();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void requiresBearerAuthenticationAndRejectsBrowserOrigins() throws Exception {
        assertThat(server.address().getAddress().getHostAddress()).isEqualTo("127.0.0.1");
        HttpResponse<String> missing = client.send(request("GET", "/v1/status", null, null),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> wrong = client.send(request("GET", "/v1/status", null, "wrong"),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> allowed = client.send(request("GET", "/v1/status", null, TOKEN),
                HttpResponse.BodyHandlers.ofString());
        HttpRequest browserRequest = HttpRequest.newBuilder(uri("/v1/status"))
                .header("Authorization", "Bearer " + TOKEN)
                .header("Origin", "https://example.test")
                .GET().build();
        HttpResponse<String> browser = client.send(browserRequest, HttpResponse.BodyHandlers.ofString());

        assertThat(missing.statusCode()).isEqualTo(401);
        assertThat(wrong.statusCode()).isEqualTo(401);
        assertThat(allowed.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(allowed.body()).path("sessionId").asText()).isEqualTo("session-a");
        assertThat(browser.statusCode()).isEqualTo(403);
    }

    @Test
    void filtersAndPaginatesHistoryAndReturnsBinaryByOpaqueReference() throws Exception {
        BurpGateway.Service service = new BurpGateway.Service("example.test", 443, true);
        gateway.history.add(new BurpGateway.HistoryMessage(
                1, Instant.parse("2026-01-01T00:00:00Z"), "GET", "/old",
                "https://example.test/old", service, 404, "HTML", false, 8080,
                "GET /old HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1),
                "HTTP/1.1 404 Not Found\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1)));
        gateway.history.add(new BurpGateway.HistoryMessage(
                2, Instant.parse("2026-01-02T00:00:00Z"), "POST", "/new",
                "https://example.test/new", service, 201, "JSON", true, 8080,
                "POST /new HTTP/1.1\r\n\r\nneedle".getBytes(StandardCharsets.ISO_8859_1),
                "HTTP/1.1 201 Created\r\n\r\nok".getBytes(StandardCharsets.ISO_8859_1)));

        String queryBody = """
                {"filter":{"host":"example.test","method":"POST","statusCodes":[201],
                 "hasResponse":true,"search":"needle"},"pageSize":1}
                """;
        HttpResponse<String> queried = client.send(request("POST", "/v1/proxy-history/query", queryBody, TOKEN),
                HttpResponse.BodyHandlers.ofString());
        JsonNode query = mapper.readTree(queried.body());
        String reference = query.path("items").get(0).path("ref").asText();

        HttpResponse<String> fetched = client.send(request("GET", "/v1/proxy-history/" + reference, null, TOKEN),
                HttpResponse.BodyHandlers.ofString());
        JsonNode item = mapper.readTree(fetched.body());
        byte[] decoded = Base64.getDecoder().decode(item.path("request").path("data").asText());

        assertThat(queried.statusCode()).isEqualTo(200);
        assertThat(query.path("returned").asInt()).isEqualTo(1);
        assertThat(query.path("items").get(0).path("url").asText()).isEqualTo("https://example.test/new");
        assertThat(fetched.statusCode()).isEqualTo(200);
        assertThat(new String(decoded, StandardCharsets.ISO_8859_1)).contains("needle");
        assertThat(item.path("request").path("sha256").asText()).matches("[a-f0-9]{64}");
        assertThat(item.path("request").path("returnedByteLength").asInt()).isEqualTo(decoded.length);

        String tampered = reference.substring(0, reference.indexOf('.') + 1)
                + (reference.charAt(reference.indexOf('.') + 1) == 'A' ? 'B' : 'A')
                + reference.substring(reference.indexOf('.') + 2);
        HttpResponse<String> rejected = client.send(request(
                        "GET", "/v1/proxy-history/" + tampered, null, TOKEN),
                HttpResponse.BodyHandlers.ofString());
        assertThat(rejected.statusCode()).isEqualTo(400);
        assertThat(mapper.readTree(rejected.body()).path("error").path("code").asText())
                .isEqualTo("INVALID_REFERENCE");
    }

    @Test
    void sendsRawRequestAndCreatesRepeaterTabWithoutSending() throws Exception {
        byte[] raw = "POST / HTTP/1.1\r\nHost: different.test\r\n\r\nbody".getBytes(StandardCharsets.ISO_8859_1);
        String common = """
                {"service":{"host":"destination.test","port":8443,"secure":true},
                 "request":{"encoding":"base64","data":"%s"}%s}
                """;
        String sendBody = common.formatted(Base64.getEncoder().encodeToString(raw), "");
        String repeaterBody = common.formatted(Base64.getEncoder().encodeToString(raw), "," + "\"tabName\":\"MCP test\"");

        HttpResponse<String> sent = client.send(request("POST", "/v1/http/send", sendBody, TOKEN),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> repeated = client.send(request("POST", "/v1/repeater", repeaterBody, TOKEN),
                HttpResponse.BodyHandlers.ofString());

        assertThat(sent.statusCode()).isEqualTo(200);
        assertThat(repeated.statusCode()).isEqualTo(202);
        assertThat(gateway.sentService.host()).isEqualTo("destination.test");
        assertThat(gateway.sentRequest).isEqualTo(raw);
        assertThat(gateway.repeaterRequest).isEqualTo(raw);
        assertThat(gateway.repeaterTitle).isEqualTo("MCP test");
    }

    @Test
    void servesHistoryQueriesFromTheIndexAndStaysContentIdentical() throws Exception {
        BurpGateway.Service service = new BurpGateway.Service("example.test", 443, true);
        gateway.history.add(new BurpGateway.HistoryMessage(
                1, Instant.parse("2026-01-01T00:00:00Z"), "GET", "/old",
                "https://example.test/old", service, 404, "HTML", false, 8080,
                "GET /old HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1),
                "HTTP/1.1 404 Not Found\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1)));
        gateway.history.add(new BurpGateway.HistoryMessage(
                2, Instant.parse("2026-01-02T00:00:00Z"), "POST", "/new",
                "https://example.test/new", service, 201, "JSON", true, 8080,
                "POST /new HTTP/1.1\r\n\r\nok".getBytes(StandardCharsets.ISO_8859_1),
                "HTTP/1.1 201 Created\r\n\r\nok".getBytes(StandardCharsets.ISO_8859_1)));

        String queryBody = """
                {"filter":{"host":"example.test"},"pageSize":10}
                """;
        HttpResponse<String> live = client.send(request("POST", "/v1/proxy-history/query", queryBody, TOKEN),
                HttpResponse.BodyHandlers.ofString());

        try (HistoryIndex index = new HistoryIndex(
                temporaryDirectory.resolve("index").resolve("history.db"), 60_000)) {
            BridgeServer indexed = new BridgeServer(gateway, mapper,
                    new AuditLog(temporaryDirectory.resolve("indexed-audit.jsonl"), mapper),
                    "session-a", TOKEN, new byte[32], index);
            indexed.start();
            try {
                HttpResponse<String> fromIndex = client.send(indexedRequest(indexed, queryBody),
                        HttpResponse.BodyHandlers.ofString());
                JsonNode indexedResult = mapper.readTree(fromIndex.body());

                assertThat(fromIndex.statusCode()).isEqualTo(200);
                assertThat(indexedResult.path("returned").asInt()).isEqualTo(2);
                assertThat(indexedResult.path("malformedItemCount").asInt()).isEqualTo(0);
                // Content parity with the unindexed bridge: same order, same fields.
                assertThat(stripRefs(mapper.readTree(live.body())))
                        .isEqualTo(stripRefs(indexedResult));

                // A new Burp history entry becomes visible without restarting the bridge.
                gateway.history.add(new BurpGateway.HistoryMessage(
                        3, Instant.parse("2026-01-03T00:00:00Z"), "GET", "/newest",
                        "https://example.test/newest", service, 200, "HTML", true, 8080,
                        "GET /newest HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1),
                        "HTTP/1.1 200 OK\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1)));
                index.refresh(() -> gateway.history);
                HttpResponse<String> afterRefresh = client.send(indexedRequest(indexed, queryBody),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(mapper.readTree(afterRefresh.body()).path("returned").asInt()).isEqualTo(3);
            } finally {
                indexed.close();
            }
        }
    }

    private HttpRequest indexedRequest(BridgeServer bridge, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + bridge.port() + "/v1/proxy-history/query"))
                .header("Authorization", "Bearer " + TOKEN)
                .header("Content-Type", "application/json");
        return builder.POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private static com.fasterxml.jackson.databind.node.ArrayNode stripRefs(JsonNode node) {
        com.fasterxml.jackson.databind.node.ArrayNode items = (com.fasterxml.jackson.databind.node.ArrayNode) node.path("items");
        for (com.fasterxml.jackson.databind.JsonNode item : items) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) item).remove("ref");
        }
        return items;
    }

    @Test
    void rejectsDecodedRawInputAboveTenMiB() throws Exception {
        byte[] tooLarge = new byte[BridgeServer.MAX_RAW_BYTES + 1];
        String body = """
                {"service":{"host":"destination.test","port":443,"secure":true},
                 "request":{"encoding":"base64","data":"%s"}}
                """.formatted(Base64.getEncoder().encodeToString(tooLarge));

        HttpResponse<String> response = client.send(request("POST", "/v1/repeater", body, TOKEN),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(mapper.readTree(response.body()).path("error").path("code").asText())
                .isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(gateway.repeaterRequest).isNull();
    }

    private HttpRequest request(String method, String path, String body, String token) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (body == null) {
            return builder.method(method, HttpRequest.BodyPublishers.noBody()).build();
        }
        return builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.port() + path);
    }
}
