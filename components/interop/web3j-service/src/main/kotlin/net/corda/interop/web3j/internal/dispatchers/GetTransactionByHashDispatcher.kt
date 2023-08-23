package net.corda.interop.web3j.internal.besu

import net.corda.data.interop.evm.EvmRequest
import net.corda.data.interop.evm.EvmResponse
import net.corda.data.interop.evm.request.GetTransactionByHash
import net.corda.interop.web3j.EvmDispatcher
import net.corda.interop.web3j.internal.EthereumConnector

class GetTransactionByHashDispatcher(val evmConnector: EthereumConnector) : EvmDispatcher {
    /**
     * Retrieves the balance of a given Ethereum address using the provided RPC connection.
     *
     * @param rpcConnection The RPC connection URL for Ethereum communication.
     * @param from The Ethereum address for which to retrieve the balance.
     * @return The balance of the specified Ethereum address as a string.
     */
    override fun dispatch(evmRequest: EvmRequest): EvmResponse {
        val transactionHashRequest = evmRequest.payload as GetTransactionByHash
        val resp = evmConnector.send(evmRequest.rpcUrl, "eth_getTransactionByHash", listOf(transactionHashRequest.transactionHash))
        return EvmResponse(evmRequest.flowId,resp.result.toString())
    }
}