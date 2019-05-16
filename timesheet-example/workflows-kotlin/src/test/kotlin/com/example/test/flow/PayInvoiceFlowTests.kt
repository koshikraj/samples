package com.example.test.flow

import com.example.flow.IssueInvoiceFlow
import com.example.flow.PayInvoiceFlow
import com.example.state.InvoiceState
import net.corda.core.contracts.TransactionVerificationException
import net.corda.core.identity.CordaX500Name
import net.corda.core.node.services.queryBy
import net.corda.core.utilities.getOrThrow
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNetworkParameters
import net.corda.testing.node.StartedMockNode
import net.corda.testing.node.TestCordapp
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PayInvoiceFlowTests {
    private lateinit var network: MockNetwork
    private lateinit var a: StartedMockNode
    private lateinit var b: StartedMockNode
    private lateinit var o: StartedMockNode
    private lateinit var today: LocalDate

    @Before
    fun setup() {
        network = MockNetwork(MockNetworkParameters(cordappsForAllNodes = listOf(
                TestCordapp.findCordapp("com.example.contract"),
                TestCordapp.findCordapp("com.example.flow")
        )))
        val oracleName = CordaX500Name("Oracle", "London","GB")
        a = network.createPartyNode()
        b = network.createPartyNode()
        o = network.createNode(oracleName)
        // For real nodes this happens automatically, but we have to manually register the flow for tests.
        listOf(a, b).forEach { it.registerInitiatedFlow(IssueInvoiceFlow.Acceptor::class.java) }
        network.runNetwork()
        today = LocalDate.now()
    }

    @After
    fun tearDown() {
        network.stopNodes()
    }

    @Test
    fun `flow rejects invalid invoices`() {
        val invoice = InvoiceState(today, -1, 10.0, a.info.singleIdentity(), b.info.singleIdentity(), o.info.singleIdentity())
        val flow = PayInvoiceFlow.Initiator(invoice, b.info.singleIdentity())
        val future = a.startFlow(flow)
        network.runNetwork()

        // The InvoiceContract specifies that hoursWorked cannot have negative values.
        assertFailsWith<TransactionVerificationException> { future.getOrThrow() }
    }

    @Test
    fun `SignedTransaction returned by the flow is signed by the initiator`() {
        val flow = IssueInvoiceFlow.Initiator(1, today, b.info.singleIdentity())
        val future = a.startFlow(flow)
        network.runNetwork()

        val signedTx = future.getOrThrow()
        signedTx.verifySignaturesExcept(b.info.singleIdentity().owningKey)
    }

    @Test
    fun `SignedTransaction returned by the flow is signed by the acceptor`() {
        val flow = IssueInvoiceFlow.Initiator(1, today, b.info.singleIdentity())
        val future = a.startFlow(flow)
        network.runNetwork()

        val signedTx = future.getOrThrow()
        signedTx.verifySignaturesExcept(a.info.singleIdentity().owningKey)
    }

    @Test
    fun `flow records a transaction in both parties' transaction storage`() {
        val flow = IssueInvoiceFlow.Initiator(1, today, b.info.singleIdentity())
        val future = a.startFlow(flow)
        network.runNetwork()
        val signedTx = future.getOrThrow()

        // We check the recorded transaction in both transaction storages.
        for (node in listOf(a, b)) {
            assertEquals(signedTx, node.services.validatedTransactions.getTransaction(signedTx.id))
        }
    }

    @Test
    fun `recorded transaction has no inputs and a single output, the input invoice`() {
        val invoiceValue = 1
        val flow = IssueInvoiceFlow.Initiator(invoiceValue, today, b.info.singleIdentity())
        val future = a.startFlow(flow)
        network.runNetwork()
        val signedTx = future.getOrThrow()

        // We check the recorded transaction in both vaults.
        for (node in listOf(a, b)) {
            val recordedTx = node.services.validatedTransactions.getTransaction(signedTx.id)
            val txOutputs = recordedTx!!.tx.outputs
            assert(txOutputs.size == 1)

            val recordedState = txOutputs[0].data as InvoiceState
            assertEquals(recordedState.hoursWorked, invoiceValue)
            assertEquals(recordedState.contractor, a.info.singleIdentity())
            assertEquals(recordedState.company, b.info.singleIdentity())
        }
    }

    @Test
    fun `flow records the correct invoice in both parties' vaults`() {
        val invoiceValue = 1
        val flow = IssueInvoiceFlow.Initiator(1, today, b.info.singleIdentity())
        val future = a.startFlow(flow)
        network.runNetwork()
        future.getOrThrow()

        // We check the recorded invoice in both vaults.
        for (node in listOf(a, b)) {
            node.transaction {
                val invoices = node.services.vaultService.queryBy<InvoiceState>().states
                assertEquals(1, invoices.size)
                val recordedState = invoices.single().state.data
                assertEquals(recordedState.hoursWorked, invoiceValue)
                assertEquals(recordedState.date, today)
                assertEquals(recordedState.contractor, a.info.singleIdentity())
                assertEquals(recordedState.company, b.info.singleIdentity())
            }
        }
    }
}