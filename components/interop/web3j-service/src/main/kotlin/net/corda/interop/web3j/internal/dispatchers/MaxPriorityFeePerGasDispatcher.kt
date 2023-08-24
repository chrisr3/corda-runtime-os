package net.corda.interop.web3j.internal.besu

import net.corda.data.interop.evm.EvmRequest
import net.corda.data.interop.evm.EvmResponse
import net.corda.interop.web3j.EvmDispatcher
import net.corda.interop.web3j.internal.EthereumConnector

/**
 * Dispatcher used to get max priority fee per gas.
 *
 * @param evmConnector The evmConnector class used to make rpc calls to the node
 */
class MaxPriorityFeePerGasDispatcher(val evmConnector: EthereumConnector) : EvmDispatcher {
override fun dispatch(evmRequest: EvmRequest): EvmResponse {
        // Send an RPC request to retrieve the maximum priority fee per gas.
        val resp = evmConnector.send(evmRequest.rpcUrl, "eth_maxPriorityFeePerGas", emptyList<String>())

        // Return the maximum priority fee per gas as a BigInteger.
        return EvmResponse(evmRequest.flowId,Integer.decode(resp.result.toString()).toLong().toString())
    }
}