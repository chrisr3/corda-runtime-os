package net.corda.membership.impl.registration.dynamic.handler.member

import net.corda.data.identity.HoldingIdentity
import net.corda.data.membership.command.registration.member.PersistMemberRegistrationState
import net.corda.data.membership.state.RegistrationState
import net.corda.membership.impl.registration.dynamic.handler.RegistrationHandler
import net.corda.membership.impl.registration.dynamic.handler.RegistrationHandlerResult
import net.corda.membership.persistence.client.MembershipPersistenceClient
import net.corda.virtualnode.toCorda

internal class PersistMemberRegistrationStateHandler(
    private val membershipPersistenceClient: MembershipPersistenceClient,
) : RegistrationHandler<PersistMemberRegistrationState> {
    override fun invoke(
        state: RegistrationState?,
        key: String,
        command: PersistMemberRegistrationState,
    ): RegistrationHandlerResult {
        val member = command.member.toCorda()
        val commands = membershipPersistenceClient.setRegistrationRequestStatus(
            member,
            command.setStatusRequest.registrationId,
            command.setStatusRequest.newStatus,
        ).createAsyncCommands()
        return RegistrationHandlerResult(
            null,
            commands.toList(),
        )
    }

    override val commandType = PersistMemberRegistrationState::class.java

    override fun getOwnerHoldingId(
        state: RegistrationState?,
        command: PersistMemberRegistrationState
    ): HoldingIdentity = command.member
}
