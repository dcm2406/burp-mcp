package io.github.burpmcp.extension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BridgeRuntimeTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void killSwitchDeletesDescriptorAndNextEnableRotatesToken() throws Exception {
        Path descriptor = temporaryDirectory.resolve("active-session.json");
        ObjectMapper mapper = new ObjectMapper();
        BridgeRuntime runtime = new BridgeRuntime(new FakeBurpGateway(), mapper, descriptor);
        try {
            runtime.enable();
            JsonNode first = mapper.readTree(descriptor.toFile());
            String firstToken = first.path("token").asText();
            int firstPort = runtime.snapshot().port();

            runtime.disable();
            assertThat(runtime.snapshot().enabled()).isFalse();
            assertThat(Files.exists(descriptor)).isFalse();

            runtime.enable();
            JsonNode second = mapper.readTree(descriptor.toFile());
            assertThat(second.path("token").asText()).isNotEqualTo(firstToken);
            assertThat(second.path("token").asText()).hasSize(43);
            assertThat(runtime.snapshot().port()).isPositive();
            assertThat(firstPort).isPositive();
        } finally {
            runtime.close();
        }
    }

    @Test
    void disableDisposesTheHistoryIndexFile() throws Exception {
        Path descriptor = temporaryDirectory.resolve("active-session.json");
        ObjectMapper mapper = new ObjectMapper();
        Path index = temporaryDirectory.resolve("index").resolve("history-test.db");
        try (HistoryIndex historyIndex = new HistoryIndex(index, 60_000)) {
            historyIndex.refresh(() -> List.of());
            assertThat(index).exists();
        } finally {
            java.nio.file.Files.deleteIfExists(descriptor);
        }
        assertThat(index).doesNotExist();
    }

    @Test
    void auditContainsMetadataOnly() throws Exception {
        Path auditPath = temporaryDirectory.resolve("audit.jsonl");
        AuditLog audit = new AuditLog(auditPath, new ObjectMapper());
        audit.record("request-id", "POST /v1/http/send", "OK", 12);

        String content = Files.readString(auditPath, StandardCharsets.UTF_8);
        assertThat(content).contains("POST /v1/http/send", "durationMs", "request-id");
        assertThat(content).doesNotContain("Authorization", "Bearer", "rawRequest", "https://target.test/path");
    }
}
