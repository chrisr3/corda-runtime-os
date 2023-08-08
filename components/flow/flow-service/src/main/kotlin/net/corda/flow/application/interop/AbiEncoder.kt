package net.corda.flow.application.interop

const val EVM_FUNCTIONS = "EvmFunctions"

interface AbiEncoder {
    // Encodes The Function Signature With Parameters
    fun encodeFunctionSignature(contract: String = EVM_FUNCTIONS, method: String, params: List<Any>): String
    fun encodeFunctionSignature(contract: String = EVM_FUNCTIONS, method: String, params: Map<String, Any>): String
    fun <T> decodeFunctionResult(result: String, clazz: Class<T>): T
}

inline fun <reified T> AbiEncoder.decodeFunctionResult(result: String): T =
    decodeFunctionResult(result, T::class.java)
