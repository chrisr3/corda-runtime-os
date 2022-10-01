package net.corda.v5.ledger.utxo;

import net.corda.v5.application.crypto.DigitalSignatureAndMetadata;
import net.corda.v5.crypto.SecureHash;
import net.corda.v5.ledger.utxo.transaction.UtxoLedgerTransaction;
import net.corda.v5.ledger.utxo.transaction.UtxoSignedTransaction;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.security.PublicKey;
import java.util.List;
import java.util.Set;

public final class UtxoSignedTransactionJavaApiTests extends AbstractMockTestHarness {

    @Test
    public void getTransactionIdShouldReturnTheExpectedValue() {
        SecureHash value = utxoSignedTransaction.getTransactionId();
        Assertions.assertEquals(hash, value);
    }

    @Test
    public void getSignaturesShouldReturnTheExpectedValue() {
        Set<DigitalSignatureAndMetadata> value = utxoSignedTransaction.getSignatures();
        Assertions.assertEquals(signatures, value);
    }

    @Test
    public void addSignaturesShouldReturnTheExpectedValue() {
        UtxoSignedTransaction value = utxoSignedTransaction.addSignatures(List.of(aliceSignature, bobSignature));
        Assertions.assertEquals(utxoSignedTransaction, value);
    }

    @Test
    public void addSignaturesVarargShouldReturnTheExpectedValue() {
        UtxoSignedTransaction value = utxoSignedTransaction.addSignatures(aliceSignature, bobSignature);
        Assertions.assertEquals(utxoSignedTransaction, value);
    }

    @Test
    public void getMissingSignatoriesShouldReturnTheExpectedValue() {
        Set<PublicKey> value = utxoSignedTransaction.getMissingSignatories();
        Assertions.assertEquals(keys, value);
    }

    @Test
    public void toLedgerTransactionShouldReturnTheExpectedValue() {
        UtxoLedgerTransaction value = utxoSignedTransaction.toLedgerTransaction();
        Assertions.assertEquals(utxoLedgerTransaction, value);
    }
}
