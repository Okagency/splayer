package com.splayer.video.ui.split

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.splayer.video.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class VideoSplitActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "VideoSplitActivity"
    }

    // UI
    private lateinit var toolbar: MaterialToolbar
    private lateinit var playerView: PlayerView
    private lateinit var txtSelectGuide: TextView
    private lateinit var seekSection: View
    private lateinit var controlSection: View
    private lateinit var splitSeekBar: SeekBar
    private lateinit var txtCurrentTime: TextView
    private lateinit var editKeyframeNumber: EditText
    private lateinit var txtTotalKeyframes: TextView
    private lateinit var txtTotalTime: TextView
    private lateinit var btnFrom: Button
    private lateinit var btnTo: Button
    private lateinit var btnAddSegment: Button
    private lateinit var txtFromToInfo: TextView
    private lateinit var segmentList: RecyclerView
    private lateinit var btnSelectVideo: Button
    private lateinit var btnStartSplit: Button

    // Player
    private var player: ExoPlayer? = null

    // Data
    private var videoUri: Uri? = null
    private var videoDurationMs: Long = 0L
    private val keyframes = mutableListOf<Long>() // 키프레임 위치 (ms)
    private var fromTimeMs: Long = -1L
    private var toTimeMs: Long = -1L
    private var fromKeyframeIndex: Int = -1
    private var toKeyframeIndex: Int = -1
    private val segments = mutableListOf<SplitSegment>()
    private var segmentAdapter: SplitSegmentAdapter? = null
    private var seekBarTracking = false
    private var outputFolderUri: Uri? = null
    private lateinit var btnOutputFolder: Button

    data class SplitSegment(
        val fromMs: Long,
        val toMs: Long,
        val fromKfIndex: Int,
        val toKfIndex: Int
    )

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

    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            loadVideo(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_split)
        setupViews()
        setupListeners()
    }

    private fun setupViews() {
        toolbar = findViewById(R.id.splitToolbar)
        playerView = findViewById(R.id.splitPlayerView)
        txtSelectGuide = findViewById(R.id.txtSelectGuide)
        seekSection = findViewById(R.id.seekSection)
        controlSection = findViewById(R.id.controlSection)
        splitSeekBar = findViewById(R.id.splitSeekBar)
        txtCurrentTime = findViewById(R.id.txtCurrentTime)
        editKeyframeNumber = findViewById(R.id.editKeyframeNumber)
        txtTotalKeyframes = findViewById(R.id.txtTotalKeyframes)
        txtTotalTime = findViewById(R.id.txtTotalTime)
        btnFrom = findViewById(R.id.btnFrom)
        btnTo = findViewById(R.id.btnTo)
        btnAddSegment = findViewById(R.id.btnAddSegment)
        txtFromToInfo = findViewById(R.id.txtFromToInfo)
        segmentList = findViewById(R.id.splitSegmentList)
        btnSelectVideo = findViewById(R.id.btnSelectVideo)
        btnStartSplit = findViewById(R.id.btnStartSplit)
        btnOutputFolder = findViewById(R.id.btnOutputFolder)

        toolbar.setNavigationOnClickListener { finish() }

        segmentList.layoutManager = LinearLayoutManager(this)
        segmentAdapter = SplitSegmentAdapter(
            segments = segments,
            onEdit = { pos -> showEditSegmentDialog(pos) },
            onDelete = { pos ->
                segments.removeAt(pos)
                segmentAdapter?.notifyItemRemoved(pos)
                segmentAdapter?.notifyItemRangeChanged(pos, segments.size - pos)
                updateSplitButton()
            }
        )
        segmentList.adapter = segmentAdapter
    }

    private fun setupListeners() {
        btnSelectVideo.setOnClickListener {
            videoPickerLauncher.launch("video/*")
        }

        btnOutputFolder.setOnClickListener {
            folderPickerLauncher.launch(null)
        }

        // SeekBar 이동 → 영상 + 키프레임 정보 업데이트
        splitSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && player != null) {
                    val posMs = progress.toLong()
                    player?.seekTo(posMs)
                    updateTimeDisplay(posMs)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { seekBarTracking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) { seekBarTracking = false }
        })

        // 키프레임 번호 직접 입력 → 해당 위치로 이동
        editKeyframeNumber.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                val kfNum = editKeyframeNumber.text.toString().toIntOrNull()
                if (kfNum != null && kfNum in 0 until keyframes.size) {
                    val posMs = keyframes[kfNum]
                    player?.seekTo(posMs)
                    splitSeekBar.progress = posMs.toInt()
                    updateTimeDisplay(posMs)
                } else {
                    Toast.makeText(this, "유효한 키프레임 번호를 입력하세요 (0~${keyframes.size - 1})", Toast.LENGTH_SHORT).show()
                }
                true
            } else false
        }

        // From 버튼
        btnFrom.setOnClickListener {
            val posMs = player?.currentPosition ?: return@setOnClickListener
            fromTimeMs = posMs
            fromKeyframeIndex = findKeyframeIndex(posMs)
            updateFromToDisplay()
        }

        // To 버튼
        btnTo.setOnClickListener {
            val posMs = player?.currentPosition ?: return@setOnClickListener
            if (fromTimeMs < 0) {
                Toast.makeText(this, "먼저 From을 설정하세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (posMs <= fromTimeMs) {
                Toast.makeText(this, "To는 From보다 뒤여야 합니다.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            toTimeMs = posMs
            toKeyframeIndex = findKeyframeIndex(posMs)
            updateFromToDisplay()
        }

        // Add 버튼
        btnAddSegment.setOnClickListener {
            if (fromTimeMs >= 0 && toTimeMs > fromTimeMs) {
                segments.add(SplitSegment(fromTimeMs, toTimeMs, fromKeyframeIndex, toKeyframeIndex))
                segmentAdapter?.notifyItemInserted(segments.size - 1)
                segmentList.visibility = View.VISIBLE
                // 초기화
                fromTimeMs = -1L
                toTimeMs = -1L
                fromKeyframeIndex = -1
                toKeyframeIndex = -1
                updateFromToDisplay()
                updateSplitButton()
            }
        }

        // 분할 시작
        btnStartSplit.setOnClickListener {
            if (segments.isNotEmpty() && videoUri != null) {
                startSplit()
            }
        }
    }

    private fun loadVideo(uri: Uri) {
        videoUri = uri

        // ExoPlayer 초기화
        player?.release()
        val exo = ExoPlayer.Builder(this).build()
        player = exo
        playerView.player = exo
        exo.setMediaItem(MediaItem.fromUri(uri))
        exo.prepare()
        exo.playWhenReady = false

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    videoDurationMs = exo.duration
                    splitSeekBar.max = videoDurationMs.toInt()
                    txtTotalTime.text = formatTime(videoDurationMs)
                    txtSelectGuide.visibility = View.GONE
                    seekSection.visibility = View.VISIBLE
                    controlSection.visibility = View.VISIBLE

                    // 키프레임 스캔
                    lifecycleScope.launch(Dispatchers.IO) {
                        scanKeyframes(uri)
                        withContext(Dispatchers.Main) {
                            txtTotalKeyframes.text = "/ ${keyframes.size - 1}"
                            updateTimeDisplay(0L)
                        }
                    }
                }
            }
        })

        // 주기적 시크바 업데이트
        startPositionUpdater()

        // 기존 데이터 초기화
        segments.clear()
        segmentAdapter?.notifyDataSetChanged()
        segmentList.visibility = View.GONE
        fromTimeMs = -1L
        toTimeMs = -1L
        updateFromToDisplay()
        updateSplitButton()
    }

    private fun startPositionUpdater() {
        val handler = android.os.Handler(mainLooper)
        val runnable = object : Runnable {
            override fun run() {
                val p = player ?: return
                if (!seekBarTracking && p.isPlaying) {
                    val pos = p.currentPosition
                    splitSeekBar.progress = pos.toInt()
                    updateTimeDisplay(pos)
                }
                handler.postDelayed(this, 200)
            }
        }
        handler.post(runnable)
    }

    private fun scanKeyframes(uri: Uri) {
        keyframes.clear()
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(this, uri, null)
            // 비디오 트랙 선택
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    extractor.selectTrack(i)
                    break
                }
            }
            // SEEK_TO_NEXT_SYNC로 처음부터 끝까지 키프레임 수집
            var lastTimeUs = -1L
            extractor.seekTo(0, MediaExtractor.SEEK_TO_NEXT_SYNC)
            while (true) {
                val sampleTime = extractor.sampleTime
                if (sampleTime < 0) break
                if (sampleTime == lastTimeUs) break
                lastTimeUs = sampleTime
                keyframes.add(sampleTime / 1000L) // us → ms
                // 다음 키프레임으로 이동
                extractor.seekTo(sampleTime + 1, MediaExtractor.SEEK_TO_NEXT_SYNC)
            }
            Log.d(TAG, "키프레임 ${keyframes.size}개 발견")
        } catch (e: Throwable) {
            Log.e(TAG, "키프레임 스캔 실패", e)
        } finally {
            extractor.release()
        }
    }

    private fun findKeyframeIndex(posMs: Long): Int {
        if (keyframes.isEmpty()) return -1
        // 이진 탐색으로 posMs 이하인 가장 가까운 키프레임 인덱스
        var lo = 0
        var hi = keyframes.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (keyframes[mid] <= posMs) lo = mid + 1
            else hi = mid - 1
        }
        return hi.coerceAtLeast(0)
    }

    private fun updateTimeDisplay(posMs: Long) {
        txtCurrentTime.text = formatTime(posMs)
        val kfIndex = findKeyframeIndex(posMs)
        if (kfIndex >= 0) {
            editKeyframeNumber.setText(kfIndex.toString())
        }
    }

    private fun updateFromToDisplay() {
        val fromStr = if (fromTimeMs >= 0) "${formatTime(fromTimeMs)} (KF #$fromKeyframeIndex)" else "---"
        val toStr = if (toTimeMs >= 0) "${formatTime(toTimeMs)} (KF #$toKeyframeIndex)" else "---"
        txtFromToInfo.text = "From: $fromStr  →  To: $toStr"
        btnFrom.text = if (fromTimeMs >= 0) "From ✓" else "From"
        btnTo.text = if (toTimeMs >= 0) "To ✓" else "To"
        btnAddSegment.isEnabled = fromTimeMs >= 0 && toTimeMs > fromTimeMs
    }

    private fun updateSplitButton() {
        btnStartSplit.isEnabled = segments.isNotEmpty()
        btnStartSplit.text = if (segments.isEmpty()) "분할 시작" else "분할 시작 (${segments.size}개)"
    }

    private fun showEditSegmentDialog(position: Int) {
        val seg = segments[position]
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 0)
        }
        val etFrom = EditText(this).apply {
            hint = "From (ms)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(seg.fromMs.toString())
        }
        val etTo = EditText(this).apply {
            hint = "To (ms)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(seg.toMs.toString())
        }
        view.addView(etFrom)
        view.addView(etTo)

        AlertDialog.Builder(this)
            .setTitle("구간 수정")
            .setView(view)
            .setPositiveButton("확인") { _, _ ->
                val newFrom = etFrom.text.toString().toLongOrNull() ?: return@setPositiveButton
                val newTo = etTo.text.toString().toLongOrNull() ?: return@setPositiveButton
                if (newTo > newFrom) {
                    segments[position] = SplitSegment(newFrom, newTo, findKeyframeIndex(newFrom), findKeyframeIndex(newTo))
                    segmentAdapter?.notifyItemChanged(position)
                } else {
                    Toast.makeText(this, "To는 From보다 커야 합니다.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun startSplit() {
        val uri = videoUri ?: return
        val segs = segments.toList()

        val cancelled = AtomicBoolean(false)
        val progressDialog = android.app.ProgressDialog(this).apply {
            setTitle("영상 분할 중")
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

        val displayName = getDisplayName(uri)
        val baseName = displayName.substringBeforeLast(".")

        lifecycleScope.launch(Dispatchers.IO) {
            val tempFiles = mutableListOf<File>()
            var tempInputFile: File? = null
            try {
                val isContentUri = uri.toString().startsWith("content://")

                // content:// URI인 경우 임시 파일로 복사 (SAF fd 재사용 문제 방지)
                val ffmpegInput = if (isContentUri) {
                    withContext(Dispatchers.Main) {
                        progressDialog.setMessage("입력 영상 준비 중...")
                    }
                    val tmp = File(cacheDir, "split_input_${System.currentTimeMillis()}.mp4")
                    contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw Exception("입력 영상을 열 수 없습니다")
                    tempInputFile = tmp
                    tmp.absolutePath
                } else {
                    uri.toString()
                }

                for ((index, seg) in segs.withIndex()) {
                    if (cancelled.get()) break

                    withContext(Dispatchers.Main) {
                        progressDialog.setMessage("구간 ${index + 1}/${segs.size} 추출 중...")
                        progressDialog.progress = (index * 100 / segs.size)
                    }

                    val startSec = "%.3f".format(java.util.Locale.US, seg.fromMs / 1000.0)
                    val durationSec = "%.3f".format(java.util.Locale.US, (seg.toMs - seg.fromMs) / 1000.0)
                    val outputFileName = "${baseName}_kf${seg.fromKfIndex}_kf${seg.toKfIndex}.mp4"
                    val tempFile = File(cacheDir, "split_${index}_${System.currentTimeMillis()}.mp4")
                    tempFiles.add(tempFile)

                    val session = com.arthenica.ffmpegkit.FFmpegKit.executeWithArguments(
                        arrayOf("-y", "-ss", startSec, "-i", ffmpegInput, "-t", durationSec, "-c", "copy", tempFile.absolutePath)
                    )

                    if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)
                        || !tempFile.exists() || tempFile.length() == 0L
                    ) {
                        throw Exception("구간 ${index + 1} 추출 실패")
                    }

                    // 저장
                    if (outputFolderUri != null) {
                        saveToFolder(outputFolderUri!!, tempFile, outputFileName)
                    } else {
                        saveToMediaStore(tempFile, outputFileName)
                    }
                }

                if (cancelled.get()) throw kotlinx.coroutines.CancellationException()

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(this@VideoSplitActivity, "${segs.size}개 구간 분할 완료", Toast.LENGTH_SHORT).show()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(this@VideoSplitActivity, "분할 취소됨", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(this@VideoSplitActivity, "분할 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
                Log.e(TAG, "Split failed", e)
            } finally {
                tempFiles.forEach { it.delete() }
                tempInputFile?.delete()
            }
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

    private fun getLastPathSegment(uri: Uri): String {
        val path = uri.lastPathSegment ?: uri.toString()
        return path.substringAfterLast(":")
            .substringAfterLast("/")
            .ifEmpty { path }
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

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }
}
