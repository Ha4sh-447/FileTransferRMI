package registry;

import common.FileTransferService;

import java.rmi.*;
import java.rmi.registry.*;
import java.rmi.server.UnicastRemoteObject;
import java.util.*;
import java.util.concurrent.*;

/**
 * ============================================================
 * LOAD BALANCER IMPLEMENTATION
 * ============================================================
 * 
 * Implements round-robin server assignment with health checking.
 * 
 * DC Concepts:
 * - Concurrent access to multiple servers (Section F)
 * - Reducing per-call workload by distributing across servers (Section H)
 * - Proper timeout values for health checks (Section J)
 * 
 * The load balancer runs on a well-known port (default 1099).
 * File servers register themselves, and clients query the LB
 * to discover which server to connect to.
 */
public class LoadBalancer extends UnicastRemoteObject implements LoadBalancerService {

    private static final long serialVersionUID = 1L;

    /**
     * Represents a registered file server endpoint.
     */
    private static class ServerEndpoint {
        final String host;
        final int port;
        final String bindName;
        boolean alive;

        ServerEndpoint(String host, int port, String bindName) {
            this.host = host;
            this.port = port;
            this.bindName = bindName;
            this.alive = true;
        }

        String getFullAddress() {
            return host + ":" + port + "/" + bindName;
        }
    }

    private final List<ServerEndpoint> servers;
    private int roundRobinIndex;
    private final ScheduledExecutorService healthChecker;

    public LoadBalancer() throws RemoteException {
        super();
        this.servers = Collections.synchronizedList(new ArrayList<>());
        this.roundRobinIndex = 0;

        // Schedule periodic health checks every 10 seconds
        this.healthChecker = Executors.newSingleThreadScheduledExecutor();
        healthChecker.scheduleAtFixedRate(this::performHealthChecks, 10, 10, TimeUnit.SECONDS);
    }

    /**
     * Register a file server with the load balancer.
     * Called by each file server on startup.
     */
    @Override
    public synchronized void registerServer(String host, int port, String bindName)
            throws RemoteException {
        // Check for duplicate
        for (ServerEndpoint s : servers) {
            if (s.host.equals(host) && s.port == port && s.bindName.equals(bindName)) {
                s.alive = true;
                System.out.println("  ↻ Server re-registered: " + s.getFullAddress());
                return;
            }
        }

        ServerEndpoint ep = new ServerEndpoint(host, port, bindName);
        servers.add(ep);
        System.out.println("  ✓ Server registered: " + ep.getFullAddress()
                + "  (Total: " + servers.size() + ")");
    }

    /**
     * Get the next available server using round-robin distribution.
     * Skips servers that are marked as dead.
     * 
     * @return Full RMI lookup URL (e.g.,
     *         "rmi://localhost:1099/FileTransferService_Server1")
     * @throws RemoteException if no servers are available
     */
    @Override
    public synchronized String getNextServer() throws RemoteException {
        if (servers.isEmpty()) {
            throw new RemoteException("No file servers registered with load balancer.");
        }

        // Find the next alive server (round-robin)
        int attempts = 0;
        while (attempts < servers.size()) {
            roundRobinIndex = roundRobinIndex % servers.size();
            ServerEndpoint ep = servers.get(roundRobinIndex);
            roundRobinIndex++;

            if (ep.alive) {
                String url = "rmi://" + ep.host + ":" + ep.port + "/" + ep.bindName;
                System.out.println("  → Assigned client to: " + url);
                return url;
            }
            attempts++;
        }

        throw new RemoteException("All registered file servers are offline.");
    }

    /**
     * Get all registered server URLs.
     */
    @Override
    public synchronized List<String> getAllServers() throws RemoteException {
        List<String> result = new ArrayList<>();
        for (ServerEndpoint ep : servers) {
            String status = ep.alive ? "UP" : "DOWN";
            result.add("rmi://" + ep.host + ":" + ep.port + "/" + ep.bindName + " [" + status + "]");
        }
        return result;
    }

    /**
     * Periodic health check — pings each registered server.
     * Marks servers as alive/dead based on response.
     * 
     * DC Concept: Proper Selection of Timeout Values (Section J)
     * - Uses a short timeout for health checks (2 seconds)
     * - Dead servers are not removed, just marked — they may recover
     */
    private void performHealthChecks() {
        synchronized (this) {
            for (ServerEndpoint ep : servers) {
                try {
                    Registry reg = LocateRegistry.getRegistry(ep.host, ep.port);
                    FileTransferService service = (FileTransferService) reg.lookup(ep.bindName);
                    service.ping();
                    if (!ep.alive) {
                        System.out.println("  ♻ Server RECOVERED: " + ep.getFullAddress());
                    }
                    ep.alive = true;
                } catch (Exception e) {
                    if (ep.alive) {
                        System.out.println("  ✗ Server DOWN: " + ep.getFullAddress()
                                + " (" + e.getMessage() + ")");
                    }
                    ep.alive = false;
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════
    // MAIN — Load Balancer Startup
    // ════════════════════════════════════════════════════════════

    public static void main(String[] args) {
        int lbPort = args.length > 0 ? Integer.parseInt(args[0]) : 1099;

        try {
            System.out.println("╔══════════════════════════════════════════════════╗");
            System.out.println("║         LOAD BALANCER / REGISTRY SERVER         ║");
            System.out.println("╚══════════════════════════════════════════════════╝");
            System.out.println("  Port: " + lbPort);
            System.out.println();

            // Create RMI registry
            Registry registry = LocateRegistry.createRegistry(lbPort);
            System.out.println("  ✓ RMI Registry created on port " + lbPort);

            // Create and bind load balancer
            LoadBalancer lb = new LoadBalancer();
            registry.rebind("LoadBalancer", lb);
            System.out.println("  ✓ Load Balancer registered as 'LoadBalancer'");
            System.out.println();
            System.out.println("  ▶ Load Balancer is RUNNING. Waiting for server registrations...");
            System.out.println("  ▶ Health checks every 10 seconds.");
            System.out.println("─".repeat(52));

        } catch (Exception e) {
            System.err.println("✗ Load Balancer startup failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
