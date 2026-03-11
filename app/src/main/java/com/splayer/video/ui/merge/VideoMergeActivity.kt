package com.splayer.video.ui.merge

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
import com.splayer.video.ui.adapter.MergeItem
import com.splayer.video.ui.adapter.VideoMergeAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class VideoMergeActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "VideoMergeActivity"
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var btnAddVideo: Button
    private lateinit var txtMergeGuide: TextView
    private lateinit var mergeVideoList: RecyclerView
    private lateinit var btnStartMerge: Button

    private val items = mutableListOf<MergeItem>()
    private var adapter: VideoMergeAdapter? = null

    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@registerForActivityResult
        for (uri in uris) {
            try {
                contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            val name = getDisplayName(uri)
            val duration = getDuration(uri)
            items.add(MergeItem(uri, name, duration))
        }
        adapter?.notifyDataSetChanged()
        updateUI()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_merge)
        setupViews()
    }

    private fun setupViews() {
        toolbar = findViewById(R.id.mergeToolbar)
        btnAddVideo = findViewById(R.id.btnAddVideo)
        txtMergeGuide = findViewById(R.id.txtMergeGuide)
        mergeVideoList = findViewById(R.id.mergeVideoList)
        btnStartMerge = findViewById(R.id.btnStartMerge)

        toolbar.setNavigationOnClickListener { finish() }

        adapter = VideoMergeAdapter(
            items = items,
            onMoveUp = { pos ->
                if (pos > 0) {
                    val temp = items[pos]
                    items[pos] = items[pos - 1]
                    items[pos - 1] = temp
                    adapter?.notifyItemMoved(pos, pos - 1)
                    adapter?.notifyItemChanged(pos)
                    adapter?.notifyItemChanged(pos - 1)
                }
            },
            onMoveDown = { pos ->
                if (pos < items.size - 1) {
                    val temp = items[pos]
                    items[pos] = items[pos + 1]
                    items[pos + 1] = temp
                    adapter?.notifyItemMoved(pos, pos + 1)
                    adapter?.notifyItemChanged(pos)
                    adapter?.notifyItemChanged(pos + 1)
                }
            },
            onRemove = { pos ->
                items.removeAt(pos)
                adapter?.notifyItemRemoved(pos)
                adapter?.notifyItemRangeChanged(pos, items.size - pos)
                updateUI()
            }
        )

        mergeVideoList.layoutManager = LinearLayoutManager(this)
        mergeVideoList.adapter = adapter

        btnAddVideo.setOnClickListener {
            videoPickerLauncher.launch(arrayOf("video/*"))
        }

        btnStartMerge.setOnClickListener {
            if (items.size >= 2) {
                startMerge()
            } else {
                Toast.makeText(this, "2개 이상의 영상이 필요합니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateUI() {
        txtMergeGuide.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        btnStartMerge.isEnabled = items.size >= 2
        btnStartMerge.text = if (items.size >= 2) "병합 시작 (${items.size}개)" else "병합 시작"
    }

    private fun startMerge() {
        val mergeItems = items.toList()
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US)
            .format(java.util.Date())
        val firstBaseName = mergeItems.first().displayName.substringBeforeLast(".")
        val outputFileName = "merged_${firstBaseName}_${mergeItems.size}files_$timestamp.mp4"

        val cancelled = AtomicBoolean(false)
        val progressDialog = android.app.ProgressDialog(this).apply {
            setTitle("병합 중")
            setMessage("준비 중...")
            setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            progress = 0
            isIndeterminate = true
            setCancelable(false)
            setButton(android.app.ProgressDialog.BUTTON_NEGATIVE, "취소") { _, _ ->
                cancelled.set(true)
                try { com.arthenica.ffmpegkit.FFmpegKit.cancel() } catch (_: Throwable) {}
            }
            show()
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val tempFiles = mutableListOf<File>()
            val concatListFile = File(cacheDir, "concat_list_${System.currentTimeMillis()}.txt")
            val tempOutputFile = File(cacheDir, "ffmpeg_merge_${System.currentTimeMillis()}.mp4")

            try {
                // 1단계: content:// URI를 임시파일로 복사
                withContext(Dispatchers.Main) {
                    progressDialog.isIndeterminate = false
                    progressDialog.setMessage("파일 복사 중...")
                }

                for ((index, item) in mergeItems.withIndex()) {
                    if (cancelled.get()) break
                    val tempFile = File(cacheDir, "merge_input_${index}_${System.currentTimeMillis()}.mp4")
                    tempFiles.add(tempFile)
                    contentResolver.openInputStream(item.uri)?.use { input ->
                        tempFile.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw Exception("파일을 읽을 수 없습니다: ${item.displayName}")
                    withContext(Dispatchers.Main) {
                        progressDialog.progress = ((index + 1) * 30 / mergeItems.size)
                        progressDialog.setMessage("파일 복사 중... (${index + 1}/${mergeItems.size})")
                    }
                }

                if (cancelled.get()) throw kotlinx.coroutines.CancellationException()

                // 2단계: concat list 작성
                val concatContent = tempFiles.joinToString("\n") { "file '${it.absolutePath.replace("'", "'\\''")}'" }
                concatListFile.writeText(concatContent)

                // 3단계: FFmpeg concat
                val totalDurationMs = mergeItems.sumOf { it.duration }
                withContext(Dispatchers.Main) {
                    progressDialog.setMessage("[FFmpeg] 병합 중...\n$outputFileName")
                    progressDialog.progress = 30
                }

                com.arthenica.ffmpegkit.FFmpegKitConfig.enableStatisticsCallback { stats ->
                    if (totalDurationMs > 0) {
                        val progress = 30 + ((stats.time.toFloat() / totalDurationMs) * 70).toInt().coerceIn(0, 70)
                        lifecycleScope.launch(Dispatchers.Main) {
                            if (progressDialog.isShowing) progressDialog.progress = progress
                        }
                    }
                }

                val session = com.arthenica.ffmpegkit.FFmpegKit.executeWithArguments(
                    arrayOf(
                        "-y", "-f", "concat", "-safe", "0",
                        "-i", concatListFile.absolutePath,
                        "-c", "copy", "-avoid_negative_ts", "make_zero",
                        tempOutputFile.absolutePath
                    )
                )

                if (cancelled.get()) throw kotlinx.coroutines.CancellationException()

                if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)
                    || !tempOutputFile.exists() || tempOutputFile.length() == 0L
                ) {
                    throw Exception("FFmpeg 병합 실패")
                }

                // 4단계: MediaStore 저장
                saveToMediaStore(tempOutputFile, outputFileName)

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(this@VideoMergeActivity, "병합 완료: $outputFileName", Toast.LENGTH_SHORT).show()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(this@VideoMergeActivity, "병합 취소됨", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(this@VideoMergeActivity, "병합 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
                Log.e(TAG, "Merge failed", e)
            } finally {
                tempFiles.forEach { it.delete() }
                concatListFile.delete()
                tempOutputFile.delete()
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

    private fun getDisplayName(uri: Uri): String {
        var name = "video"
        try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) name = cursor.getString(0) ?: "video"
            }
        } catch (_: Exception) {}
        return name
    }

    private fun getDuration(uri: Uri): Long {
        return try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(this, uri)
            val dur = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            dur?.toLongOrNull() ?: 0L
        } catch (_: Exception) { 0L }
    }
}
