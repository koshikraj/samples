package com.example.test.contract

import com.example.contract.InvoiceContract
import com.example.state.InvoiceState
import net.corda.core.identity.CordaX500Name
import net.corda.testing.core.TestIdentity
import net.corda.testing.node.MockServices
import net.corda.testing.node.ledger
import org.junit.Test

class InvoiceContractTests {
    private val ledgerServices = MockServices(listOf("com.example.contract", "com.example.flow"))
    private val megaCorp = TestIdentity(CordaX500Name("MegaCorp", "London", "GB"))
    private val contractor = TestIdentity(CordaX500Name("Contractor", "New York", "US"))
    private val date = 20190513
    private val invoiceValue = 1

    @Test
    fun `transaction must include Create command`() {
        ledgerServices.ledger {
            transaction {
                output(InvoiceContract.ID, InvoiceState(invoiceValue, date, contractor.party, megaCorp.party))
                fails()
                command(listOf(megaCorp.publicKey, contractor.publicKey), InvoiceContract.Commands.Create())
                verifies()
            }
        }
    }

    @Test
    fun `transaction must have no inputs`() {
        ledgerServices.ledger {
            transaction {
                input(InvoiceContract.ID, InvoiceState(invoiceValue, date, contractor.party, megaCorp.party))
                output(InvoiceContract.ID, InvoiceState(invoiceValue, date, contractor.party, megaCorp.party))
                command(listOf(megaCorp.publicKey, contractor.publicKey), InvoiceContract.Commands.Create())
                `fails with`("No inputs should be consumed when issuing an invoice.")
            }
        }
    }

    @Test
    fun `transaction must have one output`() {
        ledgerServices.ledger {
            transaction {
                output(InvoiceContract.ID, InvoiceState(invoiceValue, date, contractor.party, megaCorp.party))
                output(InvoiceContract.ID, InvoiceState(invoiceValue, date, contractor.party, megaCorp.party))
                command(listOf(megaCorp.publicKey, contractor.publicKey), InvoiceContract.Commands.Create())
                `fails with`("Only one output state should be created.")
            }
        }
    }

    @Test
    fun `contractor must sign transaction`() {
        ledgerServices.ledger {
            transaction {
                output(InvoiceContract.ID, InvoiceState(invoiceValue, date, contractor.party, megaCorp.party))
                command(contractor.publicKey, InvoiceContract.Commands.Create())
                `fails with`("All of the participants must be signers.")
            }
        }
    }

    @Test
    fun `company must sign transaction`() {
        ledgerServices.ledger {
            transaction {
                output(InvoiceContract.ID, InvoiceState(invoiceValue, date, contractor.party, megaCorp.party))
                command(megaCorp.publicKey, InvoiceContract.Commands.Create())
                `fails with`("All of the participants must be signers.")
            }
        }
    }

    @Test
    fun `contractor is not company`() {
        ledgerServices.ledger {
            transaction {
                output(InvoiceContract.ID, InvoiceState(invoiceValue, date, megaCorp.party, megaCorp.party))
                command(listOf(megaCorp.publicKey, contractor.publicKey), InvoiceContract.Commands.Create())
                `fails with`("The lender and the borrower cannot be the same entity.")
            }
        }
    }

    @Test
    fun `cannot create negative-value Invoices`() {
        ledgerServices.ledger {
            transaction {
                output(InvoiceContract.ID, InvoiceState(-1, date, contractor.party, megaCorp.party))
                command(listOf(megaCorp.publicKey, contractor.publicKey), InvoiceContract.Commands.Create())
                `fails with`("The Invoice's value must be non-negative.")
            }
        }
    }
}