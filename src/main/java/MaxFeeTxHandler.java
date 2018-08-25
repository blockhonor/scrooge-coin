import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static java.lang.Double.compare;
import static java.util.Arrays.stream;
import static java.util.Comparator.comparingDouble;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toSet;
import static java.util.stream.IntStream.range;

public class MaxFeeTxHandler {

    private UTXOPool utxoPool;

    /**
     * Creates a public ledger whose current UTXOPool (collection of unspent transaction outputs) is
     * {@code utxoPool}. This should make a copy of utxoPool by using the UTXOPool(UTXOPool uPool)
     * constructor.
     */
    public MaxFeeTxHandler(UTXOPool utxoPool) {
        this.utxoPool = new UTXOPool(utxoPool);
    }

    /**
     * @return true if:
     * (1) all outputs claimed by {@code tx} are in the current UTXO pool,
     * (2) the signatures on each input of {@code tx} are valid,
     * (3) no UTXO is claimed multiple times by {@code tx},
     * (4) all of {@code tx}s output values are non-negative, and
     * (5) the sum of {@code tx}s input values is greater than or equal to the sum of its output
     * values; and false otherwise.
     */
    public boolean isValidTx(Transaction tx) {
        Boolean outputsExistes = range(0, tx.numInputs())
                .mapToObj(o -> {
                    Transaction.Input input = tx.getInput(o);
                    UTXO ut = new UTXO(input.prevTxHash, input.outputIndex);
                    return utxoPool.contains(ut);
                })
                .reduce(true, (l, r) -> r && l);
        if (!outputsExistes) {
            return false;
        }

        Boolean signature = range(0, tx.numInputs())
                .mapToObj($ -> {
                    Transaction.Input input = tx.getInput($);
                    return Crypto.verifySignature(
                            utxoPool.getTxOutput(new UTXO(input.prevTxHash, input.outputIndex)).address,
                            tx.getRawDataToSign($), input.signature);
                })
                .reduce(true, (l, r) -> r && l);


        if (!signature) {
            return false;
        }

        boolean uniqueness = tx.getInputs().stream().map(i -> new UTXO(i.prevTxHash, i.outputIndex)).distinct().count() == tx.numInputs();

        if (!uniqueness) {
            return false;
        }

        Boolean positive = tx.getOutputs().stream().map(o -> o.value >= 0).reduce(true, (l, r) -> l && r);
        if (!positive) {
            return false;
        }

        Double input = range(0, tx.numInputs()).mapToObj($ -> {
            Transaction.Input in = tx.getInput($);
            UTXO utxo = new UTXO(in.prevTxHash, in.outputIndex);
            return utxoPool.getTxOutput(utxo).value;
        }).reduce(0.0, Double::sum);

        Double output = tx.getOutputs().stream().map(o -> o.value).reduce(0.0, Double::sum);
        return input >= output;
    }

    private double fee(Transaction tx) {
        Double input = range(0, tx.numInputs()).mapToObj($ -> {
            Transaction.Input in = tx.getInput($);
            UTXO utxo = new UTXO(in.prevTxHash, in.outputIndex);
            return utxoPool.contains(utxo) ? utxoPool.getTxOutput(utxo).value : 0.0;
        }).reduce(0.0, Double::sum);

        Double output = tx.getOutputs().stream().map(o -> o.value).reduce(0.0, Double::sum);
        return input - output;
    }

    /**
     * Handles each epoch by receiving an unordered array of proposed transactions, checking each
     * transaction for correctness, returning a mutually valid array of accepted transactions, and
     * updating the current UTXO pool as appropriate.
     */
    public Transaction[] handleTxs(Transaction[] possibleTxs) {
        Set<Transaction> txs = Arrays.stream(possibleTxs)
                .filter(this::isValidTx)
                .collect(toCollection(() -> new TreeSet<>((tx1, tx2) -> compare(fee(tx2), fee(tx1)))));

        txs.stream()
                .peek(tx -> {
                    tx.getInputs().forEach(i -> utxoPool.removeUTXO(new UTXO(i.prevTxHash, i.outputIndex)));
                    range(0, tx.numOutputs()).forEach(i -> utxoPool.addUTXO(new UTXO(tx.getHash(), i), tx.getOutput(i)));
                })
                .collect(toSet());
        return txs.toArray(new Transaction[txs.size()]);
    }

}
