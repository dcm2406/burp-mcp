package io.github.burpmcp.extension;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class BridgeServer implements AutoCloseable {
    static final int MAX_RAW_BYTES = 10 * 1024 * 1024;
    static final int MAX_JSON_BYTES = 14 * 1024 * 1024 + 4096;
    static final int DEFAULT_PAGE_SIZE = 50;
    static final int MAX_PAGE_SIZE = 200;
    static final int MAX_BATCH_ITEMS = 50;
    static final int MAX_BATCH_INPUT_BYTES = 8 * 1024 * 1024;
    static final int MAX_BATCH_OUTPUT_BYTES = 8 * 1024 * 1024;
    static final int MAX_COLLABORATOR_PAYLOADS = 20;
    static final int MAX_COLLABORATOR_CUSTOM_DATA = 16;
    // Burp Collaborator only accepts alphanumeric custom data, at most 16 characters.
    private static final java.util.regex.Pattern COLLABORATOR_CUSTOM_DATA =
            java.util.regex.Pattern.compile("^[A-Za-z0-9]{1,16}$");
    private static final String COLLABORATOR_REF_KIND = "oast";
    static final int DEFAULT_BATCH_CONCURRENCY = 4;
    static final int MAX_BATCH_CONCURRENCY = 10;

    private final BurpGateway gateway;
    private final ObjectMapper mapper;
    private final AuditLog audit;
    private final HistoryIndex index;
    private final String sessionId;
    private final Instant startedAt;
    private final byte[] expectedAuthHash;
    private final OpaqueTokenCodec opaqueTokens;
    private final HttpServer server;
    private final ExecutorService httpExecutor;
    private final ThreadPoolExecutor operationExecutor;
    private final ExecutorService batchExecutor;
    private int collaboratorPayloadCount;

    BridgeServer(
            BurpGateway gateway,
            ObjectMapper mapper,
            AuditLog audit,
            String sessionId,
            String bearerToken,
            byte[] hmacKey) throws IOException {
        this(gateway, mapper, audit, sessionId, bearerToken, hmacKey, null);
    }

    BridgeServer(
            BurpGateway gateway,
            ObjectMapper mapper,
            AuditLog audit,
            String sessionId,
            String bearerToken,
            byte[] hmacKey,
            HistoryIndex index) throws IOException {
        this.gateway = Objects.requireNonNull(gateway);
        this.index = index;
        this.mapper = Objects.requireNonNull(mapper);
        this.audit = Objects.requireNonNull(audit);
        this.sessionId = Objects.requireNonNull(sessionId);
        this.startedAt = Instant.now();
        this.expectedAuthHash = sha256(bearerToken.getBytes(StandardCharsets.US_ASCII));
        this.opaqueTokens = new OpaqueTokenCodec(sessionId, hmacKey);

        InetAddress loopback = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        this.server = HttpServer.create(new InetSocketAddress(loopback, 0), 0);
        this.httpExecutor = new ThreadPoolExecutor(
                2, 24, 30, TimeUnit.SECONDS,
                new SynchronousQueue<>(), daemonFactory("burp-mcp-http-"),
                new ThreadPoolExecutor.CallerRunsPolicy());
        this.operationExecutor = new ThreadPoolExecutor(
                2, 4, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(16), daemonFactory("burp-mcp-operation-"),
                new ThreadPoolExecutor.AbortPolicy());
        this.batchExecutor = new ThreadPoolExecutor(
                2, MAX_BATCH_CONCURRENCY, 30, TimeUnit.SECONDS,
                new SynchronousQueue<>(), daemonFactory("burp-mcp-batch-"),
                new ThreadPoolExecutor.CallerRunsPolicy());
        this.server.setExecutor(httpExecutor);
        this.server.createContext("/", this::enqueue);
    }

    void start() {
        server.start();
    }

    int port() {
        return server.getAddress().getPort();
    }

    InetSocketAddress address() {
        return server.getAddress();
    }

    @Override
    public void close() {
        server.stop(0);
        operationExecutor.getQueue().clear();
        operationExecutor.shutdownNow();
        httpExecutor.shutdownNow();
        batchExecutor.shutdownNow();
        awaitTermination(operationExecutor);
        awaitTermination(httpExecutor);
        awaitTermination(batchExecutor);
    }

    private static void awaitTermination(ExecutorService executor) {
        boolean interrupted = false;
        try {
            try {
                executor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void enqueue(HttpExchange exchange) throws IOException {
        CountDownLatch completed = new CountDownLatch(1);
        try {
            operationExecutor.execute(() -> {
                try {
                    handle(exchange);
                } finally {
                    completed.countDown();
                }
            });
        } catch (RejectedExecutionException exception) {
            String requestId = requestId();
            audit.record(requestId, operation(exchange), "QUEUE_FULL", 0);
            try {
                sendError(exchange, 503, "QUEUE_FULL", "The bridge queue is full", requestId);
            } finally {
                exchange.close();
            }
            return;
        }

        boolean interrupted = false;
        while (completed.getCount() != 0) {
            try {
                completed.await();
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void handle(HttpExchange exchange) {
        long started = System.nanoTime();
        String requestId = requestId();
        String operation = operation(exchange);
        String outcome = "OK";
        try {
            validateTransport(exchange);
            BridgeResponse response = route(exchange);
            sendJson(exchange, response.status(), response.body(), requestId);
        } catch (BridgeException exception) {
            outcome = exception.code();
            safeSendError(exchange, exception.status(), exception.code(), exception.getMessage(), requestId);
        } catch (Exception exception) {
            outcome = "INTERNAL_ERROR";
            if (System.getProperty("burp.mcp.debug") != null) {
                exception.printStackTrace();
            }
            safeSendError(exchange, 500, "INTERNAL_ERROR", "The bridge could not complete the request", requestId);
        } finally {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            audit.record(requestId, operation, outcome, durationMs);
            exchange.close();
        }
    }

    private BridgeResponse route(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        if ("GET".equals(method) && "/v1/status".equals(path)) {
            rejectUnexpectedBody(exchange);
            return new BridgeResponse(200, status());
        }
        if ("POST".equals(method) && "/v1/proxy-history/query".equals(path)) {
            return new BridgeResponse(200, query(readJson(exchange)));
        }
        if ("GET".equals(method) && path.startsWith("/v1/proxy-history/")) {
            rejectUnexpectedBody(exchange);
            String reference = path.substring("/v1/proxy-history/".length());
            if (reference.isBlank() || reference.contains("/")) {
                throw new BridgeException(404, "NOT_FOUND", "Endpoint not found");
            }
            return new BridgeResponse(200, historyItem(reference));
        }
        if ("POST".equals(method) && "/v1/http/send".equals(path)) {
            return new BridgeResponse(200, send(readJson(exchange)));
        }
        if ("POST".equals(method) && "/v1/http/send-batch".equals(path)) {
            return new BridgeResponse(200, sendBatch(readJson(exchange)));
        }
        if ("POST".equals(method) && "/v1/collaborator/payload".equals(path)) {
            return new BridgeResponse(200, collaboratorPayload(readJson(exchange)));
        }
        if ("POST".equals(method) && "/v1/collaborator/interactions".equals(path)) {
            return new BridgeResponse(200, collaboratorInteractions(readJson(exchange)));
        }
        if ("POST".equals(method) && "/v1/repeater".equals(path)) {
            return new BridgeResponse(202, repeater(readJson(exchange)));
        }
        if (Set.of("/v1/status", "/v1/proxy-history/query", "/v1/http/send", "/v1/http/send-batch",
                "/v1/repeater", "/v1/collaborator/payload", "/v1/collaborator/interactions").contains(path)
                || path.startsWith("/v1/proxy-history/")) {
            throw new BridgeException(405, "METHOD_NOT_ALLOWED", "Method not allowed");
        }
        throw new BridgeException(404, "NOT_FOUND", "Endpoint not found");
    }

    private Map<String, Object> status() {
        return Map.of(
                "connected", true,
                "sessionId", sessionId,
                "extensionVersion", "0.1.0",
                "burpVersion", gateway.burpVersion(),
                "startedAt", startedAt.toString(),
                "capabilities", List.of("proxyHistory", "sendHttpRequest", "sendToRepeater",
                        "sendHttpRequestBatch", "collaborator"),
                "limits", Map.of(
                        "maxRawBytes", MAX_RAW_BYTES,
                        "maxPageSize", MAX_PAGE_SIZE,
                        "maxBatchItems", MAX_BATCH_ITEMS,
                        "maxBatchConcurrency", MAX_BATCH_CONCURRENCY,
                        "maxBatchInputBytes", MAX_BATCH_INPUT_BYTES,
                        "maxBatchOutputBytes", MAX_BATCH_OUTPUT_BYTES));
    }

    private Map<String, Object> query(JsonNode input) {
        requireObject(input);
        JsonNode filter = input.get("filter");
        if (filter != null && !filter.isNull() && !filter.isObject()) {
            throw badInput("'filter' must be an object");
        }
        JsonNode filters = filter == null || filter.isNull() ? null : filter;
        String host = filters == null ? null : optionalText(filters, "host", 253);
        String method = filters == null ? null : optionalText(filters, "method", 32);
        String search = filters == null ? null : optionalText(filters, "search", 256);
        Boolean hasResponse = filters == null ? null : optionalBoolean(filters, "hasResponse");
        Instant from = filters == null ? null : optionalInstant(filters, "from");
        Instant to = filters == null ? null : optionalInstant(filters, "to");
        if (from != null && to != null && from.isAfter(to)) {
            throw badInput("'from' must be before or equal to 'to'");
        }
        Set<Integer> statusCodes = statusCodes(filters == null ? null : filters.get("statusCodes"));
        int limit = optionalInt(input, "pageSize", DEFAULT_PAGE_SIZE);
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw badInput("'pageSize' must be between 1 and " + MAX_PAGE_SIZE);
        }

        String fingerprint = fingerprint(host, method, statusCodes, hasResponse, search, from, to, limit);
        String cursor = optionalText(input, "cursor", 2048);
        int offset = cursor == null ? 0 : opaqueTokens.parseCursor(cursor, fingerprint);

        List<BurpGateway.HistoryMessage> filtered = null;
        HistoryIndex.QueryResult indexed = null;
        int indexedMalformed = 0;
        if (index != null && search == null) {
            // Text search matches raw request/response bytes, so it stays on the live scan below.
            HistoryIndex.Filter indexFilter = new HistoryIndex.Filter(host, method, statusCodes, hasResponse, from, to);
            try {
                index.refreshIfDue(() -> gateway.proxyHistory());
                indexed = index.query(indexFilter, limit, offset);
                if (offset >= indexed.total()) {
                    // Empty-guard: prove the result set really is this small before reporting it, so
                    // entries deleted in Burp are never mistaken for "no matches".
                    index.refresh(() -> gateway.proxyHistory());
                    indexed = index.query(indexFilter, limit, offset);
                }
            } catch (IOException | RuntimeException exception) {
                // The index is a cache; never fail a query because of it.
                indexed = null;
            }
            if (indexed != null) {
                indexedMalformed = indexed.malformedSkipped();
            }
        }
        if (indexed == null) {
            filtered = gateway.proxyHistory().stream()
                    .filter(item -> matches(item, host, method, statusCodes, hasResponse, search, from, to))
                    .sorted(Comparator.comparing(BurpGateway.HistoryMessage::time,
                                    Comparator.nullsLast(Comparator.reverseOrder()))
                            .thenComparing(BurpGateway.HistoryMessage::id, Comparator.reverseOrder()))
                    .toList();
        }
        int total = indexed != null ? indexed.total() : filtered.size();
        if (offset > total) {
            throw new BridgeException(400, "INVALID_CURSOR", "Cursor is outside the result set");
        }
        int end = Math.min(total, offset + limit);
        List<Map<String, Object>> items = new ArrayList<>(Math.max(0, end - offset));
        int malformedItemCount = indexedMalformed;
        if (indexed == null) {
            for (BurpGateway.HistoryMessage item : filtered.subList(offset, end)) {
                try {
                    items.add(projectHistory(opaqueTokens.reference(item.id()), item));
                } catch (RuntimeException exception) {
                    malformedItemCount++;
                }
            }
        } else {
            for (HistoryIndex.Entry entry : indexed.entries()) {
                try {
                    items.add(projectIndexedHistory(opaqueTokens.reference(entry.id()), entry));
                } catch (RuntimeException exception) {
                    malformedItemCount++;
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("nextCursor", end < total ? opaqueTokens.cursor(end, fingerprint) : null);
        result.put("returned", items.size());
        result.put("malformedItemCount", malformedItemCount);
        return result;
    }

    private static Map<String, Object> projectHistory(String reference, BurpGateway.HistoryMessage item) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("ref", reference);
        output.put("burpHistoryId", item.id());
        output.put("time", Objects.requireNonNull(item.time()).toString());
        output.put("method", Objects.requireNonNull(item.method()));
        output.put("url", Objects.requireNonNull(item.url()));
        output.put("service", service(Objects.requireNonNull(item.service())));
        output.put("statusCode", item.statusCode());
        output.put("mimeType", item.mimeType());
        output.put("hasResponse", item.hasResponse());
        output.put("edited", item.edited());
        output.put("listenerPort", item.listenerPort());
        output.put("requestByteLength", Objects.requireNonNull(item.request()).length);
        output.put("responseByteLength", item.response() == null ? 0 : item.response().length);
        return output;
    }

    private static Map<String, Object> projectIndexedHistory(String reference, HistoryIndex.Entry entry) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("ref", reference);
        output.put("burpHistoryId", entry.id());
        output.put("time", Objects.requireNonNull(entry.time()).toString());
        output.put("method", Objects.requireNonNull(entry.method()));
        output.put("url", Objects.requireNonNull(entry.url()));
        output.put("service", Map.of(
                "host", Objects.requireNonNull(entry.host()),
                "port", entry.port(),
                "secure", entry.secure()));
        output.put("statusCode", entry.statusCode());
        output.put("mimeType", entry.mimeType());
        output.put("hasResponse", entry.hasResponse());
        output.put("edited", entry.edited());
        output.put("listenerPort", entry.listenerPort());
        output.put("requestByteLength", entry.requestByteLength());
        output.put("responseByteLength", entry.responseByteLength());
        return output;
    }

    private Map<String, Object> historyItem(String reference) {
        int id = opaqueTokens.parseReference(reference);
        BurpGateway.HistoryMessage item = gateway.proxyHistoryItem(id)
                .orElseThrow(() -> new BridgeException(410, "STALE_REFERENCE", "History item is no longer available"));
        CombinedBinary combined = combined(item.request(), item.response());
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("ref", reference);
        output.put("burpHistoryId", item.id());
        output.put("time", item.time().toString());
        output.put("method", item.method());
        output.put("url", item.url());
        output.put("service", service(item.service()));
        output.put("statusCode", item.statusCode());
        output.put("request", binary(combined.first()));
        output.put("response", combined.second() == null ? null : binary(combined.second()));
        output.put("hasResponse", item.hasResponse());
        return output;
    }

    private Map<String, Object> send(JsonNode input) {
        RawRequest raw = rawRequest(input);
        BurpGateway.SendResult result = gateway.send(raw.service(), raw.bytes());
        CombinedBinary combined = combined(raw.bytes(), result.response());
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("service", service(raw.service()));
        output.put("request", binary(combined.first()));
        output.put("response", combined.second() == null ? null : binary(combined.second()));
        output.put("hasResponse", result.response() != null);
        output.put("statusCode", result.statusCode());
        return output;
    }

    private Map<String, Object> repeater(JsonNode input) {
        RawRequest raw = rawRequest(input);
        String tabName = optionalText(input, "tabName", 80);
        gateway.sendToRepeater(raw.service(), raw.bytes(), tabName);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("accepted", true);
        if (tabName != null) {
            output.put("tabName", tabName);
        }
        return output;
    }

    private RawRequest rawRequest(JsonNode input) {
        requireObject(input);
        JsonNode service = requiredObject(input, "service");
        String host = requiredText(service, "host", 255);
        int port = requiredInt(service, "port");
        if (port < 1 || port > 65535) {
            throw badInput("'service.port' must be between 1 and 65535");
        }
        boolean secure = requiredBoolean(service, "secure");
        JsonNode request = requiredObject(input, "request");
        if (!"base64".equals(requiredText(request, "encoding", 16))) {
            throw badInput("Only base64 request encoding is supported");
        }
        String encoded = requiredText(request, "data", MAX_JSON_BYTES);
        final byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw badInput("'request.data' is not valid base64");
        }
        if (bytes.length == 0 || !Base64.getEncoder().encodeToString(bytes).equals(encoded)) {
            throw badInput("'request.data' must use canonical standard base64");
        }
        if (bytes.length > MAX_RAW_BYTES) {
            throw new BridgeException(413, "PAYLOAD_TOO_LARGE", "Decoded request exceeds 10 MiB");
        }
        return new RawRequest(new BurpGateway.Service(host, port, secure), bytes);
    }

    private Map<String, Object> sendBatch(JsonNode input) {
        requireObject(input);
        JsonNode itemsNode = input.get("items");
        if (itemsNode == null || !itemsNode.isArray() || itemsNode.size() < 1) {
            throw badInput("'items' must be a non-empty array");
        }
        if (itemsNode.size() > MAX_BATCH_ITEMS) {
            throw new BridgeException(400, "BATCH_TOO_LARGE",
                    "A batch may contain at most " + MAX_BATCH_ITEMS + " items");
        }
        int concurrency = optionalInt(input, "concurrency", DEFAULT_BATCH_CONCURRENCY);
        if (concurrency < 1 || concurrency > MAX_BATCH_CONCURRENCY) {
            throw new BridgeException(400, "BATCH_TOO_LARGE",
                    "'concurrency' must be between 1 and " + MAX_BATCH_CONCURRENCY);
        }
        String responseMode = optionalText(input, "responseMode", 16);
        if (responseMode != null && !"summary".equals(responseMode) && !"full".equals(responseMode)) {
            throw badInput("'responseMode' must be 'summary' or 'full'");
        }
        boolean full = "full".equals(responseMode);

        // Validate everything before sending anything: a malformed item must not cause a partial send.
        List<RawRequest> requests = new ArrayList<>(itemsNode.size());
        long totalInput = 0;
        for (JsonNode itemNode : itemsNode) {
            RawRequest raw = rawRequest(itemNode);
            totalInput += raw.bytes().length;
            requests.add(raw);
        }
        if (totalInput > MAX_BATCH_INPUT_BYTES) {
            throw new BridgeException(413, "PAYLOAD_TOO_LARGE",
                    "Decoded batch input exceeds 8 MiB");
        }

        List<Map<String, Object>> results = Collections.synchronizedList(new ArrayList<>(requests.size()));
        for (int i = 0; i < requests.size(); i++) {
            results.add(null);
        }
        java.util.concurrent.atomic.AtomicInteger succeeded = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger failed = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger remainingOutput =
                new java.util.concurrent.atomic.AtomicInteger(MAX_BATCH_OUTPUT_BYTES);
        Semaphore permits = new Semaphore(concurrency);
        CountDownLatch done = new CountDownLatch(requests.size());

        for (int position = 0; position < requests.size(); position++) {
            RawRequest raw = requests.get(position);
            int slot = position;
            batchExecutor.execute(() -> {
                String itemRequestId = requestId();
                boolean acquired = false;
                try {
                    try {
                        permits.acquire();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Batch worker interrupted");
                    }
                    acquired = true;
                    long started = System.nanoTime();
                    BurpGateway.SendResult sent = gateway.send(raw.service(), raw.bytes());
                    long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                    CombinedBinary combined = combined(raw.bytes(), sent.response());
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("statusCode", sent.statusCode());
                    entry.put("request", summaryBinary(combined.first(), full));
                    Map<String, Object> response = responseBinary(combined.second(), full, sent.response() != null, remainingOutput);
                    entry.put("response", response);
                    entry.put("hasResponse", sent.response() != null);
                    entry.put("truncated", combined.first().truncated()
                            || (combined.second() != null && combined.second().truncated()));
                    entry.put("omitted", response == null);
                    entry.put("durationMs", durationMs);
                    results.set(slot, entry);
                    succeeded.incrementAndGet();
                } catch (RuntimeException exception) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("statusCode", null);
                    entry.put("request", Map.of(
                            "byteLength", raw.bytes().length,
                            "sha256", hex(sha256(raw.bytes()))));
                    entry.put("response", null);
                    entry.put("hasResponse", false);
                    entry.put("truncated", false);
                    entry.put("omitted", false);
                    entry.put("durationMs", 0);
                    entry.put("error", Map.of("code", "SEND_FAILED", "requestId", itemRequestId));
                    results.set(slot, entry);
                    failed.incrementAndGet();
                } finally {
                    if (acquired) {
                        permits.release();
                    }
                    done.countDown();
                }
            });
        }
        try {
            done.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BridgeException(503, "QUEUE_FULL", "Batch execution was interrupted");
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("results", results);
        output.put("succeeded", succeeded.get());
        output.put("failed", failed.get());
        return output;
    }

    private static Map<String, Object> summaryBinary(BinaryPart part, boolean full) {
        Map<String, Object> output = new LinkedHashMap<>();
        if (full) {
            output.put("data", Base64.getEncoder().encodeToString(part.bytes()));
        }
        output.put("byteLength", part.originalBytes());
        output.put("sha256", part.sha256());
        return output;
    }

    private static Map<String, Object> responseBinary(
            BinaryPart part,
            boolean full,
            boolean hasResponse,
            java.util.concurrent.atomic.AtomicInteger remainingOutput) {
        if (!hasResponse || part == null) {
            return null;
        }
        int cost = full ? part.bytes().length : 0;
        int available = remainingOutput.get();
        if (cost > available) {
            // The batch output budget is spent: report the item without a body.
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("omitted", true);
            output.put("byteLength", part.originalBytes());
            output.put("sha256", part.sha256());
            return output;
        }
        remainingOutput.addAndGet(-cost);
        Map<String, Object> output = summaryBinary(part, full);
        output.put("omitted", false);
        return output;
    }

    private synchronized Map<String, Object> collaboratorPayload(JsonNode input) {
        requireObject(input);
        String customData = optionalText(input, "customData", MAX_COLLABORATOR_CUSTOM_DATA);
        if (customData != null && !COLLABORATOR_CUSTOM_DATA.matcher(customData).matches()) {
            throw badInput("'customData' must be 1..64 characters of letters, digits, ':', '.', '_' or '-'");
        }
        boolean includeServerLocation = optionalBoolean(input, "includeServerLocation", true);
        if (collaboratorPayloadCount >= MAX_COLLABORATOR_PAYLOADS) {
            throw new BridgeException(400, "COLLABORATOR_LIMIT",
                    "At most " + MAX_COLLABORATOR_PAYLOADS + " Collaborator payloads per session");
        }

        BurpGateway.CollaboratorPayload payload;
        try {
            payload = gateway.generateCollaboratorPayload(customData, includeServerLocation);
        } catch (RuntimeException exception) {
            audit.record("collaborator", "POST /v1/collaborator/payload",
                    "GEN_" + exception.getClass().getSimpleName(), 0);
            throw new BridgeException(500, "COLLABORATOR_ERROR",
                    "Collaborator payload generation failed: " + exception.getClass().getSimpleName());
        }
        if (payload == null) {
            throw new BridgeException(503, "COLLABORATOR_UNAVAILABLE",
                    "Burp could not create a Collaborator payload (Collaborator unreachable?)");
        }
        collaboratorPayloadCount++;

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("ref", opaqueTokens.reference(COLLABORATOR_REF_KIND, payload.payloadId()));
        output.put("payloadId", payload.payloadId());
        output.put("payload", payload.payload());
        if (payload.server() != null) {
            output.put("server", payload.server());
        }
        if (payload.customData() != null) {
            output.put("customData", payload.customData());
        }
        return output;
    }

    private Map<String, Object> collaboratorInteractions(JsonNode input) {
        requireObject(input);
        boolean hasRef = input.has("ref") && !input.get("ref").isNull();
        boolean hasPayload = input.has("payload") && !input.get("payload").isNull();
        if (hasRef && hasPayload) {
            throw badInput("Provide either 'ref' or 'payload', not both");
        }
        String payloadId = null;
        if (hasRef) {
            String ref = optionalText(input, "ref", 1024);
            payloadId = opaqueTokens.parseReference(COLLABORATOR_REF_KIND, ref, "STALE_REFERENCE");
        } else if (hasPayload) {
            String payload = optionalText(input, "payload", 253);
            // Burp builds payloads as "<id>.<server>": the leading label is the interaction id prefix.
            int separator = payload.indexOf('.');
            payloadId = separator <= 0 ? payload : payload.substring(0, separator);
        }
        String mode = optionalText(input, "mode", 16);
        if (mode != null && !"summary".equals(mode) && !"full".equals(mode)) {
            throw badInput("'mode' must be 'summary' or 'full'");
        }
        boolean full = !"summary".equals(mode);

        List<BurpGateway.CollaboratorInteraction> interactions = payloadId == null
                ? gateway.allCollaboratorInteractions()
                : gateway.collaboratorInteractions(payloadId);

        List<Map<String, Object>> entries = new ArrayList<>();
        // Montoya hands each interaction over exactly once (getAllInteractions is a one-shot
        // fetch), so everything Burp reports here is new since the previous poll. The bridge
        // keeps no seen-set: Burp already implements "new since last poll".
        for (BurpGateway.CollaboratorInteraction interaction : interactions) {
            try {
                entries.add(projectInteraction(
                        opaqueTokens.reference(COLLABORATOR_REF_KIND, interaction.id()), interaction, full));
            } catch (RuntimeException exception) {
                // Keep the rest of the list usable.
            }
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("interactions", entries);
        output.put("total", entries.size());
        output.put("newCount", entries.size());
        return output;
    }

    private static Map<String, Object> projectInteraction(
            String ref, BurpGateway.CollaboratorInteraction interaction, boolean full) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("ref", ref);
        output.put("type", interaction.type());
        output.put("time", Objects.requireNonNull(interaction.time()).toString());
        output.put("clientIp", interaction.clientIp());
        output.put("clientPort", interaction.clientPort());
        if (interaction.customData() != null) {
            output.put("customData", interaction.customData());
        }
        if (interaction.dnsQueryType() != null || interaction.dnsDomain() != null) {
            Map<String, Object> dns = new LinkedHashMap<>();
            dns.put("queryType", Objects.requireNonNull(interaction.dnsQueryType()));
            if (interaction.dnsDomain() != null) {
                dns.put("domain", interaction.dnsDomain());
            }
            if (full && interaction.dnsQuery() != null) {
                dns.put("rawHex", hex(interaction.dnsQuery()));
            }
            output.put("dns", dns);
        }
        if (interaction.httpMethod() != null) {
            Map<String, Object> http = new LinkedHashMap<>();
            http.put("method", interaction.httpMethod());
            http.put("path", Objects.requireNonNull(interaction.httpPath()));
            http.put("request", binaryPart(interaction.httpRequest(), full));
            http.put("response", binaryPart(interaction.httpResponse(), full));
            output.put("http", http);
        }
        if (interaction.smtpProtocol() != null) {
            output.put("smtp", Map.of("protocol", interaction.smtpProtocol()));
        }
        return output;
    }

    private static Map<String, Object> binaryPart(byte[] bytes, boolean full) {
        if (bytes == null) {
            return null;
        }
        Map<String, Object> output = new LinkedHashMap<>();
        if (full) {
            output.put("data", Base64.getEncoder().encodeToString(bytes));
        }
        output.put("byteLength", bytes.length);
        output.put("sha256", hex(sha256(bytes)));
        return output;
    }

    private void validateTransport(HttpExchange exchange) {
        Headers headers = exchange.getRequestHeaders();
        if (headers.containsKey("Origin")) {
            throw new BridgeException(403, "ORIGIN_REJECTED", "Browser-origin requests are not accepted");
        }
        String expectedHost = "127.0.0.1:" + port();
        String host = headers.getFirst("Host");
        if (host == null || !host.equalsIgnoreCase(expectedHost)) {
            throw new BridgeException(400, "INVALID_HOST", "Invalid Host header");
        }
        String authorization = headers.getFirst("Authorization");
        String supplied = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : "";
        byte[] suppliedHash = supplied.length() <= 512
                ? sha256(supplied.getBytes(StandardCharsets.UTF_8)) : sha256(new byte[0]);
        if (!MessageDigest.isEqual(expectedAuthHash, suppliedHash)) {
            throw new BridgeException(401, "UNAUTHORIZED", "Bearer authentication failed");
        }
        if ("POST".equals(exchange.getRequestMethod())) {
            String contentType = headers.getFirst("Content-Type");
            if (contentType == null || !contentType.toLowerCase(Locale.ROOT).matches(
                    "application/json(?:\\s*;\\s*charset=(?:utf-8|\"utf-8\"))?")) {
                throw new BridgeException(415, "UNSUPPORTED_MEDIA_TYPE", "Content-Type must be application/json");
            }
        }
    }

    private JsonNode readJson(HttpExchange exchange) throws IOException {
        String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
        if (contentLength != null) {
            try {
                if (Long.parseLong(contentLength) > MAX_JSON_BYTES) {
                    throw new BridgeException(413, "PAYLOAD_TOO_LARGE", "JSON envelope is too large");
                }
            } catch (NumberFormatException exception) {
                throw badInput("Invalid Content-Length");
            }
        }
        byte[] body;
        try (InputStream input = exchange.getRequestBody()) {
            body = input.readNBytes(MAX_JSON_BYTES + 1);
        }
        if (body.length > MAX_JSON_BYTES) {
            throw new BridgeException(413, "PAYLOAD_TOO_LARGE", "JSON envelope is too large");
        }
        try {
            return mapper.readTree(body);
        } catch (JsonProcessingException exception) {
            throw badInput("Malformed JSON body");
        }
    }

    private static void rejectUnexpectedBody(HttpExchange exchange) {
        String length = exchange.getRequestHeaders().getFirst("Content-Length");
        if (length != null && !"0".equals(length)) {
            throw badInput("A request body is not allowed");
        }
    }

    private static boolean matches(
            BurpGateway.HistoryMessage item,
            String host,
            String method,
            Set<Integer> statusCodes,
            Boolean hasResponse,
            String search,
            Instant from,
            Instant to) {
        try {
            if (host != null && !item.service().host().equalsIgnoreCase(host)) {
                return false;
            }
            if (method != null && !item.method().equalsIgnoreCase(method)) {
                return false;
            }
            if (!statusCodes.isEmpty() && (item.statusCode() == null || !statusCodes.contains(item.statusCode()))) {
                return false;
            }
            if (hasResponse != null && item.hasResponse() != hasResponse) {
                return false;
            }
            if (from != null && (item.time() == null || item.time().isBefore(from))) {
                return false;
            }
            if (to != null && (item.time() == null || item.time().isAfter(to))) {
                return false;
            }
            if (search != null) {
                String needle = search.toLowerCase(Locale.ROOT);
                if (!contains(item.request(), needle) && !contains(item.response(), needle)) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean contains(byte[] bytes, String lowerCaseNeedle) {
        return bytes != null && new String(bytes, StandardCharsets.ISO_8859_1)
                .toLowerCase(Locale.ROOT).contains(lowerCaseNeedle);
    }

    private static Map<String, Object> service(BurpGateway.Service service) {
        return Map.of("host", service.host(), "port", service.port(), "secure", service.secure());
    }

    private static Map<String, Object> binary(BinaryPart part) {
        return Map.of(
                "encoding", "base64",
                "data", Base64.getEncoder().encodeToString(part.bytes()),
                "byteLength", part.originalBytes(),
                "returnedByteLength", part.bytes().length,
                "truncated", part.truncated(),
                "sha256", part.sha256());
    }

    private static CombinedBinary combined(byte[] firstBytes, byte[] secondBytes) {
        int remaining = MAX_RAW_BYTES;
        BinaryPart first = truncate(firstBytes, remaining);
        remaining -= first == null ? 0 : first.bytes().length;
        BinaryPart second = truncate(secondBytes, remaining);
        return new CombinedBinary(first, second);
    }

    private static BinaryPart truncate(byte[] bytes, int maxBytes) {
        if (bytes == null) {
            return null;
        }
        int length = Math.min(bytes.length, Math.max(0, maxBytes));
        byte[] output = length == bytes.length ? bytes.clone() : java.util.Arrays.copyOf(bytes, length);
        return new BinaryPart(output, bytes.length, length < bytes.length, hex(sha256(bytes)));
    }

    private static Set<Integer> statusCodes(JsonNode node) {
        if (node == null || node.isNull()) {
            return Set.of();
        }
        if (!node.isArray() || node.size() > 20) {
            throw badInput("'statusCodes' must be an array with at most 20 entries");
        }
        Set<Integer> result = new HashSet<>();
        for (JsonNode value : node) {
            if (!value.canConvertToInt() || value.intValue() < 100 || value.intValue() > 599) {
                throw badInput("Invalid HTTP status code");
            }
            result.add(value.intValue());
        }
        return result;
    }

    private static String fingerprint(Object... values) {
        StringBuilder canonical = new StringBuilder();
        for (Object value : values) {
            canonical.append(value == null ? "<null>" : value).append('\n');
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                sha256(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder output = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            output.append(Character.forDigit((value >>> 4) & 0x0f, 16));
            output.append(Character.forDigit(value & 0x0f, 16));
        }
        return output.toString();
    }

    private static void requireObject(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw badInput("JSON body must be an object");
        }
    }

    private static JsonNode requiredObject(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isObject()) {
            throw badInput("'" + field + "' must be an object");
        }
        return value;
    }

    private static String requiredText(JsonNode parent, String field, int maxLength) {
        String value = optionalText(parent, field, maxLength);
        if (value == null || value.isBlank()) {
            throw badInput("'" + field + "' is required");
        }
        return value;
    }

    private static String optionalText(JsonNode parent, String field, int maxLength) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.textValue().length() > maxLength) {
            throw badInput("'" + field + "' must be text of at most " + maxLength + " characters");
        }
        return value.textValue();
    }

    private static int requiredInt(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.canConvertToInt()) {
            throw badInput("'" + field + "' must be an integer");
        }
        return value.intValue();
    }

    private static int optionalInt(JsonNode parent, String field, int defaultValue) {
        JsonNode value = parent.get(field);
        return value == null || value.isNull() ? defaultValue : requiredInt(parent, field);
    }

    private static boolean requiredBoolean(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isBoolean()) {
            throw badInput("'" + field + "' must be a boolean");
        }
        return value.booleanValue();
    }

    private static boolean optionalBoolean(JsonNode parent, String field, boolean fallback) {
        Boolean value = optionalBoolean(parent, field);
        return value == null ? fallback : value;
    }

    private static Boolean optionalBoolean(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isBoolean()) {
            throw badInput("'" + field + "' must be a boolean");
        }
        return value.booleanValue();
    }

    private static Instant optionalInstant(JsonNode parent, String field) {
        String value = optionalText(parent, field, 64);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw badInput("'" + field + "' must be an ISO-8601 instant");
        }
    }

    private static BridgeException badInput(String message) {
        return new BridgeException(400, "INVALID_REQUEST", message);
    }

    private void sendJson(HttpExchange exchange, int status, Object body, String requestId) throws IOException {
        byte[] output = mapper.writeValueAsBytes(body);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        headers.set("X-Request-Id", requestId);
        exchange.sendResponseHeaders(status, output.length);
        exchange.getResponseBody().write(output);
    }

    private void sendError(HttpExchange exchange, int status, String code, String message, String requestId)
            throws IOException {
        sendJson(exchange, status, Map.of("error", Map.of(
                "code", code,
                "message", message,
                "requestId", requestId)), requestId);
    }

    private void safeSendError(HttpExchange exchange, int status, String code, String message, String requestId) {
        try {
            sendError(exchange, status, code, message, requestId);
        } catch (IOException ignored) {
            // The client may have disconnected while the error was being written.
        }
    }

    private static String requestId() {
        return UUID.randomUUID().toString();
    }

    private static String operation(HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        if (path.startsWith("/v1/proxy-history/") && !path.endsWith("/query")) {
            path = "/v1/proxy-history/{ref}";
        }
        return exchange.getRequestMethod() + " " + path;
    }

    private static ThreadFactory daemonFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private record RawRequest(BurpGateway.Service service, byte[] bytes) { }

    private record BridgeResponse(int status, Object body) { }

    private record BinaryPart(byte[] bytes, int originalBytes, boolean truncated, String sha256) { }

    private record CombinedBinary(BinaryPart first, BinaryPart second) { }
}
