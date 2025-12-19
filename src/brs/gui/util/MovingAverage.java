package brs.gui.util;

/**
 * A thread-safe utility class for calculating the moving average of a stream of
 * double values.
 * <p>
 * This class uses circular arrays to store a fixed number of raw data points
 * and their
 * corresponding moving averages. It employs the Kahan summation algorithm to
 * minimize
 * floating-point precision errors when calculating the sum over the sliding
 * window.
 * <p>
 * The class also tracks the minimum and maximum moving average values recorded.
 * All public methods are synchronized.
 */
public class MovingAverage {
    private final double[] rawDataList;
    private int rawDataHead = 0;
    private int rawDataCount = 0;
    private final double[] avgDataList;
    private int avgDataHead = 0;
    private int avgDataCount = 0;
    private double sum = 0.0;
    private double compensation = 0.0; // Kahan summation compensation
    private double max = 0.0;
    private double min = Double.MAX_VALUE;
    private double avg = 0.0;
    private int capacity;
    private int windowSize;
    private short index = 0;

    private final Object lock = new Object();

    /**
     * Constructs a new MovingAverage instance.
     *
     * @param capacity   The maximum number of data points and averages to store.
     *                   This defines the size of the internal buffers.
     * @param windowSize The size of the sliding window used to calculate the moving
     *                   average.
     */
    public MovingAverage(int capacity, int windowSize) {
        this.capacity = capacity;
        this.windowSize = windowSize;
        this.rawDataList = new double[capacity];
        this.avgDataList = new double[capacity];
    }

    /**
     * Sets a new size for the moving average window.
     *
     * @param newWindowSize The new window size. Must be greater than 0 and not
     *                      exceed the capacity.
     * @throws IllegalArgumentException if the new window size is invalid.
     */
    public void setWindowSize(int newWindowSize) {
        synchronized (lock) {
            if (newWindowSize <= 0 || newWindowSize > capacity) {
                throw new IllegalArgumentException("Window size must be between 1 and capacity.");
            }

            this.windowSize = newWindowSize;
            recalculateSum();
        }
    }

    /**
     * Recalculates the sum of the values within the current window from scratch.
     * This is used to correct potential floating-point drift or after the window
     * size changes.
     */
    private void recalculateSum() {
        sum = 0.0;
        compensation = 0.0;
        avg = 0.0;
        for (int i = 0; i < Math.min(rawDataCount, windowSize); i++) {
            int index = (rawDataHead - 1 - i + capacity) % capacity;
            kahanAdd(rawDataList[index]);
        }
    }

    /**
     * Adds a value to the sum using the Kahan summation algorithm to reduce
     * numerical error.
     *
     * @param input The double value to add to the sum.
     */
    private void kahanAdd(double input) {
        double y = input - compensation;
        double t = sum + y;
        compensation = (t - sum) - y;
        sum = t;
    }

    /**
     * Adds a new data point to the series, updates the moving average, and stores
     * the new average.
     *
     * @param value The new data point to add.
     */
    public void add(double value) {
        synchronized (lock) {
            rawDataList[rawDataHead] = value;
            rawDataHead = (rawDataHead + 1) % capacity;
            if (rawDataCount < capacity) {
                rawDataCount++;
            }

            kahanAdd(value);
            if (rawDataCount > windowSize) {
                int outOfWindowIndex = (rawDataHead - windowSize - 1 + capacity) % capacity;
                kahanAdd(-rawDataList[outOfWindowIndex]);
            }

            if (sum < 0.0) {
                sum = 0.0;
                compensation = 0.0;
            }

            if (index >= 10000) {
                // Recalculate sum with Kahan to prevent precision drift
                recalculateSum();
                index = 0;
            }

            avg = sum / Math.min(rawDataCount, windowSize);
            addToAvg(avg);
            index++;
        }
    }

    /**
     * Adds a calculated average value to the internal storage of averages.
     * It also updates the overall min and max average values.
     *
     * @param value The new average value to store.
     */
    private void addToAvg(double value) {
        double removed = 0.0;
        boolean wasFull = avgDataCount == capacity;
        if (wasFull) {
            removed = avgDataList[avgDataHead];
        }

        avgDataList[avgDataHead] = value;
        avgDataHead = (avgDataHead + 1) % capacity;
        if (avgDataCount < capacity) {
            avgDataCount++;
        }

        max = Math.max(max, value);
        min = Math.min(min, value);
        if (wasFull) {
            if (removed >= max) {
                recalculateMax();
            }
            if (removed <= min) {
                recalculateMin();
            }
        }
    }

