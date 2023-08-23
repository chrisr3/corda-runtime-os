package net.corda.interop.web3j.internal.besu

import net.corda.data.interop.evm.EvmRequest
import net.corda.data.interop.evm.EvmResponse
import net.corda.data.interop.evm.request.SendRawTransaction
import net.corda.interop.web3j.EvmDispatcher
import net.corda.interop.web3j.internal.EthereumConnector
import net.corda.interop.web3j.internal.NonEip1559Block
import net.corda.interop.web3j.internal.NonEip1559BlockData
import org.web3j.crypto.Credentials
import org.web3j.crypto.RawTransaction
import org.web3j.service.TxSignServiceImpl
import org.web3j.utils.Numeric
import java.math.BigInteger

class BesuSendRawTransactionDispatcher(val evmConnector: EthereumConnector) : EvmDispatcher {

    override fun dispatch(evmRequest: EvmRequest): EvmResponse {
        return SendRawTransactionDispatcher(evmConnector).dispatch(evmRequest)
    }
}