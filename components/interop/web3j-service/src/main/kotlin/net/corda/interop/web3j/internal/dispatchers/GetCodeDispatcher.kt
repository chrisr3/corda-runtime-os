package net.corda.interop.web3j.internal.dispatchers

import net.corda.data.interop.evm.EvmRequest
import net.corda.data.interop.evm.EvmResponse
import net.corda.data.interop.evm.request.GetCode
import net.corda.interop.web3j.EvmDispatcher
import net.corda.interop.web3j.internal.EthereumConnector
import org.web3j.utils.Numeric
import java.math.BigInteger

/**
 * Dispatcher used to Get Code.
 *
 * @param evmConnector The evmConnector class used to make rpc calls to the node
 */
class GetCodeDispatcher(val evmConnector: EthereumConnector) : EvmDispatcher {
    override fun dispatch(evmRequest: EvmRequest): EvmResponse {
        // Send an RPC request to retrieve the balance of the specified address.
        val codeRequest = evmRequest.payload as GetCode
        val resp = evmConnector.send(
            evmRequest.rpcUrl,
            "eth_getCode",
            listOf(evmRequest.to, Numeric.toHexStringWithPrefix(BigInteger.valueOf(codeRequest.blockNumber.toLong())))
        )
        // Return the code as a string.
        return EvmResponse(evmRequest.flowId, resp.result.toString())
    }
}