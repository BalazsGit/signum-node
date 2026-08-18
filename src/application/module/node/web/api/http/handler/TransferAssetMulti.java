package application.module.node.web.api.http.handler;

import static application.module.node.web.api.http.common.JSONResponses.NOT_ENOUGH_ASSETS;
import static application.module.node.web.api.http.common.Parameters.AMOUNT_NQT_PARAMETER;
import static application.module.node.web.api.http.common.Parameters.ASSET_IDS_AND_QUANTITIES_PARAMETER;
import static application.module.node.web.api.http.common.Parameters.RECIPIENT_PARAMETER;

import java.util.ArrayList;

import jakarta.servlet.http.HttpServletRequest;

import application.module.node.web.api.http.common.APITransactionManager;
import application.module.node.web.api.http.common.JSONResponses;
import application.module.node.web.api.http.common.LegacyDocTag;
import application.module.node.web.api.http.common.ParameterParser;
import com.google.gson.JsonElement;

import application.module.node.Account;
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
import application.module.node.util.Convert;

public final class TransferAssetMulti extends CreateTransaction {

    private final ParameterService parameterService;
    private final Blockchain blockchain;
    private final AccountService accountService;
    private final AssetExchange assetExchange;
    private final FluxCapacitor fluxCapacitor;

    public TransferAssetMulti(ParameterService parameterService, Blockchain blockchain,
            APITransactionManager apiTransactionManager, AccountService accountService,
            AssetExchange assetExchange, FluxCapacitor fluxCapacitor) {
        super(new LegacyDocTag[] { LegacyDocTag.AE, LegacyDocTag.CREATE_TRANSACTION }, apiTransactionManager, fluxCapacitor,
                RECIPIENT_PARAMETER, ASSET_IDS_AND_QUANTITIES_PARAMETER, AMOUNT_NQT_PARAMETER);
        this.parameterService = parameterService;
        this.blockchain = blockchain;
        this.accountService = accountService;
        this.assetExchange = assetExchange;
        this.fluxCapacitor = fluxCapacitor;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) throws SignumException {

        long recipient = ParameterParser.getRecipientId(req, fluxCapacitor);
        Account account = parameterService.getSenderAccount(req);

        String assetIdsString = Convert.emptyToNull(req.getParameter(ASSET_IDS_AND_QUANTITIES_PARAMETER));

        if (assetIdsString == null) {
            return JSONResponses.missing(ASSET_IDS_AND_QUANTITIES_PARAMETER);
        }

        String[] assetIdsArray = assetIdsString.split(";", Constants.MAX_MULTI_ASSET_IDS);
        ArrayList<Long> assetIds = new ArrayList<>();
        ArrayList<Long> quantitiesQNT = new ArrayList<>();

        if (assetIdsArray.length == 0 || assetIdsArray.length > Constants.MAX_MULTI_ASSET_IDS) {
            return JSONResponses.incorrect(ASSET_IDS_AND_QUANTITIES_PARAMETER);
        }

        for (String assetIdString : assetIdsArray) {
            String[] assetIdAndQuantity = assetIdString.split(":", 2);
            long assetId = Convert.parseUnsignedLong(assetIdAndQuantity[0]);
            Asset asset = this.assetExchange.getAsset(assetId);
            if (asset == null || assetIds.contains(assetId)) {
                return JSONResponses.incorrect(ASSET_IDS_AND_QUANTITIES_PARAMETER);
            }
            long quantityQNT = Long.parseLong(assetIdAndQuantity[1]);
            if (quantityQNT <= 0L) {
                return JSONResponses.incorrect(ASSET_IDS_AND_QUANTITIES_PARAMETER);
            }
            assetIds.add(assetId);
            quantitiesQNT.add(quantityQNT);

            long assetBalance = accountService.getUnconfirmedAssetBalanceQNT(account, assetId);
            if (assetBalance < 0 || quantityQNT > assetBalance) {
                return NOT_ENOUGH_ASSETS;
            }
        }

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
            } else if (!this.fluxCapacitor.getValue(FluxValues.SMART_TOKEN)) {
                return JSONResponses.incorrect(AMOUNT_NQT_PARAMETER);
            }
        }

        Attachment attachment = new Attachment.ColoredCoinsAssetMultiTransfer(fluxCapacitor, assetIds, quantitiesQNT,
                blockchain.getHeight());
        return createTransaction(req, account, recipient, amountNQT, attachment);

    }

}