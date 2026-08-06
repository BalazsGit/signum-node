package application.module.node.db.sql;

import application.module.node.Blockchain;
import application.module.node.db.store.Dbs;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.props.PropertyService;

/**
 * Immutable context object that bundles the dependencies required by Sql*Store classes.
 * This eliminates stateful static Signum.getXxx() calls via constructor injection.
 *
 * @param blockchain      the current blockchain instance (can be null during construction - wired later via Stores.wireDependencies())
 * @param propertyService the configuration property service
 * @param fluxCapacitor   the consensus parameter flux capacitor (can be null during construction)
 * @param dbs             the database wrapper (BlockDb, TransactionDb, PeerDb)
 * @param dbContext       the instance-scoped database context replacing static Db.activeContext
 */
public record StoreDependencies(
        Blockchain blockchain,
        PropertyService propertyService,
        FluxCapacitor fluxCapacitor,
        Dbs dbs,
        DbContext dbContext) {

    /**
     * Validates that required (non-deferrable) dependencies are provided.
     * Note: blockchain and fluxCapacitor can be null during construction since they're created after Stores.
     *
     * @throws IllegalArgumentException if any non-optional dependency is null
     */
    public StoreDependencies {
        if (propertyService == null) {
            throw new IllegalArgumentException("PropertyService must not be null");
        }
        if (dbs == null) {
            throw new IllegalArgumentException("Dbs must not be null");
        }
        if (dbContext == null) {
            throw new IllegalArgumentException("DbContext must not be null");
        }
    }
}