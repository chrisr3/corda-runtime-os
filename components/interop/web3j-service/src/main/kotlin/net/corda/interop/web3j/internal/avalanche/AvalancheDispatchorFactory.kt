package net.corda.interop.web3j.internal.avalanche

import net.corda.interop.web3j.DispatcherFactory
import net.corda.interop.web3j.EvmDispatcher
import net.corda.interop.web3j.internal.EthereumConnector
import net.corda.interop.web3j.internal.dispatchers.GetBalanceDispatcher


object AvalancheDispatcherFactory : DispatcherFactory {

    override fun balanceDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return GetBalanceDispatcher(evmConnector)
    }

    override fun chainIdDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return chainIdDispatcher(evmConnector)
    }

    override fun callDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return callDispatcher(evmConnector)
    }

    override fun estimateGasDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return estimateGasDispatcher(evmConnector)
    }

    override fun gasPriceDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return gasPriceDispatcher(evmConnector)
    }

    override fun getBalanceDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return GetBalanceDispatcher(evmConnector)
    }

    override fun getCodeDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return getCodeDispatcher(evmConnector)
    }

    override fun getTransactionByHashDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return getTransactionByHashDispatcher(evmConnector)
    }

    override fun getTransactionByReceiptDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return getTransactionByReceiptDispatcher(evmConnector)
    }

    override fun isSyncingDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return isSyncingDispatcher(evmConnector)
    }

    override fun maxPriorityFeePerGasDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return AvalancheMaxPriorityFeePerGasDispatcher(evmConnector)
    }

    override fun sendRawTransactionDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return AvalancheSendRawTransactionDispatcher(evmConnector)
    }

}
