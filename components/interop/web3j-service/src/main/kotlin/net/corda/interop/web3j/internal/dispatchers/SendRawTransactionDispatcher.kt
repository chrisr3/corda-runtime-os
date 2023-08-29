package net.corda.interop.web3j.internal.dispatchers

import net.corda.data.interop.evm.EvmRequest
import net.corda.data.interop.evm.EvmResponse
import net.corda.data.interop.evm.request.SendRawTransaction
import net.corda.interop.web3j.EvmDispatcher
import net.corda.interop.web3j.internal.EthereumConnector
import org.web3j.crypto.Credentials
import org.web3j.crypto.RawTransaction
import org.web3j.service.TxSignServiceImpl
import org.web3j.utils.Numeric
import java.math.BigInteger

/**
 * Dispatcher used to send transaction.
 *
 * @param evmConnector The evmConnector class used to make rpc calls to the node
 */
class SendRawTransactionDispatcher(val evmConnector: EthereumConnector) : EvmDispatcher {

    private val regularMaxFeePerGas = BigInteger.valueOf(515814755000)


    // This is used in absence of the crypto worker being able to sign these transactions for use
    private val temporaryPrivateKey = "0x8f2a55949038a9610f50fb23b5883af3b4ecb3c3bb792cbcefbd1542c692be63"

    // This will be overridden in the config
    private val genericGasLimit = "0x47b760"


    /**
     * Query the completion status of a contract using the Ethereum node.
     *
     * @param rpcConnection The URL of the Ethereum RPC endpoint.
     * @param transactionHash The hash of the transaction to query.
     * @return The JSON representation of the transaction receipt.
     */
    private fun queryCompletionContract(rpcConnection: String, transactionHash: String): String {
        val resp = evmConnector.send(rpcConnection, "eth_getTransactionReceipt", listOf(transactionHash))
        return resp.result.toString()
    }


    override fun dispatch(evmRequest: EvmRequest): EvmResponse {
        val sentTransaction = evmRequest.payload as SendRawTransaction
        val transactionCountResponse = evmConnector.send(
            evmRequest.rpcUrl, "eth_getTransactionCount", listOf(evmRequest.from, "latest")
        )
        val nonce = BigInteger.valueOf(Integer.decode(transactionCountResponse.result.toString()).toLong())

        val chainId = evmConnector.send(evmRequest.rpcUrl, "eth_chainId", emptyList<String>())
        val parsedChainId = Numeric.toBigInt(chainId.result.toString()).toLong()

        val gasPrice = evmConnector.send(evmRequest.rpcUrl, "eth_gasPrice", emptyList<String>())
        val maxPriorityFeePerGas =
            47000 * Integer.decode(gasPrice.result.toString()) * sentTransaction.payload.toByteArray().size

        val transaction = RawTransaction.createTransaction(
            parsedChainId,
            nonce,
            BigInteger.valueOf(Numeric.toBigInt(genericGasLimit).toLong()),
            evmRequest.to,
            BigInteger.valueOf(0),
            sentTransaction.payload,
            BigInteger.valueOf(maxPriorityFeePerGas.toLong()),
            regularMaxFeePerGas
        )

        val signer = Credentials.create(temporaryPrivateKey)
        val signed = TxSignServiceImpl(signer).sign(transaction, parsedChainId)
        val tReceipt =
            evmConnector.send(evmRequest.rpcUrl, "eth_sendRawTransaction", listOf(Numeric.toHexString(signed)))
        println("tReceipt $tReceipt")
        // Exception Case When Contract is Being Created we need to wait the address
        return if (evmRequest.to.isEmpty()) {
            println("Minting transaction")
            EvmResponse(evmRequest.flowId, queryCompletionContract(evmRequest.rpcUrl, tReceipt.result.toString()))
        } else {
            EvmResponse(evmRequest.flowId, tReceipt.result.toString())
        }
    }
}