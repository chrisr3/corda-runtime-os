package net.corda.interop.web3j.internal

import com.fasterxml.jackson.databind.ObjectMapper
import net.corda.v5.base.exceptions.CordaRuntimeException
import java.util.concurrent.TimeUnit


/**
 * EthereumConnector Class
 *
 * This class facilitates interaction with an Ethereum node's RPC interface. It enables making RPC requests,
 * handling responses, and extracting useful data from those responses.
 *
 * @property evmRpc The reference to the EvmRPCCall service for making RPC calls to the Ethereum node.
 */
class EthereumConnector(
    private val evmRpc: EvmRPCCall
) {
    companion object {
        private val objectMapper = ObjectMapper()
        private const val maxLoopedRequests = 10
        private val expectedReturnTypes = mapOf(
            "eth_call" to JsonRpcResponse::class.java,
            "eth_chainId" to JsonRpcResponse::class.java,
            "eth_estimateGas" to JsonRpcResponse::class.java,
            "eth_gasPrice" to JsonRpcResponse::class.java,
            "eth_getBalance" to JsonRpcResponse::class.java,
            "eth_getTransactionByHash" to TransactionResponse::class.java,
            "eth_getTransactionCount" to JsonRpcResponse::class.java,
            "eth_getTransactionReceipt" to TransactionResponse::class.java,
            "eth_maxPriorityFeePerGas " to JsonRpcResponse::class.java,
            "eth_subscribe" to JsonRpcResponse::class.java,
            "eth_syncing" to JsonRpcResponse::class.java,
            "eth_unsubscribe" to JsonRpcResponse::class.java,
            "eth_sendRawTransaction" to JsonRpcResponse::class.java,
            "eth_getBlockByNumber" to NonEip1559Block::class.java,
            "eth_getCode" to JsonRpcResponse::class.java,
        )

        // requestId being sent, this is temporary
        private const val requestId = "90"

        // JSON RPC Version Being interacted with
        private const val jsonRpcVersion = "2.0"
    }


    /**
     * Finds if a json string contains a given key
     *
     * @param jsonString The Key string to be found.
     * @param key The Key string to be found.
     * @return The matching data class from candidateDataClasses, or null if no match is found.
     */
    private fun jsonStringContainsKey(jsonString: String, key: String): Boolean {
        return try {
            // Regex to find the key
//            val regex = """"$key":\s*""".toRegex()
            val tree = objectMapper.readTree(jsonString)
            tree.has(key)

//            return
//            // Check if the key is in the string
//            regex.containsMatchIn(jsonString)
        } catch (e: Exception) {
            // Handle any parsing errors here
            false
        }
    }


    /**
     * Finds the appropriate data class from the candidateDataClasses list that fits the JSON structure.
     *
     * @param json The JSON string to be parsed.
     * @return The matching data class from candidateDataClasses, or null if no match is found.
     */
    private fun findDataClassForJson(json: String, method: String): Class<*> {
        return if (jsonStringContainsKey(json, "error")) {
            JsonRpcError::class.java
        } else {
            expectedReturnTypes[method] ?: Any::class.java
        }
    }

    /**
     * Returns the useful data from the given input based on its type.
     *
     * @param input The input data object to process.
     * @return The useful data extracted from the input as a string, or an empty string if not applicable.
     */
    private fun returnUsefulData(input: Any): String {
        println("++++++++++++++=")
        println(input is JsonRpcResponse)
        when (input) {
            is JsonRpcError -> {
                throw EVMErrorException(input)
            }

            is TransactionResponse -> {
                return try {
                    if (input.result == null) {
                        "null"
                    } else {
                        input.result.contractAddress
                    }
                } catch (e: Exception) {
                    input.result.toString()
                }
            }

            is JsonRpcResponse -> return input.result.toString()
            is NonEip1559Block -> return input.result.toString()
        }
        throw CordaRuntimeException("Failed to find appropriate type")
    }


    /**
     * Makes an RPC request to the Ethereum node and waits for the response.
     *
     * @param rpcUrl The URL of the Ethereum RPC endpoint.
     * @param method The RPC method to call.
     * @param params The parameters for the RPC call.
     * @return A Response object representing the result of the RPC call.
     */
    private fun performRequest(
        rpcUrl: String,
        method: String,
        params: List<*>
    ): Response {

        // Make the RPC call to the Ethereum node
        val response = evmRpc.rpcCall(rpcUrl, method, params)
        val responseBody = response.message
        val success = response.success
        // Handle the response based on success status
        if (!success) {
            return Response(requestId, jsonRpcVersion, response.message)
        }

        val responseType = findDataClassForJson(
            responseBody,
            method
        )
        println("Before actual parsed response")
        println(method)
        println(responseType)
        println(responseBody)
        val actualParsedResponse = objectMapper.readValue(responseBody, responseType)
        println("After actual parsed response")
        val usefulResponse = returnUsefulData(actualParsedResponse)
        println("AFTER USEFUL Response $usefulResponse")
        if (usefulResponse == "null") {
            return performRequest(rpcUrl, method, params)
        }


        return Response(requestId, jsonRpcVersion, usefulResponse)
    }

    /**
     * Sends an RPC request to the Ethereum node and returns the response without waiting for it.
     *
     * @param rpcUrl The URL of the Ethereum RPC endpoint.
     * @param method The RPC method to call.
     * @param params The parameters for the RPC call.
     * @return A Response object representing the result of the RPC call.
     */
    fun send(rpcUrl: String, method: String, params: List<*>): Response {
        return performRequest(rpcUrl, method, params)
    }


}