package net.corda.interop.web3j.internal.avalanche

import net.corda.interop.web3j.DispatcherFactory
import net.corda.interop.web3j.EvmDispatcher
import net.corda.interop.web3j.internal.EthereumConnector


object AvalancheDispatcherFactory : DispatcherFactory {

    override fun maxPriorityFeePerGasDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return AvalancheMaxPriorityFeePerGasDispatcher(evmConnector)
    }

    override fun sendRawTransactionDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return AvalancheSendRawTransactionDispatcher(evmConnector)
    }

}
