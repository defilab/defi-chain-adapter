package com.defilab.ChainAdapter.ledgers;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.alibaba.fastjson.JSON;
import com.github.ontio.OntSdk;
import com.github.ontio.account.Account;
import com.github.ontio.common.Helper;
import com.github.ontio.core.block.Block;
import com.github.ontio.core.payload.InvokeCode;
import com.github.ontio.core.transaction.Transaction;
import com.github.ontio.core.transaction.TransactionType;
import com.github.ontio.crypto.SignatureScheme;
import com.github.ontio.smartcontract.neovm.abi.AbiFunction;
import com.github.ontio.smartcontract.neovm.abi.Parameter;


public class OntologyLedger extends Ledger {
    private Account account;
    private OntSdk ontSdk = OntSdk.getInstance();
    private String ontChainEndpoint = null;
    private String ontRpcEndpoint = null;
    private String ontRestfulEndpoint = null;
    private String smartContractAddress = null;
    private final String OFFER_BODY_CODE = "-----OFFERBODY-----";
    private Logger logger = LogManager.getLogger(OntologyLedger.class);
    
    public OntologyLedger(String accountPrivateKey) throws Exception {
        this(accountPrivateKey, "chain.test.pts.foundation", "25b5e3d5325793b3f4411b47e92d8306b903cc29");
    }
    
    public OntologyLedger(String accountPrivateKey, String chainEndpoint) throws Exception {
        this(accountPrivateKey, chainEndpoint, "25b5e3d5325793b3f4411b47e92d8306b903cc29");
    }
    
    public OntologyLedger(String accountPrivateKey, String chainEndpoint, String smartContractAddress) throws Exception {
        ontChainEndpoint = chainEndpoint;
        ontRpcEndpoint = "http://" + ontChainEndpoint + ":20336";
        ontRestfulEndpoint = "http://" + ontChainEndpoint + ":20334";
        ontSdk.setRpc(ontRpcEndpoint);
        ontSdk.setRestful(ontRestfulEndpoint);

        this.smartContractAddress = smartContractAddress;
        
        account = new Account(Helper.hexToBytes(accountPrivateKey), SignatureScheme.SHA256WITHECDSA);
    }

    @Override
    public String putOffer(Map<String, Object> offer) throws Exception {
        if (!offer.containsKey("offer_id")) {
            throw new Exception("offer_id is required");
        }
        Map<String, Object> smartContractParams = new HashMap<String, Object>();
        smartContractParams.put("offer_id", offer.get("offer_id"));
        smartContractParams.put("offer_body", OFFER_BODY_CODE + new String(Base64.getEncoder().encode(JSON.toJSONString(offer).getBytes())) + OFFER_BODY_CODE);
        return invokeSmartContract("PutOffer", smartContractParams);
    }

    @Override
    public String acceptOffer(Map<String, Object> offer) throws Exception {
        if (!offer.containsKey("offer_id")) {
            throw new Exception("offer_id is required");
        } else if (!offer.containsKey("postman_receipt")) {
            throw new Exception("postman_receipt is required");
        }
        Map<String, Object> smartContractParams = new HashMap<String, Object>();
        smartContractParams.put("offer_id", offer.get("offer_id"));
        smartContractParams.put("offer_body", OFFER_BODY_CODE + new String(Base64.getEncoder().encode(JSON.toJSONString(offer).getBytes())) + OFFER_BODY_CODE);
        smartContractParams.put("postman_receipt", offer.get("postman_receipt"));
        return invokeSmartContract("AcceptOffer", smartContractParams);
    }

    @Override
    public Map<String, Object> getTransaction(String transactionId) throws Exception {
        return parseTransaction(ontSdk.getRpc().getTransaction(transactionId));
    }

    @Override
    public Boolean verifyTransaction(String offerId, String transactionId, String action) {
        try {
            Map<String, Object> tx = getTransaction(transactionId);
            if (tx == null || !tx.get("offer_id").toString().equals(offerId)) {
                return false;
            }
        } catch (Exception e) {
            logger.error(String.format("Failed to verify transaction %s (%s)", transactionId, e.getMessage()));
            return false;
        }
        return true;
    }

    @Override
    public Integer getBlockchainHeight() throws Exception {
        return ontSdk.getRpc().getBlockHeight();
    }

    @Override
    public List<Map<String, Object>> getTransactionsFromBlock(Integer blockNumber) throws Exception {
        List<Map<String, Object>> transactions = new ArrayList<Map<String, Object>>();
        Block block = ontSdk.getRpc().getBlock(blockNumber);
        for (Transaction rawTx : block.transactions) {
            try {
                Map<String, Object> tx = parseTransaction(rawTx);
                if (tx != null) {
                    transactions.add(tx);
                }
            } catch (Exception e) {
                logger.debug(String.format("Failed to parse transactions in block %s (%s)", blockNumber, e.toString()));
            }
        }
        return transactions;
    }
    
    private String invokeSmartContract(String functionName, Map<String, Object> params) {
        Parameter scParameter = new Parameter();
        scParameter.type = "Map";
        AbiFunction func = new AbiFunction(functionName, scParameter);
        func.name = functionName;
        String transactionId;
        try {
            func.setParamsValue(params);
            //transactionId = ontSdk.neovm().sendTransaction(Helper.reverse(smartContractAddress), account, account, 20000, 500, func, false).toString();
            transactionId = ontSdk.neovm().sendTransaction(Helper.reverse(smartContractAddress), account, account, 30000, 0, func, false).toString();
        } catch (Exception e) {
                e.printStackTrace();
                return null;
        }
        return transactionId;
    }

    private Map<String, Object> parseTransaction(Transaction rawTx) {
        if (rawTx.txType != TransactionType.InvokeCode) {
            return null;
        }
        InvokeCode invokeTx = (InvokeCode)rawTx;
        String codeString = new String(invokeTx.code);
        String offerB64String = codeString.split(OFFER_BODY_CODE)[1];
        Map<String, Object> offer = JSON.parseObject(new String(Base64.getDecoder().decode(offerB64String)));
        return offer;
    }

    @Override
    public Double getAccountBalance(String accountAddress) throws Exception {
        return null;
    }
}
