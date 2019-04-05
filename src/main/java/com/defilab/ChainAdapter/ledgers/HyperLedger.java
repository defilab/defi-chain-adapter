package com.defilab.ChainAdapter.ledgers;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hyperledger.fabric.sdk.BlockInfo;
import org.hyperledger.fabric.sdk.ChaincodeID;
import org.hyperledger.fabric.sdk.ChaincodeResponse;
import org.hyperledger.fabric.sdk.Channel;
import org.hyperledger.fabric.sdk.Enrollment;
import org.hyperledger.fabric.sdk.HFClient;
import org.hyperledger.fabric.sdk.NetworkConfig;
import org.hyperledger.fabric.sdk.ProposalResponse;
import org.hyperledger.fabric.sdk.PublicEnvelopeDeserializer;
import org.hyperledger.fabric.sdk.TransactionInfo;
import org.hyperledger.fabric.sdk.TransactionProposalRequest;
import org.hyperledger.fabric.sdk.TransactionRequest;
import org.hyperledger.fabric.sdk.User;
import org.hyperledger.fabric.sdk.security.CryptoSuite;

import com.alibaba.fastjson.JSON;
import com.google.protobuf.ByteString;

import static org.hyperledger.fabric.sdk.BlockInfo.EnvelopeType.TRANSACTION_ENVELOPE;


public class HyperLedger extends Ledger {
    private HFClient hfClient = null;
    private Channel hfChannel = null;
    private String chainCodeName = null;
    
    public HyperLedger(String account) throws Exception {
        this(account, "pts-exchange");
    }
    
    public HyperLedger(String account, String channelName) throws Exception {
        this(account, channelName, channelName);
    }
    
    public HyperLedger(String account, String channelName, String chainCode) throws Exception {
        this(account, channelName, chainCode, "fabric_network.json");
    }
    
    class SendTransactionThread extends Thread {
        private Channel hfChannel;
        private Collection<ProposalResponse> proposalResponses;
        SendTransactionThread(Channel hfChannel, Collection<ProposalResponse> proposalResponses) {
            this.hfChannel = hfChannel;
            this.proposalResponses = proposalResponses;
        }
        public void run() {
            hfChannel.sendTransaction(proposalResponses);
        }
    }
    
    @SuppressWarnings("unchecked")
    public HyperLedger(String account, String channelName, String chainCode, String fabricNetworkConfigFile) throws Exception {      
        Map<String, Object> networkConfig = (Map<String, Object>) JSON.parse(new String(Files.readAllBytes(Paths.get(fabricNetworkConfigFile))));
        Map<String, Object> organizations = (Map<String, Object>) networkConfig.get("organizations");
        Map<String, Object> organization = (Map<String, Object>) organizations.get(account.split("@")[1]);
        Map<String, Object> user = (Map<String, Object>) ((Map<String, Object>) organization.get("users")).get(account.split("@")[0]);
        hfClient = HFClient.createNewInstance();
        hfClient.setCryptoSuite(CryptoSuite.Factory.getCryptoSuite());
        UserContext userContext = new UserContext();
        userContext.setName(account.split("@")[0]);
        userContext.setAffiliation(account.split("@")[1]);
        UserEnrollment enrollment = new UserEnrollment();
        enrollment.setCert(user.get("cert").toString());
        enrollment.setKey(user.get("private_key").toString());
        userContext.setEnrollment(enrollment);
        userContext.setMspId(organization.get("mspid").toString());
        hfClient.setUserContext(userContext);
        hfChannel = hfClient.loadChannelFromConfig(channelName, NetworkConfig.fromJsonFile(new File(fabricNetworkConfigFile)));
        hfChannel.initialize();
        chainCodeName = chainCode;
    }

    @Override
    public String putOffer(Map<String, Object> offer) throws Exception {
        ArrayList<String> contractArgs = new ArrayList<>();
        contractArgs.add("PutOffer");
        contractArgs.add(offer.get("offer_id").toString());
        contractArgs.add(JSON.toJSONString(offer));
        return invokeSmartContract("invoke", contractArgs, false);
    }

    @Override
    public String acceptOffer(Map<String, Object> offer) throws Exception {
        ArrayList<String> contractArgs = new ArrayList<>();
        contractArgs.add("AcceptOffer");
        contractArgs.add(offer.get("offer_id").toString());
        contractArgs.add(JSON.toJSONString(offer));
        return invokeSmartContract("invoke", contractArgs, false);
    }
    
    @SuppressWarnings("unchecked")
    @Override
    public Double getAccountBalance(String accountName) throws Exception {
        ArrayList<String> args = new ArrayList<>();
        args.add("account_balance");
        args.add(accountName);
        return Double.parseDouble(((Map<String, Object>)JSON.parse(invokeSmartContract("query", args, true))).get("balance").toString());
    }
    
