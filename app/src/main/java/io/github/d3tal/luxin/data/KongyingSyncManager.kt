package io.github.d3tal.luxin.data

import android.content.Context
import io.github.d3tal.luxin.data.KongyingMarkerStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

data class SyncSummary(
    val status: String,
    val areaCount: Int = 0,
    val itemCount: Int = 0,
    val markerChunksTotal: Int = 0,
    val markerCountSample: Int = 0,
    val undergroundMarkerCount: Int = 0,
    val error: String? = null
)

object KongyingSyncManager {

    private const val BASE_URL = "https://cloud.yuanshen.site"
    private const val BASIC_CLIENT_HEADER = "Basic Y2xpZW50OnNlY3JldA=="

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _syncState = MutableStateFlow(
        SyncSummary(status = "Не синхронизировано")
    )
    val syncState: StateFlow<SyncSummary> = _syncState.asStateFlow()

    // Mapping of special area codes to their distinct tile spaces (from research/kongying.md section 4)
    private val areaSpaceOverrides = mapOf(
        "A:MD:SHENDIAN" to "kzsd2",
        "A:LY:CENGYAN_UG" to "cyjy",
        "A:DQ:YUANXIAGONG" to "yxg",
        "A:DQ:SANJIE" to "yxg",
        "A:FD:ANCIENT_SEA" to "jrzh",
        "A:NT:NATA4" to "ygss",
        "A:NDKL:SY" to "moon2",
        "A:VELURIYAM:3_8" to "lxsj",
        "A:SIMULANKA:4_8" to "xmlk",
        "A:APPLE:1_6" to "qd1",
        "A:APPLE:2_8" to "qd28"
    )

    suspend fun syncMetadataAndSampleChunk(context: Context): SyncSummary = withContext(Dispatchers.IO) {
        try {
            _syncState.value = _syncState.value.copy(status = "Получение гостевого OAuth токена...", error = null)
            val token = fetchGuestToken()

            _syncState.value = _syncState.value.copy(status = "Загрузка списка областей (areas)...")
            val areas = fetchAreas(token)
            val areaById = areas.associateBy { it.id }

            _syncState.value = _syncState.value.copy(status = "Загрузка индекса предметов (items MD5)...")
            val itemMd5s = fetchMd5List(token, "/api/item_doc/list_page_bin_md5")
            val allItems = mutableListOf<KongyingItem>()
            for ((idx, entry) in itemMd5s.withIndex()) {
                _syncState.value = _syncState.value.copy(
                    status = "Загрузка чанка предметов ${idx + 1}/${itemMd5s.size}..."
                )
                val chunkBytes = fetchAndCacheGzipChunk(
                    context, token, "items_${entry.md5}.bin", "/api/item_doc/list_page_bin/${entry.md5}"
                )
                val decoded = gunzipToString(chunkBytes)
                allItems += json.decodeFromString(ListSerializer(KongyingItem.serializer()), decoded)
            }
            val itemById = allItems.associateBy { it.id }

            _syncState.value = _syncState.value.copy(status = "Загрузка индекса меток (markers MD5)...")
            val markerMd5s = fetchMd5List(token, "/api/marker_doc/list_page_bin_md5")

            // Загружаем ВСЕ чанки меток (ранее только первый и последний из 46).
            val parsedRecords = mutableListOf<LocalMarkerRecord>()
            for ((idx, entry) in markerMd5s.withIndex()) {
                _syncState.value = _syncState.value.copy(
                    status = "Загрузка и разбор чанка меток ${idx + 1}/${markerMd5s.size}..."
                )
                val chunkBytes = fetchAndCacheGzipChunk(
                    context, token, "markers_${entry.md5}.bin", "/api/marker_doc/list_page_bin/${entry.md5}"
                )
                val decoded = gunzipToString(chunkBytes)
                val rawMarkers = json.decodeFromString(ListSerializer(KongyingMarkerDto.serializer()), decoded)
                for (dto in rawMarkers) {
                    val firstLink = dto.itemList.firstOrNull() ?: continue
                    val item = itemById[firstLink.itemId]
                    val areaId = item?.areaId ?: 0L
                    val areaCode = areaById[areaId]?.code ?: ""
                    val space = areaSpaceOverrides[areaCode] ?: "twt702"
                    val coords = dto.position.split(",")
                    if (coords.size != 2) continue
                    val x = coords[0].trim().toDoubleOrNull() ?: continue
                    val y = coords[1].trim().toDoubleOrNull() ?: continue
                    val ug = dto.extra?.underground
                    parsedRecords += LocalMarkerRecord(
                        id = dto.id,
                        title = dto.markerTitle,
                        x = x,
                        y = y,
                        itemId = firstLink.itemId,
                        iconId = firstLink.iconId,
                        areaId = areaId,
                        space = space,
                        isUnderground = ug?.isUnderground == true,
                        levels = ug?.regionLevels.orEmpty()
                    )
                }
            }

            // Сохраняем все маркеры в хранилище с индексом по'espace.
            KongyingMarkerStore.replaceAll(parsedRecords)

            val undergroundCount = parsedRecords.count { it.isUnderground || it.levels.isNotEmpty() }
            val result = SyncSummary(
                status = "Синхронизировано (кэш обновлён)",
                areaCount = areas.size,
                itemCount = allItems.size,
                markerChunksTotal = markerMd5s.size,
                markerCountSample = parsedRecords.size,
                undergroundMarkerCount = undergroundCount
            )
            _syncState.value = result
            result
        } catch (e: Throwable) {
            e.printStackTrace()
            val failed = _syncState.value.copy(
                status = "Ошибка синхронизации",
                error = "${e.javaClass.simpleName}: ${e.message}"
            )
            _syncState.value = failed
            failed
        }
    }

