package client;

import common.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;

public class HttpRpcBackend implements ProtocolBackend {
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    @Override
    public long measurePing(String targetUrl) throws Exception {
        return measureTransfer(null, targetUrl);
    }

    @Override
    public long measureTransfer(byte[] data, String targetUrl) throws Exception {
        return measureChunk("test", data, 0, 1, targetUrl);
    }

    @Override
    public long measureChunk(String fileName, byte[] data, int index, int total, String targetUrl) throws Exception {
        java.net.URI uri = new java.net.URI(targetUrl);
        String host = uri.getHost();
        int port = uri.getPort() + 2; // Auxiliary HTTP port

        long start = System.currentTimeMillis();
        URL url = new URL("http://" + host + ":" + port + "/ping");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.getOutputStream().write(data);
        conn.getInputStream().readAllBytes();
        return System.currentTimeMillis() - start;
    }

    @Override
    public void reset() {
    }

    @Override
    public String getName() {
        return "Pseudo-gRPC (HTTP/2)";
    }
}