    public String invokeSmartContract(String func, ArrayList<String> args, Boolean readOonly) throws Exception {
        TransactionProposalRequest request = hfClient.newTransactionProposalRequest();
        ChaincodeID ccid = ChaincodeID.newBuilder().setName(chainCodeName).build();
        request.setChaincodeID(ccid);
        request.setChaincodeLanguage(TransactionRequest.Type.JAVA);
        request.setFcn(func);
        request.setArgs(args);
        request.setProposalWaitTime(3000);
        Collection<ProposalResponse> responses = hfChannel.sendTransactionProposal(request);
        
        if (readOonly) {
            for (ProposalResponse res: responses) {
                return new String(res.getChaincodeActionResponsePayload());
            }
        } else {
            String txId = null;
            for (ProposalResponse res: responses) {
                if (res.getStatus() != ChaincodeResponse.Status.SUCCESS) {
                    throw new Exception(String.format("Failed to run chain code. (%s)", res.getMessage()));
                } else {
                    txId = res.getTransactionID();
                }
            }
            new SendTransactionThread(hfChannel, responses).start();
            return txId;
        }
        return null;
    }

    @Override
    public Map<String, Object> getTransaction(String transactionId) throws Exception {
        TransactionInfo txInfo = hfChannel.queryTransactionByID(transactionId);
        ByteString txEnvelopePayload = txInfo.getProcessedTransaction().getTransactionEnvelope().getPayload();
        List<String> responseMessages = PublicEnvelopeDeserializer.parseResponseMessages(txEnvelopePayload);
        List<String> responsePayloads = PublicEnvelopeDeserializer.parseResponsePayloads(txEnvelopePayload);
        
        Map<String, Object> offer = parseTransaction(responsePayloads.get(0));
        offer.put("status", "success".equals(responseMessages.get(0)));
        return offer;
    }
    
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseTransaction(String payloadString) {
        Map<String, Object> payload = (Map<String, Object>) JSON.parse(payloadString);
        if (!payload.get("action").toString().equals("TopUp")) {
            payload.put("offer_body", JSON.parse(payload.get("offer_body").toString()));
        }
        return payload;
    }

    @Override
    public Boolean verifyTransaction(String offerId, String transactionId, String action) throws Exception {
        try {
            Map<String, Object> tx = getTransaction(transactionId);
            return offerId.equals(tx.get("offer_id")) && ((Boolean)tx.get("status")) && action.equals(tx.get("action"));
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public Integer getBlockchainHeight() throws Exception {
        return new BigDecimal(hfChannel.queryBlockchainInfo().getHeight()).intValueExact();
    }

    @Override
    public List<Map<String, Object>> getTransactionsFromBlock(Integer blockNumber) throws Exception {
        BlockInfo block = hfChannel.queryBlockByNumber(blockNumber);
        List<Map<String, Object>> transactions = new ArrayList<>();
        for (BlockInfo.EnvelopeInfo envelopeInfo : block.getEnvelopeInfos()) {
            if (envelopeInfo.getType() == TRANSACTION_ENVELOPE) {
                BlockInfo.TransactionEnvelopeInfo transactionEnvelopeInfo = (BlockInfo.TransactionEnvelopeInfo) envelopeInfo;
                for (BlockInfo.TransactionEnvelopeInfo.TransactionActionInfo actionInfo : transactionEnvelopeInfo.getTransactionActionInfos()) {
                    String payload = new String(actionInfo.getProposalResponsePayload());
                    Map<String, Object> offer = parseTransaction(payload);
                    offer.put("status", "success".equals(new String(actionInfo.getProposalResponseMessageBytes())));
                    transactions.add(offer);
                }
            }
        }
        return transactions;
    }

    
    //****************************************
    //*********** For HFClient ***************
    //****************************************
    
    private class UserEnrollment implements Enrollment {
        private PrivateKey key;
        private String cert;
        
        public void setKey(String keyFilePath) throws Exception {
            String keyBody = new String(Files.readAllBytes(Paths.get(keyFilePath)));
            String privKeyPEM = keyBody.replace("\n", "").replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "");
            byte[] decoded = Base64.getDecoder().decode(privKeyPEM);
            key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(decoded));
        }
        
        public void setCert(String certFilePath) throws IOException {
            cert = new String(Files.readAllBytes(Paths.get(certFilePath)));
        }

        public PrivateKey getKey() {
            return key;
        }

        public String getCert() {
            return this.cert;
        }

    }
    
    private class UserContext implements User, Serializable {
        
        private static final long serialVersionUID = 1L;
        protected String name;
        protected Set<String> roles;
        protected String account;
        protected String affiliation;
        protected Enrollment enrollment;
        protected String mspId;
        
        public void setName(String name) {
            this.name = name;
        }

        public void setRoles(Set<String> roles) {
            this.roles = roles;
        }

        public void setAccount(String account) {
            this.account = account;
        }

        public void setAffiliation(String affiliation) {
            this.affiliation = affiliation;
        }

        public void setEnrollment(Enrollment enrollment) {
            this.enrollment = enrollment;
        }

        public void setMspId(String mspId) {
            this.mspId = mspId;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Set<String> getRoles() {
            return roles;
        }

        @Override
        public String getAccount() {
            return account;
        }

        @Override
        public String getAffiliation() {
            return affiliation;
        }

        @Override
        public Enrollment getEnrollment() {
            return enrollment;
        }

        @Override
        public String getMspId() {
            return mspId;
        }
    }
}
