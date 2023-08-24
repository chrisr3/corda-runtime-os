package net.corda.interop.web3j.internal.avalanche

import net.corda.interop.web3j.DispatcherFactory
import net.corda.interop.web3j.EvmDispatcher
import net.corda.interop.web3j.internal.EthereumConnector
import net.corda.interop.web3j.internal.besu.GetBalanceDispatcher


object AvalancheDispatcherFactory : DispatcherFactory {

    override fun balanceDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return GetBalanceDispatcher(evmConnector)
    }

    override fun chainIdDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return AvalancheChainIdDispatcher(evmConnector)
    }

    override fun callDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return AvalancheCallDispatcher(evmConnector)
    }

    override fun estimateGasDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return AvalancheEstimateGasDispatcher(evmConnector)
    }

    override fun gasPriceDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return AvalancheGasPriceDispatcher(evmConnector)
    }

    override fun getBalanceDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return GetBalanceDispatcher(evmConnector)
    }

    override fun getCodeDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return AvalancheGetCodeDispatcher(evmConnector)
    }

    override fun getTransactionByHashDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return AvalancheGetTransactionByHashDispatcher(evmConnector)
    }

    override fun getTransactionByReceiptDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return AvalancheGetTransactionReceiptDispatcher(evmConnector)
    }

    override fun isSyncingDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return AvalancheIsSyncingDispatcher(evmConnector)
    }

    override fun maxPriorityFeePerGasDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return AvalancheMaxPriorityFeePerGasDispatcher(evmConnector)
    }

    override fun sendRawTransactionDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return AvalancheSendRawTransactionDispatcher(evmConnector)
    }

}
