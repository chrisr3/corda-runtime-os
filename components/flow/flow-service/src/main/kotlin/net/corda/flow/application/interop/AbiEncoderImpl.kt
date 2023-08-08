package net.corda.flow.application.interop

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.math.BigInteger

data class AbiContractFunctionInput(
    val name: String,
    val type: String,
    val internalType: String,
    val value: Any?,
    val components: List<AbiContractFunctionInput>?,
)

data class AbiContractFunction(
    val name: String?,
    val inputs: List<AbiContractFunctionInput>,
    val outputs: List<AbiContractFunctionInput>?,
    val type: String,
    val stateMutability: String?,
)

class AbiEncoderImpl(
    private val abiContents: Collection<String>,
) : AbiEncoder {

    private val abis by lazy {

        val objectMapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.FAIL_ON_IGNORED_PROPERTIES, false)

        (abiContents + EVM_FUNCTIONS).associate {
            val root = objectMapper.readTree(it)
            val contractName = root.findValue("title") ?: root.findValue("contractName")
            val contractFunctions = objectMapper.readValue<List<AbiContractFunction>>(root.findPath("abi").toString())

            contractName.textValue() to contractFunctions
        }
    }

    // Encodes The Function Signature With Parameters
    override fun encodeFunctionSignature(
        contract: String,
        method: String,
        params: List<Any>,
    ): String {
        if (params.isEmpty()) {
            return jacksonObjectMapper().writeValueAsString(getFunction(contract, method))
        }

        return doEncoding(contract, method) {function ->
            function.inputs.mapIndexed { index, input ->
                if (index < params.size) {
                    input
                } else {
                    function.inputs[index]
                }
            }

        }
    }

    override fun encodeFunctionSignature(
        contract: String,
        method: String,
        params: Map<String, Any>,
    ): String {
        if (params.isEmpty()) {
            return jacksonObjectMapper().writeValueAsString(getFunction(contract, method))
        }

        return doEncoding(contract, method) { function ->
            function.inputs.toMutableList().map {
                if (it.name in params.keys) {
                    it.copy(value = params[it.name])
                } else {
                    it
                }
            }
        }
    }

    private fun doEncoding(
        contract: String,
        method: String,
        block: (AbiContractFunction) -> List<AbiContractFunctionInput>
    ): String {
        val function = getFunction(contract, method)
        val newParams = block(function)
        val outputFunction = function.copy(inputs = newParams)

        return jacksonObjectMapper().writeValueAsString(outputFunction)
    }

    override fun <T> decodeFunctionResult(result: String, clazz: Class<T>): T {
        return jacksonObjectMapper().readValue(result, clazz)
    }


    private fun getFunction(contract: String, method: String) = abis[contract]!!.find { it.name == method }!!

    private fun getClassType(type: String): Class<*> {
        return when (type) {
            "address", "string" -> {
                String::class.java
            }

            "uint256" -> {
                BigInteger::class.java
            }

            "bool" -> {
                Boolean::class.java
            }

            else -> {
                throw Exception()
            }
        }
    }

    companion object {

        // Functions that aren't part of a specific contract
        private const val EVM_FUNCTIONS = """

        """
    }
}