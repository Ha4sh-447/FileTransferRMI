package common;

import java.io.Serializable;

/**
 * Diagnostic Data Transfer Object (DTO) for demonstrating server architecture.
 * 
 * DC Concept: Stateless/Stateful Design (Point A)
 * DC Concept: Server Creation Semantic (Point B)
 */
public class ServerDiagnostics implements Serializable {
    private static final long serialVersionUID = 1L;

    public final String serverName;
    public final long startupTimestamp;
    public final boolean isStateless;
    public final int activeSessionCount; // Should be 0 in a stateless design
    public final String creationSemantic; // e.g., "Persistent/UnicastRemoteObject"

    public ServerDiagnostics(String name, long startup, boolean stateless, int sessions, String semantic) {
        this.serverName = name;
        this.startupTimestamp = startup;
        this.isStateless = stateless;
        this.activeSessionCount = sessions;
        this.creationSemantic = semantic;
    }

    @Override
    public String toString() {
        return String.format(
                "Server: %s\n" +
                        "  - Created: %tc\n" +
                        "  - Semantic: %s\n" +
                        "  - Stateless: %b (Active Sessions: %d)",
                serverName, startupTimestamp, creationSemantic, isStateless, activeSessionCount);
    }
}
