package com.r3.corda.ethereumConnector

import net.corda.interop.web3j.internal.EthereumConnector
import net.corda.interop.web3j.internal.EvmRPCCall
import net.corda.interop.web3j.internal.RPCResponse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class EthereumConnectorTests {

    private lateinit var mockedEVMRpc: EvmRPCCall
    private lateinit var evmConnector: EthereumConnector

    private val rpcUrl = "http://127.0.0.1:8545"
    private val mainAddress = "0xfe3b557e8fb62b89f4916b721be55ceb828dbd73"

    @BeforeEach
    fun setUp(){
        mockedEVMRpc = mock(EvmRPCCall::class.java)
        evmConnector = EthereumConnector(mockedEVMRpc)
    }
    @Test
    fun getBalance() {

        val jsonString = "{\"jsonrpc\":\"2.0\",\"id\":\"90.0\",\"result\":\"1337\"}"
        `when`(
            mockedEVMRpc.rpcCall(
                rpcUrl,
                "eth_getBalance",
                listOf(mainAddress, "latest")
            )
        ).thenReturn(RPCResponse(true, jsonString))
        val final = evmConnector.send(
            rpcUrl,
            "eth_getBalance",
            listOf(mainAddress, "latest")
        )
        println(final)
    }


    @Test
    fun getCode() {
        val mockedEVMRpc = mock(EvmRPCCall::class.java)
        val evmConnector = EthereumConnector(mockedEVMRpc)
        val jsonString = "{\"jsonrpc\":\"2.0\",\"id\":\"90.0\",\"result\":\"0xfd2ds\"}"
        `when`(
            mockedEVMRpc.rpcCall(
                rpcUrl,
                "eth_getCode",
                listOf(mainAddress, "0x1")
            )
        ).thenReturn(RPCResponse(true, jsonString))
        println(evmConnector)
        val final = evmConnector.send(
            rpcUrl,
            "eth_getCode",
            listOf(mainAddress, "0x1")
        )
        println(final)
    }


    @Test
    fun getChainId() {
        val mockedEVMRpc = mock(EvmRPCCall::class.java)
        val evmConnector = EthereumConnector(mockedEVMRpc)
        val jsonString = "{\"jsonrpc\":\"2.0\",\"id\":\"90.0\",\"result\":\"1337\"}"
        `when`(
            mockedEVMRpc.rpcCall(
                rpcUrl,
                "eth_chainId",
                emptyList<String>()
            )
        ).thenReturn(RPCResponse(true, jsonString))
        println(evmConnector)
        val final = evmConnector.send(rpcUrl, "eth_chainId", emptyList<String>())
        assert(final.result == "1337")
    }


    @Test
    fun isSyncing() {
        val mockedEVMRpc = mock(EvmRPCCall::class.java)
        val evmConnector = EthereumConnector(mockedEVMRpc)
        val jsonString = "{\"jsonrpc\":\"2.0\",\"id\":\"90.0\",\"result\":\"false\"}"
        `when`(
            mockedEVMRpc.rpcCall(
                rpcUrl,
                "eth_syncing",
                emptyList<String>()
            )
        ).thenReturn(RPCResponse(true, jsonString))
        println(evmConnector)
        val final = evmConnector.send(rpcUrl, "eth_syncing", emptyList<String>())
        assert(final.result == "false")
    }



}