package net.corda.interop.web3j

import net.corda.interop.web3j.internal.EthereumConnector

interface DispatcherFactory {
    /**
     * Dispatcher used to make call methods to a EVM Node
     *
     * @param evmConnector The evmConnector class used to make rpc calls to the node
     */
    fun balanceDispatcher(evmConnector: EthereumConnector): EvmDispatcher

    /**
     * Dispatcher used to get the chain id of an EVM Node
     *
     * @param evmConnector The evmConnector class used to make rpc calls to the node
     */
    fun chainIdDispatcher(evmConnector: EthereumConnector): EvmDispatcher

    /**
     * Dispatcher used to make a call on an EVM Node
     *
     * @param evmConnector The evmConnector class used to make rpc calls to the node
     */
    fun callDispatcher(evmConnector: EthereumConnector): EvmDispatcher

    /**
     * Dispatcher used to estimate gas on a EVM Node
     *
     * @param evmConnector The evmConnector class used to make rpc calls to the node
     */
    fun estimateGasDispatcher(evmConnector: EthereumConnector): EvmDispatcher

    /**
     * Dispatcher used to get the gas price on a EVM Node
     *
     * @param evmConnector The evmConnector class used to make rpc calls to the node
     */
    fun gasPriceDispatcher(evmConnector: EthereumConnector): EvmDispatcher

    /**
     * Dispatcher used to get the balance of an address on an EVM Node
     *
     * @param evmConnector The evmConnector class used to make rpc calls to the node
     */
    fun getBalanceDispatcher(evmConnector: EthereumConnector): EvmDispatcher

    /**
     * Dispatcher used to get the code of an address on a specific block on an EVM Node
     *
     * @param evmConnector The evmConnector class used to make rpc calls to the node
     */
    fun getCodeDispatcher(evmConnector: EthereumConnector): EvmDispatcher


    /**
     * Dispatcher used to get the balance of an address on an EVM Node
     *
     * @param evmConnector The evmConnector class used to make rpc calls to the node
     */
    fun getTransactionByHashDispatcher(evmConnector: EthereumConnector): EvmDispatcher

    /**
     * Dispatcher used to get the transaction receipt on an EVM Node
     *
     * @param evmConnector The evmConnector class used to make rpc calls to the node
     */
    fun getTransactionByReceiptDispatcher(evmConnector: EthereumConnector): EvmDispatcher

    /**
     * Dispatcher used to get the syncing status of an EVM Node
     *
     * @param evmConnector The evmConnector class used to make rpc calls to the node
     */
    fun isSyncingDispatcher(evmConnector: EthereumConnector): EvmDispatcher


    /**
     * Dispatcher used to get the max priority fee per gas of an EVM Node
     *
     * @param evmConnector The evmConnector class used to make rpc calls to the node
     */
    fun maxPriorityFeePerGasDispatcher(evmConnector: EthereumConnector): EvmDispatcher

    /**
     * Dispatcher used to send a transaction on an EVM Node
     *
     * @param evmConnector The evmConnector class used to make rpc calls to the node
     */
    fun sendRawTransactionDispatcher(evmConnector: EthereumConnector): EvmDispatcher

}