    /**
     * Recalculates the maximum value among the stored averages.
     * This is an O(N) operation and is only called when the previous maximum value
     * is removed from the buffer.
     * This method must be called from within a synchronized block.
     */
    private void recalculateMax() {
        // This method should only be called from within a synchronized(lock) block.
        if (avgDataCount == 0) {
            max = 0.0;
        } else {
            // This is O(N) but only called when max is removed or window shrinks.
            double currentMax = 0.0;
            for (int i = 0; i < avgDataCount; i++) {
                double value = avgDataList[i];
                if (value > currentMax)
                    currentMax = value;
            }
            max = currentMax;
        }
    }

    /**
     * Recalculates the minimum value among the stored averages.
     * This is an O(N) operation and is only called when the previous minimum value
     * is removed from the buffer.
     * This method must be called from within a synchronized block.
     */
    private void recalculateMin() {
        // This method should only be called from within a synchronized(lock) block.
        if (avgDataCount == 0) {
            min = 0.0;
        } else {
            // This is O(N) but only called when min is removed or window shrinks.
            double currentMin = Double.MAX_VALUE;
            for (int i = 0; i < avgDataCount; i++) {
                double value = avgDataList[i];
                if (value < currentMin)
                    currentMin = value;
            }
            min = currentMin;
        }
    }

    /**
     * Gets the current moving average.
     *
     * @return The current moving average, or 0.0 if no data is available. Returns a
     *         non-negative value.
     */
    public double getAverage() {
        synchronized (lock) {
            return avgDataCount == 0 ? 0.0 : Math.max(avg, 0.0);
        }
    }

    /**
     * Gets the number of raw data points currently stored.
     *
     * @return The number of data points.
     */
    public int size() {
        synchronized (lock) {
            return rawDataCount;
        }
    }

    /**
     * Checks if the data series is empty.
     *
     * @return {@code true} if no data points have been added, {@code false}
     *         otherwise.
     */
    public boolean isEmpty() {
        synchronized (lock) {
            return rawDataCount == 0;
        }
    }

    /**
     * Gets the most recently added raw data point.
     *
     * @return The last raw value added, or 0.0 if empty.
     */
    public double getLast() {
        synchronized (lock) {
            if (rawDataCount == 0) {
                return 0.0;
            }
            return rawDataList[(rawDataHead - 1 + capacity) % capacity];
        }
    }

    /**
     * Gets the raw data point at a specific logical index. Index 0 is the oldest
     * element.
     *
     * @param index The logical index of the data point to retrieve.
     * @return The raw data point at the specified index, or 0.0 if the index is out
     *         of bounds.
     */
    public double get(int index) {
        synchronized (lock) {
            if (index < 0 || index >= rawDataCount) {
                return 0.0;
            }
            int internalIndex = (rawDataHead - rawDataCount + index + capacity) % capacity;
            return rawDataList[internalIndex];
        }
    }

    /**
     * Gets the maximum moving average value recorded so far.
     *
     * @return The maximum average, or 0.0 if no data is available. Returns a
     *         non-negative value.
     */
    public double getMax() {
        synchronized (lock) {
            return Math.max(max, 0.0);
        }
    }

    /**
     * Gets the minimum moving average value recorded so far.
     *
     * @return The minimum average, or 0.0 if no data is available. Returns a
     *         non-negative value.
     */
    public double getMin() {
        synchronized (lock) {
            return avgDataCount == 0 ? 0.0 : Math.max(min, 0.0);
        }
    }

    /**
     * Gets the current sum of values in the sliding window.
     *
     * @return The current sum, or 0.0 if no data is available. Returns a
     *         non-negative value.
     */
    public double getSum() {
        synchronized (lock) {
            return Math.max(sum, 0.0);
        }
    }

    /**
     * Clears all data and resets the state of the MovingAverage instance.
     * All stored data points, averages, and calculated values (sum, min, max) are
     * reset
     * to their initial states.
     */
    public void clear() {
        synchronized (lock) {
            rawDataHead = 0;
            rawDataCount = 0;
            avgDataHead = 0;
            avgDataCount = 0;
            sum = 0.0;
            compensation = 0.0;
            max = 0.0;
            min = Double.MAX_VALUE;
            avg = 0.0;
        }
    }
}