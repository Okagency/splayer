package com.splayer.video.util

import android.util.Log
import java.io.File
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.*

/**
 * SAMI (SMI) 자막을 SRT 포맷으로 변환하는 유틸리티
 */
object SamiToSrtConverter {

    private const val TAG = "SamiToSrtConverter"

    /**
     * SMI 파일을 SRT로 변환
     * @param smiFile 원본 SMI 파일
     * @param outputFile 출력 SRT 파일
     * @return 성공 여부
     */
    fun convert(smiFile: File, outputFile: File): Boolean {
        return try {
            Log.d(TAG, "SMI 변환 시작: ${smiFile.name} -> ${outputFile.name}")

            // SMI 파일 읽기 (EUC-KR 시도 -> UTF-8 폴백)
            val content = readSmiFile(smiFile)
            if (content.isEmpty()) {
                Log.e(TAG, "SMI 파일이 비어있음")
                return false
            }

            // SYNC 태그 파싱
            val subtitles = parseSamiContent(content)
            if (subtitles.isEmpty()) {
                Log.e(TAG, "파싱된 자막이 없음")
                return false
            }

            Log.d(TAG, "파싱된 자막 수: ${subtitles.size}")

            // SRT 포맷으로 변환하여 저장
            writeSrtFile(outputFile, subtitles)

            Log.d(TAG, "SMI -> SRT 변환 성공")
            true
        } catch (e: Exception) {
            Log.e(TAG, "SMI 변환 오류", e)
            false
        }
    }

    /**
     * SMI 파일 읽기 (인코딩 자동 감지)
     */
    private fun readSmiFile(file: File): String {
        // EUC-KR 시도
        try {
            val content = file.readText(Charset.forName("EUC-KR"))
            if (!content.contains("�")) { // 깨진 문자 체크
                Log.d(TAG, "EUC-KR 인코딩으로 읽기 성공")
                return content
            }
        } catch (e: Exception) {
            Log.w(TAG, "EUC-KR 읽기 실패, UTF-8 시도")
        }

        // UTF-8 시도
        try {
            val content = file.readText(Charsets.UTF_8)
            Log.d(TAG, "UTF-8 인코딩으로 읽기 성공")
            return content
        } catch (e: Exception) {
            Log.w(TAG, "UTF-8 읽기 실패, CP949 시도")
        }

        // CP949 시도 (마지막 시도)
        return try {
            file.readText(Charset.forName("CP949"))
        } catch (e: Exception) {
            Log.e(TAG, "모든 인코딩 시도 실패")
            ""
        }
    }

    /**
     * SAMI 컨텐츠 파싱
     */
    private fun parseSamiContent(content: String): List<SubtitleEntry> {
        val subtitles = mutableListOf<SubtitleEntry>()

        // <SYNC Start=숫자> 패턴 찾기
        val syncPattern = Regex("""<SYNC\s+Start=(\d+)>""", RegexOption.IGNORE_CASE)
        val matches = syncPattern.findAll(content).toList()

        for (i in matches.indices) {
            val match = matches[i]
            val startTime = match.groupValues[1].toLongOrNull() ?: continue

            // 다음 SYNC까지의 텍스트 추출
            val startIndex = match.range.last + 1
            val endIndex = if (i < matches.size - 1) {
                matches[i + 1].range.first
            } else {
                content.length
            }

            val textBlock = content.substring(startIndex, endIndex)

            // HTML 태그 제거 및 텍스트 정리
            val text = cleanText(textBlock)

            if (text.isNotBlank() && text != "&nbsp;") {
                // 종료 시간은 다음 자막 시작 시간 (없으면 +3초)
                val endTime = if (i < matches.size - 1) {
                    matches[i + 1].groupValues[1].toLongOrNull() ?: (startTime + 3000)
                } else {
                    startTime + 3000
                }

                subtitles.add(SubtitleEntry(startTime, endTime, text))
            }
        }

        return subtitles
    }

    /**
     * HTML 태그 제거 및 텍스트 정리
     */
    private fun cleanText(html: String): String {
        return html
            .replace(Regex("""<P[^>]*>""", RegexOption.IGNORE_CASE), "") // <P> 태그 제거
            .replace(Regex("""</P>""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""<font[^>]*>""", RegexOption.IGNORE_CASE), "") // <font> 태그 제거
            .replace(Regex("""</font>""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n") // <br> -> 줄바꿈
            .replace(Regex("""<[^>]+>"""), "") // 나머지 HTML 태그 제거
            .replace("&nbsp;", " ")
            .trim()
    }

    /**
     * SRT 파일 쓰기
     */
    private fun writeSrtFile(file: File, subtitles: List<SubtitleEntry>) {
        file.parentFile?.mkdirs()

        file.bufferedWriter(Charsets.UTF_8).use { writer ->
            subtitles.forEachIndexed { index, subtitle ->
                // 인덱스
                writer.write("${index + 1}\n")

                // 시간 (00:00:00,000 --> 00:00:05,000)
                writer.write("${formatTime(subtitle.startTime)} --> ${formatTime(subtitle.endTime)}\n")

                // 텍스트
                writer.write("${subtitle.text}\n")

                // 빈 줄
                writer.write("\n")
            }
        }
    }

    /**
     * 밀리초를 SRT 시간 포맷으로 변환 (HH:MM:SS,mmm)
     */
    private fun formatTime(milliseconds: Long): String {
        val hours = milliseconds / 3600000
        val minutes = (milliseconds % 3600000) / 60000
        val seconds = (milliseconds % 60000) / 1000
        val millis = milliseconds % 1000

        return String.format("%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }

    /**
     * 자막 엔트리 데이터 클래스
     */
    data class SubtitleEntry(
        val startTime: Long,  // 밀리초
        val endTime: Long,    // 밀리초
        val text: String
    )
}
