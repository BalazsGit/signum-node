package application.module.node.at;

import application.module.node.Account;
import application.module.node.Block;
import application.module.node.TransactionType;
import application.module.node.db.TransactionDb;
import application.module.node.db.VersionedEntityTable;
import application.module.node.db.store.ATStore;
import application.module.node.services.AccountService;
import application.module.node.util.Convert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static java.util.List.of;

/**
 * AT + database-consistency regression suite.
 *
 * <p>Protects the code paths implicated in the "ats are not matching at block height 59763"
 * failure loop and the recurring DB-consistency mismatches
 * (see {@code debug/AT_59763_REGRESSION_DEBUG.md} for the full debug journal, hypotheses H1-H6).
 *
 * <h3>Coverage map</h3>
 * <ul>
 *   <li><b>Forging &harr; validation consistency</b> — {@code getCurrentBlockATs()} must produce
 *       blockATs bytes that {@code validateATs()} deterministically accepts (T1, T3).</li>
 *   <li><b>Production entry point</b> — {@code ATServiceImpl.validateATs()} (the method actually
 *       called by {@code BlockchainProcessorImpl.accept()}) accepts known-good blockATs (T2).</li>
 *   <li><b>Checksum integrity</b> — tampered MD5 is rejected with the exact checksum error (T4);
 *       malformed blockATs (duplicate id, wrong length) are rejected (T5, T6).</li>
 *   <li><b>No false positives</b> - every invalid blockATs is rejected with its exact reason:
 *       an unknown AT id, an insufficient balance, a frozen AT (unchanged balance), an AT not yet
 *       due to run (sleep gate), and a non-multiple length - so an over-permissive validator that
 *       would accept an invalid block fails loudly.</li>
 *   <li><b>No false negatives</b> - every valid blockATs is accepted: a null payload, an empty
 *       payload, and a single-AT block are all accepted (an over-strict validator that would
 *       reject a valid block fails loudly); the length boundary accepts an exact multiple.</li>
 *   <li><b>AtConstants resolution</b> — resolves from the injected context even when the static
 *       web-path registry is null (NPE regression of 2026-09-04); the legacy null-context
 *       singleton fails fast with {@link IllegalStateException}, never a raw NPE (T7, T8).</li>
 *   <li><b>Multi-node isolation</b> — {@link ATPendingState} instances never leak fees/txs/map
 *       updates across node instances; clear/remove semantics are rollback-safe (T9, T10, T11).</li>
 *   <li><b>DB-consistency invariants</b> — AT state bytes round-trip through GZIP (T12);
 *       {@code totalFees} equals the sum of pending fees (T13); AT fees are applied to the
 *       account exactly once (T14); the AT state row is persisted with consistent heights (T15).</li>
 * </ul>
 */
class AtRegressionSuiteTest {

    /**
     * Known-good blockATs (3 ATs: id 1, 2, 3 + their MD5 checksums, 24 bytes per AT).
     * Forged by the V2 test ATs in {@code AtControllerTest#testRunSteps}; the same bytes are
     * accepted by the V2 (T2) and V3 (T3) validation paths.
     */
    private static final String GOOD_BLOCK_ATS_HEX =
            "010000000000000097c1d1e5b25c1d109f2ba522d1dda248"
                    + "020000000000000014ea12712c274caebc49ccd7fff0b0b7"
                    + "03000000000000009f1af5443c8d1e7b492f848e91fccb1f";

    private static final int TEST_HEIGHT = Integer.MAX_VALUE;
    private static final long TEST_GENERATOR = 0L;

    @BeforeEach
    public void setUp() {
        AtTestHelper.setupMocks();
    }

    @AfterEach
    public void tearDown() {
        AtTestHelper.closeStatics();
    }

