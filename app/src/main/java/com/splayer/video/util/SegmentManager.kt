package com.splayer.video.util

import android.content.Context
import com.splayer.video.data.model.PlaybackSegment
import java.io.File

class SegmentManager(private val context: Context) {

    companion object {
        const val PREF_SEGMENT_PATH_TYPE = "segment_path_type"
        const val PATH_TYPE_SUBTITLES = 0    // /storage/emulated/0/Subtitles/
        const val PATH_TYPE_APP_EXTERNAL = 1  // 앱 외부 저장소
        const val PATH_TYPE_APP_CACHE = 2     // 앱 캐시
    }

    private val prefs by lazy {
        context.getSharedPreferences("splayer_settings", Context.MODE_PRIVATE)
    }

    private val segmentFile: File
        get() {
            // 사용자가 설정한 경로 사용
            val customPath = prefs.getString("segment_file_path", null)
            return if (customPath != null) {
                File(customPath, "splayer.per")
            } else {
                // 기본값: 앱 외부 저장소
                File(context.getExternalFilesDir(null), "splayer.per")
            }
        }

    private fun getFileForPathType(pathType: Int): File {
        return when (pathType) {
            PATH_TYPE_SUBTITLES -> {
                val subtitlesDir = File("/storage/emulated/0/Subtitles")
                try {
                    // Android 13+ 에서는 이 경로에 쓰기 권한이 없을 수 있음
                    if (!subtitlesDir.exists()) {
                        val created = subtitlesDir.mkdirs()
                        android.util.Log.d("SegmentManager", "Subtitles 디렉토리 생성: $created")
                    }

                    // 쓰기 테스트
                    val testFile = File(subtitlesDir, "splayer.per")
                    if (!testFile.exists()) {
                        testFile.createNewFile()
                    }

                    if (testFile.canWrite()) {
                        android.util.Log.d("SegmentManager", "Subtitles 경로 사용: ${testFile.absolutePath}")
                        testFile
                    } else {
                        throw SecurityException("No write permission")
                    }
                } catch (e: Exception) {
                    android.util.Log.w("SegmentManager", "Subtitles 경로 사용 불가, 앱 저장소로 폴백: ${e.message}")
                    e.printStackTrace()
                    // 실패 시 앱 외부 저장소로 폴백
                    File(context.getExternalFilesDir(null), "splayer.per")
                }
            }
            PATH_TYPE_APP_EXTERNAL -> {
                File(context.getExternalFilesDir(null), "splayer.per")
            }
            PATH_TYPE_APP_CACHE -> {
                File(context.cacheDir, "splayer.per")
            }
            else -> File(context.getExternalFilesDir(null), "splayer.per")
        }
    }

    /**
     * 구간 파일 경로 가져오기
     */
    fun getSegmentFilePath(): String {
        return segmentFile.absolutePath
    }

