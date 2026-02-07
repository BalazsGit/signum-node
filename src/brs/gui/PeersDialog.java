package brs.gui;

import brs.Block;
import brs.Signum;
import brs.BlockchainProcessor;
import brs.peer.Peer;
import brs.util.Listener;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@SuppressWarnings("serial")
public class PeersDialog extends JFrame {

    private static volatile PeersDialog instance;

    private final Listener<Block> peerListener;
    private final JTabbedPane tabbedPane;

    public enum PeerCategory {
        ACTIVE("Active", p -> p.getState() != Peer.State.NON_CONNECTED),
        CONNECTED("Connected", p -> p.getState() == Peer.State.CONNECTED),
        BLACKLISTED("Blacklisted", Peer::isBlacklisted),
        ALL("All Known", p -> true);

        private final String title;
        private final Predicate<Peer> filter;

        PeerCategory(String title, Predicate<Peer> filter) {
            this.title = title;
            this.filter = filter;
        }

        public String getTitle() {
            return title;
        }

        public Predicate<Peer> getFilter() {
            return filter;
        }
    }

    public static void showPeersDialog(JFrame owner) {
        if (instance == null) {
            synchronized (PeersDialog.class) {
                if (instance == null) {
                    instance = new PeersDialog(owner);
                }
            }
        }
        instance.setVisible(true);
        instance.setState(Frame.NORMAL);
        instance.toFront();
        instance.requestFocus();
    }

