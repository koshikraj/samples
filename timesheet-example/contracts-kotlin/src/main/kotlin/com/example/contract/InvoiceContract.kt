package com.example.contract

import com.example.state.InvoiceState
import net.corda.core.contracts.*
import net.corda.core.transactions.LedgerTransaction

/**
 * A implementation of a basic smart contract in Corda.
 *
 * This contract enforces rules regarding the creation of a valid [InvoiceState], which in turn encapsulates an [InvoiceState].
 *
 * For a new [InvoiceState] to be issued onto the ledger, a transaction is required which takes:
 * - Zero input states.
 * - One output state: the new [InvoiceState].
 * - An Create() command with the public keys of both the lender and the borrower.
 *
 * All contracts must sub-class the [Contract] interface.
 */
class InvoiceContract : Contract {
    companion object {
        @JvmStatic
        val ID = "com.example.contract.InvoiceContract"
    }

    class Create(val contractor: Int, val company: Int, val rate: Double) : CommandData

    /**
     * The verify() function of all the states' contracts must not throw an exception for a transaction to be
     * considered valid.
     */
    override fun verify(tx: LedgerTransaction) {
        val command = tx.commands.requireSingleCommand<Create>()
        requireThat {
            // Generic constraints around the invoice transaction.
            "No inputs should be consumed when issuing an IOU." using (tx.inputs.isEmpty())
            "Only one output state should be created." using (tx.outputs.size == 1)
            val out = tx.outputsOfType<InvoiceState>().single()
            "The lender and the borrower cannot be the same entity." using (out.contractor != out.company)
            "All of the participants must be signers." using (command.signers.containsAll(out.participants.map { it.owningKey }))

            // Invoice-specific constraints.
            "The Invoice's value must be non-negative." using (out.hoursWorked > 0)
            //"The contractor ${out.contractor} must be contracted to work with ${out.company}" using (rateOracle.query(out.contractor))
        }
    }
}
