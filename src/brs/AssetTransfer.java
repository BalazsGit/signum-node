package brs;

import brs.db.SignumKey;

public class AssetTransfer {

  public enum Event {
    ASSET_TRANSFER
  }

  private final long id;
  private final SignumKey dbKey;
  private final long assetId;
  private final int height;
  private final long senderId;
  private final long recipientId;
  private final long quantityQNT;
  private final int timestamp;

  public AssetTransfer(SignumKey dbKey, Transaction transaction, long assetId, long quantityQNT) {
    this.dbKey = dbKey;
    this.id = transaction.getId();
    this.height = transaction.getHeight();
    this.assetId = assetId;
    this.senderId = transaction.getSenderId();
    this.recipientId = transaction.getRecipientId();
    this.quantityQNT = quantityQNT;
    this.timestamp = transaction.getBlockTimestamp();
  }

  protected AssetTransfer(long id, SignumKey dbKey, long assetId, int height, long senderId, long recipientId, long quantityQNT, int timestamp) {
    this.id = id;
    this.dbKey = dbKey;
    this.assetId = assetId;
    this.height = height;
    this.senderId = senderId;
    this.recipientId = recipientId;
    this.quantityQNT = quantityQNT;
    this.timestamp = timestamp;
  }

  public SignumKey getDbKey(){
    return dbKey;
  }

  public long getId() {
    return id;
  }

  public long getAssetId() {
    return assetId;
  }

  public long getSenderId() {
    return senderId;
  }

  public long getRecipientId() {
    return recipientId;
  }

  public long getQuantityQNT() {
    return quantityQNT;
  }

  public int getTimestamp() {
    return timestamp;
  }

  public int getHeight() {
    return height;
  }

}
