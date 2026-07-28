package application.module.node.db.sql;

import application.module.node.Blockchain;
import application.module.node.db.store.Dbs;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.props.PropertyService;

/**
 * Immutable context object that bundles the dependencies required by Sql*Store classes.
 * This eliminates stateful static Signum.getXxx() calls via constructor injection.
 *
 * @param blockchain      the current blockchain instance
 * @param propertyService the configuration property service
 * @param fluxCapacitor   the consensus parameter flux capacitor
 * @param dbs             the database wrapper (BlockDb, TransactionDb, PeerDb)
 */
public record StoreDependencies(
        Blockchain blockchain,
        PropertyService propertyService,
        FluxCapacitor fluxCapacitor,
        Dbs dbs) {

    /**
     * Validates that all required dependencies are provided.
     *
     * @throws IllegalArgumentException if any dependency is null
     */
    public StoreDependencies {
        if (blockchain == null) {
            throw new IllegalArgumentException("Blockchain must not be null");
        }
        if (propertyService == null) {
            throw new IllegalArgumentException("PropertyService must not be null");
        }
        if (fluxCapacitor == null) {
            throw new IllegalArgumentException("FluxCapacitor must not be null");
        }
        if (dbs == null) {
            throw new IllegalArgumentException("Dbs must not be null");
        }
    }
}
