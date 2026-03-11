package com.splayer.video.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.splayer.video.R
import com.splayer.video.data.model.Video
import com.splayer.video.data.repository.VideoRepository
import com.splayer.video.ui.adapter.MergeItem
import com.splayer.video.ui.adapter.VideoAdapter
import com.splayer.video.ui.adapter.VideoMergeAdapter
import com.splayer.video.ui.player.PlayerActivity
import com.splayer.video.utils.CrashLogger
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var recyclerView: RecyclerView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var videoAdapter: VideoAdapter
    private lateinit var viewModel: MainViewModel

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.loadVideos()
        } else {
            Toast.makeText(this, "영상 목록을 보려면 저장소 권한이 필요합니다.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            Log.d(TAG, "onCreate called")
            CrashLogger.logInfo(this, TAG, "MainActivity onCreate started")

            // 외부에서 비디오 파일을 열려고 하는 경우 처리
            if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
                handleExternalVideoIntent()
                return
            }

            // 직접 실행: 영상 목록 표시
            setContentView(R.layout.activity_main)
            setupViews()
            setupViewModel()
            checkPermissionAndLoad()

        } catch (e: Exception) {
            CrashLogger.logError(this, TAG, "Error in onCreate", e)
            finish()
        }
    }

    private fun handleExternalVideoIntent() {
        val videoUri = intent.data
        Log.d(TAG, "Opened with URI: $videoUri")
        CrashLogger.logInfo(this, TAG, "External video opened: $videoUri")

        val subtitleUris = arrayListOf<String>()
        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                val itemUri = clip.getItemAt(i).uri?.toString() ?: continue
                if (itemUri != videoUri.toString()) {
                    subtitleUris.add(itemUri)
                }
            }
        }
        if (subtitleUris.isNotEmpty()) {
            Log.d(TAG, "ClipData에서 자막 URI ${subtitleUris.size}개 발견")
        }

        val playerIntent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_VIDEO_URI, videoUri.toString())
            if (subtitleUris.isNotEmpty()) {
                putStringArrayListExtra("subtitle_uris", subtitleUris)
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = intent.clipData
        }
        startActivity(playerIntent)
        finish()
    }

    private fun setupViews() {
        toolbar = findViewById(R.id.toolbar)
        recyclerView = findViewById(R.id.recyclerView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        progressBar = findViewById(R.id.progressBar)
        videoAdapter = VideoAdapter(
            onVideoClick = { video -> onVideoClicked(video) },
            onVideoLongClick = { video -> onVideoLongClicked(video) }
        )

        recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = videoAdapter
        }

        swipeRefresh.setOnRefreshListener {
            viewModel.loadVideos()
        }

        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_sort -> { showSortDialog(); true }
                R.id.action_refresh -> { viewModel.loadVideos(); true }
                R.id.action_split -> {
                    startActivity(android.content.Intent(this, com.splayer.video.ui.split.VideoSplitActivity::class.java))
                    true
                }
                R.id.action_merge -> {
                    startActivity(android.content.Intent(this, com.splayer.video.ui.merge.VideoMergeActivity::class.java))
                    true
                }
                R.id.action_replace -> {
                    startActivity(android.content.Intent(this, com.splayer.video.ui.replace.VideoReplaceActivity::class.java))
                    true
                }
                else -> false
            }
        }
    }

    private fun setupViewModel() {
        val factory = MainViewModelFactory(VideoRepository(contentResolver))
        viewModel = ViewModelProvider(this, factory)[MainViewModel::class.java]

        lifecycleScope.launch {
            viewModel.videos.collect { videos ->
                videoAdapter.submitList(videos)
            }
        }

        lifecycleScope.launch {
            viewModel.isLoading.collect { isLoading ->
                progressBar.visibility = if (isLoading && videoAdapter.itemCount == 0) View.VISIBLE else View.GONE
                swipeRefresh.isRefreshing = isLoading && videoAdapter.itemCount > 0
            }
        }
    }

    private fun checkPermissionAndLoad() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            viewModel.loadVideos()
        } else {
            permissionLauncher.launch(permission)
        }
    }

    private fun onVideoClicked(video: Video) {
        if (videoAdapter.isSelectionMode) {
            videoAdapter.toggleSelection(video.id)
            updateSelectionUI()
        } else {
            val playerIntent = Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_VIDEO_URI, video.uri.toString())
            }
            startActivity(playerIntent)
        }
    }

    private fun onVideoLongClicked(video: Video) {
        if (!videoAdapter.isSelectionMode) {
            videoAdapter.enterSelectionMode(video.id)
            updateSelectionUI()
        } else {
            videoAdapter.toggleSelection(video.id)
            updateSelectionUI()
        }
    }

    private fun updateSelectionUI() {
        if (videoAdapter.isSelectionMode) {
            val count = videoAdapter.getSelectedCount()
            toolbar.title = "${count}개 선택"
            toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
            toolbar.setNavigationOnClickListener {
                exitSelectionMode()
            }
            // 메뉴 변경: 합치기 버튼
            toolbar.menu.clear()
            toolbar.menu.add(0, 100, 0, "합치기").apply {
                setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
                isEnabled = count >= 2
            }
            toolbar.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    100 -> {
                        val selected = videoAdapter.getSelectedVideos()
                        if (selected.size >= 2) {
                            showMergeOrderDialog(selected)
                        } else {
                            Toast.makeText(this, "2개 이상 선택해주세요.", Toast.LENGTH_SHORT).show()
                        }
                        true
                    }
                    else -> false
                }
            }
        } else {
            exitSelectionMode()
        }
    }

    private fun exitSelectionMode() {
        videoAdapter.clearSelection()
        toolbar.title = getString(R.string.app_name)
        toolbar.navigationIcon = null
        toolbar.setNavigationOnClickListener(null)
        toolbar.menu.clear()
        menuInflater.inflate(R.menu.main_menu, toolbar.menu)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_sort -> { showSortDialog(); true }
                R.id.action_refresh -> { viewModel.loadVideos(); true }
                R.id.action_split -> {
                    startActivity(android.content.Intent(this, com.splayer.video.ui.split.VideoSplitActivity::class.java))
                    true
                }
                R.id.action_merge -> {
                    startActivity(android.content.Intent(this, com.splayer.video.ui.merge.VideoMergeActivity::class.java))
                    true
                }
                R.id.action_replace -> {
                    startActivity(android.content.Intent(this, com.splayer.video.ui.replace.VideoReplaceActivity::class.java))
                    true
                }
                else -> false
            }
        }
    }

    private fun showSortDialog() {
        val sortLabels = arrayOf("이름", "추가 날짜", "수정 날짜", "크기", "재생시간")
        val sortModes = MainViewModel.SortMode.values()

        AlertDialog.Builder(this)
            .setTitle("정렬")
            .setItems(sortLabels) { _, which ->
                viewModel.setSortMode(sortModes[which])
            }
            .show()
    }

    // =============================================
    // 비디오 합치기 기능
    // =============================================

    private fun showMergeOrderDialog(videos: List<Video>) {
        val items = videos.map { video ->
            MergeItem(video.uri, video.displayName, video.duration)
        }.toMutableList()

        val recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            setPadding(0, 16, 0, 16)
        }

        val adapter = VideoMergeAdapter(
            items = items,
            onMoveUp = { pos ->
                if (pos > 0) {
                    val temp = items[pos]
                    items[pos] = items[pos - 1]
                    items[pos - 1] = temp
                    recyclerView.adapter?.notifyItemMoved(pos, pos - 1)
                    recyclerView.adapter?.notifyItemChanged(pos)
                    recyclerView.adapter?.notifyItemChanged(pos - 1)
                }
            },
            onMoveDown = { pos ->
                if (pos < items.size - 1) {
                    val temp = items[pos]
                    items[pos] = items[pos + 1]
                    items[pos + 1] = temp
                    recyclerView.adapter?.notifyItemMoved(pos, pos + 1)
                    recyclerView.adapter?.notifyItemChanged(pos)
                    recyclerView.adapter?.notifyItemChanged(pos + 1)
                }
            },
            onRemove = { pos ->
                items.removeAt(pos)
                recyclerView.adapter?.notifyItemRemoved(pos)
                recyclerView.adapter?.notifyItemRangeChanged(pos, items.size - pos)
            }
        )

        recyclerView.adapter = adapter

        AlertDialog.Builder(this)
            .setTitle("비디오 합치기 (${items.size}개)")
            .setView(recyclerView)
            .setPositiveButton("합치기") { _, _ ->
                if (items.size >= 2) {
                    exitSelectionMode()
                    mergeVideos(items)
                } else {
                    Toast.makeText(this, "2개 이상의 비디오가 필요합니다.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun mergeVideos(items: List<MergeItem>) {
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US)
            .format(java.util.Date())
        val firstBaseName = items.first().displayName.substringBeforeLast(".")
        val outputFileName = "merged_${firstBaseName}_${items.size}files_$timestamp.mp4"

        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)

        val progressDialog = android.app.ProgressDialog(this).apply {
            setTitle("비디오 합치기")
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

        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val tempFiles = mutableListOf<File>()
            val concatListFile = File(cacheDir, "concat_list_${System.currentTimeMillis()}.txt")
            val tempOutputFile = File(cacheDir, "ffmpeg_merge_${System.currentTimeMillis()}.mp4")

            try {
                // 1단계: content:// URI를 임시파일로 복사
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    progressDialog.isIndeterminate = false
                    progressDialog.setMessage("파일 복사 중...")
                }

                for ((index, item) in items.withIndex()) {
                    if (cancelled.get()) break

                    val tempFile = File(cacheDir, "merge_input_${index}_${System.currentTimeMillis()}.mp4")
                    tempFiles.add(tempFile)

                    contentResolver.openInputStream(item.uri)?.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    } ?: throw Exception("파일을 읽을 수 없습니다: ${item.displayName}")

                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        progressDialog.progress = ((index + 1) * 30 / items.size)
                        progressDialog.setMessage("파일 복사 중... (${index + 1}/${items.size})")
                    }
                }

                if (cancelled.get()) throw kotlinx.coroutines.CancellationException()

                // 2단계: concat list 파일 작성
                val concatContent = tempFiles.joinToString("\n") { "file '${it.absolutePath.replace("'", "'\\''")}'" }
                concatListFile.writeText(concatContent)

                // 3단계: FFmpeg concat 실행
                val totalDurationMs = items.sumOf { it.duration }

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    progressDialog.setMessage("[FFmpeg] 합치는 중...\n$outputFileName")
                    progressDialog.progress = 30
                }

                com.arthenica.ffmpegkit.FFmpegKitConfig.enableStatisticsCallback { stats ->
                    if (totalDurationMs > 0) {
                        val progress = 30 + ((stats.time.toFloat() / totalDurationMs) * 70).toInt().coerceIn(0, 70)
                        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                            if (progressDialog.isShowing) {
                                progressDialog.progress = progress
                            }
                        }
                    }
                }

                val session = com.arthenica.ffmpegkit.FFmpegKit.executeWithArguments(
                    arrayOf(
                        "-y",
                        "-f", "concat",
                        "-safe", "0",
                        "-i", concatListFile.absolutePath,
                        "-c", "copy",
                        "-avoid_negative_ts", "make_zero",
                        tempOutputFile.absolutePath
                    )
                )

                if (cancelled.get()) throw kotlinx.coroutines.CancellationException()

                if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)
                    || !tempOutputFile.exists()
                    || tempOutputFile.length() == 0L
                ) {
                    throw Exception("FFmpeg 합치기 실패")
                }

                // 4단계: MediaStore에 저장
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, outputFileName)
                        put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_MOVIES)
                    }
                    val uri = contentResolver.insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        contentResolver.openOutputStream(uri)?.use { os ->
                            tempOutputFile.inputStream().use { it.copyTo(os) }
                        }
                    }
                } else {
                    val outputDir = getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)
                    outputDir?.mkdirs()
                    tempOutputFile.copyTo(File(outputDir, outputFileName), overwrite = true)
                }

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(this@MainActivity, "합치기 완료: $outputFileName", Toast.LENGTH_SHORT).show()
                    viewModel.loadVideos()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(this@MainActivity, "합치기 취소됨", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(this@MainActivity, "합치기 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
                Log.e(TAG, "Merge failed", e)
            } finally {
                tempFiles.forEach { it.delete() }
                concatListFile.delete()
                tempOutputFile.delete()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (videoAdapter.isSelectionMode) {
            exitSelectionMode()
        } else {
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        // 영상 목록이 있고 외부 인텐트가 아닌 경우 새로고침
        if (::viewModel.isInitialized) {
            viewModel.loadVideos()
        }
    }
}
