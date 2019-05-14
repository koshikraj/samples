package com.example.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.unwrap
import com.example.service.SalaryRateOracle
import net.corda.core.crypto.PartialMerkleTree
import net.corda.core.crypto.TransactionSignature
import net.corda.core.crypto.isFulfilledBy
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.transactions.FilteredTransaction
import net.corda.core.transactions.TransactionBuilder

@InitiatingFlow
class SalaryRateQueryFlow(val rateOf: SalaryRateOracle.RateOf, val oracle: Party) : FlowLogic<SalaryRateOracle.Rate>() {
    @Suspendable
    override fun call(): SalaryRateOracle.Rate {
        val oracleSession = initiateFlow(oracle)

        val resp = oracleSession.sendAndReceive<SalaryRateOracle.Rate>(rateOf)

        return resp.unwrap {
            check(it.of == rateOf)
            it
        }
    }

}

@InitiatingFlow
class SalaryRateSignFlow(val tx: TransactionBuilder, val oracle: Party,
                         val partialMerkleTx: FilteredTransaction) : FlowLogic<TransactionSignature>() {
    @Suspendable
    override fun call(): TransactionSignature {
        val oracleSession = initiateFlow(oracle)
        val resp = oracleSession.sendAndReceive<TransactionSignature>(partialMerkleTx)

        return resp.unwrap { sig ->
            check(oracleSession.counterparty.owningKey.isFulfilledBy(listOf(sig.by)))
            tx.toWireTransaction(serviceHub).checkSignature(sig)
            sig
        }
    }
}

// The oracle flow to handle salary rate queries.
@InitiatedBy(InvoiceFlow.Initiator::class)
class SalaryRateQueryHandler(val session: FlowSession) : FlowLogic<Unit>() {
    companion object {
        object RECEIVING : ProgressTracker.Step("Receiving query request.")
        object CALCULATING : ProgressTracker.Step("Checking salary table.")
        object SENDING : ProgressTracker.Step("Sending query response.")
    }

    override val progressTracker = ProgressTracker(RECEIVING, CALCULATING, SENDING)

    @Suspendable
    override fun call() {
        progressTracker.currentStep = RECEIVING
        val request = session.receive<SalaryRateOracle.RateOf>().unwrap { it }

        progressTracker.currentStep = CALCULATING
        val response = try {
            // Get the rate from the oracle.
            serviceHub.cordaService(SalaryRateOracle::class.java).query(request)
        } catch (e: Exception) {
            // Re-throw the exception as a FlowException so its propagated to the querying node.
            throw FlowException(e)
        }

        progressTracker.currentStep = SENDING
        session.send(response)
    }
}