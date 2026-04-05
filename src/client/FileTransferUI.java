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
 * Now includes a Multi-Protocol Benchmarking suite for performance analysis.
 */
public class FileTransferUI extends JFrame {

    private static final int MAX_RETRIES = 3;
    private static final String CHECKSUM_ALGO = "SHA-256";
    private static final String DOWNLOAD_DIR = "downloads";

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
    private JTextArea transferLog;
    private JButton uploadBtn, downloadBtn, deleteBtn, refreshBtn, switchBtn;

    public FileTransferUI(String lbHost, int lbPort) {
        this.lbHost = lbHost;
        this.lbPort = lbPort;

        setTitle("Distributed File Transfer System — Multi-Protocol Edition");
        setSize(1100, 800);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        initUI();
        connectToRMI();
    }

    private void initUI() {
        JTabbedPane tabbedPane = new JTabbedPane();

        // --- Tab 1: File Explorer ---
        JPanel explorerPanel = new JPanel(new BorderLayout(10, 10));

        // Server Status Header
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
        explorerPanel.add(topPanel, BorderLayout.NORTH);

        // File Table
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
        JScrollPane tableScroll = new JScrollPane(fileTable);

        // Transfer Log (Regular Operations)
        transferLog = new JTextArea();
        transferLog.setEditable(false);
        transferLog.setFont(new Font("Monospaced", Font.PLAIN, 12));
        transferLog.setBackground(new Color(240, 248, 255)); // Light bluish
        JScrollPane logScroll = new JScrollPane(transferLog);
        logScroll.setBorder(BorderFactory.createTitledBorder("Live Transfer Details (Chunks & Latency)"));

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableScroll, logScroll);
        splitPane.setDividerLocation(400);
        splitPane.setResizeWeight(0.6);
        explorerPanel.add(splitPane, BorderLayout.CENTER);

        // Bottom Controls
        JPanel bottomPanel = new JPanel(new BorderLayout(5, 5));
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel actionContainer = new JPanel(new BorderLayout());
        JPanel fileOpPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        fileOpPanel.setBorder(BorderFactory.createTitledBorder("File Operations"));
        uploadBtn = new JButton("Upload File");
        downloadBtn = new JButton("Download Selected");
        deleteBtn = new JButton("Delete Selected");
        fileOpPanel.add(uploadBtn);
        fileOpPanel.add(downloadBtn);
        fileOpPanel.add(deleteBtn);

