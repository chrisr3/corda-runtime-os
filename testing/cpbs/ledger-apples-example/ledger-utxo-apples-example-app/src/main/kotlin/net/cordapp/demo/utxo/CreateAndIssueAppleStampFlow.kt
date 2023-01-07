package net.cordapp.demo.utxo

import net.corda.v5.application.flows.CordaInject
import net.corda.v5.application.flows.InitiatingFlow
import net.corda.v5.application.flows.RPCRequestData
import net.corda.v5.application.flows.RPCStartableFlow
import net.corda.v5.application.flows.getRequestBodyAs
import net.corda.v5.application.marshalling.JsonMarshallingService
import net.corda.v5.application.membership.MemberLookup
import net.corda.v5.application.messaging.FlowMessaging
import net.corda.v5.base.annotations.Suspendable
import net.corda.v5.base.util.loggerFor
import net.corda.v5.ledger.common.NotaryLookup
import net.corda.v5.ledger.common.Party
import net.corda.v5.ledger.utxo.UtxoLedgerService
import net.cordapp.demo.utxo.contract.AppleStamp
import net.cordapp.demo.utxo.contract.AppleStampContract
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@InitiatingFlow(protocol = "create-and-issue-apple-stamp")
class CreateAndIssueAppleStampFlow : RPCStartableFlow {

    private companion object {
        val log = loggerFor<CreateAndIssueAppleStampFlow>()
    }

    @CordaInject
    lateinit var flowMessaging: FlowMessaging

    @CordaInject
    lateinit var jsonMarshallingService: JsonMarshallingService

    @CordaInject
    lateinit var memberLookup: MemberLookup

    @CordaInject
    lateinit var notaryLookup: NotaryLookup

    @CordaInject
    lateinit var utxoLedgerService: UtxoLedgerService

    @Suspendable
    override fun call(requestBody: RPCRequestData): String {
        val request = requestBody.getRequestBodyAs<CreateAndIssueAppleStampRequest>(jsonMarshallingService)
        val stampDescription = request.stampDescription
        val holderName = request.holder

        // Retrieve the notaries public key (this will change)
        val notary = notaryLookup.notaryServices.single()
        val notaryKey = memberLookup.lookup().single {
            it.memberProvidedContext["corda.notary.service.name"] == notary.name.toString()
        }.ledgerKeys.first()

        val issuer = memberLookup.myInfo().let { Party(it.name, it.ledgerKeys.first()) }
        val holder = requireNotNull(memberLookup.lookup(holderName)?.let { Party(it.name, it.ledgerKeys.first()) }) {
            "The holder does not exist within the network"
        }

        // Building the output AppleStamp state
        val newStamp = AppleStamp(
            id = UUID.randomUUID(),
            stampDesc = stampDescription,
            issuer = issuer,
            holder = holder,
            participants = listOf(issuer.owningKey, holder.owningKey)
        )

        // Create the transaction
        @Suppress("DEPRECATION")
        val transaction = utxoLedgerService.getTransactionBuilder()
            .setNotary(Party(notary.name, notaryKey))
            .addOutputState(newStamp)
            .addCommand(AppleStampContract.Commands.Issue())
            .setTimeWindowUntil(Instant.now().plus(1, ChronoUnit.DAYS))
            .addSignatories(listOf(issuer.owningKey, holder.owningKey))
            .toSignedTransaction(issuer.owningKey)

        val session = flowMessaging.initiateFlow(holderName)

        return try {
            // Send the transaction and state to the counterparty and let them sign it
            // Then notarise and record the transaction in both parties' vaults.
            utxoLedgerService.finalize(transaction, listOf(session))
            log.info("Flow Finished ID = ${newStamp.id}")
            newStamp.id.toString()
        } catch (e: Exception) {
            log.warn("Flow failed", e)
            "Flow failed, message: ${e.message}"
        }
    }
}