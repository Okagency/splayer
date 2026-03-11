package com.splayer.video.ui.replace

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.splayer.video.R
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class VideoReplaceActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "VideoReplaceActivity"
        // 파일명에서 _kf{from}_kf{to} 패턴 추출
        private val KF_PATTERN = Regex("_kf(\\d+)_kf(\\d+)")
    }

    // UI
    private lateinit var toolbar: MaterialToolbar
    private lateinit var btnSelectOriginal: Button
    private lateinit var txtOriginalName: TextView
    private lateinit var txtOriginalInfo: TextView
    private lateinit var btnAddReplacement: Button
    private lateinit var txtReplaceGuide: TextView
    private lateinit var replaceSegmentList: RecyclerView
    private lateinit var btnOutputFolder: Button
    private lateinit var btnStartReplace: Button

    // Data
    private var originalUri: Uri? = null
    private var originalDisplayName: String = ""
    private val keyframes = mutableListOf<Long>() // 키프레임 위치 (ms)
    private val segments = mutableListOf<ReplaceSegment>()
    private var segmentAdapter: ReplaceSegmentAdapter? = null
    private var outputFolderUri: Uri? = null
    private var videoDurationMs: Long = 0L

    private val originalPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            loadOriginalVideo(it)
        }
    }

    private val replacementPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@registerForActivityResult
        for (uri in uris) {
            try {
                contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            addReplacementFile(uri)
        }
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(it,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (_: Exception) {}
            outputFolderUri = it
            btnOutputFolder.text = "저장: ${getLastPathSegment(it)}"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_replace)
        setupViews()
    }

    private fun setupViews() {
        toolbar = findViewById(R.id.replaceToolbar)
        btnSelectOriginal = findViewById(R.id.btnSelectOriginal)
        txtOriginalName = findViewById(R.id.txtOriginalName)
        txtOriginalInfo = findViewById(R.id.txtOriginalInfo)
        btnAddReplacement = findViewById(R.id.btnAddReplacement)
        txtReplaceGuide = findViewById(R.id.txtReplaceGuide)
        replaceSegmentList = findViewById(R.id.replaceSegmentList)
        btnOutputFolder = findViewById(R.id.btnOutputFolder)
        btnStartReplace = findViewById(R.id.btnStartReplace)

        toolbar.setNavigationOnClickListener { finish() }

        segmentAdapter = ReplaceSegmentAdapter(
            segments = segments,
            onDelete = { pos ->
                segments.removeAt(pos)
                segmentAdapter?.notifyItemRemoved(pos)
                segmentAdapter?.notifyItemRangeChanged(pos, segments.size - pos)
                updateUI()
            }
        )
        replaceSegmentList.layoutManager = LinearLayoutManager(this)
        replaceSegmentList.adapter = segmentAdapter

        btnSelectOriginal.setOnClickListener {
            originalPickerLauncher.launch("video/*")
        }

        btnAddReplacement.setOnClickListener {
            replacementPickerLauncher.launch(arrayOf("video/*"))
        }

        btnOutputFolder.setOnClickListener {
            folderPickerLauncher.launch(null)
        }

        btnStartReplace.setOnClickListener {
            if (segments.isNotEmpty() && originalUri != null) {
                startReplace()
            }
        }
    }

    private fun loadOriginalVideo(uri: Uri) {
        originalUri = uri
        originalDisplayName = getDisplayName(uri)
        segments.clear()
        segmentAdapter?.notifyDataSetChanged()

        txtOriginalName.text = originalDisplayName
        txtOriginalInfo.text = "키프레임 스캔 중..."
        txtOriginalInfo.visibility = View.VISIBLE
        btnSelectOriginal.text = "영상 변경"

        lifecycleScope.launch(Dispatchers.IO) {
            scanKeyframes(uri)
            withContext(Dispatchers.Main) {
                txtOriginalInfo.text = "키프레임 ${keyframes.size}개"
                btnAddReplacement.isEnabled = true
                txtReplaceGuide.text = "교체할 세그먼트 파일을 추가하세요"
                updateUI()
            }
        }
    }

    private fun addReplacementFile(uri: Uri) {
        val name = getDisplayName(uri)
        val kfRange = parseKfRange(name)
        if (kfRange == null) {
            Toast.makeText(this, "파일명에서 키프레임 범위를 인식할 수 없습니다: $name\n(_kf{from}_{to} 형식 필요)", Toast.LENGTH_LONG).show()
            return
        }
        val (from, to) = kfRange
        if (from >= keyframes.size || to >= keyframes.size) {
            Toast.makeText(this, "키프레임 범위가 원본을 초과합니다: KF #$from~#$to (원본: 0~${keyframes.size - 1})", Toast.LENGTH_LONG).show()
            return
        }
        // 겹침 검사
        for (existing in segments) {
            if (from <= existing.toKfIndex && to >= existing.fromKfIndex) {
                Toast.makeText(this, "기존 세그먼트(KF #${existing.fromKfIndex}~#${existing.toKfIndex})와 구간이 겹칩니다.", Toast.LENGTH_LONG).show()
                return
            }
        }
        segments.add(ReplaceSegment(uri, name, from, to))
        segments.sortBy { it.fromKfIndex }
        segmentAdapter?.notifyDataSetChanged()
        updateUI()
    }

    private fun parseKfRange(fileName: String): Pair<Int, Int>? {
        val match = KF_PATTERN.find(fileName) ?: return null
        val from = match.groupValues[1].toIntOrNull() ?: return null
        val to = match.groupValues[2].toIntOrNull() ?: return null
        if (from > to) return null
        return Pair(from, to)
    }

    private fun updateUI() {
        val hasSegments = segments.isNotEmpty()
        txtReplaceGuide.visibility = if (hasSegments) View.GONE else View.VISIBLE
        btnStartReplace.isEnabled = hasSegments && originalUri != null
        btnStartReplace.text = if (hasSegments) "패치 시작 (${segments.size}개)" else "패치 시작"
    }

    private fun startReplace() {
        val uri = originalUri ?: return
        val sortedSegments = segments.sortedBy { it.fromKfIndex }

        val cancelled = AtomicBoolean(false)
        val progressDialog = android.app.ProgressDialog(this).apply {
            setTitle("패치 중")
            setMessage("준비 중...")
            setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            progress = 0
            isIndeterminate = false
            setCancelable(false)
            setButton(android.app.ProgressDialog.BUTTON_NEGATIVE, "취소") { _, _ ->
                cancelled.set(true)
                try { com.arthenica.ffmpegkit.FFmpegKit.cancel() } catch (_: Throwable) {}
            }
            show()
        }

        val baseName = originalDisplayName.substringBeforeLast(".")
        // 중복 방지: p_Doubt.mp4 → p_Doubt(2).mp4 → p_Doubt(3).mp4 ...
        var num = 1
        var outputFileName = "p_${baseName}.mp4"
        val outputDir = outputFolderUri
        if (outputDir == null) {
            val moviesDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES)
            while (File(moviesDir, outputFileName).exists()) {
                num++
                outputFileName = "p_${baseName}($num).mp4"
            }
        } else {
            val docDir = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, outputDir)
            while (docDir?.findFile(outputFileName) != null) {
                num++
                outputFileName = "p_${baseName}($num).mp4"
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val tempFiles = mutableListOf<File>()
            var tempInputFile: File? = null
            val concatListFile = File(cacheDir, "replace_concat_${System.currentTimeMillis()}.txt")
            val tempOutputFile = File(cacheDir, "ffmpeg_replace_${System.currentTimeMillis()}.mp4")

            try {
                val isContentUri = uri.toString().startsWith("content://")

                // content:// URI인 경우 임시 파일로 복사 (SAF fd 재사용 문제 방지)
                val ffmpegOriginalInput = if (isContentUri) {
                    withContext(Dispatchers.Main) {
                        progressDialog.setMessage("원본 영상 준비 중...")
                    }
                    val tmp = File(cacheDir, "replace_input_${System.currentTimeMillis()}.mp4")
                    contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw Exception("원본 영상을 열 수 없습니다")
                    tempInputFile = tmp
                    Log.d(TAG, "원본 임시파일 크기: ${tmp.length()} bytes (${tmp.length() / 1024 / 1024} MB)")
                    tmp.absolutePath
                } else {
                    uri.toString()
                }

                // 1단계: 갭 구간과 교체 파일 순서 결정
                data class ConcatPart(val isGap: Boolean, val fromKf: Int, val toKf: Int, val replacementUri: Uri? = null)

                val parts = mutableListOf<ConcatPart>()
                var currentKf = 0

                for (seg in sortedSegments) {
                    // 갭 구간 (현재 위치 ~ 교체 시작 전)
                    if (currentKf < seg.fromKfIndex) {
                        parts.add(ConcatPart(isGap = true, fromKf = currentKf, toKf = seg.fromKfIndex - 1))
                    }
                    // 교체 세그먼트
                    parts.add(ConcatPart(isGap = false, fromKf = seg.fromKfIndex, toKf = seg.toKfIndex, replacementUri = seg.uri))
                    currentKf = seg.toKfIndex + 1
                }
                // 마지막 갭 구간
                if (currentKf < keyframes.size) {
                    parts.add(ConcatPart(isGap = true, fromKf = currentKf, toKf = keyframes.size - 1))
                }

                val totalParts = parts.size
                val concatTempFiles = mutableListOf<File>()
                var maxProgress = 5 // 진행률은 절대 내려가지 않음

                // 2단계: 각 파트를 임시 파일로 준비
                for ((index, part) in parts.withIndex()) {
                    if (cancelled.get()) break

                    val partProgressStart = 5 + (index * 65 / totalParts)
                    val partProgressEnd = 5 + ((index + 1) * 65 / totalParts)

                    withContext(Dispatchers.Main) {
                        progressDialog.setMessage("파트 ${index + 1}/$totalParts 처리 중...")
                        if (partProgressStart > maxProgress) {
                            maxProgress = partProgressStart
                            progressDialog.progress = partProgressStart
                        }
                    }

                    if (part.isGap) {
                        // 원본에서 갭 구간 추출

                        val startMs = keyframes[part.fromKf]
                        val endMs = if (part.toKf + 1 < keyframes.size) {
                            keyframes[part.toKf + 1]
                        } else {
                            // 마지막 키프레임 이후 → 영상 끝까지
                            Long.MAX_VALUE
                        }

                        val gapDurationMs = if (endMs == Long.MAX_VALUE) {
                            (videoDurationMs - startMs).coerceAtLeast(1)
                        } else {
                            (endMs - startMs).coerceAtLeast(1)
                        }

                        // 실시간 진행률 콜백
                        com.arthenica.ffmpegkit.FFmpegKitConfig.enableStatisticsCallback { stats ->
                            val timeMs = stats.time.toLong()
                            if (timeMs > 0) {
                                val partPct = ((timeMs * 100) / gapDurationMs).toInt().coerceIn(0, 100)
                                val overall = partProgressStart + (partPct * (partProgressEnd - partProgressStart) / 100)
                                if (overall > maxProgress) {
                                    maxProgress = overall
                                    runOnUiThread { progressDialog.progress = overall }
                                }
                            }
                        }

                        val startSec = "%.3f".format(java.util.Locale.US, startMs / 1000.0)
                        val tempFile = File(cacheDir, "replace_gap_${index}_${System.currentTimeMillis()}.mp4")
                        tempFiles.add(tempFile)
                        concatTempFiles.add(tempFile)

                        if (endMs == Long.MAX_VALUE) {
                            // 영상 끝까지
                            Log.d(TAG, "갭 추출: KF#${part.fromKf}~끝, ss=${startSec}s")
                            executeFFmpeg(
                                arrayOf("-y", "-ss", startSec, "-i", ffmpegOriginalInput, "-c", "copy", tempFile.absolutePath)
                            )
                        } else {
                            val durationSec = "%.3f".format(java.util.Locale.US, (endMs - startMs) / 1000.0)
                            Log.d(TAG, "갭 추출: KF#${part.fromKf}~#${part.toKf}, ss=${startSec}s, t=${durationSec}s")
                            executeFFmpeg(
                                arrayOf("-y", "-ss", startSec, "-i", ffmpegOriginalInput, "-t", durationSec, "-c", "copy", tempFile.absolutePath)
                            )
                        }
                        Log.d(TAG, "갭 결과: ${tempFile.name} = ${tempFile.length()} bytes")
                        if (!tempFile.exists() || tempFile.length() == 0L) {
                            throw Exception("갭 구간 추출 실패 (KF #${part.fromKf})")
                        }
                    } else {
                        // 교체 파일을 임시 파일로 복사
                        val tempFile = File(cacheDir, "replace_seg_${index}_${System.currentTimeMillis()}.mp4")
                        tempFiles.add(tempFile)
                        concatTempFiles.add(tempFile)
                        contentResolver.openInputStream(part.replacementUri!!)?.use { input ->
                            tempFile.outputStream().use { output -> input.copyTo(output) }
                        } ?: throw Exception("교체 파일을 읽을 수 없습니다")
                        Log.d(TAG, "교체 파일 복사: ${tempFile.name} = ${tempFile.length()} bytes")
                    }

                    withContext(Dispatchers.Main) {
                        if (partProgressEnd > maxProgress) {
                            maxProgress = partProgressEnd
                            progressDialog.progress = partProgressEnd
                        }
                    }
                }

                if (cancelled.get()) throw kotlinx.coroutines.CancellationException()

                // 3단계: concat 리스트 작성
                val concatContent = concatTempFiles.joinToString("\n") {
                    "file '${it.absolutePath.replace("'", "'\\''")}'"
                }
                concatListFile.writeText(concatContent)
                Log.d(TAG, "concat 목록:\n$concatContent")

                // 4단계: FFmpeg concat 실행
                withContext(Dispatchers.Main) {
                    progressDialog.setMessage("병합 중...\n$outputFileName")
                    if (70 > maxProgress) {
                        maxProgress = 70
                        progressDialog.progress = 70
                    }
                }

                // concat 실시간 진행률 (70-88%)
                if (videoDurationMs > 0) {
                    com.arthenica.ffmpegkit.FFmpegKitConfig.enableStatisticsCallback { stats ->
                        val timeMs = stats.time.toLong()
                        if (timeMs > 0) {
                            val pct = ((timeMs * 100) / videoDurationMs).toInt().coerceIn(0, 100)
                            val overall = 70 + (pct * 18 / 100)
                            if (overall > maxProgress) {
                                maxProgress = overall
                                runOnUiThread { progressDialog.progress = overall }
                            }
                        }
                    }
                }

                executeFFmpeg(
                    arrayOf(
                        "-y", "-f", "concat", "-safe", "0",
                        "-i", concatListFile.absolutePath,
                        "-c", "copy", "-avoid_negative_ts", "make_zero",
                        tempOutputFile.absolutePath
                    )
                )

                if (cancelled.get()) throw kotlinx.coroutines.CancellationException()

                Log.d(TAG, "concat 결과: ${tempOutputFile.length()} bytes")
                if (!tempOutputFile.exists() || tempOutputFile.length() == 0L) {
                    throw Exception("FFmpeg 패치 실패")
                }

                // 5단계: 저장
                withContext(Dispatchers.Main) {
                    progressDialog.setMessage("저장 중...")
                    progressDialog.progress = 88
                }

                if (outputFolderUri != null) {
                    saveToFolder(outputFolderUri!!, tempOutputFile, outputFileName)
                } else {
                    saveToMediaStore(tempOutputFile, outputFileName)
                }

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(this@VideoReplaceActivity, "패치 완료: $outputFileName", Toast.LENGTH_SHORT).show()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(this@VideoReplaceActivity, "패치 취소됨", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(this@VideoReplaceActivity, "패치 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
                Log.e(TAG, "Replace failed", e)
            } finally {
                // 행 걸린 FFmpegKit 세션 정리
                try { com.arthenica.ffmpegkit.FFmpegKit.cancel() } catch (_: Throwable) {}
                tempFiles.forEach { it.delete() }
                tempInputFile?.delete()
                concatListFile.delete()
                tempOutputFile.delete()
            }
        }
    }

    private suspend fun executeFFmpeg(args: Array<String>) {
        withContext(Dispatchers.IO) {
            Log.d(TAG, "FFmpeg 실행: ${args.joinToString(" ")}")

            val session = com.arthenica.ffmpegkit.FFmpegKit.execute(args.joinToString(" "))
            val returnCode = session.returnCode

            if (com.arthenica.ffmpegkit.ReturnCode.isSuccess(returnCode)) {
                Log.d(TAG, "FFmpeg 성공")
            } else {
                val logs = session.allLogsAsString ?: ""
                Log.e(TAG, "FFmpeg 실패 (rc=${returnCode}): ${logs.takeLast(2000)}")
            }
        }
    }

    private fun scanKeyframes(uri: Uri) {
        keyframes.clear()
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(this, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    extractor.selectTrack(i)
                    videoDurationMs = try {
                        format.getLong(MediaFormat.KEY_DURATION) / 1000L
                    } catch (_: Exception) { 0L }
                    break
                }
            }
            // advance() + SAMPLE_FLAG_SYNC 방식 (IO 스레드에서 실행되므로 ANR 없음)
            while (true) {
                val sampleTime = extractor.sampleTime
                if (sampleTime < 0) break
                val flags = extractor.sampleFlags
                if (flags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    keyframes.add(sampleTime / 1000L)
                }
                extractor.advance()
            }
            Log.d(TAG, "키프레임 ${keyframes.size}개 발견")
        } catch (e: Throwable) {
            Log.e(TAG, "키프레임 스캔 실패", e)
        } finally {
            extractor.release()
        }
    }

    private fun saveToMediaStore(sourceFile: File, outputFileName: String) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, outputFileName)
                put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_MOVIES)
            }
            val uri = contentResolver.insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { os ->
                    sourceFile.inputStream().use { it.copyTo(os) }
                }
            }
        } else {
            val outputDir = getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)
            outputDir?.mkdirs()
            sourceFile.copyTo(File(outputDir, outputFileName), overwrite = true)
        }
    }

    private fun saveToFolder(folderUri: Uri, sourceFile: File, outputFileName: String) {
        val docUri = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, folderUri)
        val outDoc = docUri?.createFile("video/mp4", outputFileName) ?: throw Exception("파일 생성 실패")
        contentResolver.openOutputStream(outDoc.uri)?.use { os ->
            sourceFile.inputStream().use { it.copyTo(os) }
        } ?: throw Exception("출력 스트림 열기 실패")
    }

    private fun getDisplayName(uri: Uri): String {
        var name = "video"
        try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getString(0) ?: "video"
                }
            }
        } catch (_: Exception) {}
        return name
    }

    private fun getLastPathSegment(uri: Uri): String {
        val path = uri.lastPathSegment ?: uri.toString()
        return path.substringAfterLast(":")
            .substringAfterLast("/")
            .ifEmpty { path }
    }
}
