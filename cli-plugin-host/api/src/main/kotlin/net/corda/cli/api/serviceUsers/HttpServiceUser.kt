package net.corda.cli.api.serviceUsers

import net.corda.cli.api.services.HttpService

interface HttpServiceUser {
    var service: HttpService
}