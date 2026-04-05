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
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.*;

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

    // ──── Configuration ────
    private static final int REPLY_CACHE_SIZE = 100; // Max cached responses
    private static final long CACHE_TTL_MS = 30_000; // 30-second cache TTL
    private static final String CHECKSUM_ALGO = "SHA-256";

    private final String serverName;
    private final String storageDir;
    private final ConcurrentHashMap<String, FileMetadata> fileMetadataMap;
    private final ConcurrentHashMap<String, ReentrantLock> fileLocks;
    private final ReplyCache replyCache;
    private final AccessLogger logger;

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
        this.replyCache = new ReplyCache(REPLY_CACHE_SIZE);
        this.logger = new AccessLogger(serverName, logDir);

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
     * Scan the storage directory and rebuild the metadata map.
     * This supports fault-tolerance — server can recover state after restart.
     */
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

    // ════════════════════════════════════════════════════════════
    // REMOTE METHOD IMPLEMENTATIONS
    // ════════════════════════════════════════════════════════════

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
                return new TransferResult(false, "Checksum verification failed.");
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

            logger.logSuccess("UPLOAD", fileName, "Completed");
            return new TransferResult(true, "File '" + fileName + "' uploaded to " + serverName, serverChecksum);
        } catch (IOException e) {
            logger.logFailure("UPLOAD", fileName, e.getMessage());
            return new TransferResult(false, "Server IO Error: " + e.getMessage());
        } finally {
            lock.unlock();
        }
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
                logger.logSuccess("DELETE", fileName, "Deleted from storage");
                return new TransferResult(true, "File '" + fileName + "' deleted successfully.");
            } else {
                return new TransferResult(true, "File '" + fileName + "' not found (idempotent).");
            }
        } catch (IOException e) {
            logger.logFailure("DELETE", fileName, e.getMessage());
            return new TransferResult(false, "Delete failed: " + e.getMessage());
        } finally {
            lock.unlock();
        }
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

    // ════════════════════════════════════════════════════════════
    // UTILITY METHODS — Compression & Checksums
    // ════════════════════════════════════════════════════════════

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

    // ════════════════════════════════════════════════════════════
    // MAIN — Server Startup
    // ════════════════════════════════════════════════════════════

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
