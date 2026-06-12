package application.module.node.gui;

import application.module.node.Block;
import application.module.node.Signum;
import application.module.node.BlockchainProcessor;
import application.module.node.peer.Peer;
import application.module.node.util.Listener;
import application.utils.gui.GuiColors;
import application.utils.gui.TableUtils;

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

        JPanel mainPanel = new JPanel(new BorderLayout(0, 5));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        JEditorPane legendArea = new JEditorPane();
        legendArea.setContentType("text/html");
        legendArea.setEditable(false);
        legendArea.setBackground(UIManager.getColor("Panel.background"));
        String greenHex = toHex(GuiColors.getPeerConnected());
        String yellowHex = toHex(GuiColors.getPeerDisconnected());
        String redHex = toHex(GuiColors.getPeerBlacklisted());
        legendArea.setText(
                "<html><body style='font-family: sans-serif; font-size: 11px;'>" +
                        "<b>Peers:</b> Active / All Known (BL: Blacklisted)<br>" +
                        "<ul>" +
                        "<li><b>Active:</b> Peers your node is currently communicating with.</li>" +
                        "<li><b>Connected:</b> A subset of active peers with a stable connection.</li>" +
                        "<li><b>Blacklisted:</b> Peers temporarily banned for sending invalid data.</li>" +
                        "<li><b>All Known:</b> All peers your node has ever discovered.</li>" +
                        "<li><b>Mode:</b> Peer database mode (ARCHIVE, TRIM, PRUNE, UNKNOWN).</li>" +
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
        legendArea.setCaretPosition(0);

        JScrollPane legendScrollPane = new JScrollPane(legendArea);
        legendScrollPane.setPreferredSize(new Dimension(0, 200));
        legendScrollPane.setBorder(BorderFactory.createTitledBorder("Legend & Information"));
        mainPanel.add(legendScrollPane, BorderLayout.NORTH);

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

    private static String toHex(Color color) {
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
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
            super(new BorderLayout(0, 0));
            setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

            JPanel filterPanel = new JPanel(new BorderLayout(5, 5));
            filterPanel.setBorder(BorderFactory.createEmptyBorder(5, 0, 5, 0));
            filterPanel.add(new JLabel("Filter:"), BorderLayout.WEST);
            filterField = new JTextField();
            filterPanel.add(filterField, BorderLayout.CENTER);
            add(filterPanel, BorderLayout.NORTH);

            tableModel = new PeersTableModel();
            table = new JTable(tableModel) {
                @Override
                public void updateUI() {
                    super.updateUI();
                    PeerTableCellRenderer renderer = new PeerTableCellRenderer(category);
                    setDefaultRenderer(Object.class, renderer);
                    setDefaultRenderer(Long.class, renderer);
                    setDefaultRenderer(JButton.class, new ButtonRenderer());
                }

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
            PeerTableCellRenderer baseRenderer = new PeerTableCellRenderer(category);
            table.setDefaultRenderer(Object.class, baseRenderer);
            table.setDefaultRenderer(Long.class, baseRenderer);
            table.setDefaultRenderer(JButton.class, new ButtonRenderer());
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

            // Set button editors for the last two columns
            ButtonEditor buttonEditor = new ButtonEditor(new JCheckBox());
            table.getColumnModel().getColumn(PeersTableModel.COL_INDEX_BLACKLIST).setCellRenderer(new ButtonRenderer());
            table.getColumnModel().getColumn(PeersTableModel.COL_INDEX_BLACKLIST).setCellEditor(buttonEditor);
            table.getColumnModel().getColumn(PeersTableModel.COL_INDEX_CONNECT).setCellRenderer(new ButtonRenderer());
            table.getColumnModel().getColumn(PeersTableModel.COL_INDEX_CONNECT).setCellEditor(buttonEditor);

            table.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
                @Override
                public void mouseMoved(java.awt.event.MouseEvent e) {
                    int viewColumn = table.columnAtPoint(e.getPoint());
                    int viewRow = table.rowAtPoint(e.getPoint());
                    if (viewColumn != -1 && viewRow != -1) {
                        int modelColumn = table.convertColumnIndexToModel(viewColumn);
                        if (modelColumn == PeersTableModel.COL_INDEX_BLACKLIST
                                || modelColumn == PeersTableModel.COL_INDEX_CONNECT) {
                            table.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                            return;
                        }
                    }
                    table.setCursor(Cursor.getDefaultCursor());
                }
            });

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
        public static final String COL_MODE = "Mode";
        public static final String COL_ACTION_BLACKLIST = "Blacklist Action";
        public static final String COL_ACTION_CONNECT = "Connection Action";

        public static final int COL_INDEX_BLACKLIST = 6;
        public static final int COL_INDEX_CONNECT = 7;

        private final String[] columnNames = {
                COL_ADDRESS, COL_ANNOUNCED, COL_STATE, COL_VERSION, COL_HEIGHT, COL_MODE, COL_ACTION_BLACKLIST,
                COL_ACTION_CONNECT
        };
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
            if (COL_MODE.equals(columnName))
                return peer.getArchivalMode().toString();
            if (COL_ACTION_BLACKLIST.equals(columnName))
                return peer.isBlacklisted() ? "Whitelist" : "Blacklist";
            if (COL_ACTION_CONNECT.equals(columnName))
                return peer.getState() == Peer.State.CONNECTED ? "Disconnect" : "Connect";
            return null;
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            String colName = getColumnName(columnIndex);
            if (COL_HEIGHT.equals(colName))
                return Long.class;
            if (COL_ACTION_BLACKLIST.equals(colName) || COL_ACTION_CONNECT.equals(colName))
                return JButton.class;
            return String.class;
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            String colName = getColumnName(column);
            return COL_ACTION_BLACKLIST.equals(colName) || COL_ACTION_CONNECT.equals(colName);
        }
    }

    public static class PeerTableCellRenderer extends DefaultTableCellRenderer implements javax.swing.plaf.UIResource {
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
                Color foregroundColor;
                if (peer.isBlacklisted()) {
                    foregroundColor = GuiColors.getPeerBlacklisted();
                } else if (peer.getState() == Peer.State.NON_CONNECTED || peer.getState() == Peer.State.DISCONNECTED) {
                    foregroundColor = GuiColors.getPeerDisconnected();
                } else {
                    foregroundColor = GuiColors.getPeerConnected(); // Green for connected
                }
                component.setForeground(foregroundColor);

                // Specific column coloring overrides
                String columnName = table.getColumnName(column);
                if (PeersTableModel.COL_VERSION.equals(columnName)) { // Version
                    String version = peer.getVersion() != null ? peer.getVersion().toString() : "";
                    if (PeersDialog.compareVersions(version, model.getLatestVersion()) < 0) {
                        component.setForeground(GuiColors.getPeerOutdatedVersion());
                    } else {
                        component.setForeground(GuiColors.getPeerUpToDateVersion());
                    }
                } else if (PeersTableModel.COL_HEIGHT.equals(columnName)) { // Height
                    if (peer.getHeight() < model.getMaxHeight()) {
                        component.setForeground(GuiColors.getPeerOutdatedHeight());
                    } else {
                        component.setForeground(GuiColors.getPeerUpToDateHeight());
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

    /**
     * Custom renderer for displaying buttons in the table.
     */
    private static class ButtonRenderer extends JButton implements TableCellRenderer {
        public ButtonRenderer() {
            setOpaque(true);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int column) {
            setText((value == null) ? "" : value.toString());
            if (isSelected) {
                setForeground(table.getSelectionForeground());
                setBackground(table.getSelectionBackground());
            } else {
                setForeground(table.getForeground());
                setBackground(UIManager.getColor("Button.background"));
            }
            return this;
        }
    }

    /**
     * Custom editor for handling button clicks.
     */
    private static class ButtonEditor extends DefaultCellEditor {
        protected JButton button;
        private String label;
        private boolean isPushed;
        private Peer currentPeer;
        private String currentColumn;

        public ButtonEditor(JCheckBox checkBox) {
            super(checkBox);
            button = new JButton();
            button.setOpaque(true);
            button.addActionListener(e -> fireEditingStopped());
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value,
                boolean isSelected, int row, int column) {
            label = (value == null) ? "" : value.toString();
            button.setText(label);
            isPushed = true;
            currentPeer = ((PeersTableModel) table.getModel()).getPeerAt(table.convertRowIndexToModel(row));
            currentColumn = table.getColumnName(column);
            return button;
        }

        @Override
        public Object getCellEditorValue() {
            if (isPushed) {
                if (PeersTableModel.COL_ACTION_BLACKLIST.equals(currentColumn)) {
                    if (currentPeer.isBlacklisted())
                        currentPeer.whitelist();
                    else
                        currentPeer.blacklist();
                } else if (PeersTableModel.COL_ACTION_CONNECT.equals(currentColumn)) {
                    if (currentPeer.getState() == Peer.State.CONNECTED)
                        currentPeer.disconnect();
                    else
                        currentPeer.connect();
                }
            }
            isPushed = false;
            return label;
        }

        @Override
        public boolean stopCellEditing() {
            isPushed = false;
            return super.stopCellEditing();
        }
    }
}