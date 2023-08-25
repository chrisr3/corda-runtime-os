package net.corda.interop.web3j.internal.dispatchers

import net.corda.data.interop.evm.EvmRequest
import net.corda.data.interop.evm.EvmResponse
import net.corda.interop.web3j.EvmDispatcher
import net.corda.interop.web3j.internal.EthereumConnector

/**
 * Dispatcher used to see if the node is syncing.
 *
 * @param evmConnector The evmConnector class used to make rpc calls to the node
 */
class IsSyncingDispatcher(val evmConnector: EthereumConnector) : EvmDispatcher {
    override fun dispatch(evmRequest: EvmRequest): EvmResponse {
        val resp = evmConnector.send(evmRequest.rpcUrl, "eth_syncing", emptyList<String>())
        return EvmResponse(evmRequest.flowId, resp.result.toString())
    }
}