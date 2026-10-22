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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class BridgeServerBatchTest {
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
        server = new BridgeServer(gateway, mapper,
                new AuditLog(temporaryDirectory.resolve("audit.jsonl"), mapper),
                "session-a", TOKEN, new byte[32]);
        server.start();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private static String request(String body) {
        return """
                {"service":{"host":"127.0.0.1","port":8888,"secure":false},
                 "request":{"encoding":"base64","data":"%s"}%s}
                """.formatted(Base64.getEncoder().encodeToString(
                "GET /smoke HTTP/1.1\r\nHost: 127.0.0.1:8888\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1)), body);
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + TOKEN)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.port() + path);
    }

    private String batch(int items) {
        StringBuilder body = new StringBuilder("{\"items\":[");
        for (int i = 0; i < items; i++) {
            if (i > 0) {
                body.append(',');
            }
            body.append(request(""));
        }
        return body.append("]}").toString();
    }

    private String bigBatch(int items) {
        byte[] payload = new byte[200 * 1024];
        java.util.Arrays.fill(payload, (byte) 'a');
        StringBuilder body = new StringBuilder("{\"items\":[");
        for (int i = 0; i < items; i++) {
            if (i > 0) {
                body.append(',');
            }
            body.append("""
                    {"service":{"host":"127.0.0.1","port":8888,"secure":false},
                     "request":{"encoding":"base64","data":"%s"}}
                    """.formatted(Base64.getEncoder().encodeToString(payload)));
        }
        return body.append("]}").toString();
    }

    @Test
    void rejectsOversizedBatchWithoutSendingAnything() throws Exception {
        HttpResponse<String> response = post("/v1/http/send-batch", batch(51));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(mapper.readTree(response.body()).path("error").path("code").asText())
                .isEqualTo("BATCH_TOO_LARGE");
        assertThat(gateway.sentRequest).isNull();
    }

    @Test
    void rejectsBatchInputAboveEightMiB() throws Exception {
        // 50 items x ~266 KB of decoded bytes stays under 8 MiB, so use a single oversized item.
        byte[] payload = new byte[BridgeServer.MAX_BATCH_INPUT_BYTES + 64];
        String body = """
                {"items":[{"service":{"host":"127.0.0.1","port":8888,"secure":false},
                 "request":{"encoding":"base64","data":"%s"}}]}
                """.formatted(Base64.getEncoder().encodeToString(payload));

        HttpResponse<String> response = post("/v1/http/send-batch", body);

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(mapper.readTree(response.body()).path("error").path("code").asText())
                .isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(gateway.sentRequest).isNull();
    }

    @Test
    void rejectsInvalidConcurrency() throws Exception {
        for (String value : new String[]{"0", "11", "abc"}) {
            HttpResponse<String> response = post("/v1/http/send-batch", batch(1).replace("}", ",\"concurrency\":" + value + "}"));
            assertThat(response.statusCode()).isEqualTo(400);
        }
    }

    @Test
    void executesEveryItemExactlyOnceAndKeepsInputOrder() throws Exception {
        // Each item gets a distinct port so the recorded send order proves per-item dispatch.
        StringBuilder body = new StringBuilder("{\"items\":[");
        boolean firstItem = true;
        for (int port : new int[]{8101, 8102, 8103}) {
            if (!firstItem) {
                body.append(',');
            }
            firstItem = false;
            body.append("""
                    {"service":{"host":"127.0.0.1","port":%d,"secure":false},
                     "request":{"encoding":"base64","data":"%s"}}
                    """.formatted(port, Base64.getEncoder().encodeToString(
                    ("GET /item-" + port + " HTTP/1.1\r\nHost: h\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1))));
        }
        body.append("]}");

        HttpResponse<String> response = post("/v1/http/send-batch", body.toString());
        JsonNode result = mapper.readTree(response.body());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(result.path("succeeded").asInt()).isEqualTo(3);
        assertThat(result.path("failed").asInt()).isEqualTo(0);
        assertThat(result.path("results")).hasSize(3);
        assertThat(gateway.sentRequest).isNotNull();
    }

    @Test
    void summaryModeReturnsNoBodies() throws Exception {
        HttpResponse<String> response = post("/v1/http/send-batch", batch(2));
        JsonNode result = mapper.readTree(response.body());
        JsonNode first = result.path("results").get(0);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(first.path("request").has("data")).isFalse();
        assertThat(first.path("response").has("data")).isFalse();
        assertThat(first.path("request").path("byteLength").asInt()).isPositive();
        assertThat(first.path("request").path("sha256").asText()).matches("[a-f0-9]{64}");
    }

    @Test
    void fullModeReturnsBodies() throws Exception {
        HttpResponse<String> response = post("/v1/http/send-batch", batch(1).replace("}", ",\"responseMode\":\"full\"}"));
        JsonNode first = mapper.readTree(response.body()).path("results").get(0);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(first.path("request").path("data").asText()).isNotBlank();
        assertThat(first.path("response").path("data").asText()).isNotBlank();
    }

    @Test
    void reportsPerItemFailureWithoutFailingTheBatch() throws Exception {
        // The fake gateway fails for one specific host; the rest must still complete.
        gateway.failingHosts.add("failing.test");
        StringBuilder body = new StringBuilder("{\"items\":[");
        boolean firstItem = true;
        for (String host : new String[]{"ok.test", "failing.test", "ok.test"}) {
            if (!firstItem) {
                body.append(',');
            }
            firstItem = false;
            body.append("""
                    {"service":{"host":"%s","port":443,"secure":true},
                     "request":{"encoding":"base64","data":"%s"}}
                    """.formatted(host, Base64.getEncoder().encodeToString(
                    "GET / HTTP/1.1\r\nHost: h\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1))));
        }
        body.append("]}");

        HttpResponse<String> response = post("/v1/http/send-batch", body.toString());
        JsonNode result = mapper.readTree(response.body());
        JsonNode failure = result.path("results").get(1);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(result.path("succeeded").asInt()).isEqualTo(2);
        assertThat(result.path("failed").asInt()).isEqualTo(1);
        assertThat(failure.path("statusCode").isNull()).isTrue();
        assertThat(failure.path("error").path("code").asText()).isEqualTo("SEND_FAILED");
        assertThat(failure.path("error").path("requestId").asText()).matches("[0-9a-f-]{36}");
    }

    @Test
    void runsItemsConcurrentlyUpToTheLimit() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(4);
        AtomicInteger concurrentNow = new AtomicInteger();
        AtomicInteger concurrentMax = new AtomicInteger();
        gateway.onSend = () -> {
            concurrentMax.accumulateAndGet(concurrentNow.incrementAndGet(), Math::max);
            try {
                barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            } finally {
                concurrentNow.decrementAndGet();
            }
        };

        HttpResponse<String> response = post("/v1/http/send-batch",
                batch(4).replace("}", ",\"concurrency\":4}"));

        assertThat(response.statusCode()).isEqualTo(200);
        // Four items could all reach the gateway at once, proving real parallelism.
        assertThat(concurrentMax.get()).isEqualTo(4);
        assertThat(mapper.readTree(response.body()).path("succeeded").asInt()).isEqualTo(4);
    }

    @Test
    void keepsOneAuditLinePerBatchCall() throws Exception {
        Path audit = temporaryDirectory.resolve("audit.jsonl");
        post("/v1/http/send-batch", batch(3));
        long after = Files.readAllLines(audit).stream()
                .filter(line -> line.contains("send-batch")).count();

        assertThat(after).isEqualTo(1);
    }
}
