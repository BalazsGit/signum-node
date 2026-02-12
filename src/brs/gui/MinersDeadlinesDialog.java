package brs.gui;

import brs.gui.util.TableUtils;

import javax.swing.*;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;

public class MinersDeadlinesDialog extends JFrame {
    private static volatile MinersDeadlinesDialog instance;
    private JTable table;

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

    public static void packColumns() {
        if (instance != null && instance.isVisible() && instance.table != null) {
            TableUtils.packTableColumns(instance.table);
        }
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

        table = new JTable(model);
        table.setDefaultRenderer(Object.class, new BlockGenerationMetricsPanel.MinerTableCellRenderer());
        table.setFillsViewportHeight(true);

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
