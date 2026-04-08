package common;

/**
 * DC Concept: Persistent and Transient Communication (Point C)
 */
public enum ConnectionType {
    TRANSIENT_PROBE, // Short-lived, used for health checks (pings)
    PERSISTENT_POOL // Pooled, used for heavy data transfer (uploads/downloads)
}
