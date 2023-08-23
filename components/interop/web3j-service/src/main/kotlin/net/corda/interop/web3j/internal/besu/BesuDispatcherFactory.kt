package net.corda.interop.web3j.internal.quorum

import net.corda.interop.web3j.DispatcherFactory
import net.corda.interop.web3j.EvmDispatcher
import net.corda.interop.web3j.internal.EthereumConnector
import net.corda.interop.web3j.internal.besu.*

object BesuDispatcherFactory : DispatcherFactory {

    override fun balanceDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return CallDispatcher(evmConnector)
    }

    override fun chainIdDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return ChainIdDispatcher(evmConnector)
    }

    override fun callDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return BesuCallDispatcher(evmConnector)
    }

    override fun estimateGasDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return BesuEstimateGasDispatcher(evmConnector)
    }

    override fun gasPriceDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return BesuGasPriceDispatcher(evmConnector)
    }

    override fun getBalanceDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return BesuGetBalanceDispatcher(evmConnector)
    }

    override fun getCodeDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return BesuGetCodeDispatcher(evmConnector)
    }

    override fun getTransactionByHashDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return BesuGetTransactionByHashDispatcher(evmConnector)
    }

    override fun getTransactionByReceiptDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return BesuGetTransactionReceiptDispatcher(evmConnector)
    }

    override fun isSyncingDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return BesuIsSyncingDispatcher(evmConnector)
    }

    override fun maxPriorityFeePerGasDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return BesuMaxPriorityFeePerGasDispatcher(evmConnector)
    }

    override fun sendRawTransactionDispatcher(evmConnector: EthereumConnector): EvmDispatcher {
        return BesuSendRawTransactionDispatcher(evmConnector)
    }

}
