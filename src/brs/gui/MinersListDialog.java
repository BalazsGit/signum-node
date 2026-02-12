package brs.gui;

import brs.Generator;
import brs.Signum;
import brs.gui.BlockGenerationMetricsPanel.MinerEntry;
import brs.util.Convert;
import brs.gui.util.TableUtils;
import signumj.entity.SignumAddress;
import signumj.entity.SignumID;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class MinersListDialog extends JFrame {
    private static volatile MinersListDialog instance;
    private DefaultTableModel nodeMinersModel;
    private DefaultTableModel networkMinersModel;
    private JTable nodeMinersTable;
    private JTable networkMinersTable;
    private JTabbedPane tabbedPane;

    private static final Color COLOR_ACTIVE_MINER = new Color(218, 165, 32); // Goldenrod
    private static final Color COLOR_DISCOVERED_NODE_MINER = new Color(50, 205, 50); // Lime Green
    private static final Color COLOR_DISCOVERED_NETWORK_MINER = new Color(0, 100, 0); // Dark Green

    private static final String COL_STATUS = "Status";
    private static final String COL_ADDRESS = "Address";
    private static final String COL_ACCOUNT_ID = "Account ID";
    private static final String COL_NAME = "Name";
    private static final String COL_DEADLINES = "Deadlines";
    private static final String COL_AVG_DEADLINE = "Avg Deadline";
    private static final String COL_MIN_DEADLINE = "Min Deadline";
    private static final String COL_MAX_DEADLINE = "Max Deadline";
    private static final String COL_LAST_DEADLINE = "Last Deadline";
    private static final String COL_LAST_BLOCK_HEIGHT = "Last Block Height";
    private static final String COL_BLOCKS_FOUND = "Blocks Found";

    public static void showDialog(JFrame owner, int tabIndex,
            List<BlockGenerationMetricsPanel.BlockHistoryEntry> history,
            Map<Integer, List<MinerEntry>> nodeHistory) {
        if (instance == null) {
            synchronized (MinersListDialog.class) {
                if (instance == null) {
                    instance = new MinersListDialog(owner);
                }
            }
        }
        instance.updateData(history, nodeHistory);
        if (instance.tabbedPane != null) {
            instance.tabbedPane.setSelectedIndex(tabIndex);
        }
        instance.setVisible(true);
        instance.setState(Frame.NORMAL);
        instance.toFront();
        instance.requestFocus();
    }

    public static void updateIfVisible(List<BlockGenerationMetricsPanel.BlockHistoryEntry> history,
            Map<Integer, List<MinerEntry>> nodeHistory) {
        if (instance != null && instance.isVisible()) {
            instance.updateData(history, nodeHistory);
        }
    }

    private MinersListDialog(JFrame owner) {
        super("Miners List");
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                instance = null;
                nodeMinersModel = null;
                networkMinersModel = null;
                nodeMinersTable = null;
                networkMinersTable = null;
            }
        });

        String[] nodeMinerColumns = { COL_STATUS, COL_ADDRESS, COL_ACCOUNT_ID, COL_NAME, COL_DEADLINES,
                COL_AVG_DEADLINE,
                COL_MIN_DEADLINE, COL_MAX_DEADLINE, COL_LAST_DEADLINE, COL_LAST_BLOCK_HEIGHT, COL_BLOCKS_FOUND };
        String[] networkMinerColumns = { COL_ADDRESS, COL_ACCOUNT_ID, COL_NAME, COL_AVG_DEADLINE, COL_MIN_DEADLINE,
                COL_MAX_DEADLINE, COL_LAST_DEADLINE, COL_LAST_BLOCK_HEIGHT, COL_BLOCKS_FOUND };

        nodeMinersModel = new DefaultTableModel(nodeMinerColumns, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                String name = getColumnName(columnIndex);
                if (COL_DEADLINES.equals(name) || COL_LAST_BLOCK_HEIGHT.equals(name) || COL_BLOCKS_FOUND.equals(name))
                    return Long.class;
                return String.class;
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        networkMinersModel = new DefaultTableModel(networkMinerColumns, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                String name = getColumnName(columnIndex);
                if (COL_LAST_BLOCK_HEIGHT.equals(name) || COL_BLOCKS_FOUND.equals(name))
                    return Long.class;
                return String.class;
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        JPanel mainPanel = new JPanel(new BorderLayout(5, 5));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        tabbedPane = new JTabbedPane();

        tabbedPane.addTab("Node Miners (Active/Discovered)",
                createMinersTablePanel(nodeMinersModel, getNodeMinersLegend(), table -> {
                    nodeMinersTable = table;
                    nodeMinersTable.setDefaultRenderer(Object.class, new NodeMinerTableCellRenderer(COL_STATUS));
                }));

        tabbedPane.addTab("Network Miners (Discovered)",
                createMinersTablePanel(networkMinersModel, getNetworkMinersLegend(),
                        table -> {
                            networkMinersTable = table;
                            networkMinersTable.setDefaultRenderer(Object.class, new NetworkMinerTableCellRenderer());
                        }));

        mainPanel.add(tabbedPane, BorderLayout.CENTER);

        setContentPane(mainPanel);
        setSize(1200, 800);
        setLocationRelativeTo(owner);
    }

    private String getNodeMinersLegend() {
        return "<html><body style='font-family: sans-serif; font-size: 10px;'>" +
                "<h3>Node Miners Legend</h3>" +
                "<ul>" +
                "<li><span style='color:" + toHex(COLOR_ACTIVE_MINER)
                + "'>&#9632;</span> <b>Active:</b> Miners connected to this node that submitted a nonce for the <b>current</b> block generation cycle.</li>"
                +
                "<li><span style='color:" + toHex(COLOR_DISCOVERED_NODE_MINER)
                + "'>&#9632;</span> <b>Discovered:</b> Miners connected to this node that were active in the recent history (last "
                +
                BlockGenerationMetricsPanel.CHART_HISTORY_SIZE + " blocks) but not currently active.</li>" +
                "</ul>" +
                "<b>Columns:</b>" +
                "<ul>" +
                "<li><b>" + COL_DEADLINES
                + ":</b> Total number of nonces submitted by this miner in the history window.</li>" +
                "<li><b>" + COL_AVG_DEADLINE
                + ":</b> The average value of the <b>best</b> deadlines submitted per block (lower is better).</li>"
                +
                "<li><b>" + COL_MIN_DEADLINE + "/" + COL_MAX_DEADLINE
                + ":</b> The minimum (best) and maximum (worst) of the best-per-block deadlines.</li>"
                +
                "<li><b>" + COL_LAST_DEADLINE
                + ":</b> The best deadline submitted for the most recent active block.</li>"
                + "<li><b>" + COL_LAST_BLOCK_HEIGHT
                + ":</b> The height of the most recent block where this miner was active.</li>"
                +
                "<li><b>" + COL_BLOCKS_FOUND + ":</b> Number of blocks forged by this miner in the history window.</li>"
                +
                "</ul>" +
                "</body></html>";
    }

    private String getNetworkMinersLegend() {
        return "<html><body style='font-family: sans-serif; font-size: 10px;'>" +
                "<h3>Network Miners Legend</h3>" +
                "<ul>" +
                "<li><span style='color:" + toHex(COLOR_DISCOVERED_NETWORK_MINER)
                + "'>&#9632;</span> <b>Discovered:</b> Unique miners that forged blocks in the recent history (last " +
                BlockGenerationMetricsPanel.CHART_HISTORY_SIZE + " blocks).</li>" +
                "</ul>" +
                "<b>Columns:</b>" +
                "<ul>" +
                "<li><b>" + COL_AVG_DEADLINE
                + ":</b> The average block time (deadline) of blocks forged by this miner. Calculated from block timestamps.</li>"
                +
                "<li><b>" + COL_MIN_DEADLINE + "/" + COL_MAX_DEADLINE
                + ":</b> The minimum and maximum block time of blocks forged by this miner.</li>"
                +
                "<li><b>" + COL_LAST_DEADLINE
                + ":</b> The block time of the most recent block forged by this miner.</li>"
                + "<li><b>" + COL_LAST_BLOCK_HEIGHT
                + ":</b> The height of the most recent block forged by this miner.</li>"
                +
                "<li><b>" + COL_BLOCKS_FOUND + ":</b> Number of blocks forged by this miner in the history window.</li>"
                +
                "</ul>" +
                "</body></html>";
    }

    private void updateData(List<BlockGenerationMetricsPanel.BlockHistoryEntry> history,
            Map<Integer, List<MinerEntry>> nodeHistory) {
        if (nodeMinersModel == null || networkMinersModel == null)
            return;

        Map<Long, Long> historyCounts = history.stream()
                .collect(Collectors.groupingBy(e -> e.generatorId, Collectors.counting()));

        // --- Node Miners Data ---
        List<Object[]> nodeMinersData = new ArrayList<>();
        Map<Long, NodeMinerStats> nodeStats = new HashMap<>();
        int nextHeight = (Signum.getBlockchain().getLastBlock() != null
                ? Signum.getBlockchain().getLastBlock().getHeight()
                : 0) + 1;

        // Aggregate data from node history
        for (Map.Entry<Integer, List<MinerEntry>> historyEntry : nodeHistory.entrySet()) {
            int height = historyEntry.getKey();
            List<MinerEntry> entries = historyEntry.getValue();
            Map<Long, BigInteger> bestPerMiner = new HashMap<>();
            for (MinerEntry entry : entries) {
                if (entry.deadline != null) {
                    bestPerMiner.merge(entry.accountId, entry.deadline, (a, b) -> a.compareTo(b) < 0 ? a : b);
                }
            }
            for (Map.Entry<Long, BigInteger> entry : bestPerMiner.entrySet()) {
                nodeStats.computeIfAbsent(entry.getKey(), k -> new NodeMinerStats()).add(entry.getValue(), height);
            }
        }

        // Check active status based on next block submissions
        List<MinerEntry> currentEntries = nodeHistory.get(nextHeight);
        if (currentEntries != null) {
            for (MinerEntry entry : currentEntries) {
                nodeStats.computeIfAbsent(entry.accountId, k -> new NodeMinerStats()).isActive = true;
            }
        }

        for (Map.Entry<Long, NodeMinerStats> entry : nodeStats.entrySet()) {
            long id = entry.getKey();
            NodeMinerStats stats = entry.getValue();
            long blocksFound = historyCounts.getOrDefault(id, 0L);
            nodeMinersData.add(createNodeMinerRow(id, stats, blocksFound));
        }

        // Sort by Active status then by Deadlines count
        nodeMinersData.sort((o1, o2) -> {
            String s1 = (String) o1[0];
            String s2 = (String) o2[0];
            if (!s1.equals(s2)) {
                return s1.equals("Active") ? -1 : 1;
            }
            return Long.compare((Long) o2[4], (Long) o1[4]); // Descending deadlines
        });

        updateTableModelData(nodeMinersModel, nodeMinersData);

        // --- Network Miners Data ---
        List<Object[]> networkMinersData = new ArrayList<>();
        Map<Long, NetworkMinerStats> networkStats = new HashMap<>();

        // Calculate stats from history
        for (int i = 1; i < history.size(); i++) {
            BlockGenerationMetricsPanel.BlockHistoryEntry prev = history.get(i - 1);
            BlockGenerationMetricsPanel.BlockHistoryEntry curr = history.get(i);
            if (curr.height == prev.height + 1) {
                long diff = Math.max(0, (long) curr.timestamp - prev.timestamp);
                networkStats.computeIfAbsent(curr.generatorId, k -> new NetworkMinerStats())
                        .add(BigInteger.valueOf(diff), curr.height);
            }
        }

        if (!historyCounts.isEmpty()) {
            List<Map.Entry<Long, Long>> sortedHistory = new ArrayList<>(historyCounts.entrySet());
            sortedHistory.sort(Map.Entry.<Long, Long>comparingByValue().reversed());

            for (Map.Entry<Long, Long> entry : sortedHistory) {
                long id = entry.getKey();
                long blocksFound = entry.getValue();
                NetworkMinerStats stats = networkStats.getOrDefault(id, new NetworkMinerStats());
                networkMinersData.add(createNetworkMinerRow(id, stats, blocksFound));
            }
        }
        updateTableModelData(networkMinersModel, networkMinersData);

        if (nodeMinersTable != null) {
            TableUtils.packTableColumns(nodeMinersTable);
        }
        if (networkMinersTable != null) {
            TableUtils.packTableColumns(networkMinersTable);
        }
    }

    private void updateTableModelData(DefaultTableModel model, List<Object[]> newData) {
        model.setRowCount(0);
        for (Object[] row : newData) {
            model.addRow(row);
        }
    }

    private Object[] createNodeMinerRow(long accountId, NodeMinerStats stats, long blocksFound) {
        String accountRS = SignumAddress.fromId(SignumID.fromLong(accountId)).toString();
        brs.Account account = brs.Account.getAccount(accountId);
        String name = (account != null && account.getName() != null) ? account.getName() : "";
        String avgDeadline = stats.count > 0
                ? String.format("%.0f s", stats.sumDeadline.doubleValue() / stats.count)
                : "-";
        String minDeadline = stats.minDeadline != null ? stats.minDeadline.toString() + " s" : "-";
        String maxDeadline = stats.maxDeadline != null ? stats.maxDeadline.toString() + " s" : "-";
        String lastDeadline = stats.lastDeadline != null ? stats.lastDeadline.toString() + " s" : "-";

        return new Object[] {
                stats.isActive ? "Active" : "Discovered",
                accountRS,
                Convert.toUnsignedLong(accountId),
                name,
                stats.count,
                avgDeadline,
                minDeadline,
                maxDeadline,
                lastDeadline,
                stats.lastBlockHeight,
                blocksFound
        };
    }

    private Object[] createNetworkMinerRow(long accountId, NetworkMinerStats stats, long blocksFound) {
        String accountRS = SignumAddress.fromId(SignumID.fromLong(accountId)).toString();
        brs.Account account = brs.Account.getAccount(accountId);
        String name = (account != null && account.getName() != null) ? account.getName() : "";

        String avgDeadline = stats.count > 0
                ? String.format("%.0f s", stats.sumDeadline.doubleValue() / stats.count)
                : "-";
        String minDeadline = stats.minDeadline != null ? stats.minDeadline.toString() + " s" : "-";
        String maxDeadline = stats.maxDeadline != null ? stats.maxDeadline.toString() + " s" : "-";
        String lastDeadline = stats.lastDeadline != null ? stats.lastDeadline.toString() + " s" : "-";

        return new Object[] { accountRS, Convert.toUnsignedLong(accountId), name, avgDeadline, minDeadline, maxDeadline,
                lastDeadline, stats.lastBlockHeight, blocksFound };
    }

    private JPanel createMinersTablePanel(DefaultTableModel model, String legendHtml, Consumer<JTable> tableConsumer) {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JEditorPane legendPane = new JEditorPane();
        legendPane.setContentType("text/html");
        legendPane.setText(legendHtml);
        legendPane.setEditable(false);
        legendPane.setCaretPosition(0);
        legendPane.setBackground(UIManager.getColor("Panel.background"));

        JScrollPane legendScrollPane = new JScrollPane(legendPane);
        legendScrollPane.setPreferredSize(new Dimension(0, 140));
        legendScrollPane.setBorder(BorderFactory.createTitledBorder("Legend & Information"));

        JPanel filterPanel = new JPanel(new BorderLayout(5, 5));
        filterPanel.add(new JLabel("Filter:"), BorderLayout.WEST);
        JTextField filterField = new JTextField();
        filterPanel.add(filterField, BorderLayout.CENTER);

        JPanel topPanel = new JPanel(new BorderLayout(5, 5));
        topPanel.add(legendScrollPane, BorderLayout.CENTER);
        topPanel.add(filterPanel, BorderLayout.SOUTH);
        panel.add(topPanel, BorderLayout.NORTH);

        JTable table = new JTable(model);
        table.setFillsViewportHeight(true);
        if (tableConsumer != null) {
            tableConsumer.accept(table);
        }
        table.setAutoCreateRowSorter(true);
        table.setCellSelectionEnabled(true);

        TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(model) {
            @Override
            public void toggleSortOrder(int column) {
                List<? extends RowSorter.SortKey> sortKeys = getSortKeys();
                if (sortKeys.size() > 0) {
                    if (sortKeys.get(0).getColumn() == column
                            && sortKeys.get(0).getSortOrder() == SortOrder.DESCENDING) {
                        setSortKeys(null);
                        return;
                    }
                }
                super.toggleSortOrder(column);
            }
        };
        table.setRowSorter(sorter);

        filterField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                filter();
            }

            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                filter();
            }

            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                filter();
            }

            private void filter() {
                String text = filterField.getText();
                if (text.trim().length() == 0) {
                    sorter.setRowFilter(null);
                } else {
                    try {
                        sorter.setRowFilter(RowFilter.regexFilter("(?i)" + text));
                    } catch (java.util.regex.PatternSyntaxException pse) {
                        // ignore invalid regex
                    }
                }
            }
        });

        panel.add(new JScrollPane(table), BorderLayout.CENTER);
        return panel;
    }

    private static class NodeMinerStats {
        long count = 0;
        BigInteger sumDeadline = BigInteger.ZERO;
        BigInteger minDeadline = null;
        BigInteger maxDeadline = null;
        BigInteger lastDeadline = null;
        long lastBlockHeight = 0;
        boolean isActive = false;

        void add(BigInteger deadline, int height) {
            count++;
            sumDeadline = sumDeadline.add(deadline);
            if (minDeadline == null || deadline.compareTo(minDeadline) < 0) {
                minDeadline = deadline;
            }
            if (maxDeadline == null || deadline.compareTo(maxDeadline) > 0) {
                maxDeadline = deadline;
            }
            if (height >= lastBlockHeight) {
                lastBlockHeight = height;
                lastDeadline = deadline;
            }
        }
    }

    private static class NetworkMinerStats {
        long count = 0;
        BigInteger sumDeadline = BigInteger.ZERO;
        BigInteger minDeadline = null;
        BigInteger maxDeadline = null;
        BigInteger lastDeadline = null;
        long lastBlockHeight = 0;

        void add(BigInteger deadline, int height) {
            count++;
            sumDeadline = sumDeadline.add(deadline);
            if (minDeadline == null || deadline.compareTo(minDeadline) < 0) {
                minDeadline = deadline;
            }
            if (maxDeadline == null || deadline.compareTo(maxDeadline) > 0) {
                maxDeadline = deadline;
            }
            if (height >= lastBlockHeight) {
                lastBlockHeight = height;
                lastDeadline = deadline;
            }
        }
    }

    private static class NodeMinerTableCellRenderer extends javax.swing.table.DefaultTableCellRenderer {
        private final String statusColumnName;

        public NodeMinerTableCellRenderer(String statusColumnName) {
            this.statusColumnName = statusColumnName;
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
                int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            if (value instanceof Long) {
                long val = (Long) value;
                if (val > 1_000_000) {
                    setText(formatCount(val));
                    setToolTipText(String.format("%,d", val));
                }
            }

            if (!isSelected) {
                int statusIndex = table.getColumnModel().getColumnIndex(statusColumnName);
                String status = (String) table.getValueAt(row, statusIndex);
                if ("Active".equals(status)) {
                    c.setBackground(table.getBackground());
                    c.setForeground(COLOR_ACTIVE_MINER);
                } else {
                    c.setBackground(table.getBackground());
                    c.setForeground(COLOR_DISCOVERED_NODE_MINER);
                }
            }
            return c;
        }
    }

    private static class NetworkMinerTableCellRenderer extends javax.swing.table.DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
                int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            if (value instanceof Long) {
                long val = (Long) value;
                if (val > 1_000_000) {
                    setText(formatCount(val));
                    setToolTipText(String.format("%,d", val));
                }
            }

            if (!isSelected) {
                c.setForeground(COLOR_DISCOVERED_NETWORK_MINER);
            }
            return c;
        }
    }

    private static String toHex(Color color) {
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }

    private static String formatCount(long count) {
        if (count > 100_000_000) {
            return "> 100M";
        }
        if (count < 1_000_000) {
            return String.valueOf(count);
        }
        return String.format("%.1fM", count / 1_000_000.0);
    }
}