    /**
     * 모든 구간을 가져옴
     */
    fun getAllSegments(): List<PlaybackSegment> {
        if (!segmentFile.exists()) return emptyList()

        return try {
            segmentFile.readLines()
                .mapNotNull { PlaybackSegment.fromFileString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 특정 파일의 구간들을 가져옴 (순번 순으로 정렬)
     */
    fun getSegmentsForFile(fileName: String): List<PlaybackSegment> {
        return getAllSegments()
            .filter { it.fileName == fileName }
            .sortedBy { it.sequence }
    }

    /**
     * 구간 시작 시간 저장
     */
    fun saveSegmentStart(fileName: String, startTime: Long): Int {
        android.util.Log.d("SegmentManager", "saveSegmentStart 호출: fileName=$fileName, startTime=$startTime")

        val segments = getAllSegments().toMutableList()
        android.util.Log.d("SegmentManager", "기존 구간 수: ${segments.size}")

        val existingForFile = segments.filter { it.fileName == fileName }

        // 다음 순번 결정
        val nextSequence = if (existingForFile.isEmpty()) {
            1
        } else {
            existingForFile.maxOf { it.sequence } + 1
        }

        // 새 구간 추가 (종료 시간은 -1로 임시 저장)
        val newSegment = PlaybackSegment(
            fileName = fileName,
            sequence = nextSequence,
            startTime = startTime,
            endTime = -1
        )

        segments.add(newSegment)
        android.util.Log.d("SegmentManager", "새 구간 추가: $newSegment")
        android.util.Log.d("SegmentManager", "총 구간 수: ${segments.size}")

        saveAllSegments(segments)

        return nextSequence
    }

    /**
     * 구간 종료 시간 저장
     */
    fun saveSegmentEnd(fileName: String, endTime: Long): Boolean {
        val segments = getAllSegments().toMutableList()

        // 해당 파일의 종료 시간이 -1인 가장 최근 구간 찾기
        val targetSegment = segments
            .filter { it.fileName == fileName && it.endTime == -1L }
            .maxByOrNull { it.sequence }

        return if (targetSegment != null) {
            // 구간 업데이트
            val index = segments.indexOf(targetSegment)
            segments[index] = targetSegment.copy(endTime = endTime)
            saveAllSegments(segments)
            true
        } else {
            false
        }
    }

    /**
     * 특정 구간 삭제
     */
    fun deleteSegment(fileName: String, sequence: Int) {
        val segments = getAllSegments().toMutableList()
        segments.removeAll { it.fileName == fileName && it.sequence == sequence }

        // 순번 재조정
        val reorderedSegments = segments
            .groupBy { it.fileName }
            .flatMap { (file, fileSegments) ->
                fileSegments
                    .sortedBy { it.sequence }
                    .mapIndexed { index, segment ->
                        segment.copy(sequence = index + 1)
                    }
            }

        saveAllSegments(reorderedSegments)
    }

    /**
     * 구간 순서 변경
     */
    fun reorderSegments(fileName: String, newOrder: List<PlaybackSegment>) {
        val allSegments = getAllSegments().toMutableList()

        // 해당 파일의 구간 제거
        allSegments.removeAll { it.fileName == fileName }

        // 새 순서로 순번 업데이트
        val reorderedSegments = newOrder.mapIndexed { index, segment ->
            segment.copy(sequence = index + 1)
        }

        allSegments.addAll(reorderedSegments)
        saveAllSegments(allSegments)
    }

    /**
     * 특정 파일의 모든 구간 삭제
     */
    fun deleteAllSegmentsForFile(fileName: String) {
        val segments = getAllSegments().toMutableList()
        segments.removeAll { it.fileName == fileName }
        saveAllSegments(segments)
    }

    /**
     * 모든 구간 정보 삭제 (파일 삭제)
     */
    fun deleteAllSegmentsFile() {
        try {
            if (segmentFile.exists()) {
                val deleted = segmentFile.delete()
                android.util.Log.d("SegmentManager", "구간 파일 삭제: ${segmentFile.absolutePath}, 성공: $deleted")
            }
        } catch (e: Exception) {
            android.util.Log.e("SegmentManager", "구간 파일 삭제 실패", e)
            e.printStackTrace()
        }
    }

    /**
     * 모든 구간 저장
     */
    private fun saveAllSegments(segments: List<PlaybackSegment>) {
        try {
            // 부모 디렉토리 생성 (파일 존재 여부와 관계없이)
            segmentFile.parentFile?.let { parent ->
                if (!parent.exists()) {
                    val created = parent.mkdirs()
                    android.util.Log.d("SegmentManager", "디렉토리 생성: ${parent.absolutePath}, 성공: $created")
                }
            }

            // 미완료 구간도 포함하여 저장
            val content = if (segments.isEmpty()) {
                "" // 빈 파일 생성
            } else {
                segments.joinToString("\n") { it.toFileString() }
            }

            segmentFile.writeText(content)
            android.util.Log.d("SegmentManager", "파일 저장 완료: ${segmentFile.absolutePath}")
            android.util.Log.d("SegmentManager", "저장된 구간 수: ${segments.size}")
            android.util.Log.d("SegmentManager", "파일 존재: ${segmentFile.exists()}, 크기: ${segmentFile.length()}")
        } catch (e: Exception) {
            android.util.Log.e("SegmentManager", "파일 저장 실패: ${segmentFile.absolutePath}", e)
            e.printStackTrace()
        }
    }

    /**
     * 파일명에서 경로 제거
     */
    fun extractFileName(filePath: String): String {
        // content:// URI인 경우 ContentResolver로 파일명 추출
        if (filePath.startsWith("content://")) {
            try {
                val uri = android.net.Uri.parse(filePath)
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0) {
                            return cursor.getString(nameIndex)
                        }
                    }
                }
                // 쿼리 실패 시 URI의 마지막 경로 세그먼트 사용
                return uri.lastPathSegment ?: "unknown"
            } catch (e: Exception) {
                android.util.Log.e("SegmentManager", "Failed to extract filename from URI", e)
            }
        }

        // 일반 파일 경로인 경우
        return File(filePath).name
    }

    /**
     * 현재 파일에 미완료 구간(F만 누른 상태)이 있는지 확인
     */
    fun hasIncompleteSegment(fileName: String): Boolean {
        return getAllSegments().any { it.fileName == fileName && it.endTime == -1L }
    }
}
