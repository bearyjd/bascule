package com.ventouxlabs.bascule.delivery.fake

import com.ventouxlabs.bascule.data.ReadingEntity
import com.ventouxlabs.bascule.data.WeightUnit
import com.ventouxlabs.bascule.network.ConnectionTestResult
import com.ventouxlabs.bascule.network.ContractVersion
import com.ventouxlabs.bascule.network.LoginResult
import com.ventouxlabs.bascule.network.RecentResult
import com.ventouxlabs.bascule.network.SubmitResult
import com.ventouxlabs.bascule.network.VitalForgeApi
import kotlin.time.Duration

/** In-memory [VitalForgeApi] for [com.ventouxlabs.bascule.delivery.DeliveryDrainer] tests. */
class FakeDeliveryApi(
    override val contract: ContractVersion = ContractVersion.V1_WEIGHT_ONLY,
    private var recentResult: RecentResult = RecentResult.Readings(emptyList()),
    private val submitResults: MutableList<SubmitResult> = mutableListOf(),
) : VitalForgeApi {

    var recentReadingsCallCount: Int = 0
        private set

    val submittedReadingIds: MutableList<String> = mutableListOf()

    override suspend fun submitReading(reading: ReadingEntity, unit: WeightUnit): SubmitResult {
        submittedReadingIds += reading.id
        return if (submitResults.isNotEmpty()) submitResults.removeAt(0) else SubmitResult.Accepted(emptySet())
    }

    override suspend fun recentReadings(within: Duration): RecentResult {
        recentReadingsCallCount++
        return recentResult
    }

    override suspend fun testConnection(): ConnectionTestResult = error("not used by DeliveryDrainer tests")

    override suspend fun login(username: String, password: String): LoginResult =
        error("not used by DeliveryDrainer tests")

    fun setRecentResult(result: RecentResult) {
        recentResult = result
    }

    fun enqueueSubmitResult(result: SubmitResult) {
        submitResults += result
    }
}
