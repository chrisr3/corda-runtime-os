package net.corda.p2p.linkmanager.sessions

import net.corda.p2p.crypto.protocol.api.AuthenticationProtocolInitiator
import net.corda.p2p.crypto.protocol.api.Session
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Stores outbound [AuthenticationProtocolInitiator] during session negotiation and [Session] once negotiation is
 * complete.
 * [calculateWeightForSession] - Used to calculate a weight per session. Sessions with a small weight
 * are favoured over sessions with a larger weight.
 * [genRandomNumber] - Generates a random Long in the interval 0 (inclusive) to until (exclusive).
 */
internal class OutboundSessionPool(
    private val calculateWeightForSession: (sessionId: String) -> Long?,
    private val genRandomNumber: (until: Long) -> Long = { until -> Random.nextLong(until) }
) {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val outboundSessions = ConcurrentHashMap<SessionManager.SessionCounterparties, ConcurrentHashMap<String, SessionType>>()
    private val counterpartiesForSessionId = ConcurrentHashMap<String, SessionManager.SessionCounterparties>()

    sealed class SessionType {
        data class PendingSession(
            val sessionCounterparties: SessionManager.SessionCounterparties,
            val protocol: AuthenticationProtocolInitiator
        ) : SessionType()

        data class ActiveSession(val sessionCounterparties: SessionManager.SessionCounterparties, val session: Session) : SessionType()
    }

    sealed class SessionPoolStatus {
        object NewSessionsNeeded : SessionPoolStatus()
        object SessionPending : SessionPoolStatus()
        data class SessionActive(val session: Session) : SessionPoolStatus()
    }

    /**
     * If session negotiation is completed (for any session) then select a random [Session] for the set of [sessionCounterparties],
     * weighted by calculateWeightForSession. If session negotiation is started, but is not completed, then
     * [SessionPoolStatus.SessionPending] is returned, otherwise [SessionPoolStatus.NewSessionsNeeded] is returned.
     */
    fun getNextSession(sessionCounterparties: SessionManager.SessionCounterparties): SessionPoolStatus {
        logger.info("QQQ PPP in getNextSession(${sessionCounterparties.counterpartyId}) ${Thread.currentThread().id}")
        val outboundSessionsForCounterparties = outboundSessions[sessionCounterparties]
            ?: return SessionPoolStatus.NewSessionsNeeded.also {
            logger.info("QQQ PPP empty for ${sessionCounterparties.counterpartyId} ${Thread.currentThread().id}!")
        }


        val activeSessions = outboundSessionsForCounterparties.mapNotNull { (_, session) ->
            session as? SessionType.ActiveSession
        }.associateBy { it.session.sessionId }
        if (activeSessions.isEmpty()) return SessionPoolStatus.SessionPending.also {
            logger.info("QQQ PPP pending!")
        }


        var totalWeight = 0L
        val weights = activeSessions.mapNotNull {
            val weight = calculateWeightForSession(it.key) ?: 0L
            totalWeight += weight
            it.value to weight
        }.toMutableList()

        if (weights.size == 1) {
            logger.info("QQQ PPP active 1 for ${sessionCounterparties.counterpartyId}, " +
                    "${weights[0].first.session.sessionId}!")
            return SessionPoolStatus.SessionActive(weights[0].first.session)
        }

        //If all sessions have weight 0 select a random session (as the algorithm bellow doesn't work).
        if (totalWeight == 0L) {
            val randomNumber = genRandomNumber(weights.size.toLong()).toInt()
            logger.info("QQQ PPP active 2 for ${sessionCounterparties.counterpartyId}, " +
                    "${weights[randomNumber].first.session.sessionId}!")
            return SessionPoolStatus.SessionActive(weights[randomNumber].first.session)
        }

        /**
         * Select a session randomly with probability in proportion to totalWeight - weight.
         * For every session this sums to (weights.size - 1) * totalWeight.
         * We use a uniformly distributed random Long in the interval from 0 (inclusive) and (weights.size - 1) * totalWeight - 1
         * (inclusive) to select a session.
         */
        val randomNumber = genRandomNumber((weights.size - 1) * totalWeight)
        var totalProb = 0L
        val selectedSession = weights.find {
            totalProb += totalWeight - it.second
            randomNumber < totalProb
        }?.first

        selectedSession!!.let { return SessionPoolStatus.SessionActive(it.session).also {
            logger.info("QQQ PPP active 3 for ${sessionCounterparties.counterpartyId}, " +
                    "${it.session.sessionId}!")
        } }
    }

    /**
     * get a specific session by [sessionId]
     */
    fun getSession(sessionId: String): SessionType? {
        logger.info("QQQQ PPP getSession($sessionId)")
        val counterparties = counterpartiesForSessionId[sessionId] ?: return null
        return outboundSessions[counterparties]?.get(sessionId)
    }

    /**
     * update the session pool once session negotiation is complete
     */
    fun updateAfterSessionEstablished(session: Session) {
        logger.info("QQQQ PPP updateAfterSessionEstablished(${session.sessionId})")
        val counterparties = counterpartiesForSessionId[session.sessionId] ?: return
        outboundSessions.computeIfPresent(counterparties) { _, sessions ->
            sessions[session.sessionId] = SessionType.ActiveSession(counterparties, session)
            sessions
        }
    }

    /**
     * Add a set of [AuthenticationProtocolInitiator] for a set of [sessionCounterparties]. This replaces all
     * existing entries in the pool.
     */
    fun addPendingSessions(
        sessionCounterparties: SessionManager.SessionCounterparties,
        authenticationProtocols: List<AuthenticationProtocolInitiator>
    ) {
        logger.info("QQQQ PPP updateAfterSessionEstablished( " +
                "${sessionCounterparties.counterpartyId.x500Name}, ${authenticationProtocols.map { it.sessionId }})" +
                " ${Thread.currentThread().id}")
        outboundSessions.compute(sessionCounterparties) { _, _ ->
            val sessionsMap = ConcurrentHashMap<String, SessionType>()
            authenticationProtocols.forEach { sessionsMap[it.sessionId] = SessionType.PendingSession(sessionCounterparties, it) }
            sessionsMap
        }
        authenticationProtocols.forEach{ counterpartiesForSessionId[it.sessionId] = sessionCounterparties }
    }

    /**
     * Remove a single [AuthenticationProtocolInitiator] or [Session] in the pool and replace it
     * with a [AuthenticationProtocolInitiator].
     */
    fun replaceSession(timedOutSessionId: String,
                       newPendingSession: AuthenticationProtocolInitiator
    ): Boolean {
        var removed = false
        val counterparties = counterpartiesForSessionId[timedOutSessionId] ?: return removed
        logger.info("QQQQ PPP replaceSession($timedOutSessionId, ${newPendingSession.sessionId})")
        outboundSessions.computeIfPresent(counterparties) { _, sessions ->
            sessions.remove(timedOutSessionId) ?: return@computeIfPresent sessions
            removed = true
            sessions[newPendingSession.sessionId] = SessionType.PendingSession(counterparties, newPendingSession)
            sessions
        }
        counterpartiesForSessionId.remove(timedOutSessionId)
        if (removed) counterpartiesForSessionId[newPendingSession.sessionId] = counterparties
        return removed
    }

    /**
     * Remove all the Sessions in the pool for a set of [sessionCounterparties]
     */
    fun removeSessions(sessionCounterparties: SessionManager.SessionCounterparties) {
        logger.info("QQQQ PPP removeSessions(${sessionCounterparties.ourId.x500Name} -> ${sessionCounterparties.counterpartyId.x500Name})")
        val removedSessions = outboundSessions.remove(sessionCounterparties)
        if (removedSessions != null) {
            for (sessionId in removedSessions.keys()) {
                counterpartiesForSessionId.remove(sessionId)
            }
        }
    }

    /**
     * Get all the sessionId's in the pool.
     */
    fun getAllSessionIds(): List<String> {
        return counterpartiesForSessionId.keys.toList()
    }

    /**
     * Remove all sessions in the pool.
     */
    fun clearPool() {
        counterpartiesForSessionId.clear()
        outboundSessions.clear()
    }
}