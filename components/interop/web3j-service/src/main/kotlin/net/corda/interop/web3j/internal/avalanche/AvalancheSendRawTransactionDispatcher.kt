package net.corda.interop.web3j.internal.avalanche

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

class AvalancheSendRawTransactionDispatcher(val evmConnector: EthereumConnector) : EvmDispatcher {

    private val avalanceGasLimit = BigInteger.valueOf(Numeric.toBigInt("0x47b760").toLong())
    private val avalancheMaxPriorityFeePerGas = BigInteger.valueOf(0)
    private val avalancheMaxFeePerGas = BigInteger.valueOf(515814755000)
    /**
     * Query the completion status of a contract using the Ethereum node.
     *
     * @param rpcConnection The URL of the Ethereum RPC endpoint.
     * @param transactionHash The hash of the transaction to query.
     * @return The JSON representation of the transaction receipt.
     */
    private fun queryCompletionContract(rpcConnection: String, transactionHash: String): String {
        val resp = evmConnector.send(rpcConnection, "eth_getTransactionReceipt", listOf(transactionHash), true)
        return resp.result.toString()
    }


    override fun dispatch(evmRequest: EvmRequest): EvmResponse {
        // Send an RPC request to retrieve the maximum priority fee per gas.
        val sentTransaction = evmRequest.payload as SendRawTransaction
        val transactionCountResponse = evmConnector.send(
            evmRequest.rpcUrl,
            "eth_getTransactionCount",
            listOf(evmRequest.from, "latest")
        )
        val nonce = BigInteger.valueOf(Integer.decode(transactionCountResponse.result.toString()).toLong())

        val chainId = evmConnector.send(evmRequest.rpcUrl, "eth_chainId", emptyList<String>())
        val parsedChainId = Numeric.toBigInt(chainId.result.toString()).toLong()

//        val maxPriorityFeePerGas = evmConnector.send(evmRequest.rpcUrl, "eth_maxPriorityFeePerGas", emptyList<String>())


        val transaction = RawTransaction.createTransaction(
            parsedChainId,
            nonce,
            // Seems appropriate for the hyperledger besu network
            avalanceGasLimit,
            evmRequest.to,
            BigInteger.valueOf(0),
            sentTransaction.payload,
            avalancheMaxPriorityFeePerGas,
            avalancheMaxFeePerGas
        )

        val signer = Credentials.create("0x8f2a55949038a9610f50fb23b5883af3b4ecb3c3bb792cbcefbd1542c692be63")
        val signed = TxSignServiceImpl(signer).sign(transaction, parsedChainId)
        val tReceipt =
            evmConnector.send(evmRequest.rpcUrl, "eth_sendRawTransaction", listOf(Numeric.toHexString(signed)))
        return if (evmRequest.to.isEmpty()) {
            EvmResponse(evmRequest.flowId,queryCompletionContract(evmRequest.rpcUrl, tReceipt.result.toString()))
        } else {
            EvmResponse(evmRequest.flowId,tReceipt.result.toString())
        }
    }
}