package application.utils.gui;

import javax.swing.JTable;
import javax.swing.table.TableModel;

/**
 * Egy JTable kiterjesztés, amely automatikusan vált az AUTO_RESIZE_ALL_COLUMNS
 * és AUTO_RESIZE_OFF módok között attól függően, hogy az oszlopok
 * preferált szélessége kitölti-e a rendelkezésre álló teret.
 */
public class SmartTable extends JTable {
    public SmartTable(TableModel model) {
        super(model);
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return getParent() == null || getSumColumnPreferredWidths() < getParent().getWidth();
    }

    @Override
    public void doLayout() {
        if (getScrollableTracksViewportWidth()) {
            setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        } else {
            setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        }
        super.doLayout();
    }

    private int getSumColumnPreferredWidths() {
        int width = 0;
        for (int i = 0; i < getColumnModel().getColumnCount(); i++) {
            width += getColumnModel().getColumn(i).getPreferredWidth();
        }
        return width;
    }
}