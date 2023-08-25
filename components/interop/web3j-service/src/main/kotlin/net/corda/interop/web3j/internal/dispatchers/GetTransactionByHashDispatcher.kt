package net.corda.interop.web3j.internal.dispatchers

import net.corda.data.interop.evm.EvmRequest
import net.corda.data.interop.evm.EvmResponse
import net.corda.data.interop.evm.request.GetTransactionByHash
import net.corda.interop.web3j.EvmDispatcher
import net.corda.interop.web3j.internal.EthereumConnector


/**
 * Dispatcher used to get transaction by hash.
 *
 * @param evmConnector The evmConnector class used to make rpc calls to the node
 */
class GetTransactionByHashDispatcher(val evmConnector: EthereumConnector) : EvmDispatcher {
    override fun dispatch(evmRequest: EvmRequest): EvmResponse {
        val transactionHashRequest = evmRequest.payload as GetTransactionByHash
        val resp = evmConnector.send(
            evmRequest.rpcUrl,
            "eth_getTransactionByHash",
            listOf(transactionHashRequest.transactionHash)
        )
        return EvmResponse(evmRequest.flowId, resp.result.toString())
    }
}