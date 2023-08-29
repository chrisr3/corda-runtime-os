package net.corda.interop.web3j.internal.besu

import net.corda.interop.web3j.DispatcherFactory
import net.corda.interop.web3j.EvmDispatcher
import net.corda.interop.web3j.internal.EthereumConnector
import net.corda.interop.web3j.internal.dispatchers.*

object BesuDispatcherFactory : DispatcherFactory {

    override fun balanceDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return getBalanceDispatcher(evmConnector)
    }

    override fun chainIdDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return ChainIdDispatcher(evmConnector)
    }

    override fun callDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return CallDispatcher(evmConnector)
    }

    override fun estimateGasDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return EstimateGasDispatcher(evmConnector)
    }

    override fun gasPriceDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return GasPriceDispatcher(evmConnector)
    }

    override fun getBalanceDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return GetBalanceDispatcher(evmConnector)
    }

    override fun getCodeDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return GetCodeDispatcher(evmConnector)
    }

    override fun getTransactionByHashDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return GetTransactionByHashDispatcher(evmConnector)
    }

    override fun getTransactionByReceiptDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return GetTransactionReceiptDispatcher(evmConnector)
    }

    override fun isSyncingDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return IsSyncingDispatcher(evmConnector)
    }

    override fun maxPriorityFeePerGasDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return BesuMaxPriorityFeePerGasDispatcher(evmConnector)
    }

    override fun sendRawTransactionDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return SendRawTransactionDispatcher(evmConnector)
    }

}
