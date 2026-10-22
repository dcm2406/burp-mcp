package io.github.burpmcp.extension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

final class FakeBurpGateway implements BurpGateway {
    final List<HistoryMessage> history = new ArrayList<>();
    final java.util.Set<String> failingHosts = new java.util.HashSet<>();
    Runnable onSend;
    Service sentService;
    byte[] sentRequest;
    Service repeaterService;
    byte[] repeaterRequest;
    String repeaterTitle;
    SendResult sendResult = new SendResult(200,
            "HTTP/1.1 200 OK\r\n\r\nok".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));

    @Override
    public String burpVersion() {
        return "test-burp";
    }

    @Override
    public List<HistoryMessage> proxyHistory() {
        return List.copyOf(history);
    }

    @Override
    public Optional<HistoryMessage> proxyHistoryItem(int id) {
        return history.stream().filter(item -> item.id() == id).findFirst();
    }

    @Override
    public SendResult send(Service service, byte[] request) {
        if (failingHosts.contains(service.host())) {
            throw new IllegalStateException("send failed for " + service.host());
        }
        if (onSend != null) {
            onSend.run();
        }
        sentService = service;
        sentRequest = Arrays.copyOf(request, request.length);
        return sendResult;
    }

    final List<CollaboratorPayload> collaboratorPayloads = new ArrayList<>();
    final List<CollaboratorInteraction> collaboratorInteractions = new ArrayList<>();
    private int collaboratorPayloadSequence;

    @Override
    public CollaboratorPayload generateCollaboratorPayload(String customData, boolean includeServerLocation) {
        if (customData != null && !customData.matches("^[A-Za-z0-9]{1,16}$")) {
            throw new IllegalArgumentException("invalid customData");
        }
        collaboratorPayloadSequence++;
        String id = "payload" + collaboratorPayloadSequence + "id";
        CollaboratorPayload payload = new CollaboratorPayload(
                id,
                id + (includeServerLocation ? ".burpcollaborator.net" : ""),
                includeServerLocation ? "burpcollaborator.net" : null,
                customData);
        collaboratorPayloads.add(payload);
        return payload;
    }

    @Override
    public List<CollaboratorInteraction> collaboratorInteractions(String payloadId) {
        return collaboratorInteractions.stream()
                .filter(interaction -> interaction.id().startsWith(payloadId))
                .toList();
    }

    @Override
    public List<CollaboratorInteraction> allCollaboratorInteractions() {
        return List.copyOf(collaboratorInteractions);
    }

    @Override
    public void sendToRepeater(Service service, byte[] request, String title) {
        repeaterService = service;
        repeaterRequest = Arrays.copyOf(request, request.length);
        repeaterTitle = title;
    }
}
