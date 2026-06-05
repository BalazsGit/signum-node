package application.module.node.assetexchange;

import application.module.node.Account.AccountAsset;
import application.module.node.Asset;
import application.module.node.db.store.AccountStore;

import java.util.Collection;

class AssetAccountServiceImpl {

    private final AccountStore accountStore;

    public AssetAccountServiceImpl(AccountStore accountStore) {
        this.accountStore = accountStore;
    }

    public Collection<AccountAsset> getAssetAccounts(Asset asset, boolean ignoreTreasury, long minimumQuantity,
            boolean unconfirmed, int from, int to) {
        return accountStore.getAssetAccounts(asset, ignoreTreasury, minimumQuantity, unconfirmed, from, to);
    }

    public int getAssetAccountsCount(Asset asset, long minimumQuantity, boolean ignoreTreasury, boolean unconfirmed) {
        return accountStore.getAssetAccountsCount(asset, minimumQuantity, ignoreTreasury, unconfirmed);
    }

    public long getAssetCirculatingSupply(Asset asset, boolean ignoreTreasury, boolean unconfirmed) {
        return accountStore.getAssetCirculatingSupply(asset, ignoreTreasury, unconfirmed);
    }

}
