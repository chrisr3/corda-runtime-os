package net.corda.interop.web3j.internal.avalanche

import net.corda.data.interop.evm.EvmRequest
import net.corda.data.interop.evm.EvmResponse
import net.corda.interop.web3j.EvmDispatcher
import net.corda.interop.web3j.internal.EthereumConnector
import net.corda.interop.web3j.internal.dispatchers.EstimateGasDispatcher

class AvalancheEstimateGasDispatcher(val evmConnector: EthereumConnector): EvmDispatcher {
    override fun dispatch(evmRequest: EvmRequest): EvmResponse {
        return EstimateGasDispatcher(evmConnector).dispatch(evmRequest)
    }
}