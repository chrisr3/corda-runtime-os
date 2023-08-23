package net.corda.interop.web3j

import net.corda.data.interop.evm.EvmRequest
import net.corda.data.interop.evm.EvmResponse

interface EvmDispatcher{

     fun dispatch(evmRequest: EvmRequest): EvmResponse

}
