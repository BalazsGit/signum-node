package application.module.node.db.store;

import application.module.node.Trade;
import application.module.node.db.SignumKey;
import application.module.node.db.sql.EntitySqlTable;

import java.util.Collection;

public interface TradeStore {
    Collection<Trade> getAllTrades(int from, int to);

    Collection<Trade> getAssetTrades(long assetId, int from, int to);

    Collection<Trade> getAccountTrades(long accountId, int from, int to);

    Collection<Trade> getAccountAssetTrades(long accountId, long assetId, int from, int to);

    Collection<Trade> getOrderTrades(long orderId);

    long getTradeVolume(long assetId, int heightStart, int heightEnd);

    long getHighPrice(long assetId, int heightStart, int heightEnd);

    long getLowPrice(long assetId, int heightStart, int heightEnd);

    long getOpenPrice(long assetId, int heightStart, int heightEnd);

    long getClosePrice(long assetId, int heightStart, int heightEnd);

    int getTradeCount(long assetId);

    SignumKey.LinkKeyFactory<Trade> getTradeDbKeyFactory();

    EntitySqlTable<Trade> getTradeTable();
}
