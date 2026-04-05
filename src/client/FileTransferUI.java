package client;

import common.*;
import registry.LoadBalancerService;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.rmi.Naming;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Graphical user interface (Java Swing) for the Distributed File Transfer
 * System.
 */
public class FileTransferUI extends JFrame {

    private static final int MAX_RETRIES = 3;
    private static final String CHECKSUM_ALGO = "SHA-256";
    private static final String DOWNLOAD_DIR = "downloads";

    // RMI Components
    private String lbHost;
    private int lbPort;
    private FileTransferService currentServer;
    private String currentServerUrl;
    private LoadBalancerService loadBalancer;

    // UI Components
    private JTable fileTable;
    private DefaultTableModel tableModel;
    private JLabel statusLabel;
    private JLabel serverLabel;
    private JProgressBar progressBar;
    private JButton uploadBtn, downloadBtn, deleteBtn, refreshBtn, switchBtn;

    public FileTransferUI(String lbHost, int lbPort) {
        this.lbHost = lbHost;
        this.lbPort = lbPort;

        setTitle("Distributed File Transfer System");
        setSize(900, 600);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        initUI();
        connectToRMI();
    }

    private void initUI() {
        // Main Layout
        setLayout(new BorderLayout(10, 10));

        // Top Panel: Server Status
        JPanel topPanel = new JPanel(new GridLayout(1, 2, 10, 0));
        topPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        serverLabel = new JLabel("Connecting to Load Balancer...");
        serverLabel.setFont(new Font("SansSerif", Font.BOLD, 14));
        topPanel.add(serverLabel);

        JPanel topBtnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        switchBtn = new JButton("Switch Server");
        switchBtn.addActionListener(e -> switchServer());
        topBtnPanel.add(switchBtn);
        topPanel.add(topBtnPanel);

        add(topPanel, BorderLayout.NORTH);

        // Center Panel: File Table
        String[] columns = { "#", "File Name", "Size", "Checksum", "Uploaded", "Last Accessed" };
        tableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        fileTable = new JTable(tableModel);
        fileTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        fileTable.setRowHeight(25);
        fileTable.getTableHeader().setReorderingAllowed(false);

        JScrollPane scrollPane = new JScrollPane(fileTable);
        scrollPane.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
        add(scrollPane, BorderLayout.CENTER);

        // Bottom Panel: Actions & Progress
        JPanel bottomPanel = new JPanel(new BorderLayout(5, 5));
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Action Panel with Titled Borders
        JPanel actionContainer = new JPanel(new BorderLayout());

        JPanel fileOpPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        fileOpPanel.setBorder(BorderFactory.createTitledBorder("File Operations"));

        uploadBtn = new JButton("Upload File");
        uploadBtn.setToolTipText("Upload a local file to the current server");

        downloadBtn = new JButton("Download Selected");
        downloadBtn.setToolTipText("Download the selected file to your local 'downloads' folder");

        deleteBtn = new JButton("Delete Selected");
        deleteBtn.setToolTipText("Remove the selected file from the server");

        fileOpPanel.add(uploadBtn);
        fileOpPanel.add(downloadBtn);
        fileOpPanel.add(deleteBtn);

        JPanel sysOpPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 5));
        sysOpPanel.setBorder(BorderFactory.createTitledBorder("System"));

        refreshBtn = new JButton("Refresh List");
        refreshBtn.setToolTipText("Refresh the file list from the server");

        sysOpPanel.add(refreshBtn);

        actionContainer.add(fileOpPanel, BorderLayout.CENTER);
        actionContainer.add(sysOpPanel, BorderLayout.EAST);
        bottomPanel.add(actionContainer, BorderLayout.NORTH);

        refreshBtn.addActionListener(e -> refreshFileList());
        uploadBtn.addActionListener(e -> chooseAndUploadFile());
        downloadBtn.addActionListener(e -> downloadSelectedFile());
        deleteBtn.addActionListener(e -> deleteSelectedFile());

        JPanel statusPanel = new JPanel(new BorderLayout(5, 5));
        statusLabel = new JLabel("Ready");
        statusLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        statusPanel.add(statusLabel, BorderLayout.WEST);
        statusPanel.add(progressBar, BorderLayout.CENTER);
        bottomPanel.add(statusPanel, BorderLayout.SOUTH);

        add(bottomPanel, BorderLayout.SOUTH);

        // Menu Bar for Management
        JMenuBar menuBar = new JMenuBar();
        JMenu manageMenu = new JMenu("System Management");

        JMenuItem newServerItem = new JMenuItem("Start New Server Instance...");
        newServerItem.addActionListener(e -> showStartServerDialog());
        manageMenu.add(newServerItem);

        JMenuItem newClientItem = new JMenuItem("Launch New Client Window");
        newClientItem.addActionListener(e -> new FileTransferUI(lbHost, lbPort).setVisible(true));
        manageMenu.add(newClientItem);

        menuBar.add(manageMenu);
        setJMenuBar(menuBar);

        // Styling
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }
    }

    private void showStartServerDialog() {
        JTextField nameField = new JTextField("Server-" + (int) (Math.random() * 100));
        JTextField portField = new JTextField("" + (1100 + (int) (Math.random() * 100)));
        JTextField storageField = new JTextField("storage_" + nameField.getText());

        Object[] message = {
                "Server Name:", nameField,
                "RMI Port:", portField,
                "Storage Directory:", storageField
        };

        int option = JOptionPane.showConfirmDialog(null, message, "Launch New Server", JOptionPane.OK_CANCEL_OPTION);
        if (option == JOptionPane.OK_OPTION) {
            launchServerProcess(nameField.getText(), portField.getText(), storageField.getText());
        }
    }

    private void launchServerProcess(String name, String port, String storage) {
        new Thread(() -> {
            try {
                updateStatus("Launching " + name + "...", 50);
                ProcessBuilder pb = new ProcessBuilder(
                        "java", "-cp", "bin", "server.FileServerImpl",
                        port, storage, name, lbHost, String.valueOf(lbPort));
                pb.inheritIO();
                pb.start();
                updateStatus("Server process started for " + name, 100);
                Thread.sleep(2000);
                refreshFileList();
            } catch (Exception e) {
                showError("Launch Failed", e.getMessage());
            }
        }).start();
    }

    // ════════════════════════════════════════════════════════════
    // RMI LOGIC
    // ════════════════════════════════════════════════════════════

    private void connectToRMI() {
        new Thread(() -> {
            updateStatus("Connecting to Load Balancer...", 20);
            try {
                Registry registry = LocateRegistry.getRegistry(lbHost, lbPort);
                loadBalancer = (LoadBalancerService) registry.lookup("LoadBalancer");
                updateStatus("Connected to Load Balancer", 50);
                assignServer();
            } catch (Exception e) {
                showError("Connection Failed", "Could not connect to registry: " + e.getMessage());
                updateStatus("Connection Error", 0);
            }
        }).start();
    }

    private void assignServer() {
        try {
            updateStatus("Assigning Server...", 70);
            currentServerUrl = loadBalancer.getNextServer();
            currentServer = (FileTransferService) Naming.lookup(currentServerUrl);
            String name = currentServer.ping();

            SwingUtilities.invokeLater(() -> {
                serverLabel.setText("● Connected: " + name + " (" + currentServerUrl + ")");
                serverLabel.setForeground(new Color(0, 150, 0)); // Green
                updateStatus("System Ready", 100);
                refreshFileList();
            });
        } catch (Exception e) {
            showError("Server Assignment Error", "Could not get server from Load Balancer: " + e.getMessage());
            updateStatus("Failed to assign server", 0);
        }
    }

    private void switchServer() {
        new Thread(() -> {
            updateStatus("Switching Server...", 50);
            assignServer();
        }).start();
    }

    private void refreshFileList() {
        new Thread(() -> {
            try {
                updateStatus("Refreshing file list...", 30);
                List<FileMetadata> files = retryOperation(() -> currentServer.listFiles());

                SwingUtilities.invokeLater(() -> {
                    tableModel.setRowCount(0);
                    int i = 1;
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                    for (FileMetadata f : files) {
                        tableModel.addRow(new Object[] {
                                i++,
                                f.getFileName(),
                                f.getFormattedSize(),
                                f.getChecksum().substring(0, 12) + "...",
                                sdf.format(new Date(f.getUploadTimestamp())),
                                sdf.format(new Date(f.getLastAccessTimestamp()))
                        });
                    }
                    updateStatus("File list updated (" + files.size() + " files)", 100);
                });
            } catch (Exception e) {
                showError("Refresh Failed", e.getMessage());
                updateStatus("Refresh Error", 0);
            }
        }).start();
    }

    private void chooseAndUploadFile() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            new Thread(() -> uploadFile(file)).start();
        }
    }

    private void uploadFile(File file) {
        try {
            updateStatus("Preparing upload: " + file.getName(), 10);
            byte[] rawData = Files.readAllBytes(file.toPath());
            String checksum = computeChecksum(rawData);

            updateStatus("Compressing...", 30);
            byte[] compressed = compress(rawData);

            updateStatus("Sending to server...", 60);
            TransferResult result = retryOperation(
                    () -> currentServer.uploadFile(file.getName(), compressed, checksum));

            if (result.isSuccess()) {
                updateStatus("Upload complete!", 100);
                JOptionPane.showMessageDialog(this, result.getMessage(), "Upload Success",
                        JOptionPane.INFORMATION_MESSAGE);
                refreshFileList();
            } else {
                showError("Upload Failed", result.getMessage());
                updateStatus("Upload Error", 0);
            }
        } catch (Exception e) {
            showError("Upload Error", e.getMessage());
            updateStatus("Upload Error", 0);
        }
    }

    private void downloadSelectedFile() {
        int row = fileTable.getSelectedRow();
        if (row == -1) {
            JOptionPane.showMessageDialog(this, "Select a file first", "Error", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String fileName = (String) tableModel.getValueAt(row, 1);

        new Thread(() -> {
            try {
                updateStatus("Downloading " + fileName + "...", 20);
                byte[] compressed = retryOperation(() -> currentServer.downloadFile(fileName));

                if (compressed == null) {
                    showError("Error", "File not found on server");
                    return;
                }

                updateStatus("Decompressing...", 60);
                byte[] rawData = decompress(compressed);

                File dlDir = new File(DOWNLOAD_DIR);
                if (!dlDir.exists())
                    dlDir.mkdirs();
                Path savePath = Paths.get(DOWNLOAD_DIR, fileName);
                Files.write(savePath, rawData);

                updateStatus("Verifying checksum...", 90);
                String localChecksum = computeChecksum(rawData);
                FileMetadata meta = currentServer.getFileInfo(fileName);

                String msg = "Downloaded to: " + savePath.toAbsolutePath();
                if (meta != null && !localChecksum.equals(meta.getChecksum())) {
                    msg += "\n\nWARNING: Checksum mismatch!";
                }

                updateStatus("Download Complete", 100);
                JOptionPane.showMessageDialog(this, msg, "Download Success", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception e) {
                showError("Download Error", e.getMessage());
                updateStatus("Download Error", 0);
            }
        }).start();
    }

    private void deleteSelectedFile() {
        int row = fileTable.getSelectedRow();
        if (row == -1) {
            JOptionPane.showMessageDialog(this, "Select a file first", "Error", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String fileName = (String) tableModel.getValueAt(row, 1);

        int confirm = JOptionPane.showConfirmDialog(this, "Delete " + fileName + "?", "Confirm Delete",
                JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION)
            return;

        new Thread(() -> {
            try {
                updateStatus("Deleting " + fileName + "...", 50);
                TransferResult result = retryOperation(() -> currentServer.deleteFile(fileName));

                if (result.isSuccess()) {
                    updateStatus("Deleted", 100);
                    refreshFileList();
                } else {
                    showError("Delete Failed", result.getMessage());
                }
            } catch (Exception e) {
                showError("Delete Error", e.getMessage());
            }
        }).start();
    }

    // ════════════════════════════════════════════════════════════
    // UTILITIES
    // ════════════════════════════════════════════════════════════

    private <T> T retryOperation(RemoteOperation<T> operation) throws Exception {
        Exception lastException = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return operation.execute();
            } catch (RemoteException e) {
                lastException = e;
                SwingUtilities.invokeLater(() -> {
                    serverLabel.setText("⚠ FAILOVER: Server Unreachable...");
                    serverLabel.setForeground(Color.RED);
                });
                if (attempt < MAX_RETRIES) {
                    Thread.sleep(1000);
                    try {
                        assignServer();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        throw new Exception("Operation failed after " + MAX_RETRIES + " attempts", lastException);
    }

    @FunctionalInterface
    private interface RemoteOperation<T> {
        T execute() throws RemoteException;
    }

    private void updateStatus(String msg, int progress) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText(msg);
            progressBar.setValue(progress);
        });
    }

    private void showError(String title, String msg) {
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, msg, title, JOptionPane.ERROR_MESSAGE));
    }

    private byte[] compress(byte[] data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gos = new GZIPOutputStream(baos)) {
            gos.write(data);
        }
        return baos.toByteArray();
    }

    private byte[] decompress(byte[] data) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPInputStream gis = new GZIPInputStream(bais)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = gis.read(buffer)) != -1)
                baos.write(buffer, 0, len);
        }
        return baos.toByteArray();
    }

    private String computeChecksum(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance(CHECKSUM_ALGO);
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash)
                sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }

    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 1099;

        SwingUtilities.invokeLater(() -> new FileTransferUI(host, port).setVisible(true));
    }
}