    @Test
    public void validateAndForgeRoundTrip_isConsistent() throws AtException {
        AtTestHelper.clearAddedAts();
        AtTestHelper.addHelloWorldAT();
        AtTestHelper.addEchoAT();
        AtTestHelper.addTipThanksAT();
        ATProcessingContext ctx = AtTestHelper.getTestContext();
        ATServiceImpl service = new ATServiceImpl(ctx.getAtStore(), ctx);

        // Forging path (what a validator's peer produced) ...
        AtBlock forged = service.getCurrentBlockATs(ctx, Integer.MAX_VALUE, TEST_HEIGHT, TEST_GENERATOR, 0);
        assertNotNull(forged);
        byte[] forgedBytes = forged.getBytesForBlock();
        assertNotNull(forgedBytes, "forged blockATs bytes must not be null");
        assertEquals(72, forgedBytes.length, "each AT costs 24 bytes in blockATs");
        assertEquals(GOOD_BLOCK_ATS_HEX, Convert.toHexString(forgedBytes),
                "forging must reproduce the canonical blockATs vector");

        // Validation path, as seen by a peer node: fresh AT instances loaded from its own DB
        // (the mock store hands out one instance per AT, so re-create them to avoid state
        // carry-over from the forging run — "AT should be frozen due to unchanged balance").
        AtTestHelper.clearAddedAts();
        AtTestHelper.addHelloWorldAT();
        AtTestHelper.addEchoAT();
        AtTestHelper.addTipThanksAT();

        // ... must be accepted by the validation path with identical fee/amount totals.
        AtBlock validated = service.validateATs(ctx, forgedBytes, TEST_HEIGHT, TEST_GENERATOR);
        assertEquals(forged.getTotalFees(), validated.getTotalFees(),
                "forging and validation must agree on total fees");
        assertEquals(forged.getTotalAmount(), validated.getTotalAmount(),
                "forging and validation must agree on total amounts");
    }

    @Test
    public void productionPath_validateATs_acceptsKnownGoodBlockATs() throws AtException {
        AtTestHelper.clearAddedAts();
        AtTestHelper.addHelloWorldAT();
        AtTestHelper.addEchoAT();
        AtTestHelper.addTipThanksAT();
        ATProcessingContext ctx = AtTestHelper.getTestContext();
        ATServiceImpl service = new ATServiceImpl(ctx.getAtStore(), ctx);

        AtBlock atBlock = service.validateATs(ctx, Convert.parseHexString(GOOD_BLOCK_ATS_HEX),
                TEST_HEIGHT, TEST_GENERATOR);
        assertNotNull(atBlock);
        assertEquals(0, atBlock.getTotalAmount());
        assertEquals(5439000L, atBlock.getTotalFees());
    }

    @Test
    public void v3RoundTrip_isConsistent() throws AtException {
        AtTestHelper.clearAddedAts();
        AtTestHelper.addHelloWorldATV3();
        AtTestHelper.addEchoATV3();
        AtTestHelper.addTipThanksATV3();
        ATProcessingContext ctx = AtTestHelper.getTestContext();
        ATServiceImpl service = new ATServiceImpl(ctx.getAtStore(), ctx);

        // V3 (SMART_ATS-era) ATs must reproduce the same canonical bytes ...
        AtBlock forged = service.getCurrentBlockATs(ctx, Integer.MAX_VALUE, TEST_HEIGHT, TEST_GENERATOR, 0);
        assertEquals(GOOD_BLOCK_ATS_HEX, Convert.toHexString(forged.getBytesForBlock()),
                "V3 forging must reproduce the known-good blockATs (canonical serialization)");

        // Validation path, as seen by a peer node: fresh AT instances loaded from its own DB
        // (re-create them to avoid the forging run's state carry-over).
        AtTestHelper.clearAddedAts();
        AtTestHelper.addHelloWorldATV3();
        AtTestHelper.addEchoATV3();
        AtTestHelper.addTipThanksATV3();

        // ... and the validation path must accept them with the V3 fee schedule.
        AtBlock validated = service.validateATs(ctx, Convert.parseHexString(GOOD_BLOCK_ATS_HEX),
                TEST_HEIGHT, TEST_GENERATOR);
        assertEquals(0, validated.getTotalAmount());
        assertEquals(7400000L, validated.getTotalFees());
    }

    @Test
    public void tamperedMd5_isRejectedWithChecksumError() throws AtException {
        AtTestHelper.clearAddedAts();
        AtTestHelper.addHelloWorldAT();
        AtTestHelper.addEchoAT();
        AtTestHelper.addTipThanksAT();
        ATProcessingContext ctx = AtTestHelper.getTestContext();
        ATServiceImpl service = new ATServiceImpl(ctx.getAtStore(), ctx);

        byte[] tampered = Convert.parseHexString(GOOD_BLOCK_ATS_HEX);
        tampered[8] ^= 0xFF; // corrupt the first byte of AT#1's stored MD5

        try {
            service.validateATs(ctx, tampered, TEST_HEIGHT, TEST_GENERATOR);
            fail("expected AtException for tampered MD5");
        } catch (AtException e) {
            assertNotNull(e.getCause(), "the checksum error must be preserved as the cause");
            assertTrue(e.getCause().getMessage().contains("Calculated md5 and received md5 are not matching"),
                    "expected the MD5-mismatch cause, got: " + e.getCause());
        }
    }

    @Test
    public void duplicateATId_inBlockATs_rejected() throws AtException {
        ATProcessingContext ctx = AtTestHelper.getTestContext();
        ATServiceImpl service = new ATServiceImpl(ctx.getAtStore(), ctx);

        // Build a 2-entry blockATs where the same AT id (1) appears twice.
        byte[] good = Convert.parseHexString(GOOD_BLOCK_ATS_HEX);
        byte[] duplicated = Arrays.copyOf(good, 48);
        System.arraycopy(good, 0, duplicated, 24, 8); // second entry now repeats id=1
        try {
            service.getATsFromBlock(duplicated);
            fail("expected AtException for duplicate AT id");
        } catch (AtException e) {
            assertTrue(e.getMessage().contains("AT included in block multiple times"),
                    "expected duplicate-AT message, got: " + e.getMessage());
        }
    }

