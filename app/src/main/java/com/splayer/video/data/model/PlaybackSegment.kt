package com.splayer.video.data.model

data class PlaybackSegment(
    val fileName: String,      // 파일명 (경로 제외)
    val sequence: Int,         // 순번
    val startTime: Long,       // 시작 시간 (밀리초)
    val endTime: Long          // 종료 시간 (밀리초)
) {
    fun toFileString(): String {
        return "$fileName|$sequence|$startTime|$endTime"
    }

    companion object {
        fun fromFileString(line: String): PlaybackSegment? {
            return try {
                val parts = line.split("|")
                if (parts.size == 4) {
                    PlaybackSegment(
                        fileName = parts[0],
                        sequence = parts[1].toInt(),
                        startTime = parts[2].toLong(),
                        endTime = parts[3].toLong()
                    )
                } else null
            } catch (e: Exception) {
                null
            }
        }
    }
}
