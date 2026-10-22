package io.github.burpmcp.extension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Session-scoped metadata index over the Burp proxy history.
 *
 * <p>Montoya exposes no metadata-only history API: {@code proxy().history()} materialises every
 * request and response, and {@code history(ProxyHistoryFilter)} only moves the predicate into
 * Java. Repeated tool calls therefore repeat that cost. This index amortises it: the proxy
 * history is scanned at most once per TTL window, and every query inside that window runs
 * against SQLite.
 *
 * <p>The database holds <em>metadata only</em> - no raw bytes - is restricted to the current
 * user, and is deleted when the bridge session ends. Text search ({@code search}) still matches
 * raw request/response bytes, so {@code BridgeServer} never routes it through this index.
 *
 * <p>Robustness contract: the index is a cache, never a source of truth. Callers fall back to a
 * live Burp scan whenever the index is unavailable, and an empty result set forces one refresh
 * before it is reported, so deletions in Burp never masquerade as "no matches".
 */
final class HistoryIndex implements AutoCloseable {
    static final long DEFAULT_TTL_MS = 15_000;

    record Filter(
            String host,
            String method,
            Set<Integer> statusCodes,
            Boolean hasResponse,
            Instant from,
            Instant to) {
    }

    record Entry(
            int id,
            Instant time,
            String method,
            String url,
            String host,
            int port,
            boolean secure,
            Integer statusCode,
            String mimeType,
            boolean edited,
            int listenerPort,
            int requestByteLength,
            int responseByteLength,
            boolean hasResponse) {
    }

    record QueryResult(List<Entry> entries, int total, int malformedSkipped) {
    }

    private final Path dbFile;
    private final long ttlMillis;
    private final Connection connection;
    private long lastRefreshAt;
    private int malformedSkipped;
    private boolean closed;

    HistoryIndex(Path dbFile, long ttlMillis) throws IOException, SQLException {
        this.dbFile = dbFile.toAbsolutePath().normalize();
        this.ttlMillis = Math.max(0, ttlMillis);
        Files.createDirectories(this.dbFile.getParent());
        this.connection = openConnection(this.dbFile);
        createSchema();
        FilePermissions.restrictToCurrentUserBestEffort(this.dbFile);
    }

    static HistoryIndex open(Path dbFile) throws IOException, SQLException {
        return new HistoryIndex(dbFile, defaultTtlMillis());
    }

    /** TTL in milliseconds between proxy-history scans; overridable for tests and operators. */
    static long defaultTtlMillis() {
        String configured = System.getProperty("burp.mcp.index.ttl.ms");
        if (configured == null || configured.isBlank()) {
            return DEFAULT_TTL_MS;
        }
        try {
            return Long.parseLong(configured.trim());
        } catch (NumberFormatException exception) {
            return DEFAULT_TTL_MS;
        }
    }

    Path file() {
        return dbFile;
    }

    synchronized boolean isDue() {
        return System.currentTimeMillis() - lastRefreshAt >= ttlMillis;
    }

    synchronized void refreshIfDue(Supplier<List<BurpGateway.HistoryMessage>> snapshot) throws IOException {
        if (isDue()) {
            refresh(snapshot);
        }
    }

