package org.hyperledger.fabric.sdk;

import java.util.ArrayList;
import java.util.List;

import com.google.protobuf.ByteString;

public class PublicEnvelopeDeserializer {
    public static List<String> parseResponsePayloads(ByteString byteString) {
        List<String> ret = new ArrayList<>();
        for (TransactionActionDeserializer txAction : new TransactionPayloadDeserializer(byteString).getTransaction().getTransactionActions()) {
            ret.add(new String(txAction.getPayload().getAction().getProposalResponsePayload().getExtension().getResponsePayload().toByteArray()));
        }
        return ret;
    }
    
    public static List<String> parseResponseMessages(ByteString byteString) {
        List<String> ret = new ArrayList<>();
        for (TransactionActionDeserializer txAction : new TransactionPayloadDeserializer(byteString).getTransaction().getTransactionActions()) {
            ret.add(txAction.getPayload().getAction().getProposalResponsePayload().getExtension().getResponseMessage());
        }
        return ret;
    }
}
