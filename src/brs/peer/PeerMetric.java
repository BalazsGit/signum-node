package brs.peer;

public class PeerMetric {
    public enum Type {
        BLOCK_RX, BLOCK_TX, OTHER
    }

    private final String peerAddress;
    private final long latency;
    private final int blocksReceived;
    private final long timestamp;
    private final Type type;

    public PeerMetric(String peerAddress, long latency, int blocksReceived, Type type) {
        this.peerAddress = peerAddress;
        this.latency = latency;
        this.blocksReceived = blocksReceived;
        this.timestamp = System.currentTimeMillis();
        this.type = type;
    }

    public String getPeerAddress() {
        return peerAddress;
    }

    public long getLatency() {
        return latency;
    }

    public int getBlocksReceived() {
        return blocksReceived;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public Type getType() {
        return type;
    }
}