    /** Scan the proxy history once, upsert new entries and drop entries Burp no longer reports. */
    synchronized void refresh(Supplier<List<BurpGateway.HistoryMessage>> snapshot) throws IOException {
        ensureOpen();
        List<BurpGateway.HistoryMessage> items = snapshot.get();
        boolean previousAutoCommit;
        int malformed = 0;
        try {
            previousAutoCommit = connection.getAutoCommit();
        } catch (SQLException exception) {
            throw new IOException("History index transaction state unavailable", exception);
        }
        try (Statement dropStale = connection.createStatement()) {
            dropStale.execute("DROP TABLE IF EXISTS temp.seen");
        } catch (SQLException exception) {
            throw new IOException("History index could not be prepared for refresh", exception);
        }
        try (Statement createSeen = connection.createStatement()) {
            createSeen.execute("CREATE TEMP TABLE seen(id INTEGER PRIMARY KEY)");
        } catch (SQLException exception) {
            throw new IOException("History index could not stage the scan", exception);
        }
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement insertEntry = connection.prepareStatement(
                    "INSERT OR REPLACE INTO history(id, time_nanos, method, host, port, secure, status, mime,"
                            + " edited, listener_port, req_len, resp_len, url, has_response)"
                            + " VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
                 PreparedStatement insertSeen = connection.prepareStatement(
                         "INSERT OR REPLACE INTO temp.seen(id) VALUES(?)")) {
                for (BurpGateway.HistoryMessage item : items) {
                    try {
                        bindEntry(insertEntry, item);
                        insertEntry.executeUpdate();
                        insertSeen.setInt(1, item.id());
                        insertSeen.executeUpdate();
                    } catch (RuntimeException exception) {
                        // A malformed history item must not abort the whole refresh.
                        malformed++;
                    }
                }
            }
            try (Statement deleteVanished = connection.createStatement()) {
                deleteVanished.executeUpdate(
                        "DELETE FROM history WHERE id NOT IN (SELECT id FROM temp.seen)");
            }
            connection.commit();
        } catch (SQLException exception) {
            rollbackQuietly();
            throw new IOException("History index refresh failed", exception);
        } finally {
            restoreAutoCommitQuietly(previousAutoCommit);
            try (Statement dropSeen = connection.createStatement()) {
                dropSeen.execute("DROP TABLE IF EXISTS temp.seen");
            } catch (SQLException ignored) {
                // Temp table disappears with the connection anyway.
            }
        }
        malformedSkipped = malformed;
        lastRefreshAt = System.currentTimeMillis();
        hardenFiles();
    }

    synchronized QueryResult query(Filter filter, int limit, int offset) throws IOException {
        ensureOpen();
        StringBuilder where = new StringBuilder();
        List<Object> parameters = new ArrayList<>();
        appendCondition(where, parameters, filter.host() != null, "lower(host) = lower(?)", filter.host());
        appendCondition(where, parameters, filter.method() != null, "lower(method) = lower(?)", filter.method());
        boolean hasStatuses = filter.statusCodes() != null && !filter.statusCodes().isEmpty();
        if (hasStatuses) {
            appendCondition(where, parameters, true, "status IS NOT NULL AND status IN ("
                    + placeholders(filter.statusCodes().size()) + ")", filter.statusCodes().toArray());
        }
        if (filter.hasResponse() != null) {
            appendCondition(where, parameters, true, "has_response = ?", filter.hasResponse() ? 1 : 0);
        }
        Instant from = filter.from();
        if (from != null) {
            appendCondition(where, parameters, true, "time_nanos IS NOT NULL AND time_nanos >= ?", toNanos(from));
        }
        Instant to = filter.to();
        if (to != null) {
            appendCondition(where, parameters, true, "time_nanos IS NOT NULL AND time_nanos <= ?", toNanos(to));
        }

        String predicate = where.toString();
        String projection = "id, time_nanos, method, host, port, secure, status, mime, edited, listener_port,"
                + " req_len, resp_len, url, has_response";
        int total;
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*)" + " FROM history" + predicate)) {
            bind(statement, parameters);
            try (ResultSet results = statement.executeQuery()) {
                total = results.next() ? results.getInt(1) : 0;
            }
        } catch (SQLException exception) {
            throw new IOException("History index count failed", exception);
        }

        List<Entry> entries = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + projection + " FROM history" + predicate
                        + " ORDER BY time_nanos DESC, id DESC LIMIT ? OFFSET ?")) {
            bind(statement, parameters);
            statement.setInt(parameters.size() + 1, limit);
            statement.setInt(parameters.size() + 2, offset);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    entries.add(new Entry(
                            results.getInt("id"),
                            rawTime(results),
                            results.getString("method"),
                            results.getString("url"),
                            results.getString("host"),
                            results.getInt("port"),
                            results.getBoolean("secure"),
                            (Integer) results.getObject("status"),
                            results.getString("mime"),
                            results.getBoolean("edited"),
                            results.getInt("listener_port"),
                            results.getInt("req_len"),
                            results.getInt("resp_len"),
                            results.getBoolean("has_response")));
                }
            }
        } catch (SQLException exception) {
            throw new IOException("History index query failed", exception);
        }
        return new QueryResult(entries, total, malformedSkipped);
    }

    synchronized int count() throws IOException {
        ensureOpen();
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM history")) {
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getInt(1) : 0;
            }
        } catch (SQLException exception) {
            throw new IOException("History index count failed", exception);
        }
    }

    @Override
    public void close() {
        closeAndDelete();
    }

    synchronized void closeAndDelete() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Disposable cache: a failed close still ends in deletion.
        }
        for (String suffix : List.of("", "-journal", "-wal", "-shm")) {
            try {
                Files.deleteIfExists(dbFile.resolveSibling(dbFile.getFileName() + suffix));
            } catch (IOException ignored) {
                // Best effort; the index directory is disposable.
            }
        }
    }

    private void createSchema() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=MEMORY");
            statement.execute("PRAGMA synchronous=OFF");
            statement.execute("PRAGMA busy_timeout=3000");
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS history("
                            + " id INTEGER PRIMARY KEY,"
                            + " time_nanos INTEGER,"
                            + " method TEXT NOT NULL,"
                            + " host TEXT NOT NULL,"
                            + " port INTEGER NOT NULL,"
                            + " secure INTEGER NOT NULL,"
                            + " status INTEGER,"
                            + " mime TEXT,"
                            + " edited INTEGER NOT NULL,"
                            + " listener_port INTEGER NOT NULL,"
                            + " req_len INTEGER NOT NULL,"
                            + " resp_len INTEGER NOT NULL,"
                            + " url TEXT NOT NULL,"
                            + " has_response INTEGER NOT NULL)");
            statement.execute("CREATE INDEX IF NOT EXISTS history_recency ON history(time_nanos DESC, id DESC)");
        }
    }

    private static void bindEntry(PreparedStatement statement, BurpGateway.HistoryMessage item) throws SQLException {
        Instant time = item.time();
        statement.setInt(1, item.id());
        if (time == null) {
            statement.setNull(2, Types.INTEGER);
        } else {
            statement.setLong(2, toNanos(time));
        }
        statement.setString(3, item.method());
        statement.setString(4, item.service().host());
        statement.setInt(5, item.service().port());
        statement.setBoolean(6, item.service().secure());
        Integer status = item.statusCode();
        if (status == null) {
            statement.setNull(7, Types.INTEGER);
        } else {
            statement.setInt(7, status);
        }
        statement.setString(8, item.mimeType());
        statement.setBoolean(9, item.edited());
        statement.setInt(10, item.listenerPort());
        statement.setInt(11, item.request() == null ? 0 : item.request().length);
        int responseLength = item.response() == null ? 0 : item.response().length;
        statement.setInt(12, responseLength);
        statement.setString(13, item.url());
        statement.setBoolean(14, item.response() != null);
    }

    private static Instant rawTime(ResultSet results) throws SQLException {
        Object rawTime = results.getObject("time_nanos");
        return rawTime == null ? null : fromNanos(((Number) rawTime).longValue());
    }

    private static void appendCondition(
            StringBuilder where, List<Object> parameters, boolean active, String clause, Object... values) {
        if (!active) {
            return;
        }
        if (where.length() == 0) {
            where.append(" WHERE ");
        } else {
            where.append(" AND ");
        }
        where.append(clause);
        for (Object value : values) {
            parameters.add(value);
        }
    }

    private static String placeholders(int count) {
        return "?,".repeat(count).replaceAll(",$", "");
    }

    private static void bind(PreparedStatement statement, List<Object> parameters) throws SQLException {
        for (int index = 0; index < parameters.size(); index++) {
            Object parameter = parameters.get(index);
            switch (parameter) {
                case Integer value -> statement.setInt(index + 1, value);
                case String value -> statement.setString(index + 1, value);
                case Long value -> statement.setLong(index + 1, value);
                default -> throw new SQLException("Unsupported index parameter: " + parameter.getClass().getName());
            }
        }
    }

    private static long toNanos(Instant instant) {
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L), instant.getNano());
    }

    private static Instant fromNanos(long nanos) {
        return Instant.ofEpochSecond(Math.floorDiv(nanos, 1_000_000_000L), Math.floorMod(nanos, 1_000_000_000L));
    }

    private void hardenFiles() {
        FilePermissions.restrictToCurrentUserBestEffort(dbFile);
        Path journal = dbFile.resolveSibling(dbFile.getFileName() + "-journal");
        if (Files.exists(journal)) {
            FilePermissions.restrictToCurrentUserBestEffort(journal);
        }
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // Nothing else can be done for a disposable cache.
        }
    }

    private void restoreAutoCommitQuietly(boolean autoCommit) {
        try {
            connection.setAutoCommit(autoCommit);
        } catch (SQLException ignored) {
            // The connection is disposable; the next refresh re-establishes the state.
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("History index is closed");
        }
    }

    private static Connection openConnection(Path file) throws IOException, SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException exception) {
            throw new IOException("SQLite driver unavailable", exception);
        }
        SQLException lastFailure = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            } catch (SQLException exception) {
                lastFailure = exception;
                String message = String.valueOf(exception.getMessage()).toLowerCase(java.util.Locale.ROOT);
                boolean corrupted = message.contains("malformed") || message.contains("not a database")
                        || message.contains("encrypted");
                if (!corrupted || attempt == 1) {
                    break;
                }
                try {
                    // The index is a disposable cache: drop a corrupt file and rebuild from Burp.
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    break;
                }
            }
        }
        throw lastFailure;
    }
}
