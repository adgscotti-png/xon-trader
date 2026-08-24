package com.adgent.trader.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
    val provider: String = "BINANCE",
)

/** Voce watchlist nel backup: coppia canonica + provider (default Binance). */
@Serializable
data class BackupWatchItem(
    val symbol: String,
    val provider: String = "BINANCE",
)

/** Formato file backup: watchlist + avvisi. Versionato per evoluzioni future. */
@Serializable
data class BackupData(
    val app: String = "xon-trader",
    val version: Int = 2,
    val exportedAt: Long,
    val watchlist: List<BackupWatchItem> = emptyList(),
    val alerts: List<BackupAlert> = emptyList(),
)

/** Formato v1 (watchlist come lista di stringhe): letto solo per retro-compatibilità. */
@Serializable
private data class BackupDataV1(
    val version: Int = 1,
    val exportedAt: Long,
    val watchlist: List<String> = emptyList(),
    val alerts: List<BackupAlert> = emptyList(),
)

object BackupCodec {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun encode(data: BackupData): String = json.encodeToString(BackupData.serializer(), data)

    /** Decodifica v2 e, se il file è v1, lo converte (watchlist → provider BINANCE). */
    fun decode(text: String): BackupData? = runCatching {
        val root = json.parseToJsonElement(text)
        val version = root.jsonObject["version"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
        if (version >= 2) json.decodeFromString(BackupData.serializer(), text)
        else json.decodeFromString(BackupDataV1.serializer(), text).toV2()
    }.getOrNull()

    private fun BackupDataV1.toV2() = BackupData(
        version = 2,
        exportedAt = exportedAt,
        watchlist = watchlist.map { BackupWatchItem(it, "BINANCE") },
        alerts = alerts,
    )
}
