package application.module.node.db.store;

import application.module.node.db.DerivedTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntSupplier;

public class DerivedTableManager {

    private final Logger logger = LoggerFactory.getLogger(DerivedTableManager.class);

    private final List<DerivedTable> derivedTables = new CopyOnWriteArrayList<>();

    /**
     * Provider for minimum rollback height used by EntitySqlTable.checkAvailable().
     * Initially returns Integer.MIN_VALUE (no restriction) until wired with a live
     * BlockchainProcessor after initialization completes.
     */
    private volatile IntSupplier minRollbackHeightSupplier = () -> Integer.MIN_VALUE;

    public List<DerivedTable> getDerivedTables() {
        return derivedTables;
    }

    public void registerDerivedTable(DerivedTable table) {
        if (derivedTables.contains(table)) {
            logger.debug("Derived table {} already registered", table.getTable());
            return;
        }
        logger.info("Registering derived table {}", table.getTable());
        derivedTables.add(table);
    }

    /**
     * Returns the current minimum rollback height. Safe to call before wiring —
     * defaults to {@link Integer#MIN_VALUE} which effectively disables the check.
     */
    public int getMinRollbackHeight() {
        return minRollbackHeightSupplier.getAsInt();
    }

    /**
     * Wires a live source for the minimum rollback height.
     * Called once after BlockchainProcessor is fully initialized.
     *
     * @param supplier function that returns the current min rollback height
     */
    public void setMinRollbackHeightSupplier(IntSupplier supplier) {
        if (supplier == null) {
            throw new IllegalArgumentException("minRollbackHeightSupplier must not be null");
        }
        this.minRollbackHeightSupplier = supplier;
    }
}
