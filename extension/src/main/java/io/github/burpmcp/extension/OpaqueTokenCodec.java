package io.github.burpmcp.extension;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;

final class OpaqueTokenCodec {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final String sessionId;
    private final SecretKeySpec key;

    OpaqueTokenCodec(String sessionId, byte[] key) {
        this.sessionId = sessionId;
        this.key = new SecretKeySpec(key.clone(), "HmacSHA256");
    }

    String reference(int id) {
        return reference("ref", Integer.toString(id));
    }

    /** String-keyed reference (e.g. Collaborator payload ids); `kind` prevents cross-use of tokens. */
    String reference(String kind, String value) {
        return encode(kind, value);
    }

    /** Parses a string-keyed reference minted by {@link #reference(String, String)}. */
    String parseReference(String kind, String token, String staleCode) {
        return decode(token, kind, "INVALID_REFERENCE", staleCode);
    }

    int parseReference(String token) {
        String value = decode(token, "ref", "INVALID_REFERENCE", "STALE_REFERENCE");
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw invalid("INVALID_REFERENCE");
        }
    }

    String cursor(int offset, String fingerprint) {
        return encode("cursor", offset + ":" + fingerprint);
    }

    int parseCursor(String token, String fingerprint) {
        String value = decode(token, "cursor", "INVALID_CURSOR", "STALE_CURSOR");
        int separator = value.indexOf(':');
        if (separator <= 0 || !constantEquals(value.substring(separator + 1), fingerprint)) {
            throw invalid("INVALID_CURSOR");
        }
        try {
            int offset = Integer.parseInt(value.substring(0, separator));
            if (offset < 0) {
                throw invalid("INVALID_CURSOR");
            }
            return offset;
        } catch (NumberFormatException exception) {
            throw invalid("INVALID_CURSOR");
        }
    }

    private String encode(String kind, String value) {
        String payload = kind + "\n" + sessionId + "\n" + value;
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        return ENCODER.encodeToString(payloadBytes) + "." + ENCODER.encodeToString(sign(payloadBytes));
    }

    private String decode(String token, String kind, String invalidCode, String staleCode) {
        if (token == null || token.length() > 1024) {
            throw invalid(invalidCode);
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 2) {
            throw invalid(invalidCode);
        }
        final byte[] payloadBytes;
        final byte[] signature;
        try {
            payloadBytes = DECODER.decode(parts[0]);
            signature = DECODER.decode(parts[1]);
        } catch (IllegalArgumentException exception) {
            throw invalid(invalidCode);
        }
        String payload = new String(payloadBytes, StandardCharsets.UTF_8);
        String[] fields = payload.split("\\n", 3);
        if (fields.length != 3 || !constantEquals(fields[0], kind)) {
            throw invalid(invalidCode);
        }
        if (!constantEquals(fields[1], sessionId)) {
            throw new BridgeException(410, staleCode, "The opaque token belongs to another bridge session");
        }
        if (!MessageDigest.isEqual(sign(payloadBytes), signature)) {
            throw invalid(invalidCode);
        }
        return fields[2];
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(payload);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC unavailable", exception);
        }
    }

    private static boolean constantEquals(String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }

    private static BridgeException invalid(String code) {
        return new BridgeException(400, code, "Invalid opaque token");
    }
}
