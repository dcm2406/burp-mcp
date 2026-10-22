package io.github.burpmcp.extension;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

final class SessionDescriptorStore {
    private final Path path;
    private final ObjectMapper mapper;

    SessionDescriptorStore(Path path, ObjectMapper mapper) {
        this.path = path.toAbsolutePath().normalize();
        this.mapper = mapper;
    }

    static Path resolvePath(Map<String, String> environment) {
        String override = environment.get("BURP_MCP_SESSION_FILE");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        String localAppData = environment.get("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            return Path.of(localAppData, "burp-mcp", "active-session.json");
        }
        // No Windows roaming profile: follow XDG so the descriptor does not land in a
        // literal "AppData/Local" directory on POSIX systems.
        String xdgDataHome = environment.get("XDG_DATA_HOME");
        Path dataHome = xdgDataHome != null && !xdgDataHome.isBlank()
                ? Path.of(xdgDataHome)
                : Path.of(System.getProperty("user.home"), ".local", "share");
        return dataHome.resolve("burp-mcp").resolve("active-session.json");
    }

    Path path() {
        return path;
    }

    void write(Map<String, Object> descriptor) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), ".active-session-", ".tmp");
        try {
            Files.write(temporary, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(descriptor));
            restrictToCurrentUser(temporary);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            restrictToCurrentUser(path);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    void delete() {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best effort during kill/unload.
        }
    }

    private static void restrictToCurrentUser(Path target) throws IOException {
        FilePermissions.restrictToCurrentUser(target);
    }
}
