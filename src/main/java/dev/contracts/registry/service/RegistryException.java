package dev.contracts.registry.service;

/** Base class carrying an HTTP status code and a stable error code. */
public class RegistryException extends RuntimeException {
    private final int status;
    private final String code;

    public RegistryException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }
    public String code() { return code; }
}
