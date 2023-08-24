package net.corda.interop.web3j.internal.quorum

import net.corda.interop.web3j.DispatcherFactory
import net.corda.interop.web3j.EvmDispatcher
import net.corda.interop.web3j.internal.EthereumConnector
import net.corda.interop.web3j.internal.besu.*

object BesuDispatcherFactory : DispatcherFactory {
    override fun maxPriorityFeePerGasDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return BesuMaxPriorityFeePerGasDispatcher(evmConnector)
    }

    override fun sendRawTransactionDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return BesuSendRawTransactionDispatcher(evmConnector)
    }

}
