package io.github.burpmcp.extension;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.AclFileAttributeView;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SessionDescriptorStoreTest {
    @TempDir
    Path temporaryDirectory;

    @EnabledOnOs(OS.WINDOWS)
    @Test
    void descriptorIsRestrictedToTheCurrentUser() throws IOException {
        Path descriptor = temporaryDirectory.resolve("active-session.json");
        SessionDescriptorStore store = new SessionDescriptorStore(descriptor, new ObjectMapper());

        store.write(Map.of("schemaVersion", 1, "sessionId", "session-1", "token", "token-value"));

        assertThat(descriptor).exists();
        AclFileAttributeView view = Files.getFileAttributeView(descriptor, AclFileAttributeView.class);
        assertThat(view).isNotNull();

        String owner = Files.getOwner(descriptor).getName();
        StringBuilder seen = new StringBuilder();
        for (AclEntry entry : view.getAcl()) {
            String identity = entry.principal().getName();
            if (seen.length() > 0) {
                seen.append(", ");
            }
            seen.append(entry.type()).append(":").append(identity);
            assertThat(identity.equalsIgnoreCase(owner))
                    .withFailMessage("descriptor ACL must be owner-only (%s) but granted %s to %s",
                            owner, entry.type(), identity)
                    .isTrue();
        }
    }

    @EnabledOnOs({ OS.LINUX, OS.MAC })
    @Test
    void descriptorIsOwnerOnlyOnPosix() throws IOException {
        Path descriptor = temporaryDirectory.resolve("active-session.json");
        SessionDescriptorStore store = new SessionDescriptorStore(descriptor, new ObjectMapper());

        store.write(Map.of("schemaVersion", 1, "sessionId", "session-posix", "token", "token-value"));

        assertThat(descriptor).exists();
        assertThat(Files.getPosixFilePermissions(descriptor))
                .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    @Test
    void resolvePathPrefersExplicitOverride() {
        Map<String, String> environment = Map.of(
                "BURP_MCP_SESSION_FILE", "/isolated/session.json",
                "LOCALAPPDATA", "/roaming/profile",
                "XDG_DATA_HOME", "/xdg/data");

        assertThat(SessionDescriptorStore.resolvePath(environment)).isEqualTo(Path.of("/isolated/session.json"));
    }

    @Test
    void resolvePathUsesLocalAppDataWhenPresent() {
        Map<String, String> environment = Map.of(
                "LOCALAPPDATA", "/roaming/profile",
                "XDG_DATA_HOME", "/xdg/data");

        assertThat(SessionDescriptorStore.resolvePath(environment))
                .isEqualTo(Path.of("/roaming/profile", "burp-mcp", "active-session.json"));
    }

    @Test
    void resolvePathUsesXdgDataHomeWithoutLocalAppData() {
        Map<String, String> environment = Map.of("XDG_DATA_HOME", "/xdg/data");

        assertThat(SessionDescriptorStore.resolvePath(environment))
                .isEqualTo(Path.of("/xdg/data", "burp-mcp", "active-session.json"));
    }

    @Test
    void resolvePathFallsBackToUserLocalShareWithoutLocalAppData() {
        Path resolved = SessionDescriptorStore.resolvePath(Map.of());

        Path userHome = Path.of(System.getProperty("user.home"));
        assertThat(resolved).isEqualTo(userHome.resolve(".local").resolve("share")
                .resolve("burp-mcp").resolve("active-session.json"));
    }

    @Test
    void writeIsAtomicAndReadable() throws IOException {
        Path descriptor = temporaryDirectory.resolve("nested").resolve("active-session.json");
        SessionDescriptorStore store = new SessionDescriptorStore(descriptor, new ObjectMapper());

        store.write(Map.of("schemaVersion", 1, "sessionId", "session-2"));

        assertThat(descriptor).exists();
        assertThat(new ObjectMapper().readTree(descriptor.toFile()).path("sessionId").asText())
                .isEqualTo("session-2");
    }

    @Test
    void deleteRemovesTheDescriptor() {
        Path descriptor = temporaryDirectory.resolve("active-session.json");
        SessionDescriptorStore store = new SessionDescriptorStore(descriptor, new ObjectMapper());
        try {
            store.write(Map.of("schemaVersion", 1));
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }

        store.delete();

        assertThat(descriptor).doesNotExist();
    }
}
