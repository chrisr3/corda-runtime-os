package net.corda.processor.evm.internal

import net.corda.data.interop.evm.EvmRequest
import net.corda.data.interop.evm.request.SendRawTransaction
import net.corda.data.interop.evm.EvmResponse
import net.corda.messaging.api.processor.RPCResponderProcessor
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import net.corda.interop.web3j.internal.EthereumConnector
import java.util.concurrent.Executors
import net.corda.data.interop.evm.request.Call
import net.corda.data.interop.evm.request.ChainId
import net.corda.data.interop.evm.request.EstimateGas
import net.corda.data.interop.evm.request.GasPrice
import net.corda.data.interop.evm.request.GetBalance
import net.corda.data.interop.evm.request.GetCode
import net.corda.data.interop.evm.request.GetTransactionByHash
import net.corda.data.interop.evm.request.GetTransactionReceipt
import net.corda.data.interop.evm.request.Syncing
import net.corda.interop.web3j.internal.EVMErrorException
import java.util.concurrent.TimeUnit
import net.corda.interop.web3j.DispatcherFactory
import net.corda.interop.web3j.EvmDispatcher
import net.corda.interop.web3j.internal.EvmRPCCall
import net.corda.v5.base.exceptions.CordaRuntimeException
import okhttp3.OkHttpClient
import org.slf4j.Logger
import kotlin.reflect.KClass


/**
 * EVMOpsProcessor is an implementation of the RPCResponderProcessor for handling Ethereum Virtual Machine (EVM) requests.
 * It allows executing smart contract calls and sending transactions on an Ethereum network.
 */
class EVMOpsProcessor
    (factory: DispatcherFactory) : RPCResponderProcessor<EvmRequest, EvmResponse> {

    private var dispatcher: Map<KClass<*>, EvmDispatcher>

    private companion object {
        val log: Logger = LoggerFactory.getLogger(this::class.java.enclosingClass)
        private const val maxRetries = 3
        private const val retryDelayMs = 1000L // 1 second
        private val fixedThreadPool = Executors.newFixedThreadPool(20)
        private val transientEthereumErrorCodes = listOf(
            -32000, -32005, -32010, -32016, -32002,
            -32003, -32004, -32007, -32008, -32009,
            -32011, -32012, -32014, -32015, -32019,
            -32020, -32021
        )
    }


    init {
        val evmConnector = EthereumConnector(EvmRPCCall(OkHttpClient()))
        dispatcher = mapOf<KClass<*>, EvmDispatcher>(
            GetBalance::class to factory.balanceDispatcher(evmConnector),
            Call::class to factory.callDispatcher(evmConnector),
            ChainId::class to factory.chainIdDispatcher(evmConnector),
            EstimateGas::class to factory.estimateGasDispatcher(evmConnector),
            GasPrice::class to factory.gasPriceDispatcher(evmConnector),
            GetBalance::class to factory.getBalanceDispatcher(evmConnector),
            GetCode::class to factory.getCodeDispatcher(evmConnector),
            GetTransactionByHash::class to factory.getTransactionByHashDispatcher(evmConnector),
            GetTransactionReceipt::class to factory.getTransactionByReceiptDispatcher(evmConnector),
            SendRawTransaction::class to factory.sendRawTransactionDispatcher(evmConnector),
            Syncing::class to factory.isSyncingDispatcher(evmConnector)
        )
    }


    private fun handleRequest(request: EvmRequest, respFuture: CompletableFuture<EvmResponse>) {

        dispatcher[request.payload::class]
            ?.dispatch(request).apply {
                respFuture.complete(this)
            }
            ?: {
                val errorMessage = "Unregistered EVM operation: ${request.payload.javaClass}"
                throw CordaRuntimeException (errorMessage)
            }
    }



    /**
     * The Retry Policy is responsibly for retrying an ethereum call, given that the ethereum error is transient
     *
     * @param maxRetries The maximum amount of retires allowed for a given error.
     * @param delayMs The Ethereum address for which to retrieve the balance.
     * @return The balance of the specified Ethereum address as a string.
     */
    inner class RetryPolicy(private val maxRetries: Int, private val delayMs: Long) {
        fun execute(action: () -> Unit) {
            var retries = 0
            while (retries <= maxRetries) {
                try {
                    return action()
                } catch (e: EVMErrorException) {
                    if (e.errorResponse.error.code in transientEthereumErrorCodes) {
                        retries++
                        log.warn(e.message)
                        if (retries <= maxRetries) {
                            // Suspend and Wakeup with the threadpool
                            TimeUnit.MILLISECONDS.sleep(delayMs)
                        } else {
                            throw CordaRuntimeException(e.message)
                        }
                    } else {
                        throw CordaRuntimeException(e.message)
                    }
                }
            }
        }
    }


    override fun onNext(request: EvmRequest, respFuture: CompletableFuture<EvmResponse>) {
        val retryPolicy = RetryPolicy(maxRetries, retryDelayMs)

        fixedThreadPool.submit {
            try {
                retryPolicy.execute {
                    handleRequest(request, respFuture)
                }
            } catch (e: Exception) {
                respFuture.completeExceptionally(e)
            }
        }

    }


}

