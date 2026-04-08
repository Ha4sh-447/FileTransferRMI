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
    public static final String PROTOCOL_VERSION = "2.1-AK-FULL";

    private final boolean success;
    private final String message;
    private final String checksum;
    private final String protocolVersion;
    private final Receipt receipt;

    /**
     * DC Concept: Synchronous / Asynchronous Communication (Receipt based) - Point
     * D
     */
    public static class Receipt implements Serializable {
        private static final long serialVersionUID = 1L;
        public final long timestamp;
        public final String serverSignature; // serverName:checksum

        public Receipt(String server, String hash) {
            this.timestamp = System.currentTimeMillis();
            this.serverSignature = server + ":" + (hash != null ? hash.substring(0, 8) : "N/A");
        }
    }

    public TransferResult(boolean success, String message, String checksum, String serverName) {
        this.success = success;
        this.message = message;
        this.checksum = checksum;
        this.protocolVersion = PROTOCOL_VERSION;
        this.receipt = success ? new Receipt(serverName, checksum) : null;
    }

    public TransferResult(boolean success, String message, String serverName) {
        this(success, message, null, serverName);
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

    public String getProtocolVersion() {
        return protocolVersion;
    }

    public Receipt getReceipt() {
        return receipt;
    }

    @Override
    public String toString() {
        String base = (success ? "[SUCCESS] " : "[FAILURE] ") + message
                + (checksum != null ? " (Hash: " + checksum.substring(0, 8) + ")" : "");
        if (receipt != null) {
            base += String.format(" | Receipt: %s @ %tc", receipt.serverSignature, receipt.timestamp);
        }
        return base;
    }
}
