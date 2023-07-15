package net.corda.testing.driver.tests

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.r3.corda.demo.consensual.ConsensualDemoFlow
import com.r3.corda.demo.utxo.UtxoDemoFlow
import java.util.concurrent.TimeUnit.MINUTES
import net.corda.testing.driver.DriverNodes
import net.corda.testing.driver.runFlow
import net.corda.v5.base.types.MemberX500Name
import net.corda.v5.crypto.SecureHash
import net.corda.virtualnode.VirtualNodeInfo
import org.assertj.core.api.Assertions.fail
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.slf4j.LoggerFactory

@Suppress("JUnitMalformedDeclaration")
@Timeout(10, unit = MINUTES)
@TestInstance(PER_CLASS)
class MemoryTests {
    private val logger = LoggerFactory.getLogger(this::class.java)

    private val alice = MemberX500Name.parse("CN=Alice, OU=Testing, O=R3, L=London, C=GB")
    private val bob = MemberX500Name.parse("CN=Bob, OU=Testing, O=R3, L=San Francisco, C=US")
    private val charlie = MemberX500Name.parse("CN=Charlie, OU=Testing, O=R3, L=Paris, C=FR")
    private val lucy = MemberX500Name.parse("CN=Lucy, OU=Testing, O=R3, L=Rome, C=IT")
    private lateinit var consensualLedger: Map<MemberX500Name, VirtualNodeInfo>
    private lateinit var utxoLedger: Map<MemberX500Name, VirtualNodeInfo>
    private val jsonMapper = ObjectMapper().apply {
        registerModule(KotlinModule.Builder().build())

        val module = SimpleModule().apply {
            addSerializer(SecureHash::class.java, SecureHashSerializer)
            addDeserializer(SecureHash::class.java, SecureHashDeserializer)
        }
        registerModule(module)
    }

    @RegisterExtension
    private val driver = DriverNodes(alice, bob, charlie).withNotary(lucy, 1).forAllTests()

    @BeforeAll
    fun start() {
        driver.run { dsl ->
            dsl.startNodes(setOf(alice, bob, charlie)).forEach { vNode ->
                logger.info("VirtualNode({}): {}", vNode.holdingIdentity.x500Name, vNode)
            }
            consensualLedger = dsl.nodesFor("ledger-consensual-demo-app")
            utxoLedger = dsl.nodesFor("ledger-utxo-demo-app")
        }
        logger.info("{}, {} and {} started successfully", alice.commonName, bob.commonName, charlie.commonName)
    }

    @Test
    fun testForConsensualLedgerLeaks() {
        val aliceFlow = consensualLedger[alice] ?: fail("No Consensual Ledger node for Alice")

        driver.run { dsl ->
            dsl.dumpHeap("start")

            for (i in 0 until 100) {
                val inputResult = dsl.runFlow<ConsensualDemoFlow>(aliceFlow) {
                    val request = ConsensualDemoFlow.InputMessage("foo", listOf(alice.toString(), bob.toString()))
                    jsonMapper.writeValueAsString(request)
                } ?: fail("inputResult must not be null")
                logger.info("Consensual Demo={}", inputResult)

                if (i % 10 == 0) {
                    logger.info("Done {}", i)
                }

                if (i == 34 || i == 64 || i == 99) {
                    dsl.flushCaches()
                    dsl.dumpHeap("stage-$i")
                }
            }

            @Suppress("ExplicitGarbageCollectionCall")
            System.gc()
            Thread.sleep(1000)

            dsl.dumpHeap("finish")
        }
    }

    @Test
    fun testForUtxoLedgerLeaks() {
        val aliceFlow = utxoLedger[alice] ?: fail("No UTXO node for Alice")

        driver.run { dsl ->
            dsl.dumpHeap("start")

            for (i in 0 until 100) {
                val inputResult = dsl.runFlow<UtxoDemoFlow>(aliceFlow) {
                    val request = UtxoDemoFlow.InputMessage(
                        input = "test data",
                        members = listOf(bob.toString(), charlie.toString()),
                        notary = lucy.toString()
                    )
                    jsonMapper.writeValueAsString(request)
                } ?: fail("inputResult must not be null")
                logger.info("UTXO Demo={}", inputResult)

                if (i % 10 == 0) {
                    logger.info("Done {}", i)
                }

                if (i == 34 || i == 64 || i == 99) {
                    dsl.flushCaches()
                    dsl.dumpHeap("stage-$i")
                }
            }

            @Suppress("ExplicitGarbageCollectionCall")
            System.gc()
            Thread.sleep(1000)

            dsl.dumpHeap("finish")
        }
    }

//    @Test
//    fun testForSoakLeaks() {
//        val aliceFlow = virtualNodes.single { vnode ->
//            vnode.cpiIdentifier.name == "calculator-xml" && vnode.holdingIdentity.x500Name == alice
//        }
//        val bobFlow = virtualNodes.single { vnode ->
//            vnode.cpiIdentifier.name == "calculator-json" && vnode.holdingIdentity.x500Name == bob
//        }
//
//        dumpHeap("soak-start")
//        driver.run { dsl ->
//            for (i in 0 until 100) {
//                val jsonRequest = JsonInputMessage(i, i * 2)
//                val jsonResponse = dsl.runFlow<JsonCalculatorFlow>(bobFlow) {
//                    jsonMapper.writeValueAsString(jsonRequest)
//                }
//                logger.info("JSON>> {} -> {}", i, jsonResponse)
//
//                val xmlRequest = XmlInputMessage(i, i * 2)
//                val xmlResponse = dsl.runFlow<XmlCalculatorFlow>(aliceFlow) {
//                    xmlMapper.writeValueAsString(xmlRequest)
//                }
//                logger.info("XML>> {} -> {}", i, xmlResponse)
//
//                if (i % 10 == 0) {
//                    logger.info("Done {}", i)
//                }
//
//                if (i == 34 || i == 64 || i == 99) {
//                    dumpHeap("soak-stage-$i")
//                }
//            }
//        }
//
//        @Suppress("ExplicitGarbageCollectionCall")
//        System.gc()
//        Thread.sleep(1000)
//
//        dumpHeap("soak-finish")
//    }
}
