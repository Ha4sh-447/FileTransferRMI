package registry;

import java.rmi.*;
import java.util.*;

/**
 * Remote interface for the Load Balancer service.
 * Clients use this to discover available file servers.
 * 
 * DC Concept: Concurrent Access to Multiple Servers (Section F)
 */
public interface LoadBalancerService extends Remote {
    /**
     * Get the next available server's RMI URL using round-robin.
     * 
     * @return Full RMI URL of an active FileTransferService
     */
    String getNextServer() throws RemoteException;

    /**
     * Get all registered server URLs with their status.
     * 
     * @return List of all registered server URLs
     */
    List<String> getAllServers() throws RemoteException;

    /**
     * Register a new file server with the load balancer.
     * 
     * @param host     RMI registry host of the server
     * @param port     RMI registry port of the server
     * @param bindName RMI bind name of the FileTransferService
     */
    void registerServer(String host, int port, String bindName) throws RemoteException;
}
