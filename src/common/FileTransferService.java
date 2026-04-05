package common;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

/**
 * Remote interface for the Distributed File Transfer Service.
 * Defines all operations that clients can invoke on file servers via Java RMI.
 * 
 * Design Decisions:
 * - byte[] used for data transfer (serializable, supports chunking)
 * - FileMetadata DTO carries file info without exposing server internals
 * - TransferResult provides structured success/failure responses
 * - All methods throw RemoteException (required by RMI spec)
 */
public interface FileTransferService extends Remote {

    /**
     * Upload a file to the server.
     * Data should be GZIP-compressed before sending to reduce bandwidth.
     * Idempotent: re-uploading the same file overwrites the previous version.
     *
     * @param fileName     Name of the file to store
     * @param compressedData GZIP-compressed file content
     * @param checksum     SHA-256 checksum of the ORIGINAL (uncompressed) data
     * @return TransferResult with success status and server-computed checksum
     */
    TransferResult uploadFile(String fileName, byte[] compressedData, String checksum)
            throws RemoteException;

    /**
     * Download a file from the server.
     * Returns GZIP-compressed data to reduce bandwidth.
     *
     * @param fileName Name of the file to download
     * @return GZIP-compressed file content, or null if file not found
     */
    byte[] downloadFile(String fileName) throws RemoteException;

    /**
     * Delete a file from the server.
     * Idempotent: deleting a non-existent file returns success with a warning.
     *
     * @param fileName Name of the file to delete
     * @return TransferResult indicating success or failure
     */
    TransferResult deleteFile(String fileName) throws RemoteException;

    /**
     * List all files available on this server.
     * This is an idempotent operation — results may be served from reply cache.
     *
     * @return List of FileMetadata objects describing each stored file
     */
    List<FileMetadata> listFiles() throws RemoteException;

    /**
     * Get detailed metadata for a specific file.
     * Idempotent — results may be served from reply cache.
     *
     * @param fileName Name of the file to query
     * @return FileMetadata, or null if file not found
     */
    FileMetadata getFileInfo(String fileName) throws RemoteException;

    /**
     * Health check endpoint for the load balancer.
     * Returns the server's identifier string if alive.
     *
     * @return Server name/ID
     */
    String ping() throws RemoteException;
}
