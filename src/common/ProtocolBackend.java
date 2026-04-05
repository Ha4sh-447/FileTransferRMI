package common;

import java.io.Serializable;

/**
 * Interface representing a generic backend protocol for latency testing.
 */
public interface ProtocolBackend {
    /**
     * Perform a simple "ping" or metadata fetch and return latency in ms.
     */
    long measurePing(String targetUrl) throws Exception;

    /**
     * Transfer a data payload and return time in ms.
     */
    long measureTransfer(byte[] data, String targetUrl) throws Exception;

    /**
     * Measure chunk transfer latency.
     */
    long measureChunk(String fileName, byte[] data, int index, int total, String targetUrl) throws Exception;

    /**
     * Resets any state (like active chunk maps) for a new file transfer.
     */
    void reset();

    String getName();
}