        JPanel sysOpPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 5));
        sysOpPanel.setBorder(BorderFactory.createTitledBorder("System"));
        refreshBtn = new JButton("Refresh List");
        sysOpPanel.add(refreshBtn);

        actionContainer.add(fileOpPanel, BorderLayout.CENTER);
        actionContainer.add(sysOpPanel, BorderLayout.EAST);
        bottomPanel.add(actionContainer, BorderLayout.NORTH);

        JPanel statusPanel = new JPanel(new BorderLayout(5, 5));
        statusLabel = new JLabel("Ready");
        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        statusPanel.add(statusLabel, BorderLayout.WEST);
        statusPanel.add(progressBar, BorderLayout.CENTER);
        bottomPanel.add(statusPanel, BorderLayout.SOUTH);

        explorerPanel.add(bottomPanel, BorderLayout.SOUTH);

        // --- Tab 2: Benchmarking ---
        // --- Tab 3: System Management ---
        tabbedPane.addTab("📁 File Explorer", explorerPanel);
        tabbedPane.addTab("⚡ Protocol Benchmark", createBenchmarkPanel());
        tabbedPane.addTab("✨ System", createSystemPanel());

        add(tabbedPane, BorderLayout.CENTER);

        // Button Listeners
        refreshBtn.addActionListener(e -> refreshFileList());
        uploadBtn.addActionListener(e -> chooseAndUploadFile());
        downloadBtn.addActionListener(e -> downloadSelectedFile());
        deleteBtn.addActionListener(e -> deleteSelectedFile());

        // Native Look
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }
    }

    private JPanel createSystemPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(30, 30, 30, 30));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(10, 10, 10, 10);

        JLabel title = new JLabel("Distributed System Management", JLabel.CENTER);
        title.setFont(new Font("SansSerif", Font.BOLD, 20));
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        panel.add(title, gbc);

        JLabel desc = new JLabel(
                "<html><center>Launch more entities to test Load Balancing and Failover features.<br>Each server registers with the Registry at "
                        + lbHost + ":" + lbPort + "</center></html>",
                JLabel.CENTER);
        gbc.gridy = 1;
        panel.add(desc, gbc);

        JButton launchServer = new JButton("Launch New Server Instance");
        launchServer.setFont(new Font("SansSerif", Font.BOLD, 14));
        launchServer.setPreferredSize(new Dimension(250, 40));
        launchServer.addActionListener(e -> showStartServerDialog());
        gbc.gridy = 2;
        gbc.gridwidth = 1;
        gbc.insets = new Insets(30, 10, 10, 5);
        panel.add(launchServer, gbc);

        JButton launchClient = new JButton("Launch New Management Console");
        launchClient.setFont(new Font("SansSerif", Font.BOLD, 14));
        launchClient.setPreferredSize(new Dimension(250, 40));
        launchClient.addActionListener(e -> {
            new FileTransferUI(lbHost, lbPort).setVisible(true);
            updateStatus("New client spawned locally", 100);
        });
        gbc.gridx = 1;
        gbc.insets = new Insets(30, 5, 10, 10);
        panel.add(launchClient, gbc);

        return panel;
    }

    private JPanel createBenchmarkPanel() {
        JPanel panel = new JPanel(new BorderLayout(15, 15));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        String[] cols = { "Communication Mode", "Latency (RTT Avg ms)", "Status" };
        DefaultTableModel benchModel = new DefaultTableModel(cols, 0);
        JTable benchTable = new JTable(benchModel);
        benchTable.setRowHeight(35);
        benchTable.setFont(new Font("SansSerif", Font.PLAIN, 14));
        benchTable.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 14));

        benchTable.setFont(new Font("SansSerif", Font.PLAIN, 14));
        benchTable.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 14));

        JScrollPane scroll = new JScrollPane(benchTable);

        // Detailed results area
        JTextArea detailLog = new JTextArea();
        detailLog.setEditable(false);
        detailLog.setFont(new Font("Monospaced", Font.PLAIN, 12));
        detailLog.setBackground(new Color(245, 245, 245));
        JScrollPane detailScroll = new JScrollPane(detailLog);
        detailScroll.setPreferredSize(new Dimension(0, 150));
        detailScroll.setBorder(BorderFactory.createTitledBorder("Detailed Performance Log (Current Run)"));

        JPanel centerPanel = new JPanel(new BorderLayout(10, 10));
        centerPanel.add(scroll, BorderLayout.CENTER);
        centerPanel.add(detailScroll, BorderLayout.SOUTH);
        panel.add(centerPanel, BorderLayout.CENTER);

        JButton runBtn = new JButton("🚀 Run Side-by-Side Chunked Comparison");
        runBtn.setPreferredSize(new Dimension(0, 50));
        runBtn.setFont(new Font("SansSerif", Font.BOLD, 16));

        runBtn.addActionListener(e -> {
            if (currentServerUrl == null) {
                JOptionPane.showMessageDialog(this, "Connect to a server in File Explorer first!", "No Connection",
                        JOptionPane.WARNING_MESSAGE);
                return;
            }

            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Select File for Chunked Benchmark");
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                File file = chooser.getSelectedFile();
                runBtn.setEnabled(false);
                benchModel.setRowCount(0);
                detailLog.setText("");

                new Thread(() -> {
                    try {
                        byte[] fileData = Files.readAllBytes(file.toPath());
                        int chunkSize = 64 * 1024; // 64KB
                        int totalChunks = (int) Math.ceil((double) fileData.length / chunkSize);

                        SwingUtilities.invokeLater(() -> {
                            detailLog.append("FILE: " + file.getName() + " (" + file.length() + " bytes)\n");
                            detailLog.append("CHUNKS: " + totalChunks + " (Size: 64KB each)\n");
                            detailLog.append("──────────────────────────────────────────────────\n");
                        });

                        ProtocolBackend[] backends = {
                                new RmiBackend(),
                                new SocketRpcBackend(),
                                new HttpRpcBackend(),
                                new LegacyRpcBackend()
                        };

                        for (ProtocolBackend backend : backends) {
                            final String backName = backend.getName();
                            backend.reset();

                            SwingUtilities.invokeLater(() -> {
                                benchModel.addRow(new Object[] { backName, "Uploading...", "⏳ In Progress" });
                                detailLog.append("\n> Testing " + backName + "...\n");
                            });

                            long totalTime = 0;
                            try {
                                for (int i = 0; i < totalChunks; i++) {
                                    int startIdx = i * chunkSize;
                                    int len = Math.min(chunkSize, fileData.length - startIdx);
                                    byte[] chunk = new byte[len];
                                    System.arraycopy(fileData, startIdx, chunk, 0, len);

                                    long chunkLat = backend.measureChunk(file.getName(), chunk, i, totalChunks,
                                            currentServerUrl);
                                    totalTime += chunkLat;

                                    final int cIdx = i;
                                    final long cLat = chunkLat;
                                    SwingUtilities.invokeLater(() -> {
                                        detailLog.append(
                                                "  Chunk " + (cIdx + 1) + "/" + totalChunks + ": " + cLat + "ms\n");
                                        detailLog.setCaretPosition(detailLog.getDocument().getLength());
                                    });
                                }

                                final long finalTotal = totalTime;
                                SwingUtilities.invokeLater(() -> {
                                    int lastRow = benchModel.getRowCount() - 1;
                                    benchModel.setValueAt(finalTotal + " ms", lastRow, 1);
                                    benchModel.setValueAt("✅ Success", lastRow, 2);
                                });
                            } catch (Exception ex) {
                                SwingUtilities.invokeLater(() -> {
                                    int lastRow = benchModel.getRowCount() - 1;
                                    benchModel.setValueAt("---", lastRow, 1);
                                    benchModel.setValueAt("❌ Failed", lastRow, 2);
                                    detailLog.append("  [!] Error: " + ex.getMessage() + "\n");
                                });
                            }
                        }
                    } catch (IOException ex) {
                        showError("File Error", ex.getMessage());
                    } finally {
                        SwingUtilities.invokeLater(() -> runBtn.setEnabled(true));
                    }
                }).start();
            }
        });

        JLabel title = new JLabel(
                "<html><h2>Protocol Latency Comparison</h2><p>Tests the round-trip time for a 512KB file upload across various communication architectures.</p></html>");
        panel.add(title, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(runBtn, BorderLayout.SOUTH);
        return panel;
    }

    private void showStartServerDialog() {
        JTextField nameField = new JTextField("Server-" + (int) (Math.random() * 100));
        JTextField portField = new JTextField("" + (1100 + (int) (Math.random() * 100)));
        JTextField storageField = new JTextField("storage_" + nameField.getText());
        Object[] msg = { "Name:", nameField, "Port:", portField, "Storage:", storageField };
        if (JOptionPane.showConfirmDialog(null, msg, "New Server",
                JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            launchServerProcess(nameField.getText(), portField.getText(), storageField.getText());
        }
    }

    private void launchServerProcess(String name, String port, String storage) {
        new Thread(() -> {
            try {
                updateStatus("Launching " + name + "...", 50);
                ProcessBuilder pb = new ProcessBuilder("java", "-cp", "bin", "server.FileServerImpl", port, storage,
                        name, lbHost, String.valueOf(lbPort));
                pb.inheritIO().start();
                updateStatus("Process started", 100);
            } catch (Exception e) {
                showError("Launch Error", e.getMessage());
            }
        }).start();
    }

    private void connectToRMI() {
        new Thread(() -> {
            try {
                updateStatus("Connecting to LB...", 20);
                Registry registry = LocateRegistry.getRegistry(lbHost, lbPort);
                loadBalancer = (LoadBalancerService) registry.lookup("LoadBalancer");
                assignServer();
            } catch (Exception e) {
                showError("Connection Failure", e.getMessage());
            }
        }).start();
    }

    private void assignServer() {
        try {
            updateStatus("Finding server...", 60);
            currentServerUrl = loadBalancer.getNextServer();
            currentServer = (FileTransferService) Naming.lookup(currentServerUrl);
            String name = currentServer.ping();
            SwingUtilities.invokeLater(() -> {
                serverLabel.setText("● Connected: " + name);
                serverLabel.setForeground(new Color(0, 150, 0));
                refreshFileList();
            });
        } catch (Exception e) {
            showError("Assignment Error", e.getMessage());
        }
    }

    private void switchServer() {
        new Thread(this::assignServer).start();
    }

    private void refreshFileList() {
        new Thread(() -> {
            try {
                updateStatus("Listing files...", 50);
                List<FileMetadata> files = retryOperation(() -> currentServer.listFiles());
                SwingUtilities.invokeLater(() -> {
                    tableModel.setRowCount(0);
                    SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
                    int i = 1;
                    for (FileMetadata f : files) {
                        tableModel.addRow(new Object[] { i++, f.getFileName(), f.getFormattedSize(),
                                f.getChecksum().substring(0, 8), sdf.format(new Date(f.getUploadTimestamp())),
                                sdf.format(new Date(f.getLastAccessTimestamp())) });
                    }
                    updateStatus("Ready", 100);
                });
            } catch (Exception e) {
                showError("Refresh Error", e.getMessage());
            }
        }).start();
    }

    private void chooseAndUploadFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select File to Upload (Chunked RMI)");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            new Thread(() -> {
                try {
                    updateStatus("Preparing " + file.getName() + "...", 5);
                    byte[] fileData = Files.readAllBytes(file.toPath());
                    String checksum = computeChecksum(fileData);

                    int chunkSize = 64 * 1024; // 64KB
                    int totalChunks = (int) Math.ceil((double) fileData.length / chunkSize);

                    SwingUtilities.invokeLater(() -> {
                        transferLog.setText("");
                        transferLog.append(">>> INITIATING UPLOAD: " + file.getName() + "\n");
                        transferLog.append(">>> TOTAL SIZE: " + file.length() + " bytes\n");
                        transferLog.append(">>> TOTAL CHUNKS: " + totalChunks + " (64KB each)\n");
                        transferLog.append("──────────────────────────────────────────────────\n");
                    });

                    long totalTime = 0;
                    for (int i = 0; i < totalChunks; i++) {
                        final int currentI = i;
                        int startIdx = i * chunkSize;
                        int len = Math.min(chunkSize, fileData.length - startIdx);
                        byte[] chunkData = new byte[len];
                        System.arraycopy(fileData, startIdx, chunkData, 0, len);

                        String chunkCheck = computeChecksum(chunkData);

                        long start = System.currentTimeMillis();
                        retryOperation(
                                () -> currentServer.uploadChunk(file.getName(), currentI, totalChunks, chunkData,
                                        chunkCheck));
                        long chunkLat = System.currentTimeMillis() - start;
                        totalTime += chunkLat;

                        final int cIdx = i;
                        final long cLat = chunkLat;
                        final int progress = (int) (((double) (i + 1) / totalChunks) * 90) + 5;

                        SwingUtilities.invokeLater(() -> {
                            transferLog.append(
                                    String.format(" [%2d/%2d] Sent Chunk: %d ms\n", (cIdx + 1), totalChunks, cLat));
                            transferLog.setCaretPosition(transferLog.getDocument().getLength());
                            statusLabel.setText("Uploading Chunk " + (cIdx + 1) + "/" + totalChunks);
                            progressBar.setValue(progress);
                        });
                    }

                    final long finalTotal = totalTime;
                    SwingUtilities.invokeLater(() -> {
                        transferLog.append("──────────────────────────────────────────────────\n");
                        transferLog.append(">>> UPLOAD SUCCESSFUL\n");
                        transferLog.append(">>> TOTAL TRANSFER TIME: " + finalTotal + " ms\n");
                        updateStatus("Upload complete", 100);
                        refreshFileList();
                    });
                } catch (Exception e) {
                    showError("Upload Error", e.getMessage());
                    SwingUtilities.invokeLater(() -> transferLog.append("\n[!] ERROR: " + e.getMessage() + "\n"));
                }
            }).start();
        }
    }

    private void downloadSelectedFile() {
        int row = fileTable.getSelectedRow();
        if (row == -1)
            return;
        String name = (String) tableModel.getValueAt(row, 1);
        new Thread(() -> {
            try {
                updateStatus("Downloading...", 50);
                byte[] comp = retryOperation(() -> currentServer.downloadFile(name));
                File dir = new File(DOWNLOAD_DIR);
                if (!dir.exists())
                    dir.mkdirs();
                Files.write(Paths.get(DOWNLOAD_DIR, name), decompress(comp));
                updateStatus("Download complete", 100);
                JOptionPane.showMessageDialog(this, "Saved to " + DOWNLOAD_DIR);
            } catch (Exception e) {
                showError("Download error", e.getMessage());
            }
        }).start();
    }

    private void deleteSelectedFile() {
        int row = fileTable.getSelectedRow();
        if (row == -1)
            return;
        String name = (String) tableModel.getValueAt(row, 1);
        new Thread(() -> {
            try {
                retryOperation(() -> currentServer.deleteFile(name));
                refreshFileList();
            } catch (Exception e) {
                showError("Delete error", e.getMessage());
            }
        }).start();
    }

    private <T> T retryOperation(RemoteOperation<T> op) throws Exception {
        Exception last = null;
        for (int i = 1; i <= MAX_RETRIES; i++) {
            try {
                return op.execute();
            } catch (RemoteException e) {
                last = e;
                if (i < MAX_RETRIES) {
                    Thread.sleep(1000);
                    assignServer();
                }
            }
        }
        throw last;
    }

    @FunctionalInterface
    private interface RemoteOperation<T> {
        T execute() throws RemoteException;
    }

    private void updateStatus(String msg, int p) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText(msg);
            progressBar.setValue(p);
        });
    }

    private void showError(String t, String m) {
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, m, t, JOptionPane.ERROR_MESSAGE));
    }

    private byte[] compress(byte[] d) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(b)) {
            g.write(d);
        }
        return b.toByteArray();
    }

    private byte[] decompress(byte[] d) throws IOException {
        ByteArrayInputStream b = new ByteArrayInputStream(d);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        try (GZIPInputStream g = new GZIPInputStream(b)) {
            byte[] buf = new byte[8192];
            int l;
            while ((l = g.read(buf)) != -1)
                o.write(buf, 0, l);
        }
        return o.toByteArray();
    }

    private String computeChecksum(byte[] d) {
        try {
            MessageDigest md = MessageDigest.getInstance(CHECKSUM_ALGO);
            byte[] h = md.digest(d);
            StringBuilder s = new StringBuilder();
            for (byte b : h)
                s.append(String.format("%02x", b));
            return s.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static void main(String[] args) {
        String h = args.length > 0 ? args[0] : "localhost";
        int p = args.length > 1 ? Integer.parseInt(args[1]) : 1099;
        SwingUtilities.invokeLater(() -> new FileTransferUI(h, p).setVisible(true));
    }
}
