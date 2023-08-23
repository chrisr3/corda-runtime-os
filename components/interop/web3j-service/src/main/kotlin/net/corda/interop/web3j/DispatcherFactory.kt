package net.corda.interop.web3j

import net.corda.interop.web3j.internal.EthereumConnector

interface DispatcherFactory {
    // Design patterns
    fun balanceDispatcher(evmConnector: EthereumConnector): EvmDispatcher

    fun chainIdDispatcher(evmConnector: EthereumConnector): EvmDispatcher

    fun callDispatcher(evmConnector: EthereumConnector): EvmDispatcher

    fun estimateGasDispatcher(evmConnector: EthereumConnector): EvmDispatcher

    fun gasPriceDispatcher(evmConnector: EthereumConnector): EvmDispatcher

    fun getBalanceDispatcher(evmConnector: EthereumConnector): EvmDispatcher

    fun getCodeDispatcher(evmConnector: EthereumConnector): EvmDispatcher

    fun getTransactionByHashDispatcher(evmConnector: EthereumConnector): EvmDispatcher

    fun getTransactionByReceiptDispatcher(evmConnector: EthereumConnector): EvmDispatcher

    fun isSyncingDispatcher(evmConnector: EthereumConnector): EvmDispatcher

    fun maxPriorityFeePerGasDispatcher(evmConnector: EthereumConnector): EvmDispatcher

    fun sendRawTransactionDispatcher(evmConnector: EthereumConnector): EvmDispatcher

}