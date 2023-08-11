package net.corda.flow.application.interop

import co.paralleluniverse.fibers.Suspendable
import net.corda.data.interop.evm.EvmRequest
import net.corda.data.interop.evm.request.Call
import net.corda.data.interop.evm.request.ChainId
import net.corda.data.interop.evm.request.EstimateGas
import net.corda.data.interop.evm.request.GasPrice
import net.corda.data.interop.evm.request.GetBalance
import net.corda.data.interop.evm.request.GetCode
import net.corda.data.interop.evm.request.GetTransactionByHash
import net.corda.data.interop.evm.request.GetTransactionCount
import net.corda.data.interop.evm.request.GetTransactionReceipt
import net.corda.data.interop.evm.request.MaxPriorityFeePerGas
import net.corda.data.interop.evm.request.SendRawTransaction
import net.corda.flow.application.interop.external.events.EvmExternalEventParams
import net.corda.flow.application.interop.external.events.EvmQueryExternalEventFactory
import net.corda.flow.external.events.executor.ExternalEventExecutor
import net.corda.sandbox.type.UsedByFlow
import net.corda.v5.application.crypto.SigningService
import net.corda.v5.application.flows.FlowContextProperties
import net.corda.v5.application.interop.evm.EvmService
import net.corda.v5.application.interop.evm.Syncing
import net.corda.v5.application.interop.evm.Transaction
import net.corda.v5.application.interop.evm.TransactionReceipt
import org.osgi.framework.FrameworkUtil
import org.osgi.service.component.annotations.Activate
import org.osgi.service.component.annotations.Component
import org.osgi.service.component.annotations.ServiceScope.PROTOTYPE
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@Component(
    service = [SigningService::class, UsedByFlow::class],
    scope = PROTOTYPE
)
class EvmServiceImpl @Activate constructor(
    private val contractAddress: String,
    private val rpcUrl: String,
    private val flowContextProperties: FlowContextProperties,
    private val externalEventExecutor: ExternalEventExecutor,
) : EvmService {

    private companion object {
        private val log: Logger = LoggerFactory.getLogger(EvmServiceImpl::class.java)
        private val abiEncoder: AbiEncoder = AbiEncoderImpl(
            FrameworkUtil.getBundle(this::class.java).getResources("abis").toList().map {
                it.openStream().bufferedReader().readText()
            }
        )
    }

    @Suspendable
    override fun call(contract: String, functionName: String, contractAddress: String, vararg parameters: Any): Any {
        val encoded = abiEncoder.encodeFunctionSignature(contract, functionName, parameters.toList())
        return doRequest(Call(encoded))
    }

    @Suspendable
    override fun call(
        contract: String,
        functionName: String,
        contractAddress: String,
        parameters: MutableMap<String, Any>,
    ): Any {
        val encoded = abiEncoder.encodeFunctionSignature(contract, functionName, parameters)
        return doRequest(Call(encoded))
    }

    @Suspendable
    override fun sendRawTransaction(
        contract: String,
        functionName: String,
        contractAddress: String,
        value: Int,
        walletAddress: String,
        parameters: MutableMap<String, Any>,
    ): String {
        val encoded = abiEncoder.encodeFunctionSignature(contract, functionName, parameters)
        return doRequest(SendRawTransaction(value.toString(), encoded))
    }

    @Suspendable
    override fun getTransactionReceipt(transactionHash: String): TransactionReceipt =
        doRequest(GetTransactionReceipt(transactionHash))

    @Suspendable
    override fun chainId(): Int =
        doRequest(ChainId())

    @Suspendable
    override fun estimateGas(transaction: Transaction): Int =
        doRequest(EstimateGas(transaction.toAvro()))

    @Suspendable
    override fun gasPrice(): Int =
        doRequest(GasPrice())

    @Suspendable
    override fun getBalance(address: String, blockNumber: String): Int =
        doRequest(GetBalance(address, blockNumber))

    @Suspendable
    override fun getCode(address: String, blockNumber: String): String =
        doRequest(GetCode(address, blockNumber))

    @Suspendable
    override fun getTransactionByHash(hash: String): Transaction =
        doRequest(GetTransactionByHash(hash))

    @Suspendable
    override fun getTransactionCount(address: String, blockNumber: String): Int =
        doRequest(GetTransactionCount(address, blockNumber))

    @Suspendable
    override fun maxPriorityFeePerGas(): String =
        doRequest(MaxPriorityFeePerGas())

    @Suspendable
    override fun subscribe(subscriptionName: String, flag: Boolean, data: Any?): String {
        TODO("Not yet implemented")
    }

    @Suspendable
    override fun unsubscribe(subscriptionId: String): Boolean {
        TODO("Not yet implemented")
    }

    @Suspendable
    override fun syncing(): Syncing =
        doRequest(Syncing())

    private fun <T> doRequest(payload: Any?, clazz: Class<T>): T {
        val builder = EvmRequest.newBuilder()
        if (payload != null) {
            builder.setPayload(payload)
        }
        val request = builder.setRpcUrl(this@EvmServiceImpl.rpcUrl)
            .build()
        val result = externalEventExecutor.execute(
            EvmQueryExternalEventFactory::class.java,
            EvmExternalEventParams(
                contractAddress,
                rpcUrl,
                request
            )
        )
        return abiEncoder.decodeFunctionResult(result, clazz)
    }

    private inline fun <reified T> doRequest(payload: Any?) = doRequest(payload, T::class.java)
}

private fun Transaction.toAvro(): net.corda.data.interop.evm.Transaction {
    return net.corda.data.interop.evm.Transaction.newBuilder()
        .setFrom(from)
        .setTo(to)
        .setGas(gas.intValueExact())
        .setGasPrice(gasPrice.intValueExact())
        .setValue(value.intValueExact())
        .build()
}
