package common;

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Serializable Data Transfer Object (DTO) that carries file metadata
 * between client and server across the RMI boundary.
 * 
 * This object is stateless from the server's perspective — it is
 * constructed fresh for each request, supporting the stateless server design.
 */
public class FileMetadata implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String fileName;
    private final long fileSize; // in bytes
    private final String checksum; // SHA-256 hex string
    private final long uploadTimestamp; // epoch millis
    private long lastAccessTimestamp; // epoch millis

    public FileMetadata(String fileName, long fileSize, String checksum,
            long uploadTimestamp, long lastAccessTimestamp) {
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.checksum = checksum;
        this.uploadTimestamp = uploadTimestamp;
        this.lastAccessTimestamp = lastAccessTimestamp;
    }

    // ---- Getters ----

    public String getFileName() {
        return fileName;
    }

    public long getFileSize() {
        return fileSize;
    }

    public String getChecksum() {
        return checksum;
    }

    public long getUploadTimestamp() {
        return uploadTimestamp;
    }

    public long getLastAccessTimestamp() {
        return lastAccessTimestamp;
    }

    public void setLastAccessTimestamp(long ts) {
        this.lastAccessTimestamp = ts;
    }

    /**
     * Human-readable file size (B, KB, MB, GB).
     */
    public String getFormattedSize() {
        if (fileSize < 1024)
            return fileSize + " B";
        if (fileSize < 1024 * 1024)
            return String.format("%.2f KB", fileSize / 1024.0);
        if (fileSize < 1024 * 1024 * 1024)
            return String.format("%.2f MB", fileSize / (1024.0 * 1024));
        return String.format("%.2f GB", fileSize / (1024.0 * 1024 * 1024));
    }

    @Override
    public String toString() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        return String.format("%-30s  %10s  SHA256:%s  Uploaded:%s  LastAccess:%s",
                fileName,
                getFormattedSize(),
                checksum.substring(0, 12) + "...",
                sdf.format(new Date(uploadTimestamp)),
                sdf.format(new Date(lastAccessTimestamp)));
    }
}
