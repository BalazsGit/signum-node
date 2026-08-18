package application.module.node.web.api.http.handler;

import application.module.node.*;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.services.ParameterService;
import application.module.node.util.Convert;
import application.module.node.web.api.http.common.APITransactionManager;
import application.module.node.web.api.http.common.LegacyDocTag;
import application.module.node.web.api.http.common.ParameterParser;
import com.google.gson.JsonElement;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.web.api.http.common.JSONResponses.NOT_ENOUGH_FUNDS;
import static application.module.node.web.api.http.common.Parameters.*;

public final class PlaceBidOrder extends CreateTransaction {

    private final ParameterService parameterService;
    private final Blockchain blockchain;

    public PlaceBidOrder(ParameterService parameterService, Blockchain blockchain,
            APITransactionManager apiTransactionManager, FluxCapacitor fluxCapacitor) {
        super(new LegacyDocTag[] { LegacyDocTag.AE, LegacyDocTag.CREATE_TRANSACTION }, apiTransactionManager, fluxCapacitor,
                ASSET_PARAMETER, QUANTITY_QNT_PARAMETER, PRICE_NQT_PARAMETER);
        this.parameterService = parameterService;
        this.blockchain = blockchain;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) throws SignumException {

        Asset asset = parameterService.getAsset(req);
        long priceNQT = ParameterParser.getPriceNQT(req);
        long quantityQNT = ParameterParser.getQuantityQNT(req);
        long feeNQT = ParameterParser.getFeeNQT(req);
        Account account = parameterService.getSenderAccount(req);

        try {
            if (Convert.safeAdd(feeNQT, Convert.safeMultiply(priceNQT, quantityQNT)) > account
                    .getUnconfirmedBalanceNqt()) {
                return NOT_ENOUGH_FUNDS;
            }
        } catch (ArithmeticException e) {
            return NOT_ENOUGH_FUNDS;
        }

        Attachment attachment = new Attachment.ColoredCoinsBidOrderPlacement(fluxCapacitor, asset.getId(), quantityQNT, priceNQT,
                blockchain.getHeight());
        return createTransaction(req, account, attachment);
    }

}