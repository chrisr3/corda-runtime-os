package net.corda.interop.web3j.internal

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import net.corda.v5.base.exceptions.CordaRuntimeException

class EVMErrorException(val errorResponse: JsonRpcError) : CordaRuntimeException(errorResponse.error.toString())


data class JsonRpcResponse @JsonCreator constructor(
    @JsonProperty("jsonrpc") val jsonrpc: String,
    @JsonProperty("id") val id: String,
    @JsonProperty("result") val result: String?
)


data class JsonRpcError @JsonCreator constructor(
    @JsonProperty("jsonrpc") val jsonrpc: String,
    @JsonProperty("id") val id: String,
    @JsonProperty("error") val error: Error
)

data class Error @JsonCreator constructor(
    @JsonProperty("code") val code: Int,
    @JsonProperty("message") val message: String,
    @JsonProperty("data") val data: String?
)

data class RpcRequest @JsonCreator constructor(
    @JsonProperty("jsonrpc") val jsonrpc: String,
    @JsonProperty("id") val id: String,
    @JsonProperty("method") val method: String,
    @JsonProperty("params") val params: List<*>
)


data class ProcessedResponse(
    val success: Boolean,
    val payload: Any?
)

data class RPCResponse(
    val success: Boolean,
    val message: String
)


data class Response @JsonCreator constructor(
    @JsonProperty("id") val id: String,
    @JsonProperty("jsonrpc") val jsonrpc: String,
    @JsonProperty("result") val result: Any?
)

data class TransactionResponse @JsonCreator constructor(
    @JsonProperty("id") val id: String,
    @JsonProperty("jsonrpc") val jsonrpc: String,
    @JsonProperty("result") val result: TransactionData?
)

data class TransactionData @JsonCreator constructor(
    @JsonProperty("blockHash") val blockHash: String,
    @JsonProperty("blockNumber") val blockNumber: String,
    @JsonProperty("contractAddress") val contractAddress: String,
    @JsonProperty("cumulativeGasUsed") val cumulativeGasUsed: String,
    @JsonProperty("from") val from: String,
    @JsonProperty("gasUsed") val gasUsed: String,
    @JsonProperty("effectiveGasPrice") val effectiveGasPrice: String,
    @JsonProperty("logs") val logs: List<TransactionLog>,
    @JsonProperty("logsBloom") val logsBloom: String,
    @JsonProperty("status") val status: String,
    @JsonProperty("to") val to: String?,
    @JsonProperty("transactionHash") val transactionHash: String,
    @JsonProperty("transactionIndex") val transactionIndex: String,
    @JsonProperty("type") val type: String,
    @JsonProperty("extDataGasUsed") val extDataGasUsed: String?,

    )

data class TransactionLog @JsonCreator constructor(
    @JsonProperty("address") val address: String,
    @JsonProperty("topics") val topics: List<String>,
    @JsonProperty("data") val data: String,
    @JsonProperty("blockNumber") val blockNumber: String,
    @JsonProperty("transactionHash") val transactionHash: String,
    @JsonProperty("transactionIndex") val transactionIndex: String,
    @JsonProperty("blockHash") val blockHash: String,
    @JsonProperty("logIndex") val logIndex: String,
    @JsonProperty("removed") val removed: Boolean,
    @JsonProperty("extDataGasUsed") val extDataGasUsed: String?,

    )


data class NonEip1559Block @JsonCreator constructor(
    @JsonProperty("id") val id: String,
    @JsonProperty("jsonrpc") val jsonrpc: String,
    @JsonProperty("result") val result: NonEip1559BlockData
)

data class NonEip1559BlockData @JsonCreator constructor(
    @JsonProperty("number") val number: String,
    @JsonProperty("hash") val hash: String,
    @JsonProperty("mixHash") val mixHash: String,
    @JsonProperty("parentHash") val parentHash: String,
    @JsonProperty("nonce") val nonce: String,
    @JsonProperty("sha3Uncles") val sha3Uncles: String,
    @JsonProperty("logsBloom") val logsBloom: String,
    @JsonProperty("transactionsRoot") val transactionsRoot: String,
    @JsonProperty("stateRoot") val stateRoot: String,
    @JsonProperty("receiptsRoot") val receiptsRoot: String,
    @JsonProperty("miner") val miner: String,
    @JsonProperty("difficulty") val difficulty: String,
    @JsonProperty("totalDifficulty") val totalDifficulty: String,
    @JsonProperty("extraData") val extraData: String,
    @JsonProperty("baseFeePerGas") val baseFeePerGas: String,
    @JsonProperty("size") val size: String,
    @JsonProperty("gasLimit") val gasLimit: String,
    @JsonProperty("gasUsed") val gasUsed: String,
    @JsonProperty("timestamp") val timestamp: String,
    @JsonProperty("uncles") val uncles: List<Any>?,
    @JsonProperty("transactions") val transactions: List<Any>?,
    @JsonProperty("withdrawalsRoot") val withdrawalsRoot: String?,
    @JsonProperty("withdrawals") val withdrawals: List<String>?,
    @JsonProperty("blockExtraData") val blockExtraData: String?,
    @JsonProperty("blockGasCost") val blockGasCost: String?,
    @JsonProperty("extDataGasUsed") val extDataGasUsed: String?,
    @JsonProperty("extDataHash") val extDataHash: String?,
)