    private fun fetchGuestToken(): String {
        val req = Request.Builder()
            .url("$BASE_URL/oauth/token?grant_type=client_credentials&refresh_token=all")
            .header("Authorization", BASIC_CLIENT_HEADER)
            .post("".toRequestBody(null))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("OAuth HTTP ${resp.code}")
            }
            val body = resp.body?.string() ?: throw IllegalStateException("Empty OAuth body")
            return json.decodeFromString(OAuthTokenResponse.serializer(), body).accessToken
        }
    }

    private fun fetchAreas(token: String): List<KongyingArea> {
        val reqBody = "{\"parentId\":-1,\"isTraverse\":true}".toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("$BASE_URL/api/area/get/list")
            .header("Authorization", "Bearer $token")
            .post(reqBody)
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Areas HTTP ${resp.code}")
            }
            val body = resp.body?.string() ?: throw IllegalStateException("Empty areas body")
            val env = json.decodeFromString(
                ApiEnvelope.serializer(ListSerializer(KongyingArea.serializer())),
                body
            )
            return env.data.orEmpty()
        }
    }

    private fun fetchMd5List(token: String, path: String): List<Md5Entry> {
        val req = Request.Builder()
            .url("$BASE_URL$path")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("MD5 HTTP ${resp.code} for $path")
            }
            val body = resp.body?.string() ?: throw IllegalStateException("Empty MD5 body")
            val env = json.decodeFromString(
                ApiEnvelope.serializer(ListSerializer(Md5Entry.serializer())),
                body
            )
            return env.data.orEmpty()
        }
    }

    private fun fetchAndCacheGzipChunk(
        context: Context,
        token: String,
        cacheFileName: String,
        path: String
    ): ByteArray {
        val cacheDir = File(context.filesDir, "kongying_cache").apply { mkdirs() }
        val file = File(cacheDir, cacheFileName)
        if (file.exists() && file.length() > 0L) {
            return file.readBytes()
        }
        val req = Request.Builder()
            .url("$BASE_URL$path")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Chunk HTTP ${resp.code} for $path")
            }
            val bytes = resp.body?.bytes() ?: throw IllegalStateException("Empty chunk body")
            file.writeBytes(bytes)
            return bytes
        }
    }

    private fun gunzipToString(compressed: ByteArray): String {
        GZIPInputStream(compressed.inputStream()).bufferedReader(Charsets.UTF_8).use {
            return it.readText()
        }
    }
}
