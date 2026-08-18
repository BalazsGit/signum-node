package application.module.node.web.api.http.handler;

import static application.module.node.web.api.http.common.JSONResponses.NOT_ENOUGH_ASSETS;
import static application.module.node.web.api.http.common.Parameters.AMOUNT_NQT_PARAMETER;
import static application.module.node.web.api.http.common.Parameters.ASSET_PARAMETER;
import static application.module.node.web.api.http.common.Parameters.ASSET_TO_DISTRIBUTE_PARAMETER;
import static application.module.node.web.api.http.common.Parameters.QUANTITY_MININUM_QNT_PARAMETER;
import static application.module.node.web.api.http.common.Parameters.QUANTITY_QNT_PARAMETER;

import jakarta.servlet.http.HttpServletRequest;

import application.module.node.web.api.http.common.APITransactionManager;
import application.module.node.web.api.http.common.JSONResponses;
import application.module.node.web.api.http.common.LegacyDocTag;
import com.google.gson.JsonElement;

import application.module.node.Account;
import application.module.node.Account.AccountAsset;
import application.module.node.Asset;
import application.module.node.Attachment;
import application.module.node.Blockchain;
import application.module.node.SignumException;
import application.module.node.Constants;
import application.module.node.assetexchange.AssetExchange;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.fluxcapacitor.FluxValues;
import application.module.node.services.AccountService;
import application.module.node.services.ParameterService;
import application.module.node.util.CollectionWithIndex;
import application.module.node.util.Convert;

public final class DistributeToAssetHolders extends CreateTransaction {

    private final ParameterService parameterService;
    private final Blockchain blockchain;
    private final AssetExchange assetExchange;
    private final AccountService accountService;
    private final FluxCapacitor fluxCapacitor;

    public DistributeToAssetHolders(ParameterService parameterService, Blockchain blockchain,
            APITransactionManager apiTransactionManager, AssetExchange assetExchange, AccountService accountService,
            FluxCapacitor fluxCapacitor) {
        super(new LegacyDocTag[] { LegacyDocTag.AE, LegacyDocTag.CREATE_TRANSACTION }, apiTransactionManager, fluxCapacitor,
                ASSET_PARAMETER,
                QUANTITY_MININUM_QNT_PARAMETER, AMOUNT_NQT_PARAMETER, ASSET_TO_DISTRIBUTE_PARAMETER,
                QUANTITY_QNT_PARAMETER);
        this.parameterService = parameterService;
        this.blockchain = blockchain;
        this.assetExchange = assetExchange;
        this.accountService = accountService;
        this.fluxCapacitor = fluxCapacitor;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) throws SignumException {

        Account account = parameterService.getSenderAccount(req);
        Asset asset = parameterService.getAsset(req);

        long amountNQT = 0L;
        String amountValueNQT = Convert.emptyToNull(req.getParameter(AMOUNT_NQT_PARAMETER));
        if (amountValueNQT != null) {
            try {
                amountNQT = Long.parseLong(amountValueNQT);
            } catch (RuntimeException e) {
                return JSONResponses.incorrect(AMOUNT_NQT_PARAMETER);
            }
            if (amountNQT < 0 || amountNQT >= Constants.MAX_BALANCE_NQT) {
                return JSONResponses.incorrect(AMOUNT_NQT_PARAMETER);
            }
        }

        if (!this.fluxCapacitor.getValue(FluxValues.SMART_TOKEN)) {
            return JSONResponses.incorrect("asset distribution is not enabled yet");
        }

        // another token can also be sent
        long quantityQNT = Convert.parseUnsignedLong(req.getParameter(QUANTITY_QNT_PARAMETER));
        if (quantityQNT < 0) {
            return JSONResponses.incorrect(QUANTITY_QNT_PARAMETER);
        }
        long minimumQuantity = Convert.parseUnsignedLong(req.getParameter(QUANTITY_MININUM_QNT_PARAMETER));

        long assetToDistributeId = 0L;
        String assetToDistributeValue = Convert.emptyToNull(req.getParameter(ASSET_TO_DISTRIBUTE_PARAMETER));
        if (assetToDistributeValue != null) {
            try {
                assetToDistributeId = Convert.parseUnsignedLong(assetToDistributeValue);

                long assetBalance = accountService.getUnconfirmedAssetBalanceQNT(account, assetToDistributeId);
                if (assetBalance < 0 || quantityQNT > assetBalance) {
                    return NOT_ENOUGH_ASSETS;
                }
            } catch (RuntimeException e) {
                return JSONResponses.incorrect(ASSET_TO_DISTRIBUTE_PARAMETER);
            }
        } else if (quantityQNT != 0L) {
            return JSONResponses.incorrect(QUANTITY_QNT_PARAMETER);
        }

        if (amountNQT == 0L && quantityQNT == 0L) {
            return JSONResponses.incorrect(AMOUNT_NQT_PARAMETER);
        }

        boolean unconfirmed = !this.fluxCapacitor.getValue(FluxValues.DISTRIBUTION_FIX);
        CollectionWithIndex<AccountAsset> holders = assetExchange.getAssetAccounts(asset, false, minimumQuantity,
                unconfirmed, -1, -1);
        long circulatingSupply = 0;
        for (AccountAsset holder : holders) {
            try {
                circulatingSupply = Convert.safeAdd(circulatingSupply, holder.getQuantityQnt());
            } catch (ArithmeticException e) {
                return JSONResponses.incorrect(QUANTITY_MININUM_QNT_PARAMETER);
            }
        }
        if (circulatingSupply == 0L) {
            return JSONResponses.incorrect(QUANTITY_MININUM_QNT_PARAMETER);
        }

        Attachment attachment = new Attachment.ColoredCoinsAssetDistributeToHolders(fluxCapacitor, asset.getId(), minimumQuantity,
                assetToDistributeId, quantityQNT, blockchain.getHeight());
        return createTransaction(req, account, null, amountNQT, attachment);
    }

}