package brs.gui;

import brs.gui.util.TableUtils;

import javax.swing.*;
import javax.swing.table.TableRowSorter;
import javax.swing.table.JTableHeader;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;

/**
 * A dialog window that displays the deadlines for all connected miners.
 * <p>
 * This dialog provides a detailed table view of the current mining round,
 * showing:
 * <ul>
 * <li>Miner account details (ID, Name, RS Address).</li>
 * <li>Submitted deadline values.</li>
 * <li>Submission time and block height.</li>
 * </ul>
 * </p>
 * <p>
 * The table supports sorting and filtering to help analyze miner performance.
 * </p>
 */
public class MinersDeadlinesDialog extends JFrame {
    private static volatile MinersDeadlinesDialog instance;
    private JTable table;

    /**
     * Displays the miners deadlines dialog.
     * <p>
     * If the dialog is already open, it brings it to the front. Otherwise, it
     * creates a new instance.
     * </p>
     *
     * @param owner The parent frame.
     * @param model The table model containing the miner deadline data.
     */
    public static void showDialog(JFrame owner, BlockGenerationMetricsPanel.MinersTableModel model) {
        if (instance == null) {
            synchronized (MinersDeadlinesDialog.class) {
                if (instance == null) {
                    instance = new MinersDeadlinesDialog(owner, model);
                }
            }
        }
        instance.setVisible(true);
        instance.setState(Frame.NORMAL);
        instance.toFront();
        instance.requestFocus();
        packColumns();
    }

    /**
     * Resizes the table columns to fit their content.
     * <p>
     * This method delegates to {@link TableUtils#packTableColumns(JTable)} to
     * adjust
     * all columns of the deadlines table.
     * </p>
     */
    public static void packColumns() {
        if (instance != null && instance.table != null && instance.table.isShowing()) {
            TableUtils.packTableColumns(instance.table);
        }
    }

    /**
     * Checks if the miners deadlines dialog is currently open and visible.
     *
     * @return {@code true} if the dialog instance exists and is visible,
     *         {@code false} otherwise.
     */
    public static boolean isDialogVisible() {
        return instance != null && instance.isVisible();
    }

    private MinersDeadlinesDialog(JFrame owner, BlockGenerationMetricsPanel.MinersTableModel model) {
        super("Miners & Deadlines");
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                instance = null;
                table = null;
            }
        });

        JPanel contentPanel = new JPanel(new BorderLayout(0, 0));
        contentPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        JPanel filterPanel = new JPanel(new BorderLayout(5, 5));
        filterPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 0));
        filterPanel.add(new JLabel("Filter:"), BorderLayout.WEST);
        JTextField filterTextField = new JTextField();
        filterPanel.add(filterTextField, BorderLayout.CENTER);

        table = new JTable(model) {
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
        table.setDefaultRenderer(Object.class, new BlockGenerationMetricsPanel.MinerTableCellRenderer());
        table.setFillsViewportHeight(true);
        table.setCellSelectionEnabled(true);

        TableRowSorter<BlockGenerationMetricsPanel.MinersTableModel> sorter = new TableRowSorter<BlockGenerationMetricsPanel.MinersTableModel>(
                model) {
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

        table.getColumn(BlockGenerationMetricsPanel.MinersTableModel.COL_IO).setPreferredWidth(30);
        table.getColumn(BlockGenerationMetricsPanel.MinersTableModel.COL_IO).setMinWidth(30);
        table.getColumn(BlockGenerationMetricsPanel.MinersTableModel.COL_IO).setMaxWidth(30);

        filterTextField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
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
                String text = filterTextField.getText();
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

        JEditorPane legendPane = new JEditorPane();
        legendPane.setContentType("text/html");
        legendPane.setText(BlockGenerationMetricsPanel.getLegendHtml().replace("style='width: 350px'", ""));
        legendPane.setEditable(false);
        legendPane.setCaretPosition(0);
        legendPane.setBackground(UIManager.getColor("Panel.background"));

        JScrollPane legendScrollPane = new JScrollPane(legendPane);
        legendScrollPane.setPreferredSize(new Dimension(0, 180));

        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.add(legendScrollPane, BorderLayout.CENTER);
        topPanel.add(filterPanel, BorderLayout.SOUTH);

        contentPanel.add(topPanel, BorderLayout.NORTH);

        JScrollPane scrollPane = new JScrollPane(table);
        contentPanel.add(scrollPane, BorderLayout.CENTER);

        setContentPane(contentPanel);
        setSize(900, 700);
        setLocationRelativeTo(owner);
    }
}
