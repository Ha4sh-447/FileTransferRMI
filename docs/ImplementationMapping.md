# Implementation Mapping: Architecture Points A-K

This document maps the theoretical communication model points (A-K) to specific implementations in the Distributed File Transfer System codebase.

| Requirement | Concept | File:Lines / Methods | Implementation Detail |
| :--- | :--- | :--- | :--- |
| **A** | **Stateless Design** | [FileServerImpl.java](file:///home/harsh/projects/DC_Mini_Project/src/server/FileServerImpl.java) | `getDiagnostics()` explicitly returns `isStateless = true`. |
| **B** | **Server Creation** | [FileServerImpl.java](file:///home/harsh/projects/DC_Mini_Project/src/server/FileServerImpl.java) | Constructor captures `startupTime` and binds to Registry. |
| **C** | **Transient/Persistent** | [ConnectionType.java](file:///home/harsh/projects/DC_Mini_Project/src/common/ConnectionType.java) | Explicit enum used in client for connection lifecycle. |
| **D** | **Async & Callbacks** | [FileUpdateCallback.java](file:///home/harsh/projects/DC_Mini_Project/src/common/FileUpdateCallback.java) | Asynchronous RMI interface for notifications. |
| **D** | **Receipt Based** | [TransferResult.java](file:///home/harsh/projects/DC_Mini_Project/src/common/TransferResult.java) | `Receipt` inner class provides server-signed acknowledgment. |
| **E** | **Call Semantics** | [FileServerImpl.java](file:///home/harsh/projects/DC_Mini_Project/src/server/FileServerImpl.java) | `requestDeduplicationCache` enforces **Exactly-Once**. |
| **F** | **Concurrent Access** | [FileTransferClient.java](file:///home/harsh/projects/DC_Mini_Project/src/client/FileTransferClient.java) | `parallelListAll()` queries multiple servers in parallel. |
| **G** | **Simultaneous Requests** | [FileServerImpl.java](file:///home/harsh/projects/DC_Mini_Project/src/server/FileServerImpl.java) | RMI thread-pool dispatching and concurrent locks. |
| **H** | **Reducing Workload** | [FileTransferClient.java](file:///home/harsh/projects/DC_Mini_Project/src/client/FileTransferClient.java) | 2MB **Chunking** logic in `uploadFile`. |
| **I** | **Reply Caching** | [FileServerImpl.java](file:///home/harsh/projects/DC_Mini_Project/src/server/FileServerImpl.java) | `ReplyCache` used for `listFiles` and `getFileInfo`. |
| **J** | **Timeout Values** | [FileTransferClient.java](file:///home/harsh/projects/DC_Mini_Project/src/client/FileTransferClient.java) | Configuration of `connectionTimeout` (3s) and `readTimeout` (5s). |
| **K** | **Protocol Spec** | [TransferResult.java](file:///home/harsh/projects/DC_Mini_Project/src/common/TransferResult.java) | `PROTOCOL_VERSION` and structured DTOs define the contract. |

---

### Verification Summary
- **Statelessness**: No session objects or client-specific state maps.
- **Failover**: Handle cluster failures via `retryOperation`.
- **Concurrency**: Fully multithreaded server handling.
- **Integrity**: End-to-end SHA-256 verification.
