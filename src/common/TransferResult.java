package common;

import java.io.Serializable;

/**
 * Serializable result object returned by file transfer operations.
 * Encapsulates success/failure status, a human-readable message,
 * and an optional checksum for integrity verification.
 * 
 * Using a structured result object (instead of raw strings/booleans)
 * makes the RPC protocol specification clear and extensible.
 */
public class TransferResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private final boolean success;
    private final String message;
    private final String checksum; // optional, null if not applicable

    public TransferResult(boolean success, String message, String checksum) {
        this.success = success;
        this.message = message;
        this.checksum = checksum;
    }

    public TransferResult(boolean success, String message) {
        this(success, message, null);
    }

    // ---- Getters ----

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public String getChecksum() {
        return checksum;
    }

    @Override
    public String toString() {
        return (success ? "[SUCCESS] " : "[FAILURE] ") + message
                + (checksum != null ? "  (checksum: " + checksum + ")" : "");
    }
}