    @Test
    public void blockATs_wrongLength_rejected() throws AtException {
        ATProcessingContext ctx = AtTestHelper.getTestContext();
        ATServiceImpl service = new ATServiceImpl(ctx.getAtStore(), ctx);

        byte[] truncated = Arrays.copyOf(Convert.parseHexString(GOOD_BLOCK_ATS_HEX), 71);
        try {
            service.getATsFromBlock(truncated);
            fail("expected AtException for non-multiple-of-24 length");
        } catch (AtException e) {
            assertTrue(e.getMessage().contains("multiple of cost of one AT"),
                    "expected length message, got: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // FALSE-POSITIVE GUARDS — an invalid blockATs payload MUST be rejected, never
    // accepted. Each test drives exactly one validation gate (in the order used by
    // ATServiceImpl.validateATs: unknown-id / balance / freeze / sleep / md5) and
    // asserts the precise rejection reason, so a regression that opens a gate and
    // silently accepts an invalid block fails loudly.
    // -------------------------------------------------------------------------

    /**
     * A block that references an AT id we do not have in the DB must be rejected, not
     * silently accepted. (Guard: unknown-id — the AT must resolve from the DB.)
     */
    @Test
    public void unknownATId_inBlockATs_rejected() {
        AtTestHelper.clearAddedAts(); // no ATs in the DB at all
        ATProcessingContext ctx = AtTestHelper.getTestContext();
        ATServiceImpl service = new ATServiceImpl(ctx.getAtStore(), ctx);

        byte[] block = new byte[24];
        block[0] = 1; // little-endian AT id = 1, which is NOT present in our DB

        try {
            service.validateATs(ctx, block, TEST_HEIGHT, TEST_GENERATOR);
            fail("expected AtException for an AT id that does not exist in the DB");
        } catch (AtException e) {
            assertTrue(e.getMessage().contains("not found in database"),
                    "expected the not-found rejection, got: " + e.getMessage());
        }
    }

    /**
     * An AT whose account balance is below the run threshold must be rejected.
     * (Guard: insufficient-balance gate.)
     */
    @Test
    public void insufficientBalance_rejected() {
        AtTestHelper.clearAddedAts();
        AtTestHelper.addHelloWorldAT(); // AT#1 present in the DB
        ATProcessingContext ctx = AtTestHelper.getTestContext();
        ATServiceImpl service = new ATServiceImpl(ctx.getAtStore(), ctx);

        // Drive the gate: force the AT's account balance to 0 (below the run threshold).
        Account.Balance zeroBalance = mock(Account.Balance.class);
        when(zeroBalance.getBalanceNqt()).thenReturn(0L);
        when(ctx.getAccountService().getAccountBalance(anyLong())).thenReturn(zeroBalance);

        byte[] block = new byte[24];
        block[0] = 1; // AT#1 (exists in the DB)

        try {
            service.validateATs(ctx, block, TEST_HEIGHT, TEST_GENERATOR);
            fail("expected AtException for an AT with insufficient balance");
        } catch (AtException e) {
            assertNotNull(e.getCause(), "the balance error must be preserved as the cause");
            assertTrue(e.getCause().getMessage().contains("AT has insufficient balance to run"),
                    "expected insufficient-balance cause, got: " + e.getCause());
        }
    }

    /**
     * An AT whose balance did not increase by the minimum activation amount must be
     * rejected by the freeze gate. (Guard: freeze-on-same-balance gate.)
     * Forging first sets the AT's stored balance equal to the account balance, so a
     * subsequent validation with an unchanged balance must be frozen.
     */
    @Test
    public void frozenDueToUnchangedBalance_rejected() {
        AtTestHelper.clearAddedAts();
        AtTestHelper.addHelloWorldAT();
        ATProcessingContext ctx = AtTestHelper.getTestContext();
        ATServiceImpl service = new ATServiceImpl(ctx.getAtStore(), ctx);

        // Forging records the AT's balance (gBalance = account balance).
        AtBlock forged = service.getCurrentBlockATs(ctx, Integer.MAX_VALUE, TEST_HEIGHT, TEST_GENERATOR, 0);
        assertNotNull(forged.getBytesForBlock());

        // Re-validating the SAME instance with an unchanged balance must be frozen.
        try {
            service.validateATs(ctx, forged.getBytesForBlock(), TEST_HEIGHT, TEST_GENERATOR);
            fail("expected AtException for a frozen AT (unchanged balance)");
        } catch (AtException e) {
            assertNotNull(e.getCause(), "the freeze error must be preserved as the cause");
            assertTrue(e.getCause().getMessage().contains("AT should be frozen due to unchanged balance"),
                    "expected freeze cause, got: " + e.getCause());
        }
    }

    /**
     * An AT that is not yet due to run again (nextHeight &gt; blockHeight) must be rejected.
     * (Guard: sleep / nextHeight gate — a direct analogue of the 59763 sync stall.)
     * The complementary accept case (nextHeight &le; blockHeight) is covered by the
     * round-trip tests (T1/T3) which validate at the AT's nextHeight.
     */
    @Test
    public void atNotReadyForNextRun_rejected() {
        AtTestHelper.clearAddedAts();
        AtTestHelper.addHelloWorldAT(); // nextHeight = Integer.MAX_VALUE (mock blockchain height)
        ATProcessingContext ctx = AtTestHelper.getTestContext();
        ATServiceImpl service = new ATServiceImpl(ctx.getAtStore(), ctx);

        int belowNextHeight = Integer.MAX_VALUE - 1; // strictly below the AT's nextHeight
        byte[] block = Arrays.copyOf(Convert.parseHexString(GOOD_BLOCK_ATS_HEX), 24); // AT#1 entry

        try {
            service.validateATs(ctx, block, belowNextHeight, TEST_GENERATOR);
            fail("expected AtException for an AT that is not allowed to run again yet");
        } catch (AtException e) {
            assertNotNull(e.getCause(), "the sleep-gate error must be preserved as the cause");
            assertTrue(e.getCause().getMessage().contains("AT not allowed to run again yet"),
                    "expected nextHeight cause, got: " + e.getCause());
        }
    }

    // -------------------------------------------------------------------------
    // FALSE-NEGATIVE GUARDS — a valid blockATs payload MUST be accepted, never
    // rejected. Each test feeds a legitimately valid block and asserts acceptance,
    // so a regression that over-tightens validation and rejects a valid block fails
    // loudly.
    // -------------------------------------------------------------------------

    /**
     * A block with a null AT payload (the common case — most blocks carry no ATs) must
     * be accepted as an empty AT block, not rejected. (Guard: null input.)
     */
    @Test
    public void nullBlockATs_acceptedAsEmptyBlock() throws AtException {
        ATProcessingContext ctx = AtTestHelper.getTestContext();
        ATServiceImpl service = new ATServiceImpl(ctx.getAtStore(), ctx);

        AtBlock atBlock = service.validateATs(ctx, null, TEST_HEIGHT, TEST_GENERATOR);
        assertNotNull(atBlock, "a null blockATs payload must still yield an (empty) AtBlock");
        assertEquals(0, atBlock.getTotalFees());
        assertEquals(0, atBlock.getTotalAmount());
        assertNull(atBlock.getBytesForBlock(), "an empty AT block carries no blockATs bytes");
    }

    /**
     * A block with an empty (zero-length) AT payload must be accepted as an empty AT
     * block, not rejected. (Guard: empty input.)
     */
    @Test
    public void emptyBlockATs_acceptedAsEmptyBlock() throws AtException {
        ATProcessingContext ctx = AtTestHelper.getTestContext();
        ATServiceImpl service = new ATServiceImpl(ctx.getAtStore(), ctx);

        AtBlock atBlock = service.validateATs(ctx, new byte[0], TEST_HEIGHT, TEST_GENERATOR);
        assertNotNull(atBlock, "an empty blockATs payload must still yield an (empty) AtBlock");
        assertEquals(0, atBlock.getTotalFees());
        assertEquals(0, atBlock.getTotalAmount());
    }

    /**
     * A block carrying exactly ONE valid AT must be accepted (the validator must not
     * implicitly require multiple ATs). (Guard: single-AT valid input.)
     */
    @Test
    public void singleValidAT_accepted() throws AtException {
        AtTestHelper.clearAddedAts();
        AtTestHelper.addHelloWorldAT(); // only AT#1
        ATProcessingContext ctx = AtTestHelper.getTestContext();
        ATServiceImpl service = new ATServiceImpl(ctx.getAtStore(), ctx);

        AtBlock forged = service.getCurrentBlockATs(ctx, Integer.MAX_VALUE, TEST_HEIGHT, TEST_GENERATOR, 0);
        assertNotNull(forged.getBytesForBlock());
        assertEquals(24, forged.getBytesForBlock().length, "one AT costs exactly 24 bytes");

        // Validation path, as seen by a peer node: fresh AT instance.
        AtTestHelper.clearAddedAts();
        AtTestHelper.addHelloWorldAT();
        AtBlock validated = service.validateATs(ctx, forged.getBytesForBlock(), TEST_HEIGHT, TEST_GENERATOR);
        assertNotNull(validated);
        assertEquals(forged.getTotalFees(), validated.getTotalFees(),
                "forging and validation must agree on total fees");
    }

    /**
     * The length boundary, pinned on BOTH sides: an exact multiple of the per-AT cost
     * must parse cleanly (accepted), while a payload one byte short must be rejected.
     * Together with T6 this closes the boundary so neither a false positive (accepting
     * a non-multiple) nor a false negative (rejecting a valid multiple) can slip through.
     */
    @Test
    public void blockATs_lengthBoundary_exactMultipleAcceptedNonMultipleRejected() throws AtException {
        ATProcessingContext ctx = AtTestHelper.getTestContext();
        ATServiceImpl service = new ATServiceImpl(ctx.getAtStore(), ctx);

        // Exact multiples (distinct AT ids) must parse to the right number of entries.
        byte[] one = new byte[24];
        one[0] = 1; // AT#1
        assertEquals(1, service.getATsFromBlock(one).size(), "a 24-byte (1 AT) payload must parse to 1 entry");

        byte[] two = new byte[48];
        two[0] = 1;  // AT#1
        two[24] = 2; // AT#2
        assertEquals(2, service.getATsFromBlock(two).size(), "a 48-byte (2 AT) payload must parse to 2 entries");

        // One byte short of the multiple must be rejected.
        for (int badLen : new int[]{23, 47}) {
            try {
                service.getATsFromBlock(new byte[badLen]);
                fail("expected AtException for a " + badLen + "-byte (non-multiple) payload");
            } catch (AtException e) {
                assertTrue(e.getMessage().contains("multiple of cost of one AT"),
                        "expected length message, got: " + e.getMessage());
            }
        }
    }

    @Test
    public void atConstants_resolvesFromContext_whenStaticRegistryNull() throws InvocationTargetException {
        // Regression of the 2026-09-04 NPE: AtConstants must always resolve from the injected
        // context — the JVM-wide static registry that previously backed this path has been removed.
        ATProcessingContext ctx = AtTestHelper.getTestContext();
        assertNotNull(ctx.getAtConstants(), "the test context must carry AtConstants");

        AtApiPlatformImpl platform = new AtApiPlatformImpl(ctx);
        AtConstants resolved = invokePrivateGetAtConstants(platform);
        assertNotNull(resolved, "AtConstants must resolve from the context, not the static registry");
        if (ctx.getAtConstants() != resolved) {
            fail("expected the exact AtConstants instance from the context");
        }
    }

    @Test
    public void legacySingleton_withoutContext_failFastWithISE_notNPE() throws InvocationTargetException {
        AtApiPlatformImpl legacy = new AtApiPlatformImpl(null);
        try {
            invokePrivateGetAtConstants(legacy);
            fail("expected IllegalStateException (fail-fast)");
        } catch (InvocationTargetException e) {
            assertNotNull(e.getCause());
            assertTrue(e.getCause() instanceof IllegalStateException,
                    "expected IllegalStateException, got: " + e.getCause());
            assertFalse(e.getCause() instanceof NullPointerException,
                    "a raw NPE must NOT be the failure mode (regression of the 59763 bug)");
        }
    }

    private static AtConstants invokePrivateGetAtConstants(Object target) throws InvocationTargetException {
        try {
            Method m = AtApiPlatformImpl.class.getDeclaredMethod("getAtConstants");
            m.setAccessible(true);
            return (AtConstants) m.invoke(target);
        } catch (IllegalAccessException | NoSuchMethodException e) {
            throw new IllegalStateException("test infrastructure: getAtConstants() not accessible", e);
        }
    }

    private static AtTransaction sampleTx() {
        byte[] recipient = {(byte) 0x11, (byte) 0x22, (byte) 0x33, (byte) 0x44, 0, 0, 0, 0};
        return new AtTransaction(TransactionType.ColoredCoins.ASSET_TRANSFER,
                new byte[8], recipient, 5L, null);
    }

    @Test
    public void pendingState_isolatedBetweenTwoNodes() {
        ATPendingState nodeA = new ATPendingState();
        ATPendingState nodeB = new ATPendingState();

        nodeA.addPendingFee(1L, 100L, 10, 999L);
        nodeA.addPendingTransaction(sampleTx(), 10, 999L);
        nodeA.addMapUpdates(of(new AT.AtMapEntry(1L, 7L, 8L, 9L)), 10, 999L);

        // Node B must observe NONE of node A's state (multi-node isolation invariant).
        assertNull(nodeB.getPendingFees(10, 999L));
        assertNull(nodeB.getPendingTransactions(10, 999L));
        assertTrue(nodeB.getAndClearMapUpdates(10, 999L).isEmpty());

        // Node A must still see its own state.
        assertEquals(1, nodeA.getPendingFees(10, 999L).size());
        assertEquals(1, nodeA.getPendingTransactions(10, 999L).size());
    }

    @Test
    public void pendingState_clearAndRemove_semantics() {
        ATPendingState ps = new ATPendingState();
        ps.addPendingFee(1L, 10L, 5, 42L);
        ps.addPendingTransaction(sampleTx(), 5, 42L);
        ps.addMapUpdates(of(new AT.AtMapEntry(1L, 1L, 2L, 3L)), 5, 42L);

        // removeFeesAndTransactions: fees + txs go, map updates survive (applied via saveMapUpdates).
        ps.removeFeesAndTransactions(5, 42L);
        assertNull(ps.getPendingFees(5, 42L));
        assertNull(ps.getPendingTransactions(5, 42L));
        assertEquals(1, ps.getAndClearMapUpdates(5, 42L).size());
        assertTrue(ps.getAndClearMapUpdates(5, 42L).isEmpty(), "map updates must be cleared after retrieval");

        // clearPending (rollback path): everything for the key is dropped.
        ps.addPendingFee(2L, 20L, 7, 42L);
        ps.addMapUpdates(of(new AT.AtMapEntry(2L, 4L, 5L, 6L)), 7, 42L);
        ps.clearPending(7, 42L);
        assertNull(ps.getPendingFees(7, 42L));
        assertTrue(ps.getAndClearMapUpdates(7, 42L).isEmpty());
    }

    @Test
    public void pendingState_findPendingTransaction_detectsConflict() {
        ATPendingState ps = new ATPendingState();
        byte[] recipient = {(byte) 0x11, (byte) 0x22, (byte) 0x33, (byte) 0x44, 0, 0, 0, 0};
        ps.addPendingTransaction(new AtTransaction(TransactionType.ColoredCoins.ASSET_TRANSFER,
                new byte[8], recipient, 5L, null), 9, 7L);

        assertTrue(ps.findPendingTransaction(recipient, 9, 7L),
                "same (height, generator, recipient) must be flagged as a conflict");
        assertFalse(ps.findPendingTransaction(recipient, 9, 8L),
                "a different generator must not conflict");
    }
    @Test
    public void atStateBytes_roundTripThroughCompression() {
        byte[] original = new byte[2048];
        java.util.Random random = new java.util.Random(42);
        random.nextBytes(original);

        byte[] packed = AT.compressState(original);
        assertNotNull(packed);
        byte[] restored = AT.decompressState(packed);
        assertNotNull(restored, "decompressed state must not be null");
        assertArrayEquals(original, restored, "at_state bytes must round-trip losslessly (DB consistency)");

        assertNull(AT.compressState(null));
        assertNull(AT.compressState(new byte[0]));
        assertNull(AT.decompressState(new byte[0]));
    }

    @Test
    public void feeInvariant_pendingFeesSum_equalsTotalFees() throws AtException {
        AtTestHelper.clearAddedAts();
        AtTestHelper.addHelloWorldAT();
        AtTestHelper.addEchoAT();
        AtTestHelper.addTipThanksAT();
        ATProcessingContext ctx = AtTestHelper.getTestContext();
        ATServiceImpl service = new ATServiceImpl(ctx.getAtStore(), ctx);

        AtBlock atBlock = service.validateATs(ctx, Convert.parseHexString(GOOD_BLOCK_ATS_HEX),
                TEST_HEIGHT, TEST_GENERATOR);

        // The DB-consistency check (totalMined vs. account+escrow balances) ultimately depends
        // on the fee burn being accounted exactly: totalFees must equal the sum of the
        // pending fees that HandleATBlockTransactionsListener will later subtract.
        LinkedHashMap<Long, Long> pendingFees = ctx.getPendingState().getPendingFees(TEST_HEIGHT, TEST_GENERATOR);
        assertNotNull(pendingFees, "pending fees must be recorded for the block/generator");
        assertEquals(3, pendingFees.size());
        long sum = 0L;
        for (Long fee : pendingFees.values()) {
            sum += fee;
        }
        assertEquals(atBlock.getTotalFees(), sum,
                "sum(pendingFees) must equal atBlock.getTotalFees()");
    }

    @Test
    public void handleATBlockTransactionsListener_appliesFeeExactlyOnce() {
        long atId = 987654321L;
        long fee = 12345L;
        int height = 100;
        long generator = 555L;

        ATPendingState pendingState = new ATPendingState();
        pendingState.addPendingFee(atId, fee, height, generator);

        Account atAccount = mock(Account.class);
        AccountService accountService = mock(AccountService.class);
        when(accountService.getAccount(atId)).thenReturn(atAccount);

        ATProcessingContext ctx = mock(ATProcessingContext.class);
        when(ctx.getPendingState()).thenReturn(pendingState);
        when(ctx.getAccountService()).thenReturn(accountService);

        Block block = mock(Block.class);
        when(block.getHeight()).thenReturn(height);
        when(block.getGeneratorId()).thenReturn(generator);

        AT.HandleATBlockTransactionsListener listener =
                new AT.HandleATBlockTransactionsListener(ctx, mock(TransactionDb.class));

        listener.notify(block);
        verify(accountService, times(1)).addToBalanceAndUnconfirmedBalanceNQT(atAccount, -fee);
        assertNull(pendingState.getPendingFees(height, generator), "the pending fee must be consumed (exactly-once)");

        // A second apply (e.g. after a re-sync/rollback of the same height) must be a no-op.
        listener.notify(block);
        verify(accountService, times(1)).addToBalanceAndUnconfirmedBalanceNQT(any(Account.class), anyLong());
    }

    @Test
    public void atService_usesInjectedContext_notAFreshOne() {
        // Root cause R1 guard (see debug/AT_59763_REGRESSION_DEBUG.md): Signum wires the atService
        // and the AT-fee consumer (HandleATBlockTransactionsListener) to the SAME ATProcessingContext.
        // The 2-arg constructor MUST use the injected context verbatim so that the producer
        // (validateATs -> AT.addPendingFee) and the consumer (notify -> getPendingFees) hit the same
        // ATPendingState. The legacy 11-arg convenience constructor built a NEW context internally,
        // which is exactly the bug that created the +259.6 SIGNA phantom.
        ATStore atStore = mock(ATStore.class);
        ATProcessingContext ctx = mock(ATProcessingContext.class);
        ATServiceImpl service = new ATServiceImpl(atStore, ctx);
        assertSame(ctx, service.getProcessingContext(),
                "the atService must operate on the injected context (shared ATPendingState)");
    }

    @Test
    public void feeDebit_appliedWhenatServiceAndNotifyShareContext() {
        // Root cause R1 (fixed): when the atService (producer) and the notify listener (consumer)
        // share ONE ATProcessingContext, the AT fee recorded by validateATs IS debited by notify.
        // With the old (buggy) wiring they were two different contexts, so notify saw no fee and the
        // debit was skipped while the fee was still credited to the generator -> SIGNA created.
        int height = 400;
        long generator = 111L;
        long atId = 222L;
        long fee = 777L;

        ATPendingState sharedState = new ATPendingState();
        AccountService accountService = mock(AccountService.class);
        Account atAccount = mock(Account.class);
        when(accountService.getAccount(atId)).thenReturn(atAccount);

        ATProcessingContext sharedCtx = mock(ATProcessingContext.class);
        when(sharedCtx.getPendingState()).thenReturn(sharedState);
        when(sharedCtx.getAccountService()).thenReturn(accountService);

        // Producer: the atService is built on the SAME context (the fixed Signum wiring).
        ATServiceImpl atService = new ATServiceImpl(mock(ATStore.class), sharedCtx);
        assertSame(sharedCtx, atService.getProcessingContext());
        atService.getProcessingContext().getPendingState().addPendingFee(atId, fee, height, generator);

        // Consumer: the notify listener uses the SAME context.
        Block block = mock(Block.class);
        when(block.getHeight()).thenReturn(height);
        when(block.getGeneratorId()).thenReturn(generator);
        AT.HandleATBlockTransactionsListener listener =
                new AT.HandleATBlockTransactionsListener(sharedCtx, mock(TransactionDb.class));
        listener.notify(block);

        verify(accountService, times(1)).addToBalanceAndUnconfirmedBalanceNQT(atAccount, -fee);
        assertNull(sharedState.getPendingFees(height, generator), "the fee must be consumed exactly once");
    }

    @Test
    public void atState_persistedWithConsistentHeights() throws AtException {
        AtTestHelper.clearAddedAts();
        AtTestHelper.addHelloWorldAT();
        AtTestHelper.addEchoAT();
        AtTestHelper.addTipThanksAT();
        ATProcessingContext ctx = AtTestHelper.getTestContext();
        ATServiceImpl service = new ATServiceImpl(ctx.getAtStore(), ctx);

        // validateATs must persist an at_state row per AT (H2 regression: the row must land in the DB).
        service.validateATs(ctx, Convert.parseHexString(GOOD_BLOCK_ATS_HEX), TEST_HEIGHT, TEST_GENERATOR);

        // noinspection unchecked
        VersionedEntityTable<AT.ATState> atStateTable = ctx.getAtStore().getAtStateTable();
        ArgumentCaptor<AT.ATState> captor = ArgumentCaptor.forClass(AT.ATState.class);
        // 3 inserts from AT.addAT (setup) + 3 from the validateATs run above.
        verify(atStateTable, atLeast(6)).insert(captor.capture());

        for (AT.ATState state : captor.getAllValues()) {
            assertTrue(state.getATId() == 1L || state.getATId() == 2L || state.getATId() == 3L,
                    "at_state rows must belong to the three test ATs");
            assertNotNull(state.getState(), "at_state bytes must not be null (DB consistency)");
            assertTrue(state.getNextHeight() >= state.getPrevHeight(), "next_height must not move backwards");
        }
    }
}