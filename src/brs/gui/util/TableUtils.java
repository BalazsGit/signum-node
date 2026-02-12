package brs.gui.util;

import javax.swing.JTable;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import java.awt.Component;

public class TableUtils {

    public static void packTableColumns(JTable table) {
        for (int i = 0; i < table.getColumnCount(); i++) {
            packColumn(table, i, 2);
        }
    }

    public static void packColumn(JTable table, int vColIndex, int margin) {
        TableColumnModel colModel = table.getColumnModel();
        TableColumn col = colModel.getColumn(vColIndex);
        int width = 0;

        // Get width of column header
        TableCellRenderer renderer = col.getHeaderRenderer();
        if (renderer == null) {
            renderer = table.getTableHeader().getDefaultRenderer();
        }
        Component comp = renderer.getTableCellRendererComponent(
                table, col.getHeaderValue(), false, false, 0, 0);
        width = comp.getPreferredSize().width;
        int headerWidth = width;

        // Get width of column content
        for (int r = 0; r < table.getRowCount(); r++) {
            renderer = table.getCellRenderer(r, vColIndex);
            comp = renderer.getTableCellRendererComponent(
                    table, table.getValueAt(r, vColIndex), false, false, r, vColIndex);
            width = Math.max(width, comp.getPreferredSize().width);
        }

        width += 2 * margin;
        col.setMinWidth(headerWidth + 2 * margin);
        col.setPreferredWidth(width);
    }
}