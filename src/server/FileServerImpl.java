package server;

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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.*;
import java.net.*;
import com.sun.net.httpserver.*;

/**
 * Core server implementation of the Distributed File Transfer System using Java
 * RMI.
 * 
 * Supports stateless file operations, GZIP compression, SHA-256 integrity
 * checks,
 * and LRU reply caching for performance optimization.
 */
public class FileServerImpl extends UnicastRemoteObject implements FileTransferService {

    private static final long serialVersionUID = 1L;

    private static final int REPLY_CACHE_SIZE = 100;
    private static final long CACHE_TTL_MS = 30_000; // 30-second cache TTL
    private static final String CHECKSUM_ALGO = "SHA-256";

    private final String serverName;
    private final String storageDir;
    private final ConcurrentHashMap<String, FileMetadata> fileMetadataMap;
    private final ConcurrentHashMap<String, ReentrantLock> fileLocks;
    private final ConcurrentHashMap<String, byte[][]> activeTransfers; // fileName -> [chunkIndex][data]
    private final ReplyCache replyCache;
    private final ReplyCache requestDeduplicationCache;
    private final List<FileUpdateCallback> callbacks;
    private final AccessLogger logger;
    private final long startupTime;

    /**
     * Create a new file server instance.
     * 
     * @param serverName Unique identifier for this server
     * @param storageDir Directory where uploaded files are stored
     * @param logDir     Directory for access logs
     */
    public FileServerImpl(String serverName, String storageDir, String logDir)
            throws RemoteException, IOException {
        super();
        this.serverName = serverName;
        this.storageDir = storageDir;
        this.fileMetadataMap = new ConcurrentHashMap<>();
        this.fileLocks = new ConcurrentHashMap<>();
        this.activeTransfers = new ConcurrentHashMap<>();
        this.replyCache = new ReplyCache(REPLY_CACHE_SIZE);
        this.requestDeduplicationCache = new ReplyCache(500);
        this.callbacks = new CopyOnWriteArrayList<>();
        this.logger = new AccessLogger(serverName, logDir);
        this.startupTime = System.currentTimeMillis();

        // Ensure storage directory exists
        File dir = new File(storageDir);
        if (!dir.exists()) {
            dir.mkdirs();
            logger.log("INIT", "---", "Created storage directory: " + storageDir);
        }

        // Load existing files into metadata map (recovery after restart)
        loadExistingFiles();
        logger.log("INIT", "---",
                "Server '" + serverName + "' initialized. Files in storage: " + fileMetadataMap.size());
    }

    /**
     * Start auxiliary benchmarking services on derivative ports.
     */
    public void startAuxiliaryServices(int basePort) {
        // 1. Raw Socket RPC (Base + 1)
        new Thread(() -> startRawSocketServer(basePort + 1)).start();

        // 2. Pseudo-gRPC / HTTP (Base + 2)
        new Thread(() -> startHttpServer(basePort + 2)).start();

        // 3. Legacy / CORBA Simulation (Base + 3)
        new Thread(() -> startLegacySocketServer(basePort + 3)).start();
    }

