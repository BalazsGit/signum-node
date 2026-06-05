package application.module.node.db.store;

import application.module.node.Asset;
import application.module.node.db.SignumKey;
import application.module.node.db.sql.EntitySqlTable;

import java.util.Collection;

public interface AssetStore {
    SignumKey.LongKeyFactory<Asset> getAssetDbKeyFactory();

    EntitySqlTable<Asset> getAssetTable();

    Collection<Asset> getAssetsIssuedBy(long accountId, int from, int to);

    Asset getAsset(long assetId);

    Collection<Asset> getAssetsByName(String name, int from, int to);
}
