package application.module.node.web.api.http.handler;

import application.module.node.*;
import application.module.node.fluxcapacitor.FluxCapacitor;
import application.module.node.services.ParameterService;
import application.module.node.util.Convert;
import application.module.node.web.api.http.common.APITransactionManager;
import application.module.node.web.api.http.common.LegacyDocTag;
import com.google.gson.JsonElement;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.node.web.api.http.common.JSONResponses.*;
import static application.module.node.web.api.http.common.Parameters.DELTA_QUANTITY_PARAMETER;
import static application.module.node.web.api.http.common.Parameters.GOODS_PARAMETER;

public final class DGSQuantityChange extends CreateTransaction {

    private final ParameterService parameterService;
    private final Blockchain blockchain;

    public DGSQuantityChange(ParameterService parameterService, Blockchain blockchain,
            APITransactionManager apiTransactionManager, FluxCapacitor fluxCapacitor) {
        super(new LegacyDocTag[] { LegacyDocTag.DGS, LegacyDocTag.CREATE_TRANSACTION }, apiTransactionManager, fluxCapacitor,
                GOODS_PARAMETER, DELTA_QUANTITY_PARAMETER);

        this.parameterService = parameterService;
        this.blockchain = blockchain;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) throws SignumException {

        Account account = parameterService.getSenderAccount(req);
        DigitalGoodsStore.Goods goods = parameterService.getGoods(req);
        if (goods.isDelisted() || goods.getSellerId() != account.getId()) {
            return UNKNOWN_GOODS;
        }

        int deltaQuantity;
        try {
            String deltaQuantityString = Convert.emptyToNull(req.getParameter(DELTA_QUANTITY_PARAMETER));
            if (deltaQuantityString == null) {
                return MISSING_DELTA_QUANTITY;
            }
            deltaQuantity = Integer.parseInt(deltaQuantityString);
            if (deltaQuantity > Constants.MAX_DGS_LISTING_QUANTITY
                    || deltaQuantity < -Constants.MAX_DGS_LISTING_QUANTITY) {
                return INCORRECT_DELTA_QUANTITY;
            }
        } catch (NumberFormatException e) {
            return INCORRECT_DELTA_QUANTITY;
        }

        Attachment attachment = new Attachment.DigitalGoodsQuantityChange(goods.getId(), deltaQuantity,
                blockchain.getHeight());
        return createTransaction(req, account, attachment);

    }

}