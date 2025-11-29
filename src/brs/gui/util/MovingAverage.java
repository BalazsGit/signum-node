package brs.gui.util;

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

    public MovingAverage(int capacity, int windowSize) {
        this.capacity = capacity;
        this.windowSize = windowSize;
        this.rawDataList = new double[capacity];
        this.avgDataList = new double[capacity];
    }

    public void setWindowSize(int newWindowSize) {
        synchronized (lock) {
            if (newWindowSize <= 0 || newWindowSize > capacity) {
                throw new IllegalArgumentException("Window size must be between 1 and capacity.");
            }

            this.windowSize = newWindowSize;
            recalculateSum();
        }
    }

    private void recalculateSum() {
        sum = 0.0;
        compensation = 0.0;
        avg = 0.0;
        for (int i = 0; i < Math.min(rawDataCount, windowSize); i++) {
            int index = (rawDataHead - 1 - i + capacity) % capacity;
            kahanAdd(rawDataList[index]);
        }
    }

    private void kahanAdd(double input) {
        double y = input - compensation;
        double t = sum + y;
        compensation = (t - sum) - y;
        sum = t;
    }

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

    public double getAverage() {
        synchronized (lock) {
            return avgDataCount == 0 ? 0.0 : Math.max(avg, 0.0);
        }
    }

    public int size() {
        synchronized (lock) {
            return rawDataCount;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return rawDataCount == 0;
        }
    }

    public double getLast() {
        synchronized (lock) {
            if (rawDataCount == 0) {
                return 0.0;
            }
            return rawDataList[(rawDataHead - 1 + capacity) % capacity];
        }
    }

    public double get(int index) {
        synchronized (lock) {
            if (index < 0 || index >= rawDataCount) {
                return 0.0;
            }
            int internalIndex = (rawDataHead - rawDataCount + index + capacity) % capacity;
            return rawDataList[internalIndex];
        }
    }

    public double getMax() {
        synchronized (lock) {
            return Math.max(max, 0.0);
        }
    }

    public double getMin() {
        synchronized (lock) {
            return avgDataCount == 0 ? 0.0 : Math.max(min, 0.0);
        }
    }

    public double getSum() {
        synchronized (lock) {
            return Math.max(sum, 0.0);
        }
    }

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