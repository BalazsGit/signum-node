package brs.gui.util;

import javax.swing.JTable;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import java.awt.Component;

/**
 * Utility class for handling JTable UI operations.
 * <p>
 * This class provides static helper methods to adjust the visual representation
 * of JTables,
 * specifically for resizing columns to fit their content.
 * </p>
 */
public class TableUtils {

    /**
     * Resizes all columns in the given table to fit their content.
     * <p>
     * This method iterates through every column in the table and adjusts its width
     * so that it is wide enough to display the header and the widest cell content
     * in that column. A default margin of 2 pixels is applied.
     * </p>
     *
     * @param table The JTable whose columns should be resized.
     */
    public static void packTableColumns(JTable table) {
        for (int i = 0; i < table.getColumnCount(); i++) {
            packColumn(table, i, 2);
        }
    }

    /**
     * Resizes a specific column in the table to fit its content.
     * <p>
     * This method calculates the preferred width of the column header and all cells
     * within the specified column. It sets the preferred width of the column to the
     * maximum calculated width plus the specified margin.
     * </p>
     *
     * @param table     The JTable containing the column.
     * @param vColIndex The index of the column to resize (view index).
     * @param margin    The margin in pixels to add to the calculated width.
     */
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