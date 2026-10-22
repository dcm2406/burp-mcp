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

class CollaboratorServerTest {
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

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + TOKEN)
                .header("Content-Type", "application/json");
        if (body == null) {
            return client.send(builder.method("POST", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
        }
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.port() + path);
    }

    @Test
    void createsPayloadWithTagAndRoundTripsItsRef() throws Exception {
        HttpResponse<String> created = post("/v1/collaborator/payload",
                "{\"customData\":\"sqli12\"}");

        assertThat(created.statusCode()).isEqualTo(200);
        JsonNode payload = mapper.readTree(created.body());
        assertThat(payload.path("payload").asText()).startsWith("payload1id");
        assertThat(payload.path("customData").asText()).isEqualTo("sqli12");
        assertThat(payload.path("server").asText()).isEqualTo("burpcollaborator.net");
        assertThat(payload.path("payloadId").asText()).isEqualTo("payload1id");

        // The returned ref must resolve against the same bridge session.
        String ref = payload.path("ref").asText();
        gateway.collaboratorInteractions.add(new BurpGateway.CollaboratorInteraction(
                "payload1id0001", "HTTP", Instant.parse("2026-10-21T10:00:00Z"), "10.0.0.9", 40000,
                "sqli12", null, null, null, "GET", "/", "GET / HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1),
                "HTTP/1.1 200 OK\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), null));

        HttpResponse<String> interactions = post("/v1/collaborator/interactions",
                "{\"ref\":\"" + ref + "\"}");
        JsonNode result = mapper.readTree(interactions.body());
        assertThat(interactions.statusCode()).isEqualTo(200);
        assertThat(result.path("total").asInt()).isEqualTo(1);
        assertThat(result.path("newCount").asInt()).isEqualTo(1);
        assertThat(result.path("interactions").get(0).path("customData").asText())
                .isEqualTo("sqli12");
        assertThat(result.path("interactions").get(0).path("http").path("request").path("data").asText())
                .isNotBlank();
    }

    @Test
    void reportsEveryReturnedInteractionAsNew() throws Exception {
        // Montoya hands each interaction over exactly once, so a poll only ever returns hits
        // that arrived since the previous poll; newCount therefore always equals total.
        String ref = mapper.readTree(post("/v1/collaborator/payload", "{}").body()).path("ref").asText();
        gateway.collaboratorInteractions.add(interaction("payload1id0001"));

        HttpResponse<String> first = post("/v1/collaborator/interactions", "{\"ref\":\"" + ref + "\"}");
        JsonNode result = mapper.readTree(first.body());
        assertThat(result.path("total").asInt()).isEqualTo(1);
        assertThat(result.path("newCount").asInt()).isEqualTo(1);

        // The fake gateway keeps reporting; Burp itself would drop fetched interactions.
        HttpResponse<String> again = post("/v1/collaborator/interactions", "{\"ref\":\"" + ref + "\"}");
        assertThat(mapper.readTree(again.body()).path("newCount").asInt()).isEqualTo(1);
    }

    @Test
    void summaryModeOmitsBodiesWhileFullKeepsThem() throws Exception {
        String ref = mapper.readTree(post("/v1/collaborator/payload", "{}").body()).path("ref").asText();
        gateway.collaboratorInteractions.add(interaction("payload1id0001"));

        JsonNode summary = mapper.readTree(post(
                "/v1/collaborator/interactions", "{\"ref\":\"" + ref + "\",\"mode\":\"summary\"}").body());
        JsonNode summaryHttp = summary.path("interactions").get(0).path("http");
        assertThat(summaryHttp.path("request").has("data")).isFalse();
        assertThat(summaryHttp.path("request").path("byteLength").asInt()).isPositive();

        JsonNode full = mapper.readTree(post(
                "/v1/collaborator/interactions", "{\"ref\":\"" + ref + "\",\"mode\":\"full\"}").body());
        assertThat(full.path("interactions").get(0).path("http").path("request").path("data").asText())
                .isNotBlank();
    }

    @Test
    void pollsEveryInteractionWhenNoPayloadIsGiven() throws Exception {
        gateway.collaboratorInteractions.add(interaction("payload1id0001"));
        gateway.collaboratorInteractions.add(interaction("payload2id0001"));

        JsonNode result = mapper.readTree(post("/v1/collaborator/interactions", "{}").body());
        assertThat(result.path("total").asInt()).isEqualTo(2);
    }

    @Test
    void acceptsTheRawPayloadStringAsTheCorrelationKey() throws Exception {
        JsonNode created = mapper.readTree(post("/v1/collaborator/payload", "{}").body());
        String payload = created.path("payload").asText();
        gateway.collaboratorInteractions.add(interaction("payload1id0001"));

        JsonNode result = mapper.readTree(post("/v1/collaborator/interactions",
                "{\"payload\":\"" + payload + "\"}").body());
        assertThat(result.path("total").asInt()).isEqualTo(1);
    }

    @Test
    void rejectsInvalidCustomDataAndBadRefsWithoutSending() throws Exception {
        for (String customData : new String[]{"\"a-b\"", "\"a_b\"", "\"a.b\"", "\"sqli:login\"", "\"" + "x".repeat(17) + "\"", "\"\""}) {
            HttpResponse<String> rejected = post("/v1/collaborator/payload",
                    "{\"customData\":" + customData + "}");
            assertThat(rejected.statusCode()).isEqualTo(400);
        }
        assertThat(gateway.collaboratorPayloads).isEmpty();

        HttpResponse<String> tampered = post("/v1/collaborator/interactions", "{\"ref\":\"not-a-ref\"}");
        assertThat(tampered.statusCode()).isEqualTo(400);
        assertThat(mapper.readTree(tampered.body()).path("error").path("code").asText())
                .isEqualTo("INVALID_REFERENCE");
    }

    @Test
    void decodesTheDnsQueryNameFromWireFormat() throws Exception {
        byte[] wire = new byte[12 + 26];
        byte[] name = "\u00074lqmt4i\u0007oastify\u0003com\u0000".getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(name, 0, wire, 12, name.length);
        gateway.collaboratorInteractions.add(new BurpGateway.CollaboratorInteraction(
                "payload1id0001", "DNS", Instant.parse("2026-10-21T10:00:00Z"), "10.0.0.9", 33192,
                null, "A", "4lqmt4i.oastify.com", wire, null, null, null, null, null));

        JsonNode result = mapper.readTree(post("/v1/collaborator/interactions", "{}").body());
        JsonNode dns = result.path("interactions").get(0).path("dns");

        assertThat(dns.path("queryType").asText()).isEqualTo("A");
        assertThat(dns.path("domain").asText()).isEqualTo("4lqmt4i.oastify.com");
    }

    @Test
    void capsPayloadGenerationPerSession() throws Exception {
        for (int i = 1; i <= 20; i++) {
            assertThat(post("/v1/collaborator/payload", "{}").statusCode()).isEqualTo(200);
        }
        HttpResponse<String> overflow = post("/v1/collaborator/payload", "{}");
        assertThat(overflow.statusCode()).isEqualTo(400);
        assertThat(mapper.readTree(overflow.body()).path("error").path("code").asText())
                .isEqualTo("COLLABORATOR_LIMIT");
        assertThat(gateway.collaboratorPayloads).hasSize(20);
    }

    @Test
    void reportsInteractionsOfAnotherSessionAsStale() throws Exception {
        BridgeServer other = new BridgeServer(gateway, mapper,
                new AuditLog(temporaryDirectory.resolve("other-audit.jsonl"), mapper),
                "session-b", TOKEN, new byte[32]);
        other.start();
        String foreignRef;
        try {
            // Mint a ref under the other session by generating a payload there.
            HttpResponse<String> created = client.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + other.port() + "/v1/collaborator/payload"))
                    .header("Authorization", "Bearer " + TOKEN)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            foreignRef = mapper.readTree(created.body()).path("ref").asText();
        } finally {
            other.close();
        }

        HttpResponse<String> rejected = post("/v1/collaborator/interactions", "{\"ref\":\"" + foreignRef + "\"}");
        assertThat(rejected.statusCode()).isEqualTo(410);
        assertThat(mapper.readTree(rejected.body()).path("error").path("code").asText())
                .isEqualTo("STALE_REFERENCE");
    }

    private static BurpGateway.CollaboratorInteraction interaction(String id) {
        return new BurpGateway.CollaboratorInteraction(
                id, "HTTP", Instant.parse("2026-10-21T10:00:00Z"), "10.0.0.9", 40000,
                null, null, null, null, "GET", "/",
                "GET / HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1),
                "HTTP/1.1 200 OK\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), null);
    }
}
