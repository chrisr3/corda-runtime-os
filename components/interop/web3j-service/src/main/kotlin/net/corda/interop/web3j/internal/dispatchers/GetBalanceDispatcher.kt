package net.corda.interop.web3j.internal.dispatchers
import net.corda.data.interop.evm.EvmRequest
import net.corda.data.interop.evm.EvmResponse
import net.corda.interop.web3j.EvmDispatcher
import net.corda.interop.web3j.internal.EthereumConnector


/**
 * Dispatcher used to get the balance.
 *
 * @param evmConnector The evmConnector class used to make rpc calls to the node
 */
class GetBalanceDispatcher(val evmConnector: EthereumConnector) : EvmDispatcher {

     override fun dispatch(evmRequest: EvmRequest): EvmResponse {
          // Send an RPC request to retrieve the balance of the specified address.
          val resp = evmConnector.send(evmRequest.rpcUrl, "eth_getBalance", listOf(evmRequest.from, "latest"))
          // implement flow id
          return EvmResponse(evmRequest.flowId, resp.result.toString())
     }
}
