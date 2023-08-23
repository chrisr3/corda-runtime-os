package net.corda.interop.web3j.internal.besu

import net.corda.data.interop.evm.EvmRequest
import net.corda.data.interop.evm.EvmResponse
import net.corda.data.interop.evm.request.GetCode
import net.corda.interop.web3j.EvmDispatcher
import net.corda.interop.web3j.internal.EthereumConnector
import org.web3j.utils.Numeric
import java.math.BigInteger


class GetCodeDispatcher(val evmConnector: EthereumConnector) : EvmDispatcher {
    /**
     * Retrieves the balance of a given Ethereum address using the provided RPC connection.
     *
     * @param rpcConnection The RPC connection URL for Ethereum communication.
     * @param from The Ethereum address for which to retrieve the balance.
     * @return The balance of the specified Ethereum address as a string.
     */
    override fun dispatch(evmRequest: EvmRequest): EvmResponse {
        // Send an RPC request to retrieve the balance of the specified address.
        val codeRequest = evmRequest.payload as GetCode
        val resp = evmConnector.send(
            evmRequest.rpcUrl,
            "eth_getCode",
            listOf(evmRequest.to, Numeric.toHexStringWithPrefix(BigInteger.valueOf(codeRequest.blockNumber.toLong())))
        )
        // Return the code as a string.
        return EvmResponse(evmRequest.flowId,resp.result.toString())
    }
}