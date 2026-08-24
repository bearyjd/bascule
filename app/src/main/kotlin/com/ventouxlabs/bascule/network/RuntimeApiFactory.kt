package com.ventouxlabs.bascule.network

import com.ventouxlabs.bascule.data.ConfigStore
import com.ventouxlabs.bascule.data.WeightUnit
import kotlinx.coroutines.flow.first

data class RuntimeApi(val api: VitalForgeApi, val unit: WeightUnit)

/** Reads all mutable delivery configuration anew for each worker run. */
class RuntimeApiFactory(
    private val config: ConfigStore,
    private val tokens: AuthTokenStore,
    private val sessions: SessionCookieStore,
) {
    suspend fun create(): RuntimeApi {
        val baseUrl = config.baseUrl.first().orEmpty()
        val contract = config.contractVersion.first()
        val unit = config.displayUnit.first()
        val shaper = when (contract) {
            ContractVersion.V1_WEIGHT_ONLY -> V1Shaper
            ContractVersion.V2_BODY_COMP -> V2Shaper
        }
        return RuntimeApi(
            VitalForgeHttpClient(
                baseUrl = baseUrl,
                tokenProvider = tokens::token,
                contract = contract,
                shaper = shaper,
                sessionCookieProvider = sessions::cookie,
            ),
            unit,
        )
    }
}
