package application.module.node.at;

import application.module.node.Constants;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.fluxcapacitor.FluxValues;

import java.util.HashMap;

/**
 * Configuration constants for Automated Transaction (AT) processing.
 * <p>
 * Provides version-specific fee schedules, step limits, memory page sizes,
 * and timing parameters used by the AT virtual machine. Values are derived
 * from {@link FluxCapacitor} feature flags to support epoch-based evolution
 * of AT behavior without code changes.
 * </p>
 *
 * @since 4.0
 */
/**
 * Configuration constants for Automated Transaction (AT) processing.
 * <p>
 * Provides version-specific fee schedules, step limits, memory page sizes,
 * and timing parameters used by the AT virtual machine. Values are derived
 * from {@link FluxCapacitor} feature flags to support epoch-based evolution
 * of AT behavior without code changes.
 * </p>
 *
 * @since 4.0
 */
public final class AtConstants {

    /**
     * Size of AT identifier in bytes.
     */
    public static final int AT_ID_SIZE = 8;

    /**
     * Memory page size in bytes for AT virtual machine.
     * <p>
     * This value is constant across all AT versions (1, 2, 3) and is used
     * for code, data, user stack, and call stack memory allocation.
     * </p>
     */
    public static final int PAGE_SIZE = 256;

    private final HashMap<Short, Long> minFee = new HashMap<>();
    private final HashMap<Short, Long> stepFee = new HashMap<>();
    private final HashMap<Short, Long> maxSteps = new HashMap<>();
    private final HashMap<Short, Long> apiStepMultiplier = new HashMap<>();
    private final HashMap<Short, Long> costPerPage = new HashMap<>();
    private final HashMap<Short, Long> maxWaitForNumOfBlocks = new HashMap<>();
    private final HashMap<Short, Long> maxSleepBetweenBlocks = new HashMap<>();
    private final HashMap<Short, Long> pageSize = new HashMap<>();
    private final HashMap<Short, Long> maxMachineCodePages = new HashMap<>();
    private final HashMap<Short, Long> maxMachineDataPages = new HashMap<>();
    private final HashMap<Short, Long> maxMachineUserStackPages = new HashMap<>();
    private final HashMap<Short, Long> maxMachineCallStackPages = new HashMap<>();
    private final HashMap<Short, Long> blocksForRandom = new HashMap<>();
    private final HashMap<Short, Long> averageBlockMinutes = new HashMap<>();

    private final FluxCapacitor fluxCapacitor;

    /**
     * Creates AtConstants with the specified FluxCapacitor for feature flag queries.
     *
     * @param fluxCapacitor feature flag / epoch tracking
     */
    public AtConstants(FluxCapacitor fluxCapacitor) {
        this.fluxCapacitor = fluxCapacitor;
        initConstants();
    }

    private void initConstants() {
        // constants for AT version 1
        minFee.put((short) 1, 1000L);
        stepFee.put((short) 1, 100000000L / 10L);
        maxSteps.put((short) 1, 2000L);
        apiStepMultiplier.put((short) 1, 10L);

        costPerPage.put((short) 1, 100000000L);

        maxWaitForNumOfBlocks.put((short) 1, 31536000L);
        maxSleepBetweenBlocks.put((short) 1, 31536000L);

        pageSize.put((short) 1, 256L);

        maxMachineCodePages.put((short) 1, 10L);
        maxMachineDataPages.put((short) 1, 10L);
        maxMachineUserStackPages.put((short) 1, 10L);
        maxMachineCallStackPages.put((short) 1, 10L);

        blocksForRandom.put((short) 1, 15L); // for testing 2 -> normally 1440
        averageBlockMinutes.put((short) 1, 4L);
        // end of AT version 1

        // constants for AT version 2
        minFee.put((short) 2, 1000L);
        stepFee.put((short) 2, Constants.FEE_QUANT_SIP3 / 10L);
        maxSteps.put((short) 2, 100_000L);
        apiStepMultiplier.put((short) 2, 10L);

        costPerPage.put((short) 2, Constants.FEE_QUANT_SIP3 * 10);

        maxWaitForNumOfBlocks.put((short) 2, 31536000L);
        maxSleepBetweenBlocks.put((short) 2, 31536000L);

        pageSize.put((short) 2, 256L);

        maxMachineCodePages.put((short) 2, 20L);
        maxMachineDataPages.put((short) 2, 10L);
        maxMachineUserStackPages.put((short) 2, 10L);
        maxMachineCallStackPages.put((short) 2, 10L);

        blocksForRandom.put((short) 2, 15L); // for testing 2 -> normally 1440
        averageBlockMinutes.put((short) 2, 4L);
        // end of AT version 2

        // constants for AT version 3
        minFee.put((short) 3, 1000L);
        stepFee.put((short) 3, Constants.FEE_QUANT_SIP34 / 10L);
        maxSteps.put((short) 3, 100_000L);
        apiStepMultiplier.put((short) 3, 10L);

        costPerPage.put((short) 3, Constants.FEE_QUANT_SIP34 * 10);

        maxWaitForNumOfBlocks.put((short) 3, 31536000L);
        maxSleepBetweenBlocks.put((short) 3, 31536000L);

        pageSize.put((short) 3, 256L);

        maxMachineCodePages.put((short) 3, 40L);
        maxMachineDataPages.put((short) 3, 10L);
        maxMachineUserStackPages.put((short) 3, 10L);
        maxMachineCallStackPages.put((short) 3, 10L);

        blocksForRandom.put((short) 3, 15L); // for testing 2 -> normally 1440
        averageBlockMinutes.put((short) 3, 4L);
        // end of AT version 3
    }

    public short atVersion(int blockHeight) {
        return fluxCapacitor.getValue(FluxValues.AT_VERSION, blockHeight);
    }

    public long stepFee(short version) {
        return stepFee.get(version);
    }

    public long maxSteps(int height) {
        if (fluxCapacitor.getValue(FluxValues.SIGNUM, height)) {
            return 1_000_000L;
        }
        return maxSteps.get(atVersion(height));
    }

    public long apiStepMultiplier(short version) {
        return apiStepMultiplier.get(version);
    }

    public long costPerPage(int height) {
        return costPerPage.get(atVersion(height));
    }

    public long getMaxWaitForNumOfBlocks(int height) {
        return maxWaitForNumOfBlocks.get(atVersion(height));
    }

    public long maxSleepBetweenBlocks(int height) {
        return maxSleepBetweenBlocks.get(atVersion(height));
    }

    public long pageSize(short version) {
        return pageSize.get(version);
    }

    public long maxMachineCodePages(short version) {
        return maxMachineCodePages.get(version);
    }

    public long maxMachineDataPages(short version) {
        return maxMachineDataPages.get(atVersion(version));
    }

    public long maxMachineUserStackPages(short version) {
        return maxMachineUserStackPages.get(atVersion(version));
    }

    public long maxMachineCallStackPages(short version) {
        return maxMachineCallStackPages.get(atVersion(version));
    }

    public long blocksForRandom(int height) {
        return blocksForRandom.get(atVersion(height));
    }

    public long averageBlockMinutes(int height) {
        return averageBlockMinutes.get(atVersion(height));
    }
}