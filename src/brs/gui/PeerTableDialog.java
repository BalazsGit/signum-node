package brs.gui;

import brs.gui.util.TableUtils;

import javax.swing.*;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableModel;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * A dialog window for displaying detailed peer metrics in a tabular format.
 * <p>
 * This dialog provides a comprehensive view of peer data, including sorting and
 * filtering capabilities.
 * It is designed to show specific metrics (RX, TX, Other) passed from the
 * {@link PeerMetricsPanel}.
 * </p>
 * <p>
 * The dialog ensures only one instance is visible at a time.
 * </p>
 */
public class PeerTableDialog extends JFrame {
    private static volatile PeerTableDialog instance;
    private JTable table;

    /**
     * Displays the peer table dialog.
     * <p>
     * If a dialog is already open, it is closed and a new one is created with the
     * provided parameters.
     * </p>
     *
     * @param owner      The owner window of this dialog.
     * @param title      The title of the dialog window.
     * @param model      The table model containing the peer data to display.
     * @param renderer   The cell renderer for formatting the table cells.
     * @param legendHtml The HTML string for the legend displayed at the top of the
     *                   dialog.
     */
    public static void showDialog(Window owner, String title, TableModel model, TableCellRenderer renderer,
            String legendHtml) {
        if (instance != null) {
            instance.dispose();
        }
        instance = new PeerTableDialog(owner, title, model, renderer, legendHtml);
        instance.setVisible(true);
        instance.setState(Frame.NORMAL);
        instance.toFront();
        instance.requestFocus();
    }

    private PeerTableDialog(Window owner, String title, TableModel model, TableCellRenderer renderer,
            String legendHtml) {
        super(title);
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
            public String getToolTipText(java.awt.event.MouseEvent e) {
                String tip = super.getToolTipText(e);
                return "".equals(tip) ? null : tip;
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
        ToolTipManager.sharedInstance().registerComponent(table);
        table.setToolTipText("");
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
        table.setDefaultRenderer(Object.class, renderer);
        table.setDefaultRenderer(Double.class, renderer);
        table.setDefaultRenderer(Long.class, renderer);
        table.setDefaultRenderer(Integer.class, renderer);
        table.setFillsViewportHeight(true);
        table.setCellSelectionEnabled(true);

        TableRowSorter<TableModel> sorter = new TableRowSorter<TableModel>(model) {
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
        legendPane.setText(legendHtml);
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
        setSize(1200, 700);
        setLocationRelativeTo(owner);
        TableUtils.packTableColumns(table);
    }
}