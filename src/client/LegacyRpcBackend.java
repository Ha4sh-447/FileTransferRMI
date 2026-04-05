package client;

import common.*;
import java.io.*;
import java.net.*;

public class LegacyRpcBackend implements ProtocolBackend {
    @Override
    public long measurePing(String targetUrl) throws Exception {
        return measureTransfer("PING".getBytes(), targetUrl);
    }

    @Override
    public long measureTransfer(byte[] data, String targetUrl) throws Exception {
        return measureChunk("test", data, 0, 1, targetUrl);
    }

    @Override
    public long measureChunk(String fileName, byte[] data, int index, int total, String targetUrl) throws Exception {
        java.net.URI uri = new java.net.URI(targetUrl);
        String host = uri.getHost();
        int port = uri.getPort() + 3; // Auxiliary Legacy port

        long start = System.currentTimeMillis();
        try (Socket socket = new Socket(host, port);
                ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
                ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {
            out.writeObject(data);
            out.flush();
            in.readObject(); // Wait for DONE
        }
        return System.currentTimeMillis() - start;
    }

    @Override
    public void reset() {
    }

    @Override
    public String getName() {
        return "Legacy CORBA (Sim)";
    }
}
