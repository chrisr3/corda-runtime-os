package net.corda.interop.web3j.internal

import com.fasterxml.jackson.databind.ObjectMapper
import net.corda.v5.base.exceptions.CordaRuntimeException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.osgi.service.component.annotations.Reference

/**
 * EvmRPCCall Class
 *
 * This class facilitates making RPC calls to an Ethereum node using the JSON-RPC protocol.
 *
 * @property httpClient The reference to the OkHttpClient service for making HTTP requests.
 */
class EvmRPCCall(
    @Reference(service = OkHttpClient::class)
    private val httpClient: OkHttpClient
) {

    companion object {
        private const val JSON_RPC_VERSION = "2.0"
        private val objectMapper = ObjectMapper()
    }


    /**
     * Makes an RPC call to the Ethereum node and returns the JSON response as an RPCResponse object.
     *
     * @param rpcUrl The URL of the Ethereum RPC endpoint.
     * @param method The RPC method to call.
     * @param params The parameters for the RPC call.
     * @return An RPCResponse object representing the result of the RPC call.
     */
     fun rpcCall(rpcUrl: String, method: String, params: List<Any?>): RPCResponse {
         println("method $method")
         println("params $params")
        val body = RpcRequest(JSON_RPC_VERSION, "90.0", method, params)
        val requestBase = objectMapper.writeValueAsString(body)
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
}