package application.module.node.assetexchange;

import application.module.node.Account.AccountAsset;
import application.module.node.Asset;
import application.module.node.db.store.AccountStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssetAccountServiceImplTest {

    private AssetAccountServiceImpl t;

    private AccountStore mockAccountStore;

    @BeforeEach
    void setUp() {
        mockAccountStore = mock(AccountStore.class);

        t = new AssetAccountServiceImpl(mockAccountStore);
    }

    @Test
    void getAssetAccounts() {
        final int from = 1;
        final int to = 5;
        final Asset mockAsset = mock(Asset.class);
        final ArrayList<AccountAsset> mockAccountIterator = new ArrayList<>();

        when(mockAccountStore.getAssetAccounts(eq(mockAsset), eq(false), eq(0L), eq(true), eq(from), eq(to)))
                .thenReturn(mockAccountIterator);

        assertEquals(mockAccountIterator, t.getAssetAccounts(mockAsset, false, 0L, true, from, to));
    }

    @Test
    void getAssetAccountsCount() {
        final Asset mockAsset = mock(Asset.class);

        when(mockAccountStore.getAssetAccountsCount(eq(mockAsset), eq(0L), eq(true), eq(false))).thenReturn(5);

        assertEquals(5L, t.getAssetAccountsCount(mockAsset, 0L, true, false));
    }
}
