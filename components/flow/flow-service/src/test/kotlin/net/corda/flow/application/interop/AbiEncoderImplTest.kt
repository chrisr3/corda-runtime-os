package net.corda.flow.application.interop

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import net.corda.v5.base.exceptions.CordaRuntimeException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class AbiEncoderImplTest {

    companion object {
        private val ABI = this::class.java.getResource("/abi.json")!!.readText()
    }

    private val encoder: AbiEncoder = AbiEncoderImpl(listOf(ABI))

    @ParameterizedTest
    @ValueSource(strings = ["bool_output", "address_output", "bigint_output"])
    fun `encoder correctly encodes function without input`(method: String) {
        val encoded = encoder.encodeFunctionSignature("test", method, emptyList())
        val test = jacksonObjectMapper().readValue(encoded, AbiContractFunction::class.java)

        assertThat(test.name).isEqualTo(method)
        assertThat(test.inputs).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `encoder correctly encodes bool input`(testValue: Boolean) {
        val encoded = encoder.encodeFunctionSignature("test", "bool_input", listOf(testValue))
        val test = jacksonObjectMapper().readValue(encoded, AbiContractFunction::class.java)

        assertThat(test.inputs.single().value).isEqualTo(testValue)
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 42])
    fun `encoder correctly encodes int input`(testValue: Int) {
        val encoded = encoder.encodeFunctionSignature("test", "bigint_input", listOf(testValue))
        val test = jacksonObjectMapper().readValue(encoded, AbiContractFunction::class.java)

        assertThat(test.inputs.single().value).isEqualTo(testValue)
    }

    @ParameterizedTest
    @ValueSource(strings = ["someString1", "someOtherString2"])
    fun `encoder correctly encodes address input`(testValue: String) {
        val encoded = encoder.encodeFunctionSignature("test", "address_input", listOf(testValue))
        val test = jacksonObjectMapper().readValue(encoded, AbiContractFunction::class.java)

        assertThat(test.inputs.single().value).isEqualTo(testValue)
    }

    @Test
    fun `encoder correctly errors when input type is wrong`() {
        assertThatExceptionOfType(CordaRuntimeException::class.java).isThrownBy {
            encoder.encodeFunctionSignature("test", "address_input", listOf(1))
        }.withMessage("Incorrect type for parameter input.  Expected 'address', got 'uint256'")

        assertThatExceptionOfType(CordaRuntimeException::class.java).isThrownBy {
            encoder.encodeFunctionSignature("test", "address_input", listOf(true))
        }.withMessage("Incorrect type for parameter input.  Expected 'address', got 'bool'")

        assertThatExceptionOfType(CordaRuntimeException::class.java).isThrownBy {
            encoder.encodeFunctionSignature("test", "bool_input", listOf("someString1"))
        }.withMessage("Incorrect type for parameter input.  Expected 'bool', got 'address'")

        assertThatExceptionOfType(CordaRuntimeException::class.java).isThrownBy {
            encoder.encodeFunctionSignature("test", "bool_input", listOf(1))
        }.withMessage("Incorrect type for parameter input.  Expected 'bool', got 'uint256'")

        assertThatExceptionOfType(CordaRuntimeException::class.java).isThrownBy {
            encoder.encodeFunctionSignature("test", "bigint_input", listOf("someString1"))
        }.withMessage("Incorrect type for parameter input.  Expected 'uint256', got 'address'")

        assertThatExceptionOfType(CordaRuntimeException::class.java).isThrownBy {
            encoder.encodeFunctionSignature("test", "bigint_input", listOf(true))
        }.withMessage("Incorrect type for parameter input.  Expected 'uint256', got 'bool'")
    }

    @Test
    fun `encoder correctly errors when contract or function are wrong`() {
        assertThatExceptionOfType(CordaRuntimeException::class.java).isThrownBy {
            encoder.encodeFunctionSignature("bad contract", "address_output", emptyList())
        }.withMessage("Contract bad contract not found in ABIs")

        assertThatExceptionOfType(CordaRuntimeException::class.java).isThrownBy {
            encoder.encodeFunctionSignature("test", "bad method", emptyList())
        }.withMessage("Function bad method not found for contract test")
    }
}