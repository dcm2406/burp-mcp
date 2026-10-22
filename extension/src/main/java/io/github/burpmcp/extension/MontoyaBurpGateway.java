package io.github.burpmcp.extension;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.collaborator.CollaboratorClient;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.proxy.ProxyHttpRequestResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

final class MontoyaBurpGateway implements BurpGateway {
    private final MontoyaApi api;
    private burp.api.montoya.collaborator.CollaboratorClient collaborator;

    MontoyaBurpGateway(MontoyaApi api) {
        this.api = Objects.requireNonNull(api);
    }

    @Override
    public String burpVersion() {
        return String.valueOf(api.burpSuite().version());
    }

    @Override
    public List<HistoryMessage> proxyHistory() {
        List<HistoryMessage> result = new ArrayList<>();
        for (ProxyHttpRequestResponse item : api.proxy().history()) {
            try {
                result.add(convert(item));
            } catch (RuntimeException ignored) {
                // A malformed history item must not make the whole page unavailable.
            }
        }
        return result;
    }

    @Override
    public Optional<HistoryMessage> proxyHistoryItem(int id) {
        for (ProxyHttpRequestResponse item : api.proxy().history()) {
            try {
                if (item.id() == id) {
                    return Optional.of(convert(item));
                }
            } catch (RuntimeException ignored) {
                // Continue past malformed entries.
            }
        }
        return Optional.empty();
    }

    @Override
    public SendResult send(Service service, byte[] requestBytes) {
        HttpRequest request = request(service, requestBytes);
        HttpRequestResponse result = api.http().sendRequest(request);
        byte[] response = result.response() == null ? null : result.response().toByteArray().getBytes();
        Integer status = result.response() == null ? null : (int) result.response().statusCode();
        return new SendResult(status, response);
    }

    @Override
    public void sendToRepeater(Service service, byte[] requestBytes, String title) {
        HttpRequest request = request(service, requestBytes);
        if (title == null || title.isBlank()) {
            api.repeater().sendToRepeater(request);
        } else {
            api.repeater().sendToRepeater(request, title);
        }
    }

    @Override
    public CollaboratorPayload generateCollaboratorPayload(String customData, boolean includeServerLocation) {
        CollaboratorClient client = collaboratorClient();
        if (client == null) {
            api.logging().logToError("burp-mcp: no Collaborator client; payload generation aborted");
            return null;
        }
        try {
            return buildPayload(client, customData, includeServerLocation);
        } catch (RuntimeException exception) {
            api.logging().logToError("burp-mcp: payload generation failed: "
                    + exception.getClass().getName() + ": " + exception.getMessage());
            throw exception;
        }
    }

    private CollaboratorPayload buildPayload(
            CollaboratorClient client, String customData, boolean includeServerLocation) {
        burp.api.montoya.collaborator.CollaboratorPayload payload =
                customData == null || customData.isBlank()
                        ? (includeServerLocation
                                ? client.generatePayload()
                                : client.generatePayload(burp.api.montoya.collaborator.PayloadOption.WITHOUT_SERVER_LOCATION))
                        : (includeServerLocation
                                ? client.generatePayload(customData)
                                : client.generatePayload(customData,
                                        burp.api.montoya.collaborator.PayloadOption.WITHOUT_SERVER_LOCATION));
        return new CollaboratorPayload(
                payload.id().toString(),
                payload.toString(),
                payload.server().map(burp.api.montoya.collaborator.CollaboratorServer::toString).orElse(null),
                payload.customData().orElse(null));
    }

    @Override
    public List<CollaboratorInteraction> collaboratorInteractions(String payloadId) {
        CollaboratorClient client = collaboratorClient();
        if (client == null) {
            return List.of();
        }
        List<CollaboratorInteraction> result = new ArrayList<>();
        for (burp.api.montoya.collaborator.Interaction interaction : client.getAllInteractions()) {
            if (!interaction.id().toString().startsWith(payloadId)) {
                continue;
            }
            try {
                result.add(convertInteraction(interaction));
            } catch (RuntimeException ignored) {
                // A malformed interaction must not hide the others.
            }
        }
        return result;
    }

    @Override
    public List<CollaboratorInteraction> allCollaboratorInteractions() {
        CollaboratorClient client = collaboratorClient();
        if (client == null) {
            return List.of();
        }
        List<CollaboratorInteraction> result = new ArrayList<>();
        for (burp.api.montoya.collaborator.Interaction interaction : client.getAllInteractions()) {
            try {
                result.add(convertInteraction(interaction));
            } catch (RuntimeException ignored) {
                // A malformed interaction must not hide the others.
            }
        }
        return result;
    }

