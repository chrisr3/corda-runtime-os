package net.cordapp.demo.utxo

import net.corda.v5.base.types.MemberX500Name

data class CreateAndIssueAppleStampRequest(val stampDescription: String, val holder: MemberX500Name)