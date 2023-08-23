package net.corda.interop.web3j.internal.besu

import net.corda.data.interop.evm.EvmRequest
import net.corda.data.interop.evm.EvmResponse
import net.corda.interop.web3j.EvmDispatcher
import net.corda.interop.web3j.internal.EthereumConnector

class BesuGetTransactionReceiptDispatcher(val evmConnector: EthereumConnector) : EvmDispatcher {
    override fun dispatch(evmRequest: EvmRequest): EvmResponse {
        return GetTransactionReceiptDispatcher(evmConnector).dispatch(evmRequest)
    }
}