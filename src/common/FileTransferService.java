package common;

import java.rmi.*;
import java.util.*;

public interface FileTransferService extends Remote {

        TransferResult uploadFile(String fileName, byte[] compressedData, String checksum) throws RemoteException;

        TransferResult uploadFile(String fileName, byte[] compressedData, String checksum, String requestId)
                        throws RemoteException;

        TransferResult uploadChunk(String fileName, int chunkIndex, int totalChunks, byte[] data, String checksum)
                        throws RemoteException;

        TransferResult uploadChunk(String fileName, int chunkIndex, int totalChunks, byte[] data, String checksum,
                        String requestId) throws RemoteException;

        byte[] downloadFile(String fileName) throws RemoteException;

        TransferResult deleteFile(String fileName) throws RemoteException;

        TransferResult deleteFile(String fileName, String requestId) throws RemoteException;

        List<FileMetadata> listFiles() throws RemoteException;

        FileMetadata getFileInfo(String fileName) throws RemoteException;

        void registerCallback(FileUpdateCallback callback) throws RemoteException;

        void unregisterCallback(FileUpdateCallback callback) throws RemoteException;

        String ping() throws RemoteException;

        String getProtocolVersion() throws RemoteException;

        ServerDiagnostics getDiagnostics() throws RemoteException;
}
