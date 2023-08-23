package net.corda.interop.web3j.internal.besu

import net.corda.data.interop.evm.EvmRequest
import net.corda.data.interop.evm.EvmResponse
import net.corda.interop.web3j.EvmDispatcher
import net.corda.interop.web3j.internal.EthereumConnector
import org.web3j.utils.Numeric

class ChainIdDispatcher(val evmConnector: EthereumConnector): EvmDispatcher {

    override fun dispatch(evmRequest: EvmRequest): EvmResponse {

        val resp = evmConnector.send(evmRequest.rpcUrl, "eth_chainId", emptyList<String>())
        // Send an RPC request to retrieve the balance of the specified address.
        // implement flow id
        println("CHAIN ID")
        println(Numeric.toBigInt(resp.result.toString()))
        return EvmResponse(evmRequest.flowId, resp.result.toString())
    }
}