package net.corda.interop.web3j.internal.besu

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import net.corda.data.interop.evm.EvmRequest
import net.corda.data.interop.evm.EvmResponse
import net.corda.data.interop.evm.request.Call
import net.corda.data.interop.evm.request.EstimateGas
import net.corda.interop.web3j.EvmDispatcher
import net.corda.interop.web3j.internal.EthereumConnector
import org.web3j.utils.Numeric

class BesuEstimateGasDispatcher(val evmConnector: EthereumConnector): EvmDispatcher {
    override fun dispatch(evmRequest: EvmRequest): EvmResponse {
            return EstimateGasDispatcher(evmConnector).dispatch(evmRequest)
    }
}