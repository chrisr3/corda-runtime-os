package net.corda.interop.web3j.internal

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.concurrent.TimeUnit
import com.fasterxml.jackson.databind.JsonNode
import org.osgi.service.component.annotations.Activate
import org.osgi.service.component.annotations.Reference


/**
 * EthereumConnector Class
 *
 * This class facilitates interaction with an Ethereum node's RPC interface. It enables making RPC requests,
 * handling responses, and extracting useful data from those responses.
 *
 * @property evmRpc The reference to the EvmRPCCall service for making RPC calls to the Ethereum node.
 */
class EthereumConnector @Activate constructor(
    @Reference(service = EvmRPCCall::class)
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
        )
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
            val jsonNode: JsonNode = objectMapper.readTree(jsonString)
            jsonNode.has(key)
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
    private fun findDataClassForJson(json: String, method: String): Class<*>? {
        return if (jsonStringContainsKey(json, "error")) {
            JsonRpcError::class.java
        } else {
            expectedReturnTypes[method]
        }
    }

    /**
     * Returns the useful data from the given input based on its type.
     *
     * @param input The input data object to process.
     * @return The useful data extracted from the input as a string, or an empty string if not applicable.
     */
    private fun returnUsefulData(input: Any): ProcessedResponse {
        when (input) {
            is JsonRpcError -> {
                throw EVMErrorException(input)
            }

            is TransactionResponse -> {
                return try {
                    ProcessedResponse(true, input.result?.contractAddress)
                } catch (e: Exception) {
                    ProcessedResponse(true, input.result.toString())
                }
            }

            is JsonRpcResponse -> return ProcessedResponse(true, input.result)
            is NonEip1559Block -> return ProcessedResponse(true, input.result)
        }
        return ProcessedResponse(false, "")
    }


    /**
     * Makes an RPC request to the Ethereum node and waits for the response.
     *
     * @param rpcUrl The URL of the Ethereum RPC endpoint.
     * @param method The RPC method to call.
     * @param params The parameters for the RPC call.
     * @param waitForResponse Set to true if the function should wait for a response, otherwise false.
     * @param requests The number of requests made so far (used for recursive calls).
     * @return A Response object representing the result of the RPC call.
     */
    private fun makeRequest(
        rpcUrl: String,
        method: String,
        params: List<*>,
        waitForResponse: Boolean,
        requests: Int
    ): Response {
        // Check if the maximum number of requests has been reached
        if (requests > maxLoopedRequests) {
            return Response("90", "2.0", "Timed Out")
        }
        // Make the RPC call to the Ethereum node
        val response = evmRpc.rpcCall(rpcUrl, method, params)
        val responseBody = response.message
        val success = response.success
        // Handle the response based on success status
        if (!success) {
            return Response("90", "2.0", response.message)
        }

        val responseType = findDataClassForJson(
            responseBody,
            method
        )
        val actualParsedResponse = objectMapper.readValue(responseBody, responseType ?: Any::class.java)
        val usefulResponse = returnUsefulData(actualParsedResponse)
        if (usefulResponse.payload == null || usefulResponse.payload == "null" && waitForResponse) {
            TimeUnit.SECONDS.sleep(2)
            return makeRequest(rpcUrl, method, params, true, requests + 1) // Return the recursive call
        }

        return Response("90", "2.0", usefulResponse.payload)
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
        return makeRequest(rpcUrl, method, params, waitForResponse = false, requests = 0)
    }

    /**
     * Sends an RPC request to the Ethereum node and returns the response.
     *
     * @param rpcUrl The URL of the Ethereum RPC endpoint.
     * @param method The RPC method to call.
     * @param params The parameters for the RPC call.
     * @param waitForResponse Set to true if the function should wait for a response, otherwise false.
     * @return A Response object representing the result of the RPC call.
     */
    fun send(rpcUrl: String, method: String, params: List<*>, waitForResponse: Boolean): Response {
        return makeRequest(rpcUrl, method, params, waitForResponse, requests = 0)
    }

}