package brs.gui;

import java.util.LinkedList;

public class MovingAverage {
    private LinkedList<Double> rawDataList = new LinkedList<>();
    private LinkedList<Double> avgDataList = new LinkedList<>();
    private double sum = 0.0;
    private double compensation = 0.0; // Kahan summation compensation
    private double max = 0.0;
    private double avg = 0.0;
    private int capacity;
    private int windowSize;
    private short index = 0;

    private final Object lock = new Object();

    public MovingAverage(int capacity, int windowSize) {
        this.capacity = capacity;
        this.windowSize = windowSize;
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
        for (int i = rawDataList.size() - 1; i >= Math.max(rawDataList.size() - windowSize, 0); i--) {
            kahanAdd(rawDataList.get(i));
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
            rawDataList.addLast(value);
            kahanAdd(value);

            if (rawDataList.size() > windowSize) {
                double outFromWindow = rawDataList.get(rawDataList.size() - windowSize - 1);
                kahanAdd(-outFromWindow);
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

            avg = sum / Math.min(rawDataList.size(), windowSize);
            addToAvg(avg);
            index++;
        }
    }

    private void addToAvg(double value) {
        avgDataList.addLast(value);
        max = Math.max(max, value);
        if (avgDataList.size() > capacity) {
            double removed = avgDataList.removeFirst();
            if (removed >= max) {
                recalculateMax();
            }
        }
    }

    private void recalculateMax() {
        if (avgDataList.isEmpty()) {
            max = 0.0;
        } else {
            // This is O(N) but only called when max is removed or window shrinks.
            max = avgDataList.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        }
    }

    public double getAverage() {
        synchronized (lock) {
            return avgDataList.isEmpty() ? 0.0 : Math.max(avg, 0.0);
        }
    }

    public int size() {
        synchronized (lock) {
            return rawDataList.size();
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return rawDataList.isEmpty();
        }
    }

    public double getLast() {
        synchronized (lock) {
            return rawDataList.isEmpty() ? 0.0 : rawDataList.getLast();
        }
    }

    public double get(int index) {
        synchronized (lock) {
            return index < rawDataList.size() ? rawDataList.get(index) : 0.0;
        }
    }

    public double getMax() {
        synchronized (lock) {
            return Math.max(max, 0.0);
        }
    }

    public double getSum() {
        synchronized (lock) {
            return Math.max(sum, 0.0);
        }
    }

    public void clear() {
        synchronized (lock) {
            rawDataList.clear();
            avgDataList.clear();
            sum = 0.0;
            compensation = 0.0;
            max = 0.0;
            avg = 0.0;
        }
    }
}