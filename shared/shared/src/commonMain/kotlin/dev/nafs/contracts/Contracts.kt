package dev.nafs.contracts

import kotlinx.serialization.Serializable

@Serializable
enum class PrayerName { FAJR, DHUHR, ASR, MAGHRIB, ISHA }

@Serializable
enum class SalahStatus { NOT_RECORDED, COMPLETED, MISSED, EXCUSED_OR_OTHER }

@Serializable
data class PrayerCardDto(
    val prayer: PrayerName,
    val calculatedTime: String,
    val verifiedJamaahTime: String?,
    val jamaahSourceLabel: String?,
    val status: SalahStatus,
    val personalNote: String?
)