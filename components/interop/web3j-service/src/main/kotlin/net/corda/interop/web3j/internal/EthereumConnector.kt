package net.corda.interop.web3j.internal

import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import net.corda.v5.base.exceptions.CordaRuntimeException
import com.fasterxml.jackson.databind.JsonNode
import org.osgi.service.component.annotations.Activate
import org.osgi.service.component.annotations.Reference





class EthereumConnector @Activate constructor(
    @Reference(service = OkHttpClient::class)
    private val httpClient: OkHttpClient
) {

    companion object {
        private const val JSON_RPC_VERSION = "2.0"
        private val objectMapper = ObjectMapper()
        private const val maxLoopedRequests = 10
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

    val expectedReturnTypes = mapOf(
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

    /**
     * Finds the appropriate data class from the candidateDataClasses list that fits the JSON structure.
     *
     * @param json The JSON string to be parsed.
     * @return The matching data class from candidateDataClasses, or null if no match is found.
     */
    private fun findDataClassForJson(json: String,method: String): Class<*>? {
        println("method $method")
        return if (jsonStringContainsKey(json, "error")){
            JsonRpcError::class.java
        }
        else{
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
                println("AT JSON RPC ERROR")
                println(input)
                throw EVMErrorException(input)
            }

            is TransactionResponse -> {
                try {
                    return ProcessedResponse(true, input.result?.contractAddress)
                } catch (e: Exception) {
                    return ProcessedResponse(true, input.result.toString())
                }
            }

            is JsonRpcResponse -> return ProcessedResponse(true, input.result)
        }
        return ProcessedResponse(false, "")
    }


    /**
     * Makes an RPC call to the Ethereum node and returns the JSON response as an RPCResponse object.
     *
     * @param rpcUrl The URL of the Ethereum RPC endpoint.
     * @param method The RPC method to call.
     * @param params The parameters for the RPC call.
     * @return An RPCResponse object representing the result of the RPC call.
     */
    private fun rpcCall(rpcUrl: String, method: String, params: List<Any?>): RPCResponse {
        val body = RpcRequest(JSON_RPC_VERSION, "90.0", method, params)
        println("BEFORE REQUEST BASE")
        val requestBase = objectMapper.writeValueAsString(body)
        println("AFTER REQUEST BASE")
        println("PARAMS $params")
        println("Resuest Base: $requestBase")
        val requestBody = requestBase.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(rpcUrl)
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
            .build()
        return httpClient.newCall(request).execute().body?.use {
            RPCResponse(true, it.string())
        } ?: throw CordaRuntimeException("Response was null")
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
        val response = rpcCall(rpcUrl, method, params)
        val responseBody = response.message
        val success = response.success
        // Handle the response based on success status
        if (!success) {
            println("Request Failed")
            return Response("90", "2.0", response.message)
        }

        println("Response Body: $responseBody ")

        // Find the appropriate data class for parsing the actual response
        val responseType = findDataClassForJson(
            responseBody,
            method
        )
        // Parse the actual response using the determined data class
        val actualParsedResponse = objectMapper.readValue(responseBody, responseType ?: Any::class.java)
        // Get the useful response data from the parsed response
        val usefulResponse = returnUsefulData(actualParsedResponse)
        if (usefulResponse.payload == null || usefulResponse.payload == "null" && waitForResponse) {
            // TODO: Get rid of this
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