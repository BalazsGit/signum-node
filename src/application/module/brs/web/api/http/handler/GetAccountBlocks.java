package application.module.brs.web.api.http.handler;

import application.module.brs.Account;
import application.module.brs.Block;
import application.module.brs.Blockchain;
import application.module.brs.SignumException;
import application.module.brs.web.api.http.common.LegacyDocTag;
import application.module.brs.web.api.http.common.ParameterParser;
import application.module.brs.web.api.http.common.Parameters;
import application.module.brs.services.BlockService;
import application.module.brs.services.ParameterService;
import application.module.brs.web.api.http.ApiServlet;
import application.module.brs.web.api.http.common.JSONData;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jakarta.servlet.http.HttpServletRequest;

import static application.module.brs.web.api.http.common.Parameters.*;
import static application.module.brs.web.api.http.common.ResultFields.BLOCKS_RESPONSE;

public final class GetAccountBlocks extends ApiServlet.JsonRequestHandler {

    private final Blockchain blockchain;
    private final ParameterService parameterService;
    private final BlockService blockService;

    public GetAccountBlocks(Blockchain blockchain, ParameterService parameterService, BlockService blockService) {
        super(new LegacyDocTag[] { LegacyDocTag.ACCOUNTS }, ACCOUNT_PARAMETER, TIMESTAMP_PARAMETER,
                FIRST_INDEX_PARAMETER, LAST_INDEX_PARAMETER, INCLUDE_TRANSACTIONS_PARAMETER);
        this.blockchain = blockchain;
        this.parameterService = parameterService;
        this.blockService = blockService;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) throws SignumException {

        Account account = parameterService.getAccount(req);
        int timestamp = ParameterParser.getTimestamp(req);
        int firstIndex = ParameterParser.getFirstIndex(req);
        int lastIndex = ParameterParser.getLastIndex(req);

        boolean includeTransactions = Parameters.isTrue(req.getParameter(INCLUDE_TRANSACTIONS_PARAMETER));

        JsonArray blocks = new JsonArray();
        for (Block block : blockchain.getBlocks(account, timestamp, firstIndex, lastIndex)) {
            blocks.add(JSONData.block(block, includeTransactions, blockchain.getHeight(),
                    blockService.getBlockReward(block), blockService.getScoopNum(block)));
        }

        JsonObject response = new JsonObject();
        response.add(BLOCKS_RESPONSE, blocks);

        return response;
    }

}