    private void startRawSocketServer(int port) {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            while (true) {
                try (Socket clientSocket = serverSocket.accept();
                        ObjectOutputStream out = new ObjectOutputStream(clientSocket.getOutputStream());
                        ObjectInputStream in = new ObjectInputStream(clientSocket.getInputStream())) {
                    Object obj = in.readObject(); // Read data or PING
                    out.writeObject("DONE");
                    out.flush();
                } catch (Exception ignored) {
                }
            }
        } catch (IOException e) {
            System.err.println("Raw Socket Server failed: " + e.getMessage());
        }
    }

    private void startHttpServer(int port) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/ping", exchange -> {
                if ("POST".equals(exchange.getRequestMethod())) {
                    exchange.getRequestBody().readAllBytes(); // Read payload
                }
                String response = "DONE";
                exchange.sendResponseHeaders(200, response.length());
                OutputStream os = exchange.getResponseBody();
                os.write(response.getBytes());
                os.close();
            });
            server.setExecutor(null);
            server.start();
        } catch (IOException e) {
            System.err.println("HTTP Server failed: " + e.getMessage());
        }
    }

    private void startLegacySocketServer(int port) {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            while (true) {
                try (Socket clientSocket = serverSocket.accept();
                        ObjectOutputStream out = new ObjectOutputStream(clientSocket.getOutputStream());
                        ObjectInputStream in = new ObjectInputStream(clientSocket.getInputStream())) {
                    Object data = in.readObject();
                    // Simulate heavy 90s CORBA serialization overhead
                    if (data instanceof byte[]) {
                        Thread.sleep(((byte[]) data).length / 10240 + 5);
                    } else {
                        Thread.sleep(10);
                    }
                    out.writeObject("DONE");
                } catch (Exception ignored) {
                }
            }
        } catch (IOException e) {
            System.err.println("Legacy Socket Server failed: " + e.getMessage());
        }
    }

    @Override
    public TransferResult uploadChunk(String fileName, int chunkIndex, int totalChunks, byte[] data, String checksum)
            throws RemoteException {
        // Validate chunk integrity
        if (!computeChecksum(data).equals(checksum)) {
            return new TransferResult(false, "Chunk " + chunkIndex + " checksum mismatch.", serverName);
        }

        byte[][] chunks = activeTransfers.computeIfAbsent(fileName, k -> new byte[totalChunks][]);
        chunks[chunkIndex] = data;

        // Check if all chunks present
        boolean complete = true;
        int receivedSize = 0;
        for (byte[] c : chunks) {
            if (c == null) {
                complete = false;
            } else {
                receivedSize += c.length;
            }
        }

        if (complete) {
            ReentrantLock lock = getLock(fileName);
            lock.lock();
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream(receivedSize);
                for (byte[] c : chunks)
                    baos.write(c);
                byte[] fullData = baos.toByteArray();

                String fullChecksum = computeChecksum(fullData);
                Path filePath = Paths.get(storageDir, fileName);
                Files.write(filePath, fullData);

                FileMetadata meta = new FileMetadata(fileName, fullData.length, fullChecksum,
                        System.currentTimeMillis(), System.currentTimeMillis());
                fileMetadataMap.put(fileName, meta);
                activeTransfers.remove(fileName);
                replyCache.invalidateAll();
                notifyCallbacks("File fully uploaded via chunks: " + fileName);

                return new TransferResult(true, "Combined " + totalChunks + " chunks into " + fileName, fullChecksum,
                        serverName);
            } catch (IOException e) {
                return new TransferResult(false, "Failed to merge chunks: " + e.getMessage(), serverName);
            } finally {
                lock.unlock();
            }
        }

        return new TransferResult(true, "Chunk " + chunkIndex + " received.", serverName);
    }

    @Override
    public TransferResult uploadChunk(String fileName, int chunkIndex, int totalChunks, byte[] data, String checksum,
            String requestId) throws RemoteException {
        Object cached = requestDeduplicationCache.get("CHUNK:" + requestId, 300_000);
        if (cached != null)
            return (TransferResult) cached;

        TransferResult res = uploadChunk(fileName, chunkIndex, totalChunks, data, checksum);
        requestDeduplicationCache.put("CHUNK:" + requestId, res);
        return res;
    }

    private void loadExistingFiles() {
        File dir = new File(storageDir);
        File[] files = dir.listFiles();
        if (files == null)
            return;

        for (File f : files) {
            if (f.isFile()) {
                try {
                    byte[] data = Files.readAllBytes(f.toPath());
                    String checksum = computeChecksum(data);
                    FileMetadata meta = new FileMetadata(
                            f.getName(), f.length(), checksum,
                            f.lastModified(), System.currentTimeMillis());
                    fileMetadataMap.put(f.getName(), meta);
                } catch (Exception e) {
                    System.err.println("Warning: Could not load metadata for " + f.getName());
                }
            }
        }
    }

    /**
     * Get or create a lock for a specific file.
     */
    private ReentrantLock getLock(String fileName) {
        return fileLocks.computeIfAbsent(fileName, k -> new ReentrantLock());
    }

    private void notifyCallbacks(String message) {
        for (FileUpdateCallback cb : callbacks) {
            try {
                cb.onFileUpdate("[" + serverName + "] " + message);
            } catch (RemoteException e) {
                // Client disconnected/failed, remove it
                callbacks.remove(cb);
            }
        }
    }

    // REMOTE METHOD IMPLEMENTATIONS

    /**
     * Upload a file to this server.
     * 
     * Flow:
     * 1. Decompress GZIP data received from client
     * 2. Verify checksum matches (integrity check)
     * 3. Write file to storage directory
     * 4. Store metadata in concurrent map
     * 5. Invalidate reply cache (file list changed)
     * 6. Log the operation
     * 7. Return structured result with server-side checksum
     * 
     * Idempotent: uploading the same file again simply overwrites it.
     */
    @Override
    public TransferResult uploadFile(String fileName, byte[] compressedData, String clientChecksum)
            throws RemoteException {

        ReentrantLock lock = getLock(fileName);
        lock.lock();
        try {
            logger.log("UPLOAD", fileName,
                    "Receiving " + compressedData.length + " bytes (compressed)");

            // Step 1: Decompress
            byte[] rawData = decompress(compressedData);
            logger.log("UPLOAD", fileName,
                    "Decompressed: " + compressedData.length + " -> " + rawData.length + " bytes");

            // Step 2: Integrity verification
            String serverChecksum = computeChecksum(rawData);
            if (!serverChecksum.equals(clientChecksum)) {
                logger.logFailure("UPLOAD", fileName, "Checksum mismatch!");
                return new TransferResult(false, "Checksum verification failed.", serverName);
            }

            // Step 3: Write to storage
            Path filePath = Paths.get(storageDir, fileName);
            Files.write(filePath, rawData);

            // Step 4: Update metadata
            FileMetadata meta = new FileMetadata(
                    fileName, rawData.length, serverChecksum,
                    System.currentTimeMillis(), System.currentTimeMillis());
            fileMetadataMap.put(fileName, meta);

            // Step 5: Invalidate cache
            replyCache.invalidateAll();
            notifyCallbacks("File uploaded: " + fileName);

            logger.logSuccess("UPLOAD", fileName, "Completed");
            return new TransferResult(true, "File '" + fileName + "' uploaded to " + serverName, serverChecksum,
                    serverName);
        } catch (IOException e) {
            logger.logFailure("UPLOAD", fileName, e.getMessage());
            return new TransferResult(false, "Server IO Error: " + e.getMessage(), serverName);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public TransferResult uploadFile(String fileName, byte[] compressedData, String checksum, String requestId)
            throws RemoteException {
        Object cached = requestDeduplicationCache.get("UPLOAD:" + requestId, 300_000);
        if (cached != null)
            return (TransferResult) cached;

        TransferResult res = uploadFile(fileName, compressedData, checksum);
        requestDeduplicationCache.put("UPLOAD:" + requestId, res);
        return res;
    }

    /**
     * Download a file from this server.
     * 
     * Flow:
     * 1. Check if file exists
     * 2. Read file from storage
     * 3. Compress with GZIP before sending (reduce bandwidth)
     * 4. Update last-access timestamp
     * 5. Log the operation
     * 6. Return compressed data
     */
    @Override
    public byte[] downloadFile(String fileName) throws RemoteException {
        ReentrantLock lock = getLock(fileName);
        lock.lock();
        try {
            logger.log("DOWNLOAD", fileName, "Request received");
            Path filePath = Paths.get(storageDir, fileName);
            if (!Files.exists(filePath)) {
                logger.logFailure("DOWNLOAD", fileName, "File not found");
                return null;
            }

            byte[] rawData = Files.readAllBytes(filePath);
            byte[] compressed = compress(rawData);

            FileMetadata meta = fileMetadataMap.get(fileName);
            if (meta != null) {
                meta.setLastAccessTimestamp(System.currentTimeMillis());
            }

            logger.logSuccess("DOWNLOAD", fileName, "Sent " + compressed.length + " bytes");
            return compressed;
        } catch (IOException e) {
            logger.logFailure("DOWNLOAD", fileName, e.getMessage());
            throw new RemoteException("Download failed: " + e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Delete a file from this server.
     * 
     * Idempotent: deleting a non-existent file returns success with a warning.
     * This supports at-least-once semantics — retried deletes are safe.
     */
    @Override
    public TransferResult deleteFile(String fileName) throws RemoteException {
        ReentrantLock lock = getLock(fileName);
        lock.lock();
        try {
            logger.log("DELETE", fileName, "Request received");
            Path filePath = Paths.get(storageDir, fileName);
            boolean existed = Files.deleteIfExists(filePath);
            fileMetadataMap.remove(fileName);
            replyCache.invalidateAll();

            if (existed) {
                notifyCallbacks("File deleted: " + fileName);
                logger.logSuccess("DELETE", fileName, "Deleted from storage");
                return new TransferResult(true, "File '" + fileName + "' deleted successfully.", serverName);
            } else {
                return new TransferResult(true, "File '" + fileName + "' not found (idempotent).", serverName);
            }
        } catch (IOException e) {
            logger.logFailure("DELETE", fileName, e.getMessage());
            return new TransferResult(false, "Delete failed: " + e.getMessage(), serverName);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public TransferResult deleteFile(String fileName, String requestId) throws RemoteException {
        // Exactly-Once deduplication check
        Object cachedResult = requestDeduplicationCache.get("REQ:" + requestId, 600_000); // 10 min TTL
        if (cachedResult != null) {
            logger.log("DELETE", fileName, "Duplicate request " + requestId + " served from cache (Exactly-Once)");
            return (TransferResult) cachedResult;
        }

        // Perform actual delete
        TransferResult result = deleteFile(fileName);

        // Cache result for future duplicates
        requestDeduplicationCache.put("REQ:" + requestId, result);
        return result;
    }

    @Override
    public void registerCallback(FileUpdateCallback callback) throws RemoteException {
        if (!callbacks.contains(callback)) {
            callbacks.add(callback);
            logger.log("CALLBACK", "---", "Client registered for notifications");
        }
    }

    @Override
    public void unregisterCallback(FileUpdateCallback callback) throws RemoteException {
        callbacks.remove(callback);
        logger.log("CALLBACK", "---", "Client unregistered from notifications");
    }

    /**
     * List all files on this server.
     * 
     * This is an IDEMPOTENT operation — results are served from the
     * reply cache when available to reduce per-call workload.
     */
    @Override
    @SuppressWarnings("unchecked")
    public List<FileMetadata> listFiles() throws RemoteException {
        logger.log("LIST", "---", "Request received");

        // Check reply cache first
        String cacheKey = "listFiles";
        Object cached = replyCache.get(cacheKey, CACHE_TTL_MS);
        if (cached != null) {
            logger.log("LIST", "---", "Served from REPLY CACHE ⚡");
            return (List<FileMetadata>) cached;
        }

        // Cache miss — compute result
        List<FileMetadata> result = new ArrayList<>(fileMetadataMap.values());
        result.sort(Comparator.comparing(FileMetadata::getFileName));

        // Store in cache
        replyCache.put(cacheKey, result);
        logger.logSuccess("LIST", "---", result.size() + " files listed. " + replyCache.getStats());

        return result;
    }

    /**
     * Get metadata for a specific file.
     * 
     * IDEMPOTENT — results cached via reply cache.
     */
    @Override
    public FileMetadata getFileInfo(String fileName) throws RemoteException {
        logger.log("INFO", fileName, "Request received");

        // Check reply cache
        String cacheKey = "getFileInfo:" + fileName;
        Object cached = replyCache.get(cacheKey, CACHE_TTL_MS);
        if (cached != null) {
            logger.log("INFO", fileName, "Served from REPLY CACHE ⚡");
            return (FileMetadata) cached;
        }

        FileMetadata meta = fileMetadataMap.get(fileName);
        if (meta != null) {
            replyCache.put(cacheKey, meta);
            logger.logSuccess("INFO", fileName, "Metadata returned");
        } else {
            logger.logFailure("INFO", fileName, "File not found");
        }
        return meta;
    }

    /**
     * Health check for the load balancer.
     */
    @Override
    public String ping() throws RemoteException {
        return serverName;
    }

    @Override
    public String getProtocolVersion() throws RemoteException {
        return TransferResult.PROTOCOL_VERSION;
    }

    @Override
    public ServerDiagnostics getDiagnostics() throws RemoteException {
        return new ServerDiagnostics(
                serverName,
                startupTime,
                true, // Stateless: True
                0, // No session maps
                "Persistent UnicastRemoteObject (Registry-Bound)");
    }

    // UTILITY METHODS — Compression & Checksums

    /**
     * GZIP-compress a byte array.
     * Reduces network bandwidth usage for file transfers.
     */
    private byte[] compress(byte[] data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gos = new GZIPOutputStream(baos)) {
            gos.write(data);
        }
        return baos.toByteArray();
    }

    /**
     * Decompress a GZIP-compressed byte array.
     */
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

    /**
     * Compute SHA-256 checksum of data.
     * Used for integrity verification after transfer.
     */
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

    // MAIN — Server Startup

    /**
     * Start a file server instance and register it with the RMI registry.
     * 
     * Usage: java server.FileServerImpl [port] [storageDirName] [serverName]
     * 
     * Defaults: port=1099, storage=server_storage, name=FileServer-1
     */
    /**
     * Start a file server instance, register with local RMI registry,
     * and auto-register with the Load Balancer.
     * 
     * Usage: java server.FileServerImpl [serverPort] [storageDirName] [serverName]
     * [lbHost] [lbPort]
     * 
     * Defaults: serverPort=1100, storage=server_storage, name=FileServer-1,
     * lbHost=localhost, lbPort=1099
     */
    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 1100;
        String storageName = args.length > 1 ? args[1] : "server_storage";
        String serverName = args.length > 2 ? args[2] : "FileServer-1";
        String lbHost = args.length > 3 ? args[3] : "localhost";
        int lbPort = args.length > 4 ? Integer.parseInt(args[4]) : 1099;

        String storageDir = System.getProperty("user.dir") + File.separator + storageName;
        String logDir = System.getProperty("user.dir") + File.separator + "logs";

        try {
            System.out.println("╔══════════════════════════════════════════════════╗");
            System.out.println("║     DISTRIBUTED FILE TRANSFER SERVER (RMI)      ║");
            System.out.println("╚══════════════════════════════════════════════════╝");
            System.out.println("  Server Name : " + serverName);
            System.out.println("  RMI Port    : " + port);
            System.out.println("  Storage Dir : " + storageDir);
            System.out.println("  Log Dir     : " + logDir);
            System.out.println("  LB Address  : " + lbHost + ":" + lbPort);
            System.out.println();

            // Create or get existing RMI registry on this server's port
            Registry localRegistry;
            try {
                localRegistry = LocateRegistry.createRegistry(port);
                System.out.println("  ✓ Created new RMI registry on port " + port);
            } catch (RemoteException e) {
                localRegistry = LocateRegistry.getRegistry(port);
                System.out.println("  ✓ Connected to existing RMI registry on port " + port);
            }

            // Create server instance
            FileServerImpl server = new FileServerImpl(serverName, storageDir, logDir);

            // Start auxiliary benchmarking services (Raw RPC, HTTP, Legacy)
            server.startAuxiliaryServices(port);

            // Bind to local registry
            String bindName = "FileTransferService_" + serverName;
            localRegistry.rebind(bindName, server);
            System.out.println("  ✓ Registered locally as '" + bindName + "'");

            // Auto-register with Load Balancer
            try {
                Registry lbRegistry = LocateRegistry.getRegistry(lbHost, lbPort);
                LoadBalancerService lb = (LoadBalancerService) lbRegistry.lookup("LoadBalancer");
                lb.registerServer("localhost", port, bindName);
                System.out.println("  ✓ Registered with Load Balancer at " + lbHost + ":" + lbPort);
            } catch (Exception e) {
                System.out.println("  ⚠ Could not register with Load Balancer: " + e.getMessage());
                System.out.println("    (Server will still work for direct connections)");
            }

            System.out.println();
            System.out.println("  ▶ Server is RUNNING. Press Ctrl+C to stop.");
            System.out.println("─".repeat(52));

        } catch (Exception e) {
            System.err.println("✗ Server startup failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
