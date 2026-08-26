package application.module.node.peer;

import application.module.node.Blockchain;
import application.module.node.BlockchainProcessor;
import application.module.node.TransactionProcessor;
import application.module.node.db.store.Dbs;
import application.module.node.db.store.Stores;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.props.PropertyService;
import application.module.node.services.AccountService;
import application.module.node.services.TimeService;
import application.module.node.util.Listener;
import application.module.node.util.Listeners;
import application.module.node.util.ThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Instance-scope peer manager for a single node profile.
 * <p>
 * This class encapsulates all peer-related state and operations that were
 * previously managed by static fields/methods in {@link Peers}. Each node
 * profile owns its own PeerManager instance, enabling true multi-profile
 * isolation where concurrent profiles maintain separate peer registries,
 * connection pools, and scheduler threads.
 * </p>
 *
 * <h2>Lifecycle</h2>
 * <pre>{@code
 * // Create with dependencies via constructor injection
 * PeerManager manager = new PeerManager(
 *     propertyService, blockchain, threadPool, timeService);
 *
 * // Start peer networking for this profile
 * manager.start(timeService, accountService, blockchain,
 *               transactionProcessor, blockchainProcessor,
 *               propertyService, threadPool);
 *
 * // ... profile is running with isolated peers ...
 *
 * // Graceful shutdown when profile stops
 * manager.shutdown(threadPool);
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * All public methods are thread-safe. The internal peer registry uses
 * {@link ConcurrentHashMap} for lock-free reads and safe concurrent writes.
 *
 * @see Peers
 * @see NodeCoreContext
 */
public final class PeerManager {

    private static final Logger logger = LoggerFactory.getLogger(PeerManager.class);

    // Dependencies (constructor-injected, immutable)
    private final PropertyService propertyService;
    private final Blockchain blockchain;
    private final ThreadPool threadPool;
    private final TimeService timeService;

    // Instance-scoped P2P engine for this profile (created by start(), cleared by shutdown())
    private volatile Peers peers;

    // Lifecycle state
    private volatile boolean running = false;

    /**
     * Creates a new PeerManager instance with the given dependencies.
     *
     * @param propertyService the profile's property service
     * @param blockchain      the profile's blockchain instance
     * @param threadPool      the profile's thread pool
     * @param timeService     the profile's time service
     */
    public PeerManager(PropertyService propertyService, Blockchain blockchain,
                       ThreadPool threadPool, TimeService timeService) {
        this.propertyService = propertyService;
        this.blockchain = blockchain;
        this.threadPool = threadPool;
        this.timeService = timeService;
    }

    /**
     * Starts the peer networking subsystem for this profile.
     * <p>
     * Creates this profile's instance-scoped {@link Peers} engine (peer registry,
     * connection pools, scheduler threads, per-node peer server, UPnP). Other
     * profiles' peer networks are not affected.
     * </p>
     *
     * @param timeService          the time service
     * @param accountService       the account service
     * @param blockchain           the blockchain instance
     * @param transactionProcessor the transaction processor
     * @param blockchainProcessor  the blockchain processor
     * @param propertyService      the property service
     * @param threadPool           the thread pool
     * @param fluxCapacitor        the flux capacitor
     * @param dbs                  the dbs (peer database access)
     * @param stores               the stores
     */
    public void start(TimeService timeService, AccountService accountService, Blockchain blockchain,
                      TransactionProcessor transactionProcessor, BlockchainProcessor blockchainProcessor,
                      PropertyService propertyService, ThreadPool threadPool,
                      FluxCapacitor fluxCapacitor, Dbs dbs, Stores stores) {
        if (running) {
            logger.warn("PeerManager already running for profile, ignoring duplicate start()");
            return;
        }
        this.running = true;
        this.peers = new Peers(timeService, accountService, blockchain,
                transactionProcessor, blockchainProcessor, propertyService, threadPool,
                fluxCapacitor, dbs, stores);
        logger.info("PeerManager started (instance-scoped Peers created)");
    }

    /**
     * Shuts down the peer networking subsystem for this profile.
     * <p>
     * Stops only this instance's resources (peer server, UPnP mapping, executor
     * services). Other profiles' peer networks keep running.
     * </p>
     *
     * @param threadPool the thread pool to use for executor cleanup
     */
    public void shutdown(ThreadPool threadPool) {
        if (!running) {
            logger.warn("PeerManager not running, ignoring shutdown()");
            return;
        }
        this.running = false;
        Peers peers = this.peers;
        this.peers = null;
        if (peers != null) {
            peers.shutdown(threadPool);
        }
        logger.info("PeerManager shutdown complete");
    }

    /**
     * Returns whether this PeerManager is currently running.
     *
     * @return true if started and not yet shutdown
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Returns this profile's instance-scoped P2P engine.
     *
     * @return the {@link Peers} instance, or null before start()/after shutdown()
     */
    public Peers getPeers() {
        return peers;
    }

    /**
     * Returns an unmodifiable collection of all known peers for this profile.
     *
     * @return all peers managed by this instance (empty if not started)
     */
    public Collection<Peer> getAllPeers() {
        Peers peers = this.peers;
        return peers != null ? peers.getAllPeers() : Collections.emptyList();
    }

    /**
     * Returns all active (non-NON_CONNECTED) peers.
     *
     * @return list of active peers (empty if not started)
     */
    public List<Peer> getActivePeers() {
        Peers peers = this.peers;
        return peers != null ? peers.getActivePeers() : Collections.emptyList();
    }

    /**
     * Returns peers matching the given state.
     *
     * @param state the peer state to filter by
     * @return collection of peers in the specified state (empty if not started)
     */
    public Collection<Peer> getPeers(Peer.State state) {
        Peers peers = this.peers;
        return peers != null ? peers.getPeers(state) : Collections.emptyList();
    }

    /**
     * Gets a peer by address.
     *
     * @param peerAddress the peer address to look up
     * @return the peer if found, null otherwise
     */
    public Peer getPeer(String peerAddress) {
        Peers peers = this.peers;
        return peers != null ? peers.getPeer(peerAddress) : null;
    }

    /**
     * Adds or returns an existing peer by announced address.
     *
     * @param announcedAddress the announced address of the peer
     * @return the peer instance, or null if invalid or not started
     */
    public Peer addPeer(String announcedAddress) {
        Peers peers = this.peers;
        return peers != null ? peers.addPeer(announcedAddress) : null;
    }

    /**
     * Removes a peer from this profile's registry.
     *
     * @param peer the peer to remove
     * @return the removed peer, or null if not found or not started
     */
    public Peer removePeer(Peer peer) {
        Peers peers = this.peers;
        return peers != null ? peers.removePeer(peer) : null;
    }

    /**
     * Adds an event listener for peer events.
     *
     * @param listener  the listener to add
     * @param eventType the event type to listen for
     * @return true if the listener was added successfully
     */
    public boolean addListener(Listener<Peer> listener, Peers.Event eventType) {
        Peers peers = this.peers;
        return peers != null && peers.listeners.addListener(listener, eventType);
    }

    /**
     * Removes an event listener.
     *
     * @param listener  the listener to remove
     * @param eventType the event type
     * @return true if the listener was removed
     */
    public boolean removeListener(Listener<Peer> listener, Peers.Event eventType) {
        Peers peers = this.peers;
        return peers != null && peers.removeListener(listener, eventType);
    }

    /**
     * Returns the number of peers currently tracked.
     *
     * @return peer count
     */
    public int getPeerCount() {
        Peers peers = this.peers;
        return peers != null ? peers.getAllPeers().size() : 0;
    }

    /**
     * Returns the number of connected peers.
     *
     * @return connected peer count
     */
    public int getConnectedPeerCount() {
        Peers peers = this.peers;
        if (peers == null) {
            return 0;
        }
        int count = 0;
        for (Peer peer : peers.getAllPeers()) {
            if (peer.getState() == Peer.State.CONNECTED) {
                count++;
            }
        }
        return count;
    }

    /**
     * Gets the PropertyService associated with this PeerManager.
     *
     * @return the property service
     */
    public PropertyService getPropertyService() {
        return propertyService;
    }

    /**
     * Gets the Blockchain associated with this PeerManager.
     *
     * @return the blockchain instance
     */
    public Blockchain getBlockchain() {
        return blockchain;
    }

    /**
     * Gets the ThreadPool associated with this PeerManager.
     *
     * @return the thread pool
     */
    public ThreadPool getThreadPool() {
        return threadPool;
    }

    /**
     * Gets the TimeService associated with this PeerManager.
     *
     * @return the time service
     */
    public TimeService getTimeService() {
        return timeService;
    }
}