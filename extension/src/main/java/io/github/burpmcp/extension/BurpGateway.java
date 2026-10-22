package io.github.burpmcp.extension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Narrow boundary around Burp so the bridge can be tested without a live Burp instance. */
public interface BurpGateway {
    record Service(String host, int port, boolean secure) { }

    record HistoryMessage(
            int id,
            Instant time,
            String method,
            String path,
            String url,
            Service service,
            Integer statusCode,
            String mimeType,
            boolean edited,
            int listenerPort,
            byte[] request,
            byte[] response) {
        public boolean hasResponse() {
            return response != null;
        }
    }

    record SendResult(Integer statusCode, byte[] response) { }

    record CollaboratorPayload(String payloadId, String payload, String server, String customData) { }

    /**
     * One out-of-band interaction. Detail fields are null when the interaction is of another type;
     * raw bytes are populated for HTTP/SMTP hits and null for DNS-only interactions.
     */
    record CollaboratorInteraction(
            String id,
            String type,
            Instant time,
            String clientIp,
            int clientPort,
            String customData,
            String dnsQueryType,
            String dnsDomain,
            byte[] dnsQuery,
            String httpMethod,
            String httpPath,
            byte[] httpRequest,
            byte[] httpResponse,
            String smtpProtocol) {
    }

    String burpVersion();

    List<HistoryMessage> proxyHistory();

    Optional<HistoryMessage> proxyHistoryItem(int id);

    SendResult send(Service service, byte[] request);

    void sendToRepeater(Service service, byte[] request, String title);

    /** Creates a Collaborator payload; returns null when no Collaborator client is available. */
    CollaboratorPayload generateCollaboratorPayload(String customData, boolean includeServerLocation);

    /** All interactions recorded for one payload id (Burp prefixes interaction ids with it). */
    List<CollaboratorInteraction> collaboratorInteractions(String payloadId);

    /** Every interaction of the current session's Collaborator client. */
    List<CollaboratorInteraction> allCollaboratorInteractions();
}
