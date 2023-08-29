package net.corda.interop.web3j.internal.dispatchers

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import net.corda.data.interop.evm.EvmRequest
import net.corda.data.interop.evm.EvmResponse
import net.corda.data.interop.evm.request.EstimateGas
import net.corda.interop.web3j.EvmDispatcher
import net.corda.interop.web3j.internal.EthereumConnector

/**
 * Dispatcher used to estimate gas.
 *
 * @param evmConnector The evmConnector class used to make rpc calls to the node
 */
class EstimateGasDispatcher(val evmConnector: EthereumConnector): EvmDispatcher {
    override fun dispatch(evmRequest: EvmRequest): EvmResponse {
        val rootObject = JsonNodeFactory.instance.objectNode()

        rootObject.put("to", evmRequest.from)
        rootObject.put("data", (evmRequest.payload as EstimateGas).payload.toString())
//        rootObject.put("input", (evmRequest.payload as EstimateGas).payload.toString())
        rootObject.put("from", evmRequest.from)

        val resp = evmConnector.send(evmRequest.rpcUrl, "eth_estimateGas", listOf(rootObject))
        return EvmResponse(evmRequest.flowId, resp.result.toString())
    }
}