package com.adgent.trader.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Riga avviso nel file di backup (senza id: all'import sono riassegnati). */
@Serializable
data class BackupAlert(
    val symbol: String,
    val type: String,
    val threshold: Double,
    val repeatable: Boolean = false,
    val note: String = "",
    val enabled: Boolean = true,
    val createdAt: Long,
)

/** Formato file backup: watchlist + avvisi. Versionato per evoluzioni future. */
@Serializable
data class BackupData(
    val app: String = "adgent-trader",
    val version: Int = 1,
    val exportedAt: Long,
    val watchlist: List<String> = emptyList(),
    val alerts: List<BackupAlert> = emptyList(),
)

object BackupCodec {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun encode(data: BackupData): String = json.encodeToString(BackupData.serializer(), data)

    fun decode(text: String): BackupData? =
        runCatching { json.decodeFromString(BackupData.serializer(), text) }.getOrNull()
}