    private PeersDialog(JFrame owner) {
        super("Peer Information");

        JPanel mainPanel = new JPanel(new BorderLayout(5, 5));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JEditorPane legendArea = new JEditorPane();
        legendArea.setContentType("text/html");
        legendArea.setEditable(false);
        legendArea.setBackground(UIManager.getColor("Panel.background"));
        String greenHex = String.format("#%06x", Color.GREEN.getRGB() & 0xFFFFFF);
        String yellowHex = String.format("#%06x", Color.YELLOW.getRGB() & 0xFFFFFF);
        String redHex = String.format("#%06x", Color.RED.getRGB() & 0xFFFFFF);
        legendArea.setText(
                "<html><body style='font-family: sans-serif; font-size: 11px;'>" +
                        "<b>Peers:</b> Active / All Known (BL: Blacklisted)<br>" +
                        "<ul>" +
                        "<li><b>Active:</b> Peers your node is currently communicating with.</li>" +
                        "<li><b>Connected:</b> A subset of active peers with a stable connection.</li>" +
                        "<li><b>Blacklisted:</b> Peers temporarily banned for sending invalid data.</li>" +
                        "<li><b>All Known:</b> All peers your node has ever discovered.</li>" +
                        "</ul>" +
                        "<b>Colors:</b><br>" +
                        "<span style='color:" + greenHex + "'>&#9632;</span> <b>Green:</b> OK / Synced<br>" +
                        "<span style='color:" + yellowHex
                        + "'>&#9632;</span> <b>Yellow:</b> Lagging / Old / Non-Connected<br>" +
                        "<span style='color:" + redHex + "'>&#9632;</span> <b>Red:</b> Blacklisted" +
                        "</body></html>");
        mainPanel.add(legendArea, BorderLayout.NORTH);

        tabbedPane = new JTabbedPane();

        for (PeerCategory category : PeerCategory.values()) {
            tabbedPane.addTab(category.title, new PeerTabPanel(category));
        }

        updateTabs(); // Initial population

        peerListener = block -> SwingUtilities.invokeLater(this::updateTabs);
        Signum.getBlockchainProcessor().addListener(peerListener, BlockchainProcessor.Event.PEERS_UPDATED);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                Signum.getBlockchainProcessor().removeListener(peerListener, BlockchainProcessor.Event.PEERS_UPDATED);
                instance = null;
                dispose();
            }
        });

        mainPanel.add(tabbedPane, BorderLayout.CENTER);
        mainPanel.setPreferredSize(new Dimension(1200, 800));
        add(mainPanel);
        pack();
        setLocationRelativeTo(owner);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
    }

    private void updateTabs() {
        Collection<Peer> allPeers = Signum.getBlockchainProcessor().getAllPeers();

        long maxHeight = 0;
        String latestVersion = Signum.VERSION.toString();

        for (Peer p : allPeers) {
            if (p.getState() == Peer.State.CONNECTED) {
                maxHeight = Math.max(maxHeight, p.getHeight());
            }
            String v = p.getVersion() != null ? p.getVersion().toString() : "";
            if (!v.isEmpty() && !"unknown".equals(v)) {
                if (compareVersions(v, latestVersion) > 0) {
                    latestVersion = v;
                }
            }
        }

        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            PeerCategory category = PeerCategory.values()[i];
            List<Peer> filteredPeers = allPeers.stream().filter(category.filter).collect(Collectors.toList());
            tabbedPane.setTitleAt(i, category.title + " (" + filteredPeers.size() + ")");
            ((PeerTabPanel) tabbedPane.getComponentAt(i)).update(filteredPeers, maxHeight, latestVersion);
        }
    }

    public static int compareVersions(String v1, String v2) {
        if (v1 == null)
            v1 = "";
        if (v2 == null)
            v2 = "";
        String[] parts1 = v1.replaceAll("[^0-9.]", "").split("\\.");
        String[] parts2 = v2.replaceAll("[^0-9.]", "").split("\\.");
        int length = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < length; i++) {
            int num1 = 0;
            if (i < parts1.length && !parts1[i].isEmpty()) {
                try {
                    num1 = Integer.parseInt(parts1[i]);
                } catch (NumberFormatException e) {
                    /* ignore */ }
            }
            int num2 = 0;
            if (i < parts2.length && !parts2[i].isEmpty()) {
                try {
                    num2 = Integer.parseInt(parts2[i]);
                } catch (NumberFormatException e) {
                    /* ignore */ }
            }
            if (num1 < num2)
                return -1;
            if (num1 > num2)
                return 1;
        }
        return 0;
    }

    public static class PeerTabPanel extends JPanel {
        private final PeersTableModel tableModel;
        private final TableRowSorter<PeersTableModel> sorter;
        private final JTextField filterField;
        private final JTable table;

        public PeerTabPanel(PeerCategory category) {
            super(new BorderLayout(5, 5));
            setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

            JPanel filterPanel = new JPanel(new BorderLayout(5, 5));
            filterPanel.add(new JLabel("Filter:"), BorderLayout.WEST);
            filterField = new JTextField();
            filterPanel.add(filterField, BorderLayout.CENTER);
            add(filterPanel, BorderLayout.NORTH);

            tableModel = new PeersTableModel();
            table = new JTable(tableModel);
            table.setDefaultRenderer(Object.class, new PeerTableCellRenderer(category));
            table.setFillsViewportHeight(true);
            table.setAutoCreateRowSorter(true);

            // Custom sorter for 3rd click reset
            sorter = new TableRowSorter<PeersTableModel>(tableModel) {
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

            // Filter logic
            filterField.getDocument().addDocumentListener(new DocumentListener() {
                public void changedUpdate(DocumentEvent e) {
                    filter();
                }

                public void removeUpdate(DocumentEvent e) {
                    filter();
                }

                public void insertUpdate(DocumentEvent e) {
                    filter();
                }

                private void filter() {
                    String text = filterField.getText();
                    if (text.trim().length() == 0) {
                        sorter.setRowFilter(null);
                    } else {
                        try {
                            sorter.setRowFilter(RowFilter.regexFilter("(?i)" + text));
                        } catch (java.util.regex.PatternSyntaxException e) {
                            // ignore
                        }
                    }
                }
            });

            JScrollPane scrollPane = new JScrollPane(table);
            add(scrollPane, BorderLayout.CENTER);
        }

        public void update(List<Peer> peers, long maxHeight, String latestVersion) {
            tableModel.updateData(peers, maxHeight, latestVersion);
            BlockGenerationMetricsPanel.packTableColumns(table);
        }
    }

    public static class PeersTableModel extends AbstractTableModel {
        public static final String COL_ADDRESS = "Address";
        public static final String COL_ANNOUNCED = "Announced";
        public static final String COL_STATE = "State";
        public static final String COL_VERSION = "Version";
        public static final String COL_HEIGHT = "Height";

        private final String[] columnNames = { COL_ADDRESS, COL_ANNOUNCED, COL_STATE, COL_VERSION, COL_HEIGHT };
        private List<Peer> peers = new ArrayList<>();
        private long maxHeight;
        private String latestVersion;

        public void updateData(List<Peer> peers, long maxHeight, String latestVersion) {
            this.peers = peers;
            this.maxHeight = maxHeight;
            this.latestVersion = latestVersion;
            fireTableDataChanged();
        }

        public Peer getPeerAt(int row) {
            return peers.get(row);
        }

        public long getMaxHeight() {
            return maxHeight;
        }

        public String getLatestVersion() {
            return latestVersion;
        }

        @Override
        public int getRowCount() {
            return peers.size();
        }

        @Override
        public int getColumnCount() {
            return columnNames.length;
        }

        @Override
        public String getColumnName(int column) {
            return columnNames[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Peer p = peers.get(rowIndex);
            String columnName = getColumnName(columnIndex);
            if (COL_ADDRESS.equals(columnName))
                return p.getPeerAddress();
            if (COL_ANNOUNCED.equals(columnName))
                return p.getAnnouncedAddress() != null ? p.getAnnouncedAddress() : "-";
            if (COL_STATE.equals(columnName))
                return String.valueOf(p.getState());
            if (COL_VERSION.equals(columnName))
                return p.getVersion() != null ? p.getVersion().toString() : "";
            if (COL_HEIGHT.equals(columnName))
                return p.getHeight();
            return null;
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            if (COL_HEIGHT.equals(getColumnName(columnIndex)))
                return Long.class;
            return String.class;
        }
    }

    public static class PeerTableCellRenderer extends DefaultTableCellRenderer {
        private final PeerCategory category;

        public PeerTableCellRenderer(PeerCategory category) {
            this.category = category;
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
                int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            PeersTableModel model = (PeersTableModel) table.getModel();
            Peer p = model.getPeerAt(table.convertRowIndexToModel(row));

            if (!isSelected) {
                c.setBackground(table.getBackground());
                Color fg = Color.GREEN;
                if (p.isBlacklisted()) {
                    fg = Color.RED;
                } else if (p.getState() == Peer.State.NON_CONNECTED || p.getState() == Peer.State.DISCONNECTED) {
                    fg = Color.YELLOW;
                }
                c.setForeground(fg);

                // Specific column coloring overrides
                String columnName = table.getColumnName(column);
                if (PeersTableModel.COL_VERSION.equals(columnName)) { // Version
                    String v = p.getVersion() != null ? p.getVersion().toString() : "";
                    if (PeersDialog.compareVersions(v, model.getLatestVersion()) < 0) {
                        c.setForeground(Color.YELLOW);
                    } else {
                        c.setForeground(Color.GREEN);
                    }
                } else if (PeersTableModel.COL_HEIGHT.equals(columnName)) { // Height
                    if (p.getHeight() < model.getMaxHeight()) {
                        c.setForeground(Color.YELLOW);
                    } else {
                        c.setForeground(Color.GREEN);
                    }
                } else {
                    // Address and State keep the status color
                }
            } else {
                c.setForeground(table.getSelectionForeground());
                c.setBackground(table.getSelectionBackground());
            }

            return c;
        }
    }
}