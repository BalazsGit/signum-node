package application.module.brs.assetexchange;

import application.module.brs.AssetTransfer;
import application.module.brs.AssetTransfer.Event;
import application.module.brs.Transaction;
import application.module.brs.db.SignumKey;
import application.module.brs.db.sql.EntitySqlTable;
import application.module.brs.db.store.AssetTransferStore;
import application.module.brs.util.Listener;
import application.module.brs.util.Listeners;

import java.util.Collection;

class AssetTransferServiceImpl {

    private final Listeners<AssetTransfer, Event> listeners = new Listeners<>();

    private final AssetTransferStore assetTransferStore;
    private final EntitySqlTable<AssetTransfer> assetTransferTable;
    private final SignumKey.LinkKeyFactory<AssetTransfer> transferDbKeyFactory;

    public AssetTransferServiceImpl(AssetTransferStore assetTransferStore) {
        this.assetTransferStore = assetTransferStore;
        this.assetTransferTable = assetTransferStore.getAssetTransferTable();
        this.transferDbKeyFactory = assetTransferStore.getTransferDbKeyFactory();
    }

    public boolean addListener(Listener<AssetTransfer> listener, Event eventType) {
        return listeners.addListener(listener, eventType);
    }

    public boolean removeListener(Listener<AssetTransfer> listener, Event eventType) {
        return listeners.removeListener(listener, eventType);
    }

    public Collection<AssetTransfer> getAssetTransfers(long assetId, int from, int to) {
        return assetTransferStore.getAssetTransfers(assetId, from, to);
    }

    public Collection<AssetTransfer> getAccountAssetTransfers(long accountId, long assetId, int from, int to) {
        return assetTransferStore.getAccountAssetTransfers(accountId, assetId, from, to);
    }

    public int getTransferCount(long assetId) {
        return assetTransferStore.getTransferCount(assetId);
    }

    public int getAssetTransferCount() {
        return assetTransferTable.getCount();
    }

    public AssetTransfer addAssetTransfer(Transaction transaction, long assetId, long quantityQNT) {
        SignumKey dbKey = transferDbKeyFactory.newKey(transaction.getId(), assetId);
        AssetTransfer assetTransfer = new AssetTransfer(dbKey, transaction, assetId, quantityQNT);
        assetTransferTable.insert(assetTransfer);
        listeners.notify(assetTransfer, Event.ASSET_TRANSFER);
        return assetTransfer;
    }

}