    private CollaboratorClient collaboratorClient() {
        if (collaborator == null) {
            try {
                collaborator = api.collaborator().createClient();
            } catch (RuntimeException exception) {
                api.logging().logToError("burp-mcp: Collaborator client creation failed: "
                        + exception.getClass().getName() + ": " + exception.getMessage());
                // Collaborator is unavailable (e.g. no network path to the Collaborator cloud).
                return null;
            }
        }
        return collaborator;
    }

    /**
     * Montoya exposes the raw DNS query, not a domain string. The query name is the wire-format
     * label sequence at the end of the question section; decode it best-effort so the agent can
     * read what the target asked for (e.g. exfiltrated data in a subdomain).
     */
    private static String decodeDnsQueryName(byte[] query) {
        if (query == null || query.length <= 12) {
            return null;
        }
        int cursor = 12; // DNS header is 12 bytes
        StringBuilder name = new StringBuilder();
        while (cursor < query.length) {
            int labelLength = query[cursor] & 0xFF;
            if (labelLength == 0) {
                break;
            }
            int start = cursor + 1;
            int end = Math.min(start + labelLength, query.length);
            if (end <= start) {
                break;
            }
            if (name.length() > 0) {
                name.append('.');
            }
            for (int index = start; index < end; index++) {
                char character = (char) (query[index] & 0xFF);
                name.append(character >= 0x20 && character < 0x7F ? character : '?');
            }
            cursor = end;
        }
        return name.length() == 0 ? null : name.toString();
    }

    private static CollaboratorInteraction convertInteraction(burp.api.montoya.collaborator.Interaction interaction) {
        String dnsQueryType = null;
        String dnsDomain = null;
        byte[] dnsQuery = null;
        String httpMethod = null;
        String httpPath = null;
        byte[] httpRequest = null;
        byte[] httpResponse = null;
        String smtpProtocol = null;
        if (interaction.dnsDetails().isPresent()) {
            burp.api.montoya.collaborator.DnsDetails details = interaction.dnsDetails().get();
            dnsQueryType = details.queryType().name();
            dnsQuery = details.query().getBytes();
            dnsDomain = decodeDnsQueryName(dnsQuery);
        }
        if (interaction.httpDetails().isPresent()) {
            burp.api.montoya.collaborator.HttpDetails details = interaction.httpDetails().get();
            burp.api.montoya.http.message.HttpRequestResponse exchange = details.requestResponse();
            httpMethod = exchange.request().method();
            httpPath = exchange.request().path();
            httpRequest = exchange.request().toByteArray().getBytes();
            httpResponse = exchange.response() == null ? null : exchange.response().toByteArray().getBytes();
        }
        if (interaction.smtpDetails().isPresent()) {
            burp.api.montoya.collaborator.SmtpDetails details = interaction.smtpDetails().get();
            smtpProtocol = details.protocol().name();
        }
        return new CollaboratorInteraction(
                interaction.id().toString(),
                interaction.type().name(),
                interaction.timeStamp().toInstant(),
                interaction.clientIp().getHostAddress(),
                interaction.clientPort(),
                interaction.customData().orElse(null),
                dnsQueryType,
                dnsDomain,
                dnsQuery,
                httpMethod,
                httpPath,
                httpRequest,
                httpResponse,
                smtpProtocol);
    }

    private static HttpRequest request(Service service, byte[] bytes) {
        HttpService httpService = HttpService.httpService(service.host(), service.port(), service.secure());
        return HttpRequest.httpRequest(httpService, ByteArray.byteArray(bytes));
    }

    private static HistoryMessage convert(ProxyHttpRequestResponse item) {
        HttpRequest request = item.finalRequest();
        HttpService service = request.httpService();
        byte[] response = item.response() == null ? null : item.response().toByteArray().getBytes();
        Integer status = item.response() == null ? null : (int) item.response().statusCode();
        return new HistoryMessage(
                item.id(),
                item.time().toInstant(),
                request.method(),
                request.path(),
                request.url(),
                new Service(service.host(), service.port(), service.secure()),
                status,
                String.valueOf(item.mimeType()),
                item.edited(),
                item.listenerPort(),
                request.toByteArray().getBytes(),
                response);
    }
}
