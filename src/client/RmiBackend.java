package client;

import common.*;
import java.rmi.Naming;
import java.rmi.registry.*;

public class RmiBackend implements ProtocolBackend {
    @Override
    public long measurePing(String targetUrl) throws Exception {
        long start = System.currentTimeMillis();
        FileTransferService service = (FileTransferService) Naming.lookup(targetUrl);
        service.ping();
        return System.currentTimeMillis() - start;
    }

    @Override
    public long measureTransfer(byte[] data, String targetUrl) throws Exception {
        long start = System.currentTimeMillis();
        FileTransferService service = (FileTransferService) Naming.lookup(targetUrl);
        service.uploadFile("benchmark_test_" + System.currentTimeMillis(), data, computeChecksum(data));
        return System.currentTimeMillis() - start;
    }

    @Override
    public long measureChunk(String fileName, byte[] data, int index, int total, String targetUrl) throws Exception {
        long start = System.currentTimeMillis();
        FileTransferService service = (FileTransferService) Naming.lookup(targetUrl);
        service.uploadChunk(fileName, index, total, data, computeChecksum(data));
        return System.currentTimeMillis() - start;
    }

    @Override
    public void reset() {
        // RMI backend is stateless on client side
    }

    private String computeChecksum(byte[] data) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash)
                sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public String getName() {
        return "RMI (Native)";
    }
}
