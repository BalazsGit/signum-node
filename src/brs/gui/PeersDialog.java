package brs.gui;

import brs.Block;
import brs.Signum;
import brs.BlockchainProcessor;
import brs.peer.Peer;
import brs.util.Listener;
import brs.gui.util.TableUtils;

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

/**
 * A dialog window that displays information about peers connected to the node.
 * <p>
 * It categorizes peers into Active, Connected, Blacklisted, and All Known
 * groups,
 * providing details such as address, version, and height.
 * </p>
 */
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

    /**
     * Displays the peers dialog.
     * <p>
     * If the dialog is already open, it brings it to the front. Otherwise, it
     * creates a new instance.
     * </p>
     *
     * @param owner The parent frame.
     */
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
                        + "'>&#9632;</span> <b>Yellow:</b> Lagging / Old / Non-Connected<br>"
                        + "<span style='color:" + redHex + "'>&#9632;</span> <b>Red:</b> Blacklisted<br><br>" +
                        "<b>Version Notes:</b>" +
                        "<ul>" +
                        "<li><b>v0.0.0:</b> The peer's version is unknown. This often occurs with newly discovered or unresponsive peers.</li>"
                        +
                        "<li><b>- / empty:</b> The peer did not provide a version. This may happen with very old clients.</li>"
                        + "</ul>" +
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

        for (Peer peer : allPeers) {
            if (peer.getState() == Peer.State.CONNECTED) {
                maxHeight = Math.max(maxHeight, peer.getHeight());
            }
            String version = peer.getVersion() != null ? peer.getVersion().toString() : "";
            if (!version.isEmpty() && !"unknown".equals(version)) {
                if (compareVersions(version, latestVersion) > 0) {
                    latestVersion = version;
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

    public static int compareVersions(String version1, String version2) {
        if (version1 == null)
            version1 = "";
        if (version2 == null)
            version2 = "";

        int index1 = 0;
        int index2 = 0;
        int length1 = version1.length();
        int length2 = version2.length();

        while (index1 < length1 || index2 < length2) {
            // Skip non-digits
            while (index1 < length1 && !Character.isDigit(version1.charAt(index1)))
                index1++;
            while (index2 < length2 && !Character.isDigit(version2.charAt(index2)))
                index2++;

            // Parse number
            long number1 = 0;
            while (index1 < length1 && Character.isDigit(version1.charAt(index1))) {
                number1 = number1 * 10 + (version1.charAt(index1) - '0');
                index1++;
            }

            long number2 = 0;
            while (index2 < length2 && Character.isDigit(version2.charAt(index2))) {
                number2 = number2 * 10 + (version2.charAt(index2) - '0');
                index2++;
            }

            if (number1 < number2)
                return -1;
            if (number1 > number2)
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
            table = new JTable(tableModel) {
                @Override
                protected JTableHeader createDefaultTableHeader() {
                    JTableHeader header = super.createDefaultTableHeader();
                    header.addMouseListener(new java.awt.event.MouseAdapter() {
                        final int defaultDismissDelay = ToolTipManager.sharedInstance().getDismissDelay();

                        @Override
                        public void mouseEntered(java.awt.event.MouseEvent e) {
                            ToolTipManager.sharedInstance().setDismissDelay(60000);
                        }

                        @Override
                        public void mouseExited(java.awt.event.MouseEvent e) {
                            ToolTipManager.sharedInstance().setDismissDelay(defaultDismissDelay);
                        }
                    });
                    return header;
                }
            };
            table.addMouseListener(new java.awt.event.MouseAdapter() {
                final int defaultDismissDelay = ToolTipManager.sharedInstance().getDismissDelay();

                @Override
                public void mouseEntered(java.awt.event.MouseEvent e) {
                    ToolTipManager.sharedInstance().setDismissDelay(60000);
                }

                @Override
                public void mouseExited(java.awt.event.MouseEvent e) {
                    ToolTipManager.sharedInstance().setDismissDelay(defaultDismissDelay);
                }
            });
            table.setDefaultRenderer(Object.class, new PeerTableCellRenderer(category));
            table.setFillsViewportHeight(true);
            table.setCellSelectionEnabled(true);
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
            if (table.isShowing()) {
                TableUtils.packTableColumns(table);
            }
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
            Peer peer = peers.get(rowIndex);
            String columnName = getColumnName(columnIndex);
            if (COL_ADDRESS.equals(columnName))
                return peer.getPeerAddress();
            if (COL_ANNOUNCED.equals(columnName))
                return peer.getAnnouncedAddress() != null ? peer.getAnnouncedAddress() : "-";
            if (COL_STATE.equals(columnName))
                return String.valueOf(peer.getState());
            if (COL_VERSION.equals(columnName))
                return peer.getVersion() != null ? peer.getVersion().toString() : "";
            if (COL_HEIGHT.equals(columnName))
                return peer.getHeight();
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
            Component component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            PeersTableModel model = (PeersTableModel) table.getModel();
            Peer peer = model.getPeerAt(table.convertRowIndexToModel(row));

            if (!isSelected) {
                component.setBackground(table.getBackground());
                Color foregroundColor = Color.GREEN;
                if (peer.isBlacklisted()) {
                    foregroundColor = Color.RED;
                } else if (peer.getState() == Peer.State.NON_CONNECTED || peer.getState() == Peer.State.DISCONNECTED) {
                    foregroundColor = Color.YELLOW;
                }
                component.setForeground(foregroundColor);

                // Specific column coloring overrides
                String columnName = table.getColumnName(column);
                if (PeersTableModel.COL_VERSION.equals(columnName)) { // Version
                    String version = peer.getVersion() != null ? peer.getVersion().toString() : "";
                    if (PeersDialog.compareVersions(version, model.getLatestVersion()) < 0) {
                        component.setForeground(Color.YELLOW);
                    } else {
                        component.setForeground(Color.GREEN);
                    }
                } else if (PeersTableModel.COL_HEIGHT.equals(columnName)) { // Height
                    if (peer.getHeight() < model.getMaxHeight()) {
                        component.setForeground(Color.YELLOW);
                    } else {
                        component.setForeground(Color.GREEN);
                    }
                } else {
                    // Address and State keep the status color
                }
            } else {
                component.setForeground(table.getSelectionForeground());
                component.setBackground(table.getSelectionBackground());
            }

            return component;
        }
    }
}