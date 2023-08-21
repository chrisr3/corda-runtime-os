package net.corda.interop.identity.write.impl

import net.corda.data.KeyValuePair
import net.corda.data.KeyValuePairList
import net.corda.data.membership.PersistentMemberInfo
import net.corda.interop.core.InteropIdentity
import net.corda.membership.lib.MemberInfoExtension
import net.corda.messaging.api.publisher.Publisher
import net.corda.messaging.api.records.Record
import net.corda.schema.Schemas
import net.corda.v5.base.types.MemberX500Name
import net.corda.virtualnode.HoldingIdentity
import net.corda.virtualnode.toAvro
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference


@Suppress("ForbiddenComment")
class MembershipInfoProducer(val publisher: AtomicReference<Publisher?>) {
    companion object {
        private val log = LoggerFactory.getLogger(this::class.java.enclosingClass)
        private const val INTEROP_ROLE = "interop"
        private const val INTEROP_MAPPING_X500_NAME = "corda.interop.mapping.x500name"
        private const val INTEROP_MAPPING_GROUP = "corda.interop.mapping.group"

        // TODO: CORE-15749 - Key generation and interop certificates
        private val DUMMY_CERTIFICATE =
            this::class.java.getResource("/dummy_certificate.pem")?.readText()
        private val DUMMY_PUBLIC_SESSION_KEY =
            this::class.java.getResource("/dummy_session_key.pem")?.readText()

        /**
         * Creates member info record for each [newInteropIdentities] for publishing into the
         * view of [ownedInteropIdentity].
         */
        private fun createInteropIdentityMemberInfo(
            realHoldingIdentity: HoldingIdentity,
            ownedInteropIdentity: InteropIdentity,
            newInteropIdentities: List<InteropIdentity>
        ): List<Record<String, PersistentMemberInfo>> {

            // TODO: CORE-15749 - Key generation and interop certificates
            val sessionKeyHash = "9DEA9C982267BD142162ADC141C1C11C2F547C3C37B4C693A3EA3A017C2C6563"
            val ledgerKeyHashesKey = "DFE65EAD29C556DF3A9C94C1A0F2C2155FFCC0768A282E18985BB021E8103B9D"

            val mgmContext = listOf(
                KeyValuePair(MemberInfoExtension.STATUS, "ACTIVE"),
                KeyValuePair(MemberInfoExtension.MODIFIED_TIME, Instant.now().toString()),
                KeyValuePair(MemberInfoExtension.SERIAL, "1"),
            ).sorted()

            val viewOwningMemberHoldingIdentity = HoldingIdentity(
                MemberX500Name.parse(ownedInteropIdentity.x500Name), ownedInteropIdentity.groupId)

            //todo CORE-16385 Remove hardcoded values
            return newInteropIdentities.map { identityToPublish ->

                val memberContext = listOf(
                    KeyValuePair(MemberInfoExtension.PARTY_NAME, identityToPublish.x500Name),
                    KeyValuePair(String.format(MemberInfoExtension.URL_KEY, "0"), identityToPublish.endpointUrl),
                    KeyValuePair(String.format(MemberInfoExtension.PROTOCOL_VERSION, "0"), identityToPublish.endpointProtocol),
                    KeyValuePair(String.format(MemberInfoExtension.PARTY_SESSION_KEYS, 0), DUMMY_CERTIFICATE),
                    KeyValuePair(MemberInfoExtension.SESSION_KEYS_HASH.format(0), sessionKeyHash),
                    KeyValuePair(MemberInfoExtension.GROUP_ID, ownedInteropIdentity.groupId),
                    KeyValuePair(MemberInfoExtension.LEDGER_KEYS_KEY.format(0), DUMMY_PUBLIC_SESSION_KEY),
                    KeyValuePair(MemberInfoExtension.LEDGER_KEY_HASHES_KEY.format(0), ledgerKeyHashesKey),
                    KeyValuePair(MemberInfoExtension.LEDGER_KEY_SIGNATURE_SPEC.format(0), "SHA256withECDSA"),
                    KeyValuePair(MemberInfoExtension.SOFTWARE_VERSION, "5.0.0.0-Fox10-RC03"),
                    KeyValuePair(MemberInfoExtension.PLATFORM_VERSION, "5000"),
                    KeyValuePair(MemberInfoExtension.INTEROP_ROLE, INTEROP_ROLE),
                    KeyValuePair(INTEROP_MAPPING_X500_NAME, realHoldingIdentity.x500Name.toString()),
                    KeyValuePair(INTEROP_MAPPING_GROUP, realHoldingIdentity.groupId)
                ).sorted()

                Record(
                    Schemas.Membership.MEMBER_LIST_TOPIC,
                    "${ownedInteropIdentity.shortHash}-${identityToPublish.shortHash}",
                    PersistentMemberInfo(
                        viewOwningMemberHoldingIdentity.copy(groupId = ownedInteropIdentity.groupId).toAvro(),
                        KeyValuePairList(memberContext),
                        KeyValuePairList(mgmContext)
                    )
                )
            }
        }
    }

    fun publishMemberInfo(
        realHoldingIdentity: HoldingIdentity,
        ownedInteropIdentity: InteropIdentity,
        newInteropIdentities: List<InteropIdentity>
    ) {
        if (publisher.get() == null) {
            log.error("Member info publisher is null, not publishing.")
            return
        }

        val memberInfoList = createInteropIdentityMemberInfo(realHoldingIdentity, ownedInteropIdentity, newInteropIdentities)

        publisher.get()!!.publish(memberInfoList)
    }
}
