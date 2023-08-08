package net.corda.flow.application.interop

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.math.BigInteger
import net.corda.v5.base.exceptions.CordaRuntimeException

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

        abiContents.associate {
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
        val paramsMapped = getFunction(contract, method).inputs.mapIndexed { index, input ->
            if (index > params.size) {
                input
            } else {
                input.copy(value = params[index])
            }
        }.associate { it.name to it.value!! }

        return encodeFunctionSignature(contract, method, paramsMapped)
    }

    override fun encodeFunctionSignature(
        contract: String,
        method: String,
        params: Map<String, Any>,
    ): String {
        val function = getFunction(contract, method)
        val newParams = function.inputs.toMutableList().map {
            val value = params[it.name]
            if (value != null) {
                val type = encodedClassType(value::class.java)
                val expectedType = it.type
                if (type != expectedType) {
                    throw CordaRuntimeException("Incorrect type for parameter ${it.name}.  Expected '$expectedType', got '$type'")
                }
                it.copy(value = value)
            } else {
                it
            }
        }
        val outputFunction = function.copy(inputs = newParams)

        return jacksonObjectMapper().writeValueAsString(outputFunction)
    }

    override fun <T> decodeFunctionResult(result: String, clazz: Class<T>): T {
        return jacksonObjectMapper().readValue(result, clazz)
    }


    private fun getFunction(contract: String, method: String): AbiContractFunction {
        val abiContract = abis[contract] ?: throw CordaRuntimeException("Contract $contract not found in ABIs")
        return abiContract.find { it.name == method } ?: throw CordaRuntimeException("Function $method not found for contract $contract")
    }

    private fun getClassType(type: String): Class<*> {
        return when (type) {
            "address" -> {
                Address::class.java
            }

            "string" -> {
                String::class.java
            }

            "uint256" -> {
                BigInteger::class.java
            }

            "bool" -> {
                Boolean::class.java
            }

            else -> {
                throw Exception("Cannot decode an unknown type: $type")
            }
        }
    }

    private fun encodedClassType(clazz: Class<*>): String {
        return when (clazz) {
            java.lang.String::class.java,
            String::class.java,
            -> {
                "string"
            }

            Address::class.java -> {
                "address"
            }

            BigInteger::class.java,
            java.lang.Integer::class.java,
            Int::class.java,
            -> {
                "uint256"
            }

            java.lang.Boolean::class.java,
            Boolean::class.java,
            -> {
                "bool"
            }

            else -> {
                throw Exception("Cannot encode an unknown class: $clazz")
            }
        }
    }

    companion object {

        // Functions that aren't part of a specific contract
        private const val EVM_FUNCTIONS = """

        """
    }
}

data class Address(val address: String)