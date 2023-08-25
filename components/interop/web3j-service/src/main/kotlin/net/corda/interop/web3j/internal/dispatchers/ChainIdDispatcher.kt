package net.corda.interop.web3j.internal.dispatchers

import net.corda.data.interop.evm.EvmRequest
import net.corda.data.interop.evm.EvmResponse
import net.corda.interop.web3j.EvmDispatcher
import net.corda.interop.web3j.internal.EthereumConnector


/**
 * Dispatcher used to call the Chain Id methods to a Generic EVM Node
 *
 * @param evmConnector The evmConnector class used to make rpc calls to the node
 */
class ChainIdDispatcher(val evmConnector: EthereumConnector): EvmDispatcher {

    override fun dispatch(evmRequest: EvmRequest): EvmResponse {

        val resp = evmConnector.send(evmRequest.rpcUrl, "eth_chainId", emptyList<String>())

        return EvmResponse(evmRequest.flowId, resp.result.toString())
    }
}