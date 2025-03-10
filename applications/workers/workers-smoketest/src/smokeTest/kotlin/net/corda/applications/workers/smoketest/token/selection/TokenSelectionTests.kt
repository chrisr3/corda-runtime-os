package net.corda.applications.workers.smoketest.token.selection

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.math.BigDecimal
import java.util.UUID
import net.corda.e2etest.utilities.DEFAULT_CLUSTER
import net.corda.e2etest.utilities.RPC_FLOW_STATUS_SUCCESS
import net.corda.e2etest.utilities.TEST_NOTARY_CPB_LOCATION
import net.corda.e2etest.utilities.TEST_NOTARY_CPI_NAME
import net.corda.e2etest.utilities.awaitRpcFlowFinished
import net.corda.e2etest.utilities.conditionallyUploadCordaPackage
import net.corda.e2etest.utilities.conditionallyUploadCpiSigningCertificate
import net.corda.e2etest.utilities.getHoldingIdShortHash
import net.corda.e2etest.utilities.getOrCreateVirtualNodeFor
import net.corda.e2etest.utilities.registerStaticMember
import net.corda.e2etest.utilities.startRpcFlow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS
import org.junit.jupiter.api.TestMethodOrder

@TestInstance(PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class TokenSelectionTests {

    private companion object {
        const val TEST_CPI_NAME = "ledger-utxo-demo-app"
        const val TEST_CPB_LOCATION = "/META-INF/ledger-utxo-demo-app.cpb"
        const val NOTARY_SERVICE_X500 = "O=MyNotaryService, L=London, C=GB"

        val testRunUniqueId = UUID.randomUUID().toString()
        val groupId = UUID.randomUUID().toString()
        val cpiName = "${TEST_CPI_NAME}_$testRunUniqueId"
        val notaryCpiName = "${TEST_NOTARY_CPI_NAME}_$testRunUniqueId"

        val aliceX500 = "CN=Alice-$testRunUniqueId, OU=Application, O=R3, L=London, C=GB"
        val bobX500 = "CN=Bob-$testRunUniqueId, OU=Application, O=R3, L=London, C=GB"
        val notaryX500 = "CN=Notary-$testRunUniqueId, OU=Application, O=R3, L=London, C=GB"

        val aliceHoldingId: String = getHoldingIdShortHash(aliceX500, groupId)
        val bobHoldingId: String = getHoldingIdShortHash(bobX500, groupId)
        val notaryHoldingId: String = getHoldingIdShortHash(notaryX500, groupId)

        val staticMemberList = listOf(
            aliceX500,
            bobX500,
            notaryX500
        )

        val objectMapper = ObjectMapper().apply {
            registerModule(KotlinModule.Builder().build())
        }
    }

    private fun convertToTokenBalanceQueryResponseMsg(tokenBalanceQueryResponseMsgStr: String) =
        objectMapper.readValue(
            tokenBalanceQueryResponseMsgStr,
            TokenBalanceQueryResponseMsg::class.java
        )

    private fun runTokenBalanceQueryFlow(): TokenBalanceQueryResponseMsg {

        val tokenBalanceQueryFlowName = "com.r3.corda.demo.utxo.token.selection.TokenBalanceQueryFlow"

        val tokenBalanceQueryRpcStartArgs = mapOf(
            "tokenType" to "com.r3.corda.demo.utxo.contract.CoinState",
            "issuerBankX500" to bobX500,
            "currency" to "USD"
        )

        val flowRequestId = startRpcFlow(aliceHoldingId, tokenBalanceQueryRpcStartArgs, tokenBalanceQueryFlowName)

        val flowResult = awaitRpcFlowFinished(aliceHoldingId, flowRequestId)
        assertThat(flowResult.flowStatus).isEqualTo(RPC_FLOW_STATUS_SUCCESS)

        return convertToTokenBalanceQueryResponseMsg(flowResult.flowResult!!)
    }

    @BeforeAll
    fun beforeAll() {
        DEFAULT_CLUSTER.conditionallyUploadCpiSigningCertificate()

        conditionallyUploadCordaPackage(
            cpiName,
            TEST_CPB_LOCATION,
            groupId,
            staticMemberList
        )
        conditionallyUploadCordaPackage(
            notaryCpiName,
            TEST_NOTARY_CPB_LOCATION,
            groupId,
            staticMemberList
        )

        val aliceActualHoldingId = getOrCreateVirtualNodeFor(aliceX500, cpiName)
        val bobActualHoldingId = getOrCreateVirtualNodeFor(bobX500, cpiName)
        val notaryActualHoldingId = getOrCreateVirtualNodeFor(notaryX500, notaryCpiName)

        assertThat(aliceActualHoldingId).isEqualTo(aliceHoldingId)
        assertThat(bobActualHoldingId).isEqualTo(bobHoldingId)
        assertThat(notaryActualHoldingId).isEqualTo(notaryHoldingId)

        registerStaticMember(aliceHoldingId)
        registerStaticMember(bobHoldingId)
        registerStaticMember(notaryHoldingId, NOTARY_SERVICE_X500)

        println("Alice: $aliceX500 - $aliceHoldingId")
        println("Alice: $bobX500 - $bobHoldingId")
        println("Alice: $notaryX500 - $notaryHoldingId")
    }

    @Test
    @Order(1)
    fun `ensure it is possible to send a balance query request and receive a response`() {
        // Start the flow that will send the request and receive the response
        val tokenBalanceQuery = runTokenBalanceQueryFlow()

        // Check that the balance of the token cache is zero since no token has been created
        assertThat(tokenBalanceQuery.availableBalance).isEqualTo(BigDecimal.ZERO)
        assertThat(tokenBalanceQuery.totalBalance).isEqualTo(BigDecimal.ZERO)
    }

    @Test
    @Order(2)
    fun `create a token then select it`(){
        // Create a simple UTXO transaction
        val input = "token test input"
        val utxoFlowRequestId = startRpcFlow(
            aliceHoldingId,
            mapOf("input" to input, "members" to listOf(bobX500), "notary" to NOTARY_SERVICE_X500),
            "com.r3.corda.demo.utxo.UtxoDemoFlow"
        )
        val utxoFlowResult = awaitRpcFlowFinished(aliceHoldingId, utxoFlowRequestId)
        assertThat(utxoFlowResult.flowStatus).isEqualTo(RPC_FLOW_STATUS_SUCCESS)
        assertThat(utxoFlowResult.flowError).isNull()

        // Attempt to select the token created by the transaction
        val tokenSelectionFlowId = startRpcFlow(
            bobHoldingId,
            mapOf(),
            "com.r3.corda.demo.utxo.token.selection.TokenSelectionFlow"
        )
        val tokenSelectionResult = awaitRpcFlowFinished(bobHoldingId, tokenSelectionFlowId)
        assertThat(tokenSelectionResult.flowStatus).isEqualTo(RPC_FLOW_STATUS_SUCCESS)
        assertThat(tokenSelectionResult.flowError).isNull()
        assertThat(tokenSelectionResult.flowResult).isEqualTo("1")
    }
}

private data class TokenBalanceQueryResponseMsg(
    val availableBalance: BigDecimal,
    val totalBalance: BigDecimal
)
