package net.cordapp.demo.utxo

import net.corda.v5.application.flows.CordaInject
import net.corda.v5.application.flows.RPCRequestData
import net.corda.v5.application.flows.RPCStartableFlow
import net.corda.v5.application.flows.getRequestBodyAs
import net.corda.v5.application.marshalling.JsonMarshallingService
import net.corda.v5.application.membership.MemberLookup
import net.corda.v5.base.annotations.Suspendable
import net.corda.v5.base.util.loggerFor
import net.corda.v5.ledger.common.NotaryLookup
import net.corda.v5.ledger.common.Party
import net.corda.v5.ledger.utxo.UtxoLedgerService
import net.cordapp.demo.utxo.contract.BasketOfApples
import net.cordapp.demo.utxo.contract.BasketOfApplesContract
import java.time.Instant
import java.time.temporal.ChronoUnit

class PackApplesFlow : RPCStartableFlow {

    private companion object {
        val log = loggerFor<PackApplesFlow>()
    }

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
        val request = requestBody.getRequestBodyAs<PackApplesRequest>(jsonMarshallingService)
        val appleDescription = request.appleDescription
        val weight = request.weight

        // Retrieve the notaries public key (this will change)
        val notary = notaryLookup.notaryServices.single()
        val notaryKey = memberLookup.lookup().single {
            it.memberProvidedContext["corda.notary.service.name"] == notary.name.toString()
        }.ledgerKeys.first()

        val myInfo = memberLookup.myInfo()
        val ourIdentity = Party(myInfo.name, myInfo.ledgerKeys.first())

        // Building the output BasketOfApples state
        val basket = BasketOfApples(
            description = appleDescription,
            farm = ourIdentity,
            owner = ourIdentity,
            weight = weight,
            participants = listOf(ourIdentity.owningKey)
        )

        // Create the transaction
        @Suppress("DEPRECATION")
        val transaction = utxoLedgerService.getTransactionBuilder()
            .setNotary(Party(notary.name, notaryKey))
            .addOutputState(basket)
            .addCommand(BasketOfApplesContract.Commands.PackBasket())
            .setTimeWindowUntil(Instant.now().plus(1, ChronoUnit.DAYS))
            .addSignatories(listOf(ourIdentity.owningKey))
            .toSignedTransaction(ourIdentity.owningKey)

        return try {
            // Record the transaction, no sessions are passed in as the transaction is only being
            // recorded locally
            utxoLedgerService.finalize(transaction, emptyList()).toString()
        } catch (e: Exception) {
            log.warn("Flow failed", e)
            "Flow failed, message: ${e.message}"
        }
    }
}