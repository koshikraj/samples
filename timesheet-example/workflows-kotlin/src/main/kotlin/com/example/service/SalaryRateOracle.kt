package com.example.service

import javafx.util.Pair
import net.corda.core.contracts.Command
import net.corda.core.crypto.TransactionSignature
import net.corda.core.node.ServiceHub
import net.corda.core.node.services.CordaService
import net.corda.core.serialization.SingletonSerializeAsToken
import net.corda.core.transactions.FilteredTransaction
import com.example.contract.InvoiceContract
import net.corda.core.contracts.CommandData
import net.corda.core.serialization.CordaSerializable
import java.util.*

// We sub-class 'SingletonSerializeAsToken' to ensure that instances of this class are never serialised by Kryo.
// When a flow is check-pointed, the annotated @Suspendable methods and any object referenced from within those
// annotated methods are serialised onto the stack. Kryo, the reflection based serialisation framework we use, crawls
// the object graph and serialises anything it encounters, producing a graph of serialised objects.
// This can cause issues. For example, we do not want to serialise large objects on to the stack or objects which may
// reference databases or other external services (which cannot be serialised!). Therefore we mark certain objects with
// tokens. When Kryo encounters one of these tokens, it doesn't serialise the object. Instead, it creates a
// reference to the type of the object. When flows are de-serialised, the token is used to connect up the object
// reference to an instance which should already exist on the stack.
@CordaService
class SalaryRateOracle(val services: ServiceHub) : SingletonSerializeAsToken() {
    private val myKey = services.myInfo.legalIdentities.first().owningKey

    @CordaSerializable
    data class RateOf(val contractor: Int, val company: Int)

    @CordaSerializable
    data class Rate(val of: RateOf, val rate: Double?) : CommandData

    // A table of (contractor, company) -> salary expectations (per hour)
    val salaryTable : HashMap<Pair<Int, Int>, Double> = hashMapOf(
            Pair(1, 1) to 10.0,
            Pair(1, 2) to 8.0)

    // Returns the salary for the given contractor at that company
    fun query(rateOf: RateOf): Rate = Rate(rateOf, salaryTable[Pair(rateOf.contractor, rateOf.company)])

    // Signs over a transaction if the specified Nth prime for a particular N is correct.
    // This function takes a filtered transaction which is a partial Merkle tree. Any parts of the transaction which
    // the oracle doesn't need to see in order to verify the correctness of the nth prime have been removed. In this
    // case, all but the [PrimeContract.Create] commands have been removed. If the Nth prime is correct then the oracle
    // signs over the Merkle root (the hash) of the transaction.
    fun sign(ftx: FilteredTransaction): TransactionSignature {
        // Check the partial Merkle tree is valid.
        ftx.verify()

        /** Returns true if the component is an command that:
         *  - States the correct rate
         *  - Has the oracle listed as a signer
         */
        fun isCommandWithCorrectRateAndIAmSigner(elem: Any) = when {
            elem is Command<*> && elem.value is Rate -> {
                val cmdData = elem.value as Rate
                myKey in elem.signers && query(cmdData.of).rate == cmdData.rate
            }
            else -> false
        }

        // Is it a Merkle tree we are willing to sign over?
        val isValidMerkleTree = ftx.checkWithFun(::isCommandWithCorrectRateAndIAmSigner)

        if (isValidMerkleTree) {
            return services.createSignature(ftx, myKey)
        } else {
            throw IllegalArgumentException("SalaryRateOracle signature requested over invalid transaction.")
        }
    }
}