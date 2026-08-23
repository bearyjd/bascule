package com.ventouxlabs.bascule.ui.fake

import com.ventouxlabs.bascule.data.ReadingEntity
import com.ventouxlabs.bascule.data.WeightUnit
import com.ventouxlabs.bascule.network.ConnectionTestResult
import com.ventouxlabs.bascule.network.ContractVersion
import com.ventouxlabs.bascule.network.LoginResult
import com.ventouxlabs.bascule.network.RecentResult
import com.ventouxlabs.bascule.network.SubmitResult
import com.ventouxlabs.bascule.network.VitalForgeApi
import kotlin.time.Duration

/** In-memory [VitalForgeApi] for `ConfigViewModel` tests — only `testConnection()`/`login()` are exercised. */
class FakeVitalForgeApi(
    private var connectionResult: ConnectionTestResult = ConnectionTestResult.Authorized,
    private var loginResult: LoginResult = LoginResult.Success("fake-session-cookie"),
) : VitalForgeApi {

    override val contract: ContractVersion = ContractVersion.V1_WEIGHT_ONLY

    var testConnectionCallCount: Int = 0
        private set

    var loginCallCount: Int = 0
        private set

    override suspend fun submitReading(reading: ReadingEntity, unit: WeightUnit): SubmitResult =
        error("not used by ConfigViewModel tests")

    override suspend fun recentReadings(within: Duration): RecentResult =
        error("not used by ConfigViewModel tests")

    override suspend fun testConnection(): ConnectionTestResult {
        testConnectionCallCount++
        return connectionResult
    }

    override suspend fun login(username: String, password: String): LoginResult {
        loginCallCount++
        return loginResult
    }

    fun setResult(result: ConnectionTestResult) {
        connectionResult = result
    }

    fun setLoginResult(result: LoginResult) {
        loginResult = result
    }
}
