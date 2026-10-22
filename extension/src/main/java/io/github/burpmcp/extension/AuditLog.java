package io.github.burpmcp.extension;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

final class AuditLog {
    private static final long MAX_FILE_BYTES = 1_048_576;
    private static final int MAX_RECENT = 100;
    private static final int BACKUPS = 3;

    record Event(Instant time, String requestId, String operation, String outcome, long durationMs) { }

    private final Path path;
    private final ObjectMapper mapper;
    private final Deque<Event> recent = new ArrayDeque<>();

    AuditLog(Path path, ObjectMapper mapper) {
        this.path = path;
        this.mapper = mapper;
    }

    synchronized void record(String requestId, String operation, String outcome, long durationMs) {
        Event event = new Event(Instant.now(), requestId, operation, outcome, durationMs);
        recent.addFirst(event);
        while (recent.size() > MAX_RECENT) {
            recent.removeLast();
        }
        try {
            Files.createDirectories(path.getParent());
            rotateIfNeeded();
            String line = mapper.writeValueAsString(Map.of(
                    "time", event.time().toString(),
                    "requestId", event.requestId(),
                    "operation", event.operation(),
                    "outcome", event.outcome(),
                    "durationMs", event.durationMs())) + System.lineSeparator();
            Files.writeString(path, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        } catch (IOException ignored) {
            // Auditing must never expose data or take down the local bridge.
        }
    }

    synchronized List<Event> recent() {
        return new ArrayList<>(recent);
    }

    private void rotateIfNeeded() throws IOException {
        if (!Files.exists(path) || Files.size(path) < MAX_FILE_BYTES) {
            return;
        }
        Files.deleteIfExists(backup(BACKUPS));
        for (int index = BACKUPS - 1; index >= 1; index--) {
            Path source = backup(index);
            if (Files.exists(source)) {
                Files.move(source, backup(index + 1), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.move(path, backup(1), StandardCopyOption.REPLACE_EXISTING);
    }

    private Path backup(int index) {
        return path.resolveSibling(path.getFileName() + "." + index);
    }
}
