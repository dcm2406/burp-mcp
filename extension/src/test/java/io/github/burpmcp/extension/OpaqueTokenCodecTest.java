package io.github.burpmcp.extension;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpaqueTokenCodecTest {
    @Test
    void rejectsTamperedAndStaleReferences() {
        OpaqueTokenCodec oldSession = new OpaqueTokenCodec("old-session", new byte[32]);
        OpaqueTokenCodec currentSession = new OpaqueTokenCodec("current-session", new byte[32]);
        String stale = oldSession.reference(42);
        String valid = currentSession.reference(42);
        int signature = valid.indexOf('.') + 1;
        String tampered = valid.substring(0, signature)
                + (valid.charAt(signature) == 'A' ? 'B' : 'A')
                + valid.substring(signature + 1);

        assertThatThrownBy(() -> currentSession.parseReference(stale))
                .isInstanceOf(BridgeException.class)
                .extracting(exception -> ((BridgeException) exception).code())
                .isEqualTo("STALE_REFERENCE");
        assertThatThrownBy(() -> currentSession.parseReference(tampered))
                .isInstanceOf(BridgeException.class)
                .extracting(exception -> ((BridgeException) exception).code())
                .isEqualTo("INVALID_REFERENCE");
    }

    @Test
    void bindsCursorToFilterFingerprint() {
        OpaqueTokenCodec codec = new OpaqueTokenCodec("session", new byte[32]);
        String cursor = codec.cursor(50, "filter-a");

        assertThatThrownBy(() -> codec.parseCursor(cursor, "filter-b"))
                .isInstanceOf(BridgeException.class)
                .extracting(exception -> ((BridgeException) exception).code())
                .isEqualTo("INVALID_CURSOR");
    }
}
