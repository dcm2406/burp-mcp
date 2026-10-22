package io.github.burpmcp.extension;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class BridgeRuntime implements AutoCloseable {
    record Snapshot(
            boolean enabled,
            Integer port,
            String sessionPrefix,
            Path descriptorPath,
            String lastError,
            List<AuditLog.Event> recentAudit) { }

    private final BurpGateway gateway;
    private final ObjectMapper mapper;
    private final SessionDescriptorStore descriptorStore;
    private final AuditLog audit;
    private final SecureRandom random = new SecureRandom();

    private BridgeServer server;
    private HistoryIndex historyIndex;
    private String sessionId;
    private String lastError;

    BridgeRuntime(BurpGateway gateway, ObjectMapper mapper, Path descriptorPath) {
        this.gateway = gateway;
        this.mapper = mapper;
        this.descriptorStore = new SessionDescriptorStore(descriptorPath, mapper);
        this.audit = new AuditLog(descriptorPath.toAbsolutePath().normalize().getParent()
                .resolve("audit").resolve("bridge.jsonl"), mapper);
    }

    synchronized void enable() {
        if (server != null) {
            return;
        }
        String nextSessionId = UUID.randomUUID().toString();
        byte[] tokenBytes = randomBytes(32);
        byte[] hmacKey = randomBytes(32);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        BridgeServer next = null;
        HistoryIndex nextIndex = null;
        try {
            nextIndex = HistoryIndex.open(indexPath(nextSessionId));
            next = new BridgeServer(gateway, mapper, audit, nextSessionId, token, hmacKey, nextIndex);
            next.start();
            Map<String, Object> descriptor = new LinkedHashMap<>();
            descriptor.put("schemaVersion", 1);
            descriptor.put("sessionId", nextSessionId);
            descriptor.put("baseUrl", "http://127.0.0.1:" + next.port());
            descriptor.put("token", token);
            descriptor.put("pid", ProcessHandle.current().pid());
            descriptor.put("createdAt", Instant.now().toString());
            descriptor.put("extensionVersion", "0.1.0");
            descriptorStore.write(descriptor);
            server = next;
            historyIndex = nextIndex;
            sessionId = nextSessionId;
            lastError = null;
            audit.record("lifecycle", "BRIDGE_ENABLE", "OK", 0);
        } catch (IOException | SQLException | RuntimeException exception) {
            if (next != null) {
                next.close();
            }
            if (nextIndex != null) {
                nextIndex.closeAndDelete();
            }
            descriptorStore.delete();
            server = null;
            sessionId = null;
            lastError = "Bridge startup failed: " + exception.getClass().getSimpleName();
            audit.record("lifecycle", "BRIDGE_ENABLE", "FAILED", 0);
        } finally {
            java.util.Arrays.fill(tokenBytes, (byte) 0);
            java.util.Arrays.fill(hmacKey, (byte) 0);
        }
    }

    synchronized void disable() {
        if (server != null) {
            server.close();
            server = null;
        }
        HistoryIndex index = historyIndex;
        historyIndex = null;
        if (index != null) {
            // Dispose the metadata cache with the session: no history data outlives the bridge.
            index.closeAndDelete();
        }
        descriptorStore.delete();
        sessionId = null;
        lastError = null;
        audit.record("lifecycle", "BRIDGE_DISABLE", "OK", 0);
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(
                server != null,
                server == null ? null : server.port(),
                sessionId == null ? null : sessionId.substring(0, Math.min(8, sessionId.length())),
                descriptorStore.path(),
                lastError,
                audit.recent());
    }

    @Override
    public void close() {
        disable();
    }

    private Path indexPath(String session) {
        return descriptorStore.path().getParent().resolve("index").resolve("history-" + session + ".db");
    }

    private byte[] randomBytes(int count) {
        byte[] bytes = new byte[count];
        random.nextBytes(bytes);
        return bytes;
    }
}
