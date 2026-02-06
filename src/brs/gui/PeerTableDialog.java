package brs.gui;

import javax.swing.*;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

public class PeerTableDialog extends JFrame {
    private static volatile PeerTableDialog instance;
    private JTable table;

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

        table = new JTable(model);
        table.setDefaultRenderer(Object.class, renderer);
        table.setDefaultRenderer(Double.class, renderer);
        table.setDefaultRenderer(Long.class, renderer);
        table.setDefaultRenderer(Integer.class, renderer);
        table.setFillsViewportHeight(true);

        TableRowSorter<TableModel> sorter = new TableRowSorter<>(model);
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
        setSize(1000, 700);
        setLocationRelativeTo(owner);
    }
}