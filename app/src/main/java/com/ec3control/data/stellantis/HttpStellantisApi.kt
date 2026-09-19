package com.ec3control.data.stellantis

import com.ec3control.network.ApiEnvironment
import com.ec3control.network.HttpTransport
import com.ec3control.network.SessionProvider

class HttpStellantisApi(private val environment: ApiEnvironment, private val endpoints: StellantisEndpoints, private val sessions: SessionProvider, private val transport: HttpTransport, private val parser: StellantisResponseParser) : StellantisApi {
    override suspend fun discoverVehicles(): List<StellantisVehicle> {
        val session = sessions.validSession()
        return parser.vehicles(transport.get(environment.baseUrl + endpoints.vehiclesPath, session.accessToken))
    }
    override suspend fun getStatus(vehicleId: String): StellantisStatus {
        val session = sessions.validSession()
        return parser.status(transport.get(environment.baseUrl + endpoints.statusPath(vehicleId), session.accessToken))
    }
}
