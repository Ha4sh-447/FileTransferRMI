package client;

import common.*;
import registry.LoadBalancerService;

import java.io.*;
import java.nio.file.*;
import java.rmi.*;
import java.rmi.registry.*;
import java.rmi.server.UnicastRemoteObject;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.zip.*;

/**
 * Interactive command-line client for the Distributed File Transfer System.
 * Connects via Load Balancer and performs file operations with automatic
 * retry/failover.
 */
public class FileTransferClient extends UnicastRemoteObject implements FileUpdateCallback {

    // ──── Configuration ────
    private static final int MAX_RETRIES = 3; // At-least-once retry count
    private static final String CHECKSUM_ALGO = "SHA-256";
    private static final String DOWNLOAD_DIR = "downloads";

    // ──── State ────
    private String lbHost;
    private int lbPort;
    private FileTransferService currentServer;
    private String currentServerUrl;
    private LoadBalancerService loadBalancer;

    public FileTransferClient(String lbHost, int lbPort) throws RemoteException {
        super();
        this.lbHost = lbHost;
        this.lbPort = lbPort;
    }

    @Override
    public void onFileUpdate(String message) throws RemoteException {
        System.out.println("\n\n  [ASYNC NOTIFICATION] " + message);
        System.out.print("  Choose option [1-8] or wait for prompt: ");
    }

    /**
     * Connect to the Load Balancer and get a server assignment.
     */
    private void connectToLoadBalancer() throws Exception {
        System.out.println("\n  Connecting to Load Balancer at " + lbHost + ":" + lbPort + "...");
        Registry registry = LocateRegistry.getRegistry(lbHost, lbPort);
        loadBalancer = (LoadBalancerService) registry.lookup("LoadBalancer");
        System.out.println("  ✓ Connected to Load Balancer");
    }

    /**
     * Get a file server from the Load Balancer (round-robin).
     */
    private void assignServer() throws Exception {
        String serverUrl = loadBalancer.getNextServer();
        System.out.println("  → Assigned to server: " + serverUrl);

        // Parse the RMI URL and connect
        // URL format: rmi://host:port/bindName
        currentServerUrl = serverUrl;
        currentServer = (FileTransferService) Naming.lookup(serverUrl);

        // Verify connection with ping
        String serverName = currentServer.ping();
        System.out.println("  ✓ Connected to " + serverName);

        // Register for async callbacks (Point D)
        currentServer.registerCallback(this);
    }

    /**
     * Failover: get a different server from the Load Balancer.
     * 
     * DC Concept: If one server fails, clients can automatically
     * connect to another available server (Section F).
     */
    private boolean failover() {
        System.out.println("\n  ⚠ Server unreachable. Attempting failover...");
        try {
            assignServer();
            return true;
        } catch (Exception e) {
            System.out.println("  ✗ Failover failed: " + e.getMessage());
            return false;
        }
    }

    // FILE OPERATIONS WITH RETRY LOGIC
    private void uploadFile(String localFilePath) {
        File file = new File(localFilePath);
        if (!file.exists() || !file.isFile()) {
            System.out.println("  ✗ File not found: " + localFilePath);
            return;
        }

        System.out.println("\n  ┌─ UPLOAD ────────────────────────────────────");
        System.out.println("  │ File: " + file.getName());
        System.out.println("  │ Size: " + formatSize(file.length()));

        try {
            // Read file
            byte[] rawData = Files.readAllBytes(file.toPath());
            String checksum = computeChecksum(rawData);
            System.out.println("  │ SHA-256: " + checksum);

            // Chunked Strategy (Point H) for reducing per-call workload
            int chunkSize = 2 * 1024 * 1024; // 2MB
            if (rawData.length > chunkSize) {
                System.out.println("  │ Size > 2MB. Uploading in chunks to reduce server load...");
                int totalChunks = (int) Math.ceil((double) rawData.length / chunkSize);
                for (int i = 0; i < totalChunks; i++) {
                    int startIdx = i * chunkSize;
                    int len = Math.min(chunkSize, rawData.length - startIdx);
                    byte[] chunkData = new byte[len];
                    System.arraycopy(rawData, startIdx, chunkData, 0, len);
                    String chunkCheck = computeChecksum(chunkData);
                    final int currentI = i;
                    // Point E: Exactly-Once for every chunk
                    String chunkReqId = UUID.randomUUID().toString();
                    TransferResult tr = retryOperation(
                            () -> currentServer.uploadChunk(file.getName(), currentI, totalChunks, chunkData,
                                    chunkCheck, chunkReqId));
                    System.out.println("  │ [ACK/Receipt] " + tr.getReceipt().serverSignature);
                }
                System.out.println("  │ ✓ Upload complete!");
                System.out.println("  └──────────────────────────────────────────────");
                return;
            }

            // Exactly-Once for small files (Point E)
            String uploadId = UUID.randomUUID().toString();
            byte[] compressed = compress(rawData);
            TransferResult result = retryOperation(
                    () -> currentServer.uploadFile(file.getName(), compressed, checksum, uploadId));

            System.out.println("  │ " + result);
            if (result.isSuccess() && result.getChecksum() != null) {
                boolean match = result.getChecksum().equals(checksum);
                System.out.println("  │ Checksum verification: " + (match ? "✓ MATCH" : "✗ MISMATCH"));
            }
            System.out.println("  └──────────────────────────────────────────────");

        } catch (Exception e) {
            System.out.println("  │ ✗ Upload failed: " + e.getMessage());
            System.out.println("  └──────────────────────────────────────────────");
        }
    }

    /**
     * Download a file from the server with integrity verification.
     * 
     * Flow:
     * 1. Request compressed file data from server (with retry)
     * 2. Decompress GZIP data
     * 3. Save to local downloads directory
     * 4. Verify checksum against server metadata
     */
    private void downloadFile(String fileName) {
        System.out.println("\n  ┌─ DOWNLOAD ─────────────────────────────────");
        System.out.println("  │ File: " + fileName);

        try {
            // Download with retry
            byte[] compressed = retryOperation(() -> currentServer.downloadFile(fileName));

            if (compressed == null) {
                System.out.println("  │ ✗ File not found on server.");
                System.out.println("  └──────────────────────────────────────────────");
                return;
            }

            // Decompress
            byte[] rawData = decompress(compressed);
            System.out.println("  │ Received: " + formatSize(compressed.length)
                    + " → " + formatSize(rawData.length) + " (decompressed)");

            // Save to downloads directory
            File dlDir = new File(DOWNLOAD_DIR);
            if (!dlDir.exists())
                dlDir.mkdirs();
            Path savePath = Paths.get(DOWNLOAD_DIR, fileName);
            Files.write(savePath, rawData);
            System.out.println("  │ Saved to: " + savePath.toAbsolutePath());

            // Verify checksum
            String localChecksum = computeChecksum(rawData);
            FileMetadata meta = currentServer.getFileInfo(fileName);
            if (meta != null) {
                boolean match = localChecksum.equals(meta.getChecksum());
                System.out.println("  │ Checksum verification: " + (match ? "✓ MATCH" : "✗ MISMATCH"));
            }
            System.out.println("  │ ✓ Download complete!");
            System.out.println("  └──────────────────────────────────────────────");

        } catch (Exception e) {
            System.out.println("  │ ✗ Download failed: " + e.getMessage());
            System.out.println("  └──────────────────────────────────────────────");
        }
    }

    /**
     * Delete a file from the server.
     */
    private void deleteFile(String fileName) {
        System.out.println("\n  ┌─ DELETE ──────────────────────────────────────");
        System.out.println("  │ File: " + fileName);

        String requestId = UUID.randomUUID().toString(); // Exactly-Once Semantics (Point E)
        try {
            TransferResult result = retryOperation(() -> currentServer.deleteFile(fileName, requestId));
            System.out.println("  │ " + result);
            System.out.println("  └──────────────────────────────────────────────");
        } catch (Exception e) {
            System.out.println("  │ ✗ Delete failed: " + e.getMessage());
            System.out.println("  └──────────────────────────────────────────────");
        }
    }

    /**
     * List all files on the current server.
     */
    private void listFiles() {
        System.out.println("\n  ┌─ FILE LIST ───────────────────────────────────");
        try {
            List<FileMetadata> files = retryOperation(() -> currentServer.listFiles());

            if (files.isEmpty()) {
                System.out.println("  │ (no files on server)");
            } else {
                System.out.println("  │ " + files.size() + " file(s) found:");
                System.out.println("  │");
                System.out.printf("  │ %-4s %-25s %10s  %-14s  %-20s%n",
                        "#", "Name", "Size", "Checksum", "Uploaded");
                System.out.println("  │ " + "─".repeat(80));
                int i = 1;
                for (FileMetadata meta : files) {
                    System.out.printf("  │ %-4d %-25s %10s  %s...  %s%n",
                            i++,
                            meta.getFileName(),
                            meta.getFormattedSize(),
                            meta.getChecksum().substring(0, 12),
                            new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                                    .format(new java.util.Date(meta.getUploadTimestamp())));
                }
            }
            System.out.println("  └──────────────────────────────────────────────");

        } catch (Exception e) {
            System.out.println("  │ ✗ List failed: " + e.getMessage());
            System.out.println("  └──────────────────────────────────────────────");
        }
    }

    /**
     * Get detailed info about a specific file.
     */
    private void getFileInfo(String fileName) {
        System.out.println("\n  ┌─ FILE INFO ───────────────────────────────────");
        try {
            FileMetadata meta = retryOperation(() -> currentServer.getFileInfo(fileName));
            if (meta == null) {
                System.out.println("  │ ✗ File not found: " + fileName);
            } else {
                System.out.println("  │ Name       : " + meta.getFileName());
                System.out.println("  │ Size       : " + meta.getFormattedSize()
                        + " (" + meta.getFileSize() + " bytes)");
                System.out.println("  │ SHA-256    : " + meta.getChecksum());
                System.out.println("  │ Uploaded   : " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                        .format(new java.util.Date(meta.getUploadTimestamp())));
                System.out.println("  │ Last Access: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                        .format(new java.util.Date(meta.getLastAccessTimestamp())));
            }
            System.out.println("  └──────────────────────────────────────────────");
        } catch (Exception e) {
            System.out.println("  │ ✗ Info failed: " + e.getMessage());
            System.out.println("  └──────────────────────────────────────────────");
        }
    }

    /**
     * Demonstrate Concurrent Access to Multiple Servers (Point F).
     * Parallel query to all registered servers.
     */
    private void parallelListAll() {
        System.out.println("\n  ┌─ PARALLEL CLUSTER SCAN (Point F) ───────────");
        try {
            List<String> servers = loadBalancer.getAllServers();
            for (String url : servers) {
                new Thread(() -> {
                    try {
                        FileTransferService s = (FileTransferService) Naming.lookup(url);
                        List<FileMetadata> files = s.listFiles();
                        System.out.println("  │ " + url + " reporting " + files.size() + " files.");
                    } catch (Exception ignored) {
                    }
                }).start();
            }
        } catch (Exception e) {
            System.out.println("  │ ✗ Failed: " + e.getMessage());
        }
    }

    private void showDiagnostics() {
        System.out.println("\n  ┌─ SERVER DIAGNOSTICS (A & B) ───────────");
        try {
            ServerDiagnostics diag = currentServer.getDiagnostics();
            System.out.println(diag);
            System.out.println("  - Protocol Version: " + currentServer.getProtocolVersion());
        } catch (Exception e) {
            System.out.println("  │ ✗ Failed: " + e.getMessage());
        }
        System.out.println("  └────────────────────────────────────────────");
    }

    private void showServers() {
        System.out.println("\n  ┌─ REGISTERED SERVERS ──────────────────────────");
        try {
            List<String> servers = loadBalancer.getAllServers();
            if (servers.isEmpty()) {
                System.out.println("  │ (no servers registered)");
            } else {
                for (String s : servers) {
                    System.out.println("  │ " + s);
                }
            }
            System.out.println("  │");
            System.out.println("  │ Currently connected to: " + currentServerUrl);
            System.out.println("  └──────────────────────────────────────────────");
        } catch (Exception e) {
            System.out.println("  │ ✗ Failed: " + e.getMessage());
            System.out.println("  └──────────────────────────────────────────────");
        }
    }

    // RETRY LOGIC — At-Least-Once Semantics

    /**
     * Functional interface for remote operations.
     */
    @FunctionalInterface
    private interface RemoteOperation<T> {
        T execute() throws RemoteException;
    }

    /**
     * Execute a remote operation with at-least-once retry semantics.
     * 
     * DC Concept: At-Least-Once Call Semantics (Section E)
     * 
     * If the call fails due to a network/server error:
     * 1. Retry up to MAX_RETRIES times
     * 2. On each failure, attempt failover to another server
     * 3. Use exponential backoff between retries
     * 
     * DC Concept: Proper Selection of Timeout Values (Section J)
     * - Each retry waits longer (1s, 2s, 4s) to handle transient failures
     * - Prevents unnecessary retransmissions from short timeouts
     * 
     * @param operation The remote operation to execute
     * @return The result of the operation
     * @throws Exception if all retries are exhausted
     */
    private <T> T retryOperation(RemoteOperation<T> operation) throws Exception {
        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return operation.execute();
            } catch (RemoteException e) {
                lastException = e;
                System.out.println("  │ ⚠ Attempt " + attempt + "/" + MAX_RETRIES
                        + " failed: " + e.getMessage());

                if (attempt < MAX_RETRIES) {
                    // Exponential backoff
                    long waitMs = (long) Math.pow(2, attempt - 1) * 1000;
                    System.out.println("  │   Retrying in " + waitMs + "ms...");
                    Thread.sleep(waitMs);

                    // Attempt failover
                    failover();
                }
            }
        }

        throw new Exception("All " + MAX_RETRIES + " retry attempts failed.", lastException);
    }

    // UTILITY METHODS

    private byte[] compress(byte[] data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gos = new GZIPOutputStream(baos)) {
            gos.write(data);
        }
        return baos.toByteArray();
    }

    private byte[] decompress(byte[] compressedData) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(compressedData);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPInputStream gis = new GZIPInputStream(bais)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = gis.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
        }
        return baos.toByteArray();
    }

    private String computeChecksum(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance(CHECKSUM_ALGO);
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024)
            return bytes + " B";
        if (bytes < 1024 * 1024)
            return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024)
            return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    // INTERACTIVE CLI

    private void run() {
        try (Scanner scanner = new Scanner(System.in)) {
            System.out.println("╔══════════════════════════════════════════════════╗");
            System.out.println("║     DISTRIBUTED FILE TRANSFER CLIENT (RMI)      ║");
            System.out.println("╚══════════════════════════════════════════════════╝");

            try {
                connectToLoadBalancer();
                assignServer();
            } catch (Exception e) {
                System.err.println("  ✗ Failed to connect: " + e.getMessage());
                System.err.println("  Make sure the Load Balancer and at least one server are running.");
                return;
            }

            while (true) {
                System.out.println();
                System.out.println("  ┌─ MENU ─────────────────────────────────────────");
                System.out.println("  │  1. Upload File");
                System.out.println("  │  2. Download File");
                System.out.println("  │  3. Delete File");
                System.out.println("  │  4. List Files");
                System.out.println("  │  5. File Info");
                System.out.println("  │  6. Show Servers");
                System.out.println("  │  7. Switch Server");
                System.out.println("  │  8. Server Diagnostics (A, B, K)");
                System.out.println("  │  9. Parallel Cluster Scan (F)");
                System.out.println("  │  10. Exit");
                System.out.println("  └────────────────────────────────────────────────");
                System.out.print("  Choose option [1-10]: ");

                String choice = scanner.nextLine().trim();

                switch (choice) {
                    case "1":
                        System.out.print("  Enter file path to upload: ");
                        String uploadPath = scanner.nextLine().trim();
                        uploadFile(uploadPath);
                        break;

                    case "2":
                        System.out.print("  Enter file name to download: ");
                        String dlName = scanner.nextLine().trim();
                        downloadFile(dlName);
                        break;

                    case "3":
                        System.out.print("  Enter file name to delete: ");
                        String delName = scanner.nextLine().trim();
                        deleteFile(delName);
                        break;

                    case "4":
                        listFiles();
                        break;

                    case "5":
                        System.out.print("  Enter file name: ");
                        String infoName = scanner.nextLine().trim();
                        getFileInfo(infoName);
                        break;

                    case "6":
                        showServers();
                        break;

                    case "7":
                        System.out.println("  Switching to next available server...");
                        try {
                            assignServer();
                        } catch (Exception e) {
                            System.out.println("  ✗ Switch failed: " + e.getMessage());
                        }
                        break;

                    case "8":
                        showDiagnostics();
                        break;

                    case "9":
                        parallelListAll();
                        break;

                    case "10":
                        System.out.println("  Goodbye!");
                        return;

                    default:
                        System.out.println("  ✗ Invalid option. Please enter 1-10.");
                }
            }
        }
    }

    // MAIN

    /**
     * Start the interactive file transfer client.
     * 
     * Usage: java client.FileTransferClient [lbHost] [lbPort]
     * Defaults: localhost 1099
     */
    public static void main(String[] args) {
        // Point J: Proper Selection of Timeout Values
        System.setProperty("sun.rmi.transport.tcp.connectionTimeout", "3000"); // 3s connect timeout
        System.setProperty("sun.rmi.transport.tcp.readTimeout", "5000"); // 5s read timeout

        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 1099;

        try {
            FileTransferClient client = new FileTransferClient(host, port);
            client.run();
        } catch (RemoteException e) {
            System.err.println("Fatal Error initializing client: " + e.getMessage());
        }
    }
}
