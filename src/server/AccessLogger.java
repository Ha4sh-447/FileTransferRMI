package server;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Access Logger for the file server.
 * 
 * DC Concept: Stateful metadata tracking (Section A)
 * 
 * While the server is stateless for client sessions, it maintains
 * operational logs for monitoring and auditing. Each operation
 * (upload, download, delete, list) is logged with:
 * - Timestamp
 * - Operation type
 * - File name (if applicable)
 * - Client thread info
 * - Outcome (success/failure)
 * 
 * Logs are written to both the console and a persistent log file.
 */
public class AccessLogger {

    private final String serverName;
    private final PrintWriter logWriter;
    private final SimpleDateFormat dateFormat;

    /**
     * Create an access logger that writes to a log file.
     * 
     * @param serverName Identifier for this server instance
     * @param logDir     Directory to store log files
     */
    public AccessLogger(String serverName, String logDir) throws IOException {
        this.serverName = serverName;
        this.dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

        // Ensure log directory exists
        File dir = new File(logDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        String logFile = logDir + File.separator + serverName + "_access.log";
        this.logWriter = new PrintWriter(
                new BufferedWriter(new FileWriter(logFile, true)), true);

        log("SYSTEM", "---", "Logger initialized for server: " + serverName);
    }

    /**
     * Log an operation.
     * 
     * @param operation Operation type (UPLOAD, DOWNLOAD, DELETE, LIST, etc.)
     * @param fileName  Name of the file involved, or "---" if not applicable
     * @param detail    Additional detail or outcome message
     */
    public synchronized void log(String operation, String fileName, String detail) {
        String timestamp = dateFormat.format(new Date());
        String threadName = Thread.currentThread().getName();
        String entry = String.format("[%s] [%s] [Thread:%s] %-10s %-30s %s",
                timestamp, serverName, threadName, operation, fileName, detail);

        // Write to console
        System.out.println(entry);

        // Write to log file
        logWriter.println(entry);
    }

    /**
     * Log a successful operation.
     */
    public void logSuccess(String operation, String fileName, String detail) {
        log(operation, fileName, "SUCCESS — " + detail);
    }

    /**
     * Log a failed operation.
     */
    public void logFailure(String operation, String fileName, String detail) {
        log(operation, fileName, "FAILURE — " + detail);
    }

    /**
     * Close the log writer.
     */
    public void close() {
        logWriter.close();
    }
}
