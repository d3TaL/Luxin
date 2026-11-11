package io.github.d3tal.luxin.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OAuthTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "bearer",
    @SerialName("expires_in") val expiresIn: Long = 1799
)

@Serializable
data class ApiEnvelope<T>(
    val error: Boolean = false,
    val errorStatus: Int = 200,
    val message: String? = null,
    val data: T? = null
)

@Serializable
data class Md5Entry(
    val md5: String,
    val time: Long = 0L
)

@Serializable
data class KongyingArea(
    val id: Long,
    val name: String = "",
    val code: String = "",
    val parentId: Long = -1L,
    val isFinal: Boolean = false,
    val hiddenFlag: Int = 0
)

@Serializable
data class KongyingItem(
    val id: Long,
    val name: String = "",
    val areaId: Long = 0L,
    val iconId: Long = 0L,
    val typeIdList: List<Long> = emptyList(),
    val hiddenFlag: Int = 0
)

@Serializable
data class MarkerItemLink(
    val itemId: Long,
    val iconId: Long = 0L,
    val count: Int = 1
)

@Serializable
data class MarkerUnderground(
    @SerialName("is_underground") val isUnderground: Boolean = false,
    @SerialName("region_levels") val regionLevels: List<String> = emptyList()
)

@Serializable
data class MarkerExtra(
    val underground: MarkerUnderground? = null
)

@Serializable
data class KongyingMarkerDto(
    val id: Long,
    val markerTitle: String = "",
    val position: String = "0,0",
    val content: String = "",
    val picture: String = "",
    val itemList: List<MarkerItemLink> = emptyList(),
    val extra: MarkerExtra? = null,
    val refreshTime: Long = 0L,
    val hiddenFlag: Int = 0
)

@Serializable
data class LocalMarkerRecord(
    val id: Long,
    val title: String,
    val x: Double,
    val y: Double,
    val itemId: Long,
    val iconId: Long,
    val areaId: Long,
    val space: String,
    val isUnderground: Boolean,
    val levels: List<String>
)
