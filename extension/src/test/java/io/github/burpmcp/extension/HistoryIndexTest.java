package io.github.burpmcp.extension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HistoryIndexTest {
    @TempDir
    Path temporaryDirectory;

    private static BurpGateway.HistoryMessage message(
            int id, Instant time, String method, String url, String host, Integer status,
            String request, String response) {
        return new BurpGateway.HistoryMessage(
                id,
                time,
                method,
                "/" + url,
                url,
                new BurpGateway.Service(host, 443, true),
                status,
                "text/html",
                false,
                8080,
                request.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1),
                response == null
                        ? null
                        : response.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
    }

    private HistoryIndex open(long ttlMillis) throws IOException, java.sql.SQLException {
        return new HistoryIndex(temporaryDirectory.resolve("index").resolve("history.db"), ttlMillis);
    }

    private static Supplier<List<BurpGateway.HistoryMessage>> snapshotOf(BurpGateway.HistoryMessage... items) {
        return () -> List.of(items);
    }

    @Test
    void queryReturnsFilteredEntriesInRecencyOrder() throws Exception {
        Instant first = Instant.parse("2026-10-21T08:00:00Z");
        Instant second = Instant.parse("2026-10-21T08:00:05Z");
        try (HistoryIndex index = open(60_000)) {
            index.refresh(snapshotOf(
                    message(1, first, "GET", "https://old.example.com/a", "old.example.com", 200, "GET /a HTTP/1.1\r\n\r\n", "HTTP/1.1 200 OK\r\n\r\n"),
                    message(2, second, "POST", "https://new.example.com/b", "new.example.com", 404, "POST /b HTTP/1.1\r\n\r\n", null)));

            HistoryIndex.QueryResult all = index.query(new HistoryIndex.Filter(null, null, Set.of(), null, null, null), 50, 0);
            assertThat(all.total()).isEqualTo(2);
            assertThat(all.entries().stream().map(HistoryIndex.Entry::id).toList())
                    .containsExactly(2, 1);
            assertThat(all.entries().get(0).hasResponse()).isFalse();
            assertThat(all.entries().get(1).hasResponse()).isTrue();

            HistoryIndex.QueryResult byHost = index.query(
                    new HistoryIndex.Filter("NEW.example.com", null, Set.of(), null, null, null), 50, 0);
            assertThat(byHost.entries().stream().map(HistoryIndex.Entry::id).toList())
                    .containsExactly(2);

            HistoryIndex.QueryResult byStatus = index.query(
                    new HistoryIndex.Filter(null, null, Set.of(200), null, null, null), 50, 0);
            assertThat(byStatus.entries().stream().map(HistoryIndex.Entry::id).toList())
                    .containsExactly(1);

            HistoryIndex.QueryResult withResponse = index.query(
                    new HistoryIndex.Filter(null, null, Set.of(), Boolean.FALSE, null, null), 50, 0);
            assertThat(withResponse.entries().stream().map(HistoryIndex.Entry::id).toList())
                    .containsExactly(2);

            HistoryIndex.QueryResult window = index.query(
                    new HistoryIndex.Filter(null, null, Set.of(), null, second, second), 50, 0);
            assertThat(window.entries().stream().map(HistoryIndex.Entry::id).toList())
                    .containsExactly(2);
        }
    }

    @Test
    void refreshRemovesEntriesBurpNoLongerReports() throws Exception {
        BurpGateway.HistoryMessage one = message(1, Instant.parse("2026-10-21T08:00:01Z"), "GET", "https://a.example/", "a.example", 200, "GET / HTTP/1.1\r\n\r\n", "HTTP/1.1 200 OK\r\n\r\n");
        BurpGateway.HistoryMessage two = message(2, Instant.parse("2026-10-21T08:00:02Z"), "GET", "https://b.example/", "b.example", 200, "GET / HTTP/1.1\r\n\r\n", "HTTP/1.1 200 OK\r\n\r\n");
        try (HistoryIndex index = open(60_000)) {
            index.refresh(snapshotOf(one, two));
            assertThat(index.count()).isEqualTo(2);

            // The user clears proxy history in Burp: the next scan no longer reports either entry.
            index.refresh(() -> List.of());
            assertThat(index.count()).isEqualTo(0);
        }
    }

    @Test
    void refreshCountsMalformedEntriesWithoutFailing() throws Exception {
        BurpGateway.HistoryMessage good = message(1, Instant.parse("2026-10-21T08:00:03Z"), "GET", "https://ok.example/", "ok.example", 200, "GET / HTTP/1.1\r\n\r\n", "HTTP/1.1 200 OK\r\n\r\n");
        BurpGateway.HistoryMessage malformed = new BurpGateway.HistoryMessage(
                2, Instant.parse("2026-10-21T08:00:04Z"), "GET", "/x", "https://x.example/x",
                null, null, null, false, 8080, null, null);
        try (HistoryIndex index = open(60_000)) {
            index.refresh(snapshotOf(good, malformed));
            HistoryIndex.QueryResult result = index.query(
                    new HistoryIndex.Filter(null, null, Set.of(), null, null, null), 50, 0);
            assertThat(result.entries().stream().map(HistoryIndex.Entry::id).toList())
                    .containsExactly(1);
            assertThat(result.malformedSkipped()).isEqualTo(1);
        }
    }

    @Test
    void isDueTracksTheTtlWindow() throws Exception {
        try (HistoryIndex index = open(0)) {
            assertThat(index.isDue()).isTrue();
            index.refresh(snapshotOf());
            assertThat(index.isDue()).isTrue();
        }
        try (HistoryIndex index = open(60_000)) {
            index.refresh(snapshotOf());
            assertThat(index.isDue()).isFalse();
        }
    }

    @Test
    void paginationUsesLimitAndOffsetOverTheSameTotal() throws Exception {
        try (HistoryIndex index = open(60_000)) {
            BurpGateway.HistoryMessage[] items = new BurpGateway.HistoryMessage[5];
            for (int id = 1; id <= 5; id++) {
                items[id - 1] = message(id, Instant.parse("2026-10-21T08:00:0" + id + "Z"),
                        "GET", "https://p" + id + ".example/", "p" + id + ".example", 200,
                        "GET / HTTP/1.1\r\n\r\n", "HTTP/1.1 200 OK\r\n\r\n");
            }
            index.refresh(snapshotOf(items));
            HistoryIndex.Filter none = new HistoryIndex.Filter(null, null, Set.of(), null, null, null);
            assertThat(index.query(none, 2, 0).entries().stream().map(HistoryIndex.Entry::id).toList())
                    .containsExactly(5, 4);
            assertThat(index.query(none, 2, 2).total()).isEqualTo(5);
            assertThat(index.query(none, 2, 2).entries().stream().map(HistoryIndex.Entry::id).toList())
                    .containsExactly(3, 2);
        }
    }

    @EnabledOnOs({ OS.LINUX, OS.MAC })
    @Test
    void databaseFileIsOwnerOnlyAndDeletedOnClose() throws Exception {
        Path database = temporaryDirectory.resolve("index").resolve("history.db");
        try (HistoryIndex index = new HistoryIndex(database, 60_000)) {
            index.refresh(snapshotOf());
            assertThat(database).exists();
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(database);
            assertThat(permissions)
                    .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        }
        assertThat(database).doesNotExist();
    }
}
