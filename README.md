# Distributed File Transfer System (Java RMI)

A robust, multi-server distributed file-sharing application with Load Balancing, Fault Tolerance, and Performance Optimizations.

## 🚀 Quick Start (Complete Run Sequence)

Follow these steps exactly in separate terminal windows:

### 1. Build and Setup
```bash
# Linux/macOS
chmod +x *.sh
./cleanup.sh    # Resets storage and logs
./compile.sh    # Builds the project

# Windows (CMD)
# Rename the .sh files to .bat (e.g. compile.sh -> compile.bat)
# OR run them via: cmd /c compile.sh
```

### 2. Launch Infrastructure
```bash
# Terminal 1: Load Balancer (Registry)
./run_registry.sh 1099

# Terminal 2: File Server 1
./run_server.sh 1101 storage_1 Server-A localhost 1099

# Terminal 3: File Server 2
./run_server.sh 1102 storage_2 Server-B localhost 1099
```

### 3. Launch Client
```bash
# Terminal 4: Graphical UI (Recommended)
./run_ui.sh localhost 1099
```

---

## 🪟 Windows Support

All script files (`.sh`) are **Polyglot** — they contain both Bash and Windows Batch code.
- **Git Bash / WSL**: Run them normally like `./run_ui.sh`.
- **Windows Command Prompt (CMD)**: 
  - You can run them by adding `.bat` to the filename (e.g., rename `run_ui.sh` to `run_ui.bat`).
  - Or run them directly: `cmd /c run_ui.sh`.

---

## 🖥️ Graphical User Interface (GUI) Guide

The GUI provides a seamless way to manage the distributed system without complex commands.

### 1. Connection & Health
- **Status Indicator**: A green dot (●) indicates a healthy connection.
- **Failover**: If a server goes down, the status turns **RED (⚠ FAILOVER)**. The system automatically finds a new server and resumes.
- **Switch Server**: Click the top-right button to manually move to another server for load balancing.

### 2. File Operations
- **Table View**: Lists all files stored on the current server.
- **Upload File**: Open a file picker to send any file to the server. Files are automatically compressed and checksummed.
- **Download Selected**: Saves the selected file to your local `downloads/` folder.
- **Delete Selected**: Removes the file from the distributed storage.
- **Refresh**: Manually update the file list (though most operations auto-refresh).

### 3. System Management (Menu)
- **Start New Server**: Opens a dialog to launch a brand new server instance on a specific port.
- **Launch New Client**: Opens another UI window to simulate multiple users.

---

## 🛠️ Key Distributed Concepts
- **Load Balancing**: Clients are assigned to servers round-robin by the registry.
- **Fault Tolerance**: Automatic failover with at-least-once retry semantics.
- **Integrity**: SHA-256 checksums verified on every transfer.
- **Efficiency**: GZIP compression and LRU reply caching.
- **Concurrency**: Per-file locking in `FileServerImpl` handles multiple simultaneous uploads.

## 📂 Project Structure
- `src/common`: RMI interfaces and serializable DTOs.
- `src/server`: Server implementation, caching, and locking logic.
- `src/registry`: Load Balancer logic.
- `src/client`: GUI (Swing) and CLI client implementations.
- `*.sh`: Polyglot (Bash/Batch) scripts for cross-platform execution.
