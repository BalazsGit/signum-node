package application.module.node.assetexchange;

import application.module.node.Account.AccountAsset;
import application.module.node.*;
import application.module.node.db.SignumKey;
import application.module.node.db.sql.EntitySqlTable;
import application.module.node.db.store.AssetStore;
import application.module.node.util.CollectionWithIndex;

import java.util.ArrayList;
import java.util.Collection;

class AssetServiceImpl {

    private final Blockchain blockchain;
    private final AssetStore assetStore;
    private final AssetAccountServiceImpl assetAccountService;
    private final TradeServiceImpl tradeService;
    private final AssetTransferServiceImpl assetTransferService;

    private final EntitySqlTable<Asset> assetTable;

    private final SignumKey.LongKeyFactory<Asset> assetDbKeyFactory;

    public AssetServiceImpl(Blockchain blockchain, AssetAccountServiceImpl assetAccountService,
            TradeServiceImpl tradeService, AssetStore assetStore, AssetTransferServiceImpl assetTransferService) {
        this.blockchain = blockchain;
        this.assetAccountService = assetAccountService;
        this.tradeService = tradeService;
        this.assetStore = assetStore;
        this.assetTable = assetStore.getAssetTable();
        this.assetDbKeyFactory = assetStore.getAssetDbKeyFactory();
        this.assetTransferService = assetTransferService;
    }

    public Asset getAsset(long id) {
        Asset asset = assetTable.get(assetDbKeyFactory.newKey(id));
        if (asset != null) {
            asset.updateCurrentOwnerAccount(this.blockchain);
        }
        return asset;
    }

    public Collection<AccountAsset> getAccounts(Asset asset, boolean filterIgnored, long minimumQuantity,
            boolean unconfirmed, int from, int to) {
        return assetAccountService.getAssetAccounts(asset, filterIgnored, minimumQuantity, unconfirmed, from, to);
    }

    public Collection<Trade> getTrades(long assetId, int from, int to) {
        return tradeService.getAssetTrades(assetId, from, to);
    }

    public Collection<AssetTransfer> getAssetTransfers(long assetId, int from, int to) {
        return assetTransferService.getAssetTransfers(assetId, from, to);
    }

    public Collection<Asset> getAllAssets(int from, int to) {
        return assetTable.getAll(from, to);
    }

    public Collection<Asset> getAssetsByName(String name, int from, int to) {
        return assetStore.getAssetsByName(name, from, to);
    }

    public Collection<Asset> getAssetsIssuedBy(long accountId, int from, int to) {
        Collection<Asset> assets = assetStore.getAssetsIssuedBy(accountId, from, to);
        for (Asset asset : assets) {
            asset.updateCurrentOwnerAccount(this.blockchain);
        }
        return assets;
    }

    public CollectionWithIndex<Asset> getAssetsOwnedBy(long accountId, int from, int to) {
        Collection<Asset> assetsIssued = assetStore.getAssetsIssuedBy(accountId, from, to);
        ArrayList<Asset> assetsOwned = new ArrayList<>();
        for (Asset asset : assetsIssued) {
            asset.updateCurrentOwnerAccount(this.blockchain);
            if (asset.getAccountId() == accountId) {
                assetsOwned.add(asset);
            }
        }

        int nextIndex = assetsIssued.size() == to - from + 1 ? to + 1 : -1;

        if (nextIndex < 0) {
            // now check for ownership transfers
            int remainingSize = from - to - assetsIssued.size();

            Collection<Long> txIds = this.blockchain.getTransactionIds(null, accountId, 0,
                    TransactionType.TYPE_COLORED_COINS.getType(),
                    TransactionType.SUBTYPE_COLORED_COINS_TRANSFER_OWNERSHIP, 0,
                    0, remainingSize, false);
            for (Long txId : txIds) {
                Transaction tx = this.blockchain.getTransaction(txId);
                Transaction assetIssuance = this.blockchain.getTransactionByFullHash(
                        tx.getReferencedTransactionFullHash());
                Asset asset = getAsset(assetIssuance.getId());
                if (asset.getAccountId() == accountId) {
                    assetsOwned.add(asset);
                }
            }

            nextIndex = (assetsIssued.size() + txIds.size()) == to - from + 1 ? to + 1 : -1;
        }

        return new CollectionWithIndex<>(assetsOwned, nextIndex);
    }

    public int getAssetsCount() {
        return assetTable.getCount();
    }

    public void addAsset(long assetId, long senderId, Attachment.ColoredCoinsAssetIssuance attachment) {
        final SignumKey dbKey = assetDbKeyFactory.newKey(assetId);
        assetTable.insert(new Asset(dbKey, assetId, senderId, attachment));
    }

}