package common;

import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * Remote interface for Asynchronous Server-to-Client communication.
 * Allows clients to register for callbacks and receive updates
 * when files are modified on the server.
 * 
 * DC Concept: Asynchronous Communication (Callback nature) - Section D
 */
public interface FileUpdateCallback extends Remote {

    /**
     * Called by the server when a file event occurs (e.g. upload, delete).
     * 
     * @param message Description of the event
     * @throws RemoteException
     */
    void onFileUpdate(String message) throws RemoteException;
}
