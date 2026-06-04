package application.module.brs.db.store;

import application.module.brs.AssetTransfer;
import application.module.brs.db.SignumKey;
import application.module.brs.db.sql.EntitySqlTable;

import java.util.Collection;

public interface AssetTransferStore {
    SignumKey.LinkKeyFactory<AssetTransfer> getTransferDbKeyFactory();

    EntitySqlTable<AssetTransfer> getAssetTransferTable();

    Collection<AssetTransfer> getAssetTransfers(long assetId, int from, int to);

    Collection<AssetTransfer> getAccountAssetTransfers(long accountId, int from, int to);

    Collection<AssetTransfer> getAccountAssetTransfers(long accountId, long assetId, int from, int to);

    int getTransferCount(long assetId);
}
