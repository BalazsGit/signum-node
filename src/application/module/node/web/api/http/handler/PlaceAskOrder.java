package application.module.node.web.api.http.handler;

import application.module.node.*;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.services.AccountService;
import application.module.node.services.ParameterService;
import application.module.node.web.api.http.common.APITransactionManager;
import application.module.node.web.api.http.common.LegacyDocTag;
import application.module.node.web.api.http.common.ParameterParser;
import com.google.gson.JsonElement;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.web.api.http.common.JSONResponses.NOT_ENOUGH_ASSETS;
import static application.module.node.web.api.http.common.Parameters.*;

public final class PlaceAskOrder extends CreateTransaction {

    private final ParameterService parameterService;
    private final Blockchain blockchain;
    private final AccountService accountService;

    public PlaceAskOrder(ParameterService parameterService, Blockchain blockchain,
            APITransactionManager apiTransactionManager, AccountService accountService, FluxCapacitor fluxCapacitor) {
        super(new LegacyDocTag[] { LegacyDocTag.AE, LegacyDocTag.CREATE_TRANSACTION }, apiTransactionManager, fluxCapacitor,
                ASSET_PARAMETER, QUANTITY_QNT_PARAMETER, PRICE_NQT_PARAMETER);
        this.parameterService = parameterService;
        this.blockchain = blockchain;
        this.accountService = accountService;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) throws SignumException {

        final Asset asset = parameterService.getAsset(req);
        final long priceNQT = ParameterParser.getPriceNQT(req);
        final long quantityQNT = ParameterParser.getQuantityQNT(req);
        final Account account = parameterService.getSenderAccount(req);

        long assetBalance = accountService.getUnconfirmedAssetBalanceQNT(account, asset.getId());
        if (assetBalance < 0 || quantityQNT > assetBalance) {
            return NOT_ENOUGH_ASSETS;
        }

        Attachment attachment = new Attachment.ColoredCoinsAskOrderPlacement(asset.getId(), quantityQNT, priceNQT,
                blockchain.getHeight());
        return createTransaction(req, account, attachment);

    }

}