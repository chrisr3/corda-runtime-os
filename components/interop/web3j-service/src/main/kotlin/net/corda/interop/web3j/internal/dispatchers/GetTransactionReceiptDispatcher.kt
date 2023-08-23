package net.corda.interop.web3j.internal.besu

import net.corda.data.interop.evm.EvmRequest
import net.corda.data.interop.evm.EvmResponse
import net.corda.data.interop.evm.request.GetTransactionReceipt
import net.corda.interop.web3j.EvmDispatcher
import net.corda.interop.web3j.internal.EthereumConnector

/**
 * Dispatcher used to get transaction receipt.
 *
 * @param evmConnector The evmConnector class used to make rpc calls to the node
 */
class GetTransactionReceiptDispatcher(val evmConnector: EthereumConnector) : EvmDispatcher {

    override fun dispatch(evmRequest: EvmRequest): EvmResponse {
        val transactionReceipt = evmRequest.payload as GetTransactionReceipt
        val resp = evmConnector.send(
            evmRequest.rpcUrl,
            "eth_getTransactionReceipt",
            listOf(transactionReceipt.transactionHash)
        )
        return EvmResponse(evmRequest.flowId, resp.result.toString())
    }
}