package application.module.brs.web.api.http.handler;

import application.module.brs.Block;
import application.module.brs.Blockchain;
import application.module.brs.Signum;
import application.module.brs.Generator;
import application.module.brs.web.api.http.common.LegacyDocTag;
import application.module.brs.web.api.http.common.ResultFields;
import application.module.brs.props.Props;
import application.module.brs.services.BlockService;
import application.module.brs.util.Convert;
import application.module.brs.web.api.http.ApiServlet;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import jakarta.servlet.http.HttpServletRequest;

public final class GetMiningInfo extends ApiServlet.JsonRequestHandler {

    private final Blockchain blockchain;
    private final BlockService blockService;
    private final Generator generator;

    public GetMiningInfo(Blockchain blockchain, BlockService blockService, Generator generator) {
        super(new LegacyDocTag[] { LegacyDocTag.MINING, LegacyDocTag.INFO });
        this.blockchain = blockchain;
        this.blockService = blockService;
        this.generator = generator;
    }

    @Override
    protected JsonElement processRequest(HttpServletRequest req) {
        JsonObject response = new JsonObject();

        response.addProperty(ResultFields.HEIGHT_RESPONSE,
                Long.toString((long) Signum.getBlockchain().getHeight() + 1));

        Block lastBlock = blockchain.getLastBlock();
        byte[] newGenSig = generator.calculateGenerationSignature(lastBlock.getGenerationSignature(),
                lastBlock.getGeneratorId());

        response.addProperty(ResultFields.GENERATION_SIGNATURE_RESPONSE, Convert.toHexString(newGenSig));
        response.addProperty(ResultFields.BASE_TARGET_RESPONSE, Long.toString(lastBlock.getCapacityBaseTarget()));
        response.addProperty(ResultFields.AVERAGE_COMMITMENT_NQT_RESPONSE,
                Long.toString(lastBlock.getAverageCommitment()));
        response.addProperty(ResultFields.LAST_BLOCK_REWARD_RESPONSE,
                Long.toString(blockService.getBlockReward(lastBlock)
                        / Signum.getPropertyService().getInt(Props.ONE_COIN_NQT)));
        response.addProperty(ResultFields.LAST_BLOCK_REWARD_NQT_RESPONSE,
                Long.toString(blockService.getBlockReward(lastBlock)));
        response.addProperty(ResultFields.TIMESTAMP_RESPONSE, Long.toString((long) lastBlock.getTimestamp()));

        return response;
    }
}
