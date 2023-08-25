package net.corda.interop.web3j.internal.dispatchers

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import net.corda.data.interop.evm.EvmRequest
import net.corda.data.interop.evm.EvmResponse
import net.corda.data.interop.evm.request.Call
import net.corda.interop.web3j.EvmDispatcher
import net.corda.interop.web3j.internal.EthereumConnector


/**
 * Dispatcher used to make call methods to a Generic EVM Node
 *
 * @param evmConnector The evmConnector class used to make rpc calls to the node
 */
class CallDispatcher(val evmConnector: EthereumConnector): EvmDispatcher {
    override fun dispatch(evmRequest: EvmRequest): EvmResponse {
        val rootObject = JsonNodeFactory.instance.objectNode()
        rootObject.put("to", evmRequest.to)
        rootObject.put("data", (evmRequest.payload as Call).payload)
        rootObject.put("input", (evmRequest.payload as Call).payload)
        val resp = evmConnector.send(evmRequest.rpcUrl, "eth_call", listOf(rootObject, "latest"))
        return EvmResponse(evmRequest.flowId, resp.result.toString())
    }
}