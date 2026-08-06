package application.module.node.peer;

import application.module.node.Blockchain;
import application.module.node.BlockchainProcessor;
import application.module.node.TransactionProcessor;
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

    // Per-instance peer registry (NOT static!)
    private final ConcurrentMap<String, Peer> peers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> announcedAddresses = new ConcurrentHashMap<>();
    private final Collection<Peer> allPeers = Collections.unmodifiableCollection(peers.values());

    // Event listeners for this instance
    private final Listeners<Peer, Peers.Event> listeners = new Listeners<>();

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
     * This delegates to {@link Peers#init(TimeService, AccountService, Blockchain,
     * TransactionProcessor, BlockchainProcessor, PropertyService, ThreadPool)} which
     * currently uses static state. In Phase 10d, this delegation will be replaced
     * with fully instance-scoped peer management.
     * </p>
     *
     * @param timeService          the time service
     * @param accountService       the account service
     * @param blockchain           the blockchain instance
     * @param transactionProcessor the transaction processor
     * @param blockchainProcessor  the blockchain processor
     * @param propertyService      the property service
     * @param threadPool           the thread pool
     */
    public void start(TimeService timeService, AccountService accountService, Blockchain blockchain,
                      TransactionProcessor transactionProcessor, BlockchainProcessor blockchainProcessor,
                      PropertyService propertyService, ThreadPool threadPool) {
        if (running) {
            logger.warn("PeerManager already running for profile, ignoring duplicate start()");
            return;
        }
        this.running = true;
        // Delegate to existing static init — bridges legacy Peers during migration
        Peers.init(timeService, accountService, blockchain,
                   transactionProcessor, blockchainProcessor, propertyService, threadPool);
        logger.info("PeerManager started (delegating to Peers.init() during Phase 10.5 migration)");
    }

    /**
     * Shuts down the peer networking subsystem for this profile.
     * <p>
     * This delegates to {@link Peers#shutdown(ThreadPool)} which currently
     * stops shared static resources. In Phase 10d, shutdown will clean up
     * only this instance's resources.
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
        // Delegate to existing static shutdown — bridges legacy Peers during migration
        Peers.shutdown(threadPool);
        logger.info("PeerManager shutdown complete (delegating to Peers.shutdown() during Phase 10.5 migration)");
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
     * Returns an unmodifiable collection of all known peers for this profile.
     *
     * @return all peers managed by this instance
     */
    public Collection<Peer> getAllPeers() {
        // During migration, Peers holds the actual registry — delegate to it
        return Peers.getAllPeers();
    }

    /**
     * Returns all active (non-NON_CONNECTED) peers.
     *
     * @return list of active peers
     */
    public List<Peer> getActivePeers() {
        return Peers.getActivePeers();
    }

    /**
     * Returns peers matching the given state.
     *
     * @param state the peer state to filter by
     * @return collection of peers in the specified state
     */
    public Collection<Peer> getPeers(Peer.State state) {
        return Peers.getPeers(state);
    }

    /**
     * Gets a peer by address.
     *
     * @param peerAddress the peer address to look up
     * @return the peer if found, null otherwise
     */
    public Peer getPeer(String peerAddress) {
        return Peers.getPeer(peerAddress);
    }

    /**
     * Adds or returns an existing peer by announced address.
     *
     * @param announcedAddress the announced address of the peer
     * @return the peer instance, or null if invalid
     */
    public Peer addPeer(String announcedAddress) {
        return Peers.addPeer(announcedAddress);
    }

    /**
     * Removes a peer from this profile's registry.
     *
     * @param peer the peer to remove
     * @return the removed peer, or null if not found
     */
    public Peer removePeer(Peer peer) {
        return Peers.removePeer(peer);
    }

    /**
     * Adds an event listener for peer events.
     *
     * @param listener  the listener to add
     * @param eventType the event type to listen for
     * @return true if the listener was added successfully
     */
    public boolean addListener(Listener<Peer> listener, Peers.Event eventType) {
        return Peers.listeners.addListener(listener, eventType);
    }

    /**
     * Removes an event listener.
     *
     * @param listener  the listener to remove
     * @param eventType the event type
     * @return true if the listener was removed
     */
    public boolean removeListener(Listener<Peer> listener, Peers.Event eventType) {
        return Peers.removeListener(listener, eventType);
    }

    /**
     * Returns the number of peers currently tracked.
     *
     * @return peer count
     */
    public int getPeerCount() {
        return Peers.getAllPeers().size();
    }

    /**
     * Returns the number of connected peers.
     *
     * @return connected peer count
     */
    public int getConnectedPeerCount() {
        int count = 0;
        for (Peer peer : Peers.getAllPeers()) {
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