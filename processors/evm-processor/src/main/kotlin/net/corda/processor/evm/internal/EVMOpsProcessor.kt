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

        // Make this cleaner using SECONDS
        private const val retryDelayMs = 1000L // 1 second

        // Use the mapping to set retries and retryDelayMS
        private val evmConnector = EthereumConnector(EvmRPCCall(OkHttpClient()))
        private val fixedThreadPool = Executors.newFixedThreadPool(20)

        // Make a mapping to a textual description
        // Have it as a map
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
        log.info(request.schema.toString(true))

        dispatcher[request.payload::class]
            ?.dispatch(request).apply {
                respFuture.complete(this)
            }
            ?: {
                val errorMessage = "Unregistered EVM operation: ${request.payload.javaClass}"
                log.error (errorMessage)
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

    // Discuss whether the use of corda runtime exceptions is appropriate, or use something that inherits from them
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



///**
// * Retrieves the balance of a given Ethereum address using the provided RPC connection.
// *
// * @param rpcConnection The RPC connection URL for Ethereum communication.
// * @param from The Ethereum address for which to retrieve the balance.
// * @return The balance of the specified Ethereum address as a string.
// */
//private fun getBalance(rpcConnection: String, from: String): String {
//    // Send an RPC request to retrieve the balance of the specified address.
//    val resp = EVMOpsProcessor.evmConnector.send(rpcConnection, "eth_getBalance", listOf(from, "latest"))
//
//    // Return the balance as a string.
//    return resp.result.toString()
//}
//
//
///**
// * Get the transaction receipt details from the Ethereum node.
// *
// * @param rpcConnection The URL of the Ethereum RPC endpoint.
// * @param receipt The receipt of the transaction.
// * @return The JSON representation of the transaction receipt, or null if not found.
// */
//private fun getTransactionReceipt(rpcConnection: String, receipt: String): String {
//    val resp = EVMOpsProcessor.evmConnector.send(rpcConnection, "eth_getTransactionReceipt", listOf(receipt), true)
//    return resp.result.toString()
//}
//
//
///**
// * Retrieves transaction details for a given Ethereum transaction hash using the provided RPC connection.
// *
// * @param rpcConnection The RPC connection URL for Ethereum communication.
// * @param hash The transaction hash for which to retrieve the details.
// * @return The transaction details as a string.
// */
//private fun getTransactionByHash(rpcConnection: String, hash: String): String {
//    // Send an RPC request to retrieve transaction details for the specified hash.
//    val resp = EVMOpsProcessor.evmConnector.send(rpcConnection, "eth_getTransactionByHash", listOf(hash))
//    return resp.result.toString()
//}
//
//
///**
// * Get the Chain ID from the Ethereum node.
// *
// * @param rpcConnection The URL of the Ethereum RPC endpoint.
// * @return The chain Id as a Long.
// */
//private fun getChainId(rpcConnection: String): Long {
//    val resp = EVMOpsProcessor.evmConnector.send(rpcConnection, "eth_chainId", emptyList<String>())
//    println("CHAIN ID = ${Numeric.toBigInt(resp.result.toString())}")
//    return Numeric.toBigInt(resp.result.toString()).toLong()
//}
//
///**
// * Get the set Gas Price from the Ethereum node.
// *
// * @param rpcConnection The URL of the Ethereum RPC endpoint.
// * @return The Gas Price as a BigInteger.
// */
//private fun getGasPrice(rpcConnection: String): BigInteger {
//    val resp = EVMOpsProcessor.evmConnector.send(rpcConnection, "eth_gasPrice", emptyList<String>())
//    return BigInteger.valueOf(Integer.decode(resp.result.toString()).toLong())
//}
//
//
///**
// * Get the set syncing status from the Ethereum node.
// *
// * @param rpcConnection The URL of the Ethereum RPC endpoint.
// * @return The Gas Price as a BigInteger.
// */
//private fun isSyncing(rpcConnection: String): String {
//    val resp = EVMOpsProcessor.evmConnector.send(rpcConnection, "eth_syncing", emptyList<String>())
//    return resp.result.toString()
//}
//
//
///**
// * Retrieves the maximum priority fee per gas using the provided RPC connection.
// *
// * @param rpcConnection The RPC connection URL for Ethereum communication.
// * @return The maximum priority fee per gas as a BigInteger.
// */
//private fun maxPriorityFeePerGas(rpcConnection: String): BigInteger {
//    // Send an RPC request to retrieve the maximum priority fee per gas.
//    val resp = EVMOpsProcessor.evmConnector.send(rpcConnection, "eth_maxPriorityFeePerGas", emptyList<String>())
//
//    // Return the maximum priority fee per gas as a BigInteger.
//    return BigInteger.valueOf(Integer.decode(resp.result.toString()).toLong())
//}
//
///**
// * Retrieves the code at a specific Ethereum address using the provided RPC connection.
// *
// * @param rpcConnection The RPC connection URL for Ethereum communication.
// * @param address The Ethereum address for which to retrieve the code.
// * @param blockNumber The block number at which to retrieve the code.
// * @return The code at the specified Ethereum address and block number as a string.
// */
//private fun getCode(rpcConnection: String, address: String, blockNumber: String): String {
//    // Send an RPC request to retrieve the code at the specified address and block number.
//    val resp = EVMOpsProcessor.evmConnector.send(
//        rpcConnection,
//        "eth_getCode",
//        listOf(address, Numeric.toHexStringWithPrefix(BigInteger.valueOf(blockNumber.toLong())))
//    )
//
//    // Return the code as a string.
//    return resp.result.toString()
//}
//
//
///**
// * Estimate the Gas price for a transaction.
// *
// * @param rpcConnection The URL of the Ethereum RPC endpoint.
// * @param from The sender's address.
// * @param to The recipient's address.
// * @param payload The payload data for the transaction.
// * @return The estimated Gas price as a BigInteger.
// */
//private fun estimateGas(rpcConnection: String, from: String, to: String, payload: String): BigInteger {
//    val rootObject = JsonNodeFactory.instance.objectNode()
//
//    rootObject.put("to", to.ifEmpty { from })
//    rootObject.put("data", payload)
//    rootObject.put("input", payload)
//    rootObject.put("from", from)
//
//    val resp = EVMOpsProcessor.evmConnector.send(rpcConnection, "eth_estimateGas", listOf(rootObject, "latest"))
//    return BigInteger.valueOf(Integer.decode(resp.result.toString()).toLong())
//}
//
//
///**
// * Fetch the block of an ethereum ledger by number.
// *
// * @param rpcUrl The URL of the Ethereum RPC endpoint.
// * @param number The number of the block being fetched
// * @param includeTransaction Whether to return the transaaction hashes included in the block
// * @return The transaction count as a BigInteger.
// */
//private fun getBlockByNumber(rpcUrl: String, number: String, includeTransaction: Boolean): String {
//    val block = EVMOpsProcessor.evmConnector.send(
//        rpcUrl,
//        "eth_getBlockByNumber",
//        listOf(number, includeTransaction.toString())
//    )
//    return block.result.toString()
//}
//
//
///**
// * Fetch the number of transactions made by an address.
// *
// * @param rpcUrl The URL of the Ethereum RPC endpoint.
// * @param address The address for which to fetch the transaction count.
// * @return The transaction count as a BigInteger.
// */
//private fun getTransactionCount(rpcUrl: String, address: String): BigInteger {
//    val transactionCountResponse = EVMOpsProcessor.evmConnector.send(
//        rpcUrl,
//        "eth_getTransactionCount",
//        listOf(address, "latest")
//    )
//    return BigInteger.valueOf(Integer.decode(transactionCountResponse.result.toString()).toLong())
//}
//
///**
// * Query the completion status of a contract using the Ethereum node.
// *
// * @param rpcConnection The URL of the Ethereum RPC endpoint.
// * @param transactionHash The hash of the transaction to query.
// * @return The JSON representation of the transaction receipt.
// */
//private fun queryCompletionContract(rpcConnection: String, transactionHash: String): String {
//    val resp = EVMOpsProcessor.evmConnector.send(rpcConnection, "eth_getTransactionReceipt", listOf(transactionHash), true)
//    return resp.result.toString()
//}
//
//
///**
// * Fetches the information that needs to be gathered to send out a transaction for a given address
// *
// * @param rpcConnection The URL of the Ethereum RPC endpoint.
// * @param transactionHash The hash of the transaction to query.
// * @return The JSON representation of the transaction receipt.
// */
//private suspend fun prepareTransaction(rpcConnection: String, from: String): Pair<BigInteger, Long> =
//    coroutineScope {
//        val nonceDeferred = async { getTransactionCount(rpcConnection, from) }
//        val chainIdDeferred = async { getChainId(rpcConnection) }
//        val nonce = nonceDeferred.await()
//        val chainId = chainIdDeferred.await()
//
//        nonce to chainId
//    }
//
//
///**
// * Send a transaction to the Ethereum network.
// *
// * @param rpcConnection The URL of the Ethereum RPC endpoint.
// * @param contractAddress The address of the smart contract.
// * @param payload The payload data for the transaction.
// * @return The receipt of the transaction.
// */
//private suspend fun sendTransaction(
//    rpcConnection: String,
//    from: String,
//    contractAddress: String,
//    payload: String
//): String {
//    // Do this async
//    val (nonce, chainId) = prepareTransaction(rpcConnection, from)
//
//    // Will be replaced with getting the latest blocka
//    val blockByNumber = getBlockByNumber(rpcConnection, "latest", false)
//    println("BN $blockByNumber")
//
//    // correctly estimate the gas fee
//    val transaction = RawTransaction.createTransaction(
//        chainId,
//        nonce,
//        BigInteger.valueOf(Numeric.toBigInt("0x47b760").toLong()),
//        contractAddress,
//        BigInteger.valueOf(0),
//        payload,
//        BigInteger.valueOf(0),
//        BigInteger.valueOf(51581475500)
//    )
//
//    val signer = Credentials.create("0x8f2a55949038a9610f50fb23b5883af3b4ecb3c3bb792cbcefbd1542c692be63")
//
//    val signed = TxSignServiceImpl(signer).sign(transaction, chainId)
//    println("Passed Signing")
//    println(Numeric.toHexString(signed))
//    val tReceipt =
//        EVMOpsProcessor.evmConnector.send(rpcConnection, "eth_sendRawTransaction", listOf(Numeric.toHexString(signed)))
//    // Exception Case When Contract is Being Created we need to wait the address
//    return if (contractAddress.isEmpty()) {
//        queryCompletionContract(rpcConnection, tReceipt.result.toString())
//    } else {
//        tReceipt.result.toString()
//    }
//}
//
///**
// * Make a smart contract call to the Ethereum network.
// *
// * @param rpcConnection The URL of the Ethereum RPC endpoint.
// * @param contractAddress The address of the smart contract.
// * @param payload The payload data for the contract call.
// * @return The result of the contract call.
// */
//private fun sendCall(rpcConnection: String, contractAddress: String, payload: String): String {
//    val rootObject = JsonNodeFactory.instance.objectNode()
//    rootObject.put("to", contractAddress)
//    rootObject.put("data", payload)
//    rootObject.put("input", payload)
//    val resp = EVMOpsProcessor.evmConnector.send(rpcConnection, "eth_call", listOf(rootObject, "latest"))
//    return resp.result.toString()
//}
