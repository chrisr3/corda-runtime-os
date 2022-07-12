package net.cordapp.testing.chat

/**
 * Class used to marshall input parameters for ChatOutgoingFlow from JSON.
 */
data class ChatOutgoingFlowParameter(
    val recipientX500Name: String? = null,
    val message: String? = null
)

/**
 * Class used to marshall input parameters for ChatReaderFlow from JSON.
 */
data class ChatReaderFlowParameter(
    val fromName: String? = null
)
