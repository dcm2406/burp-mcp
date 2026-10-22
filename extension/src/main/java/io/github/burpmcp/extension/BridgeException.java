package io.github.burpmcp.extension;

final class BridgeException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int status;
    private final String code;

    BridgeException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    int status() {
        return status;
    }

    String code() {
        return code;
    }
}
