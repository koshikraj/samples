package com.example.flow

import co.paralleluniverse.fibers.Suspendable
import com.example.contract.InvoiceContract
import com.example.flow.PayInvoiceFlow.Acceptor
import com.example.flow.PayInvoiceFlow.Initiator
import com.example.state.InvoiceState
import net.corda.core.contracts.Command
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.ProgressTracker.Step
import net.corda.finance.POUNDS
import net.corda.finance.flows.CashPaymentReceiverFlow
import net.corda.finance.workflows.asset.CashUtils

/**
 * This flow allows two parties (the [Initiator] and the [Acceptor]) to come to an agreement about the IOU encapsulated
 * within an [InvoiceState].
 *
 * In our simple example, the [Acceptor] always accepts a valid IOU.
 *
 * These flows have deliberately been implemented by using only the call() method for ease of understanding. In
 * practice we would recommend splitting up the various stages of the flow into sub-routines.
 *
 * All methods called within the [FlowLogic] sub-class need to be annotated with the @Suspendable annotation.
 */
object PayInvoiceFlow {
    @InitiatingFlow
    @StartableByRPC
    class Initiator(private val invoice: InvoiceState,
                    private val otherParty: Party) : FlowLogic<SignedTransaction>() {
        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
        companion object {
            object GENERATING_TRANSACTION : Step("Generating transaction based on new hours submission.")
            object VERIFYING_TRANSACTION : Step("Verifying contract constraints.")
            object SIGNING_TRANSACTION : Step("Signing transaction with our private key.")
            object FINALISING_TRANSACTION : Step("Obtaining notary signature and recording transaction.") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }

            fun tracker() = ProgressTracker(
                    GENERATING_TRANSACTION,
                    VERIFYING_TRANSACTION,
                    SIGNING_TRANSACTION,
                    FINALISING_TRANSACTION
            )
        }

        override val progressTracker = tracker()

        /**
         * The flow logic is encapsulated within the call() method.
         */
        @Suspendable
        override fun call(): SignedTransaction {
            // Obtain a reference to the notary we want to use.
            val notary = serviceHub.networkMapCache.notaryIdentities[0]

            // Stage 2.
            progressTracker.currentStep = GENERATING_TRANSACTION
            // Generate an unsigned transaction.
            //val txCommand = Command(InvoiceContract.Pay(contractor, otherParty, rate.value), invoiceState.participants.map { it.owningKey })
            val paymentAmount = POUNDS(invoice.hoursWorked * invoice.rate)

            val txCommand = Command(InvoiceContract.Commands.Pay(), invoice.participants.map { it.owningKey })
            val txBuilder = TransactionBuilder(notary)
                    .addOutputState(invoice.copy(paid = true), InvoiceContract.ID)
                    .addCommand(txCommand)
            // Add our payment to the contractor
            CashUtils.generateSpend(serviceHub, txBuilder, paymentAmount, serviceHub.myInfo.legalIdentitiesAndCerts[0], otherParty)

            // Stage 3.
            progressTracker.currentStep = VERIFYING_TRANSACTION
            // Verify that the transaction is valid.
            txBuilder.verify(serviceHub)

            // Stage 4.
            progressTracker.currentStep = SIGNING_TRANSACTION
            // Sign the transaction.
            val signedTx = serviceHub.signInitialTransaction(txBuilder)

            progressTracker.currentStep = FINALISING_TRANSACTION
            // Notarise and record the transaction in all parties' vaults.
            return subFlow(FinalityFlow(signedTx, initiateFlow(otherParty)))
        }
    }

    @InitiatingFlow
    @InitiatedBy(Initiator::class)
    class Acceptor(val otherPartySession: FlowSession) : FlowLogic<SignedTransaction>() {
        lateinit var invoice : InvoiceState
        @Suspendable
        override fun call(): SignedTransaction {
            val signTransactionFlow = object : SignTransactionFlow(otherPartySession) {
                override fun checkTransaction(stx: SignedTransaction) = requireThat {
                    val output = stx.tx.outputs.single().data
                    "This must be an invoice transaction." using (output is InvoiceState)
                    invoice = output as InvoiceState
                    "Invoices with a value over 10 aren't accepted." using (invoice.hoursWorked <= 10)
                }
            }
            val txId = subFlow(signTransactionFlow).id

            subFlow(CashPaymentReceiverFlow(otherPartySession))

            return subFlow(ReceiveFinalityFlow(otherPartySession, expectedTxId = txId))
        }
    }
}
