package com.splayer.video.ui.player

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.graphics.Color
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import com.splayer.video.R
import com.splayer.video.data.local.SPlayerDatabase
import com.splayer.video.data.repository.PlaybackRepository
import com.splayer.video.data.repository.VideoRepository
import com.splayer.video.databinding.ActivityPlayerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer as VlcMediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import com.splayer.video.cast.CastManager
import com.splayer.video.cast.ChromecastManager
import com.splayer.video.cast.DlnaDevice
import kotlin.math.abs

@UnstableApi
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var currentVideoId: Long = -1
    private var externalVideoUri: String? = null
    private var externalSubtitleUris: List<String> = emptyList() // ClipData로 전달받은 자막 URI
    private lateinit var gestureDetector: GestureDetectorCompat
    private lateinit var audioManager: AudioManager

    // 자막 파일 선택을 위한 ActivityResultLauncher
    private val subtitleFilePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { selectedUri ->
            handleSelectedSubtitleFile(selectedUri)
        }
    }

    // 제스처 상태 변수
    private var initialX = 0f
    private var initialY = 0f
    private var initialBrightness = 0f
    private var initialVolume = 0
    private var isSeeking = false
    private var seekStartPosition = 0L // 스와이프 시작 시 비디오 위치
    private var wasPlayingBeforeSeek = false // 스와이프 시작 전 재생 상태
    private var seekResetJob: kotlinx.coroutines.Job? = null // 시크 상태 리셋용 Job
    private var positionUpdateJob: kotlinx.coroutines.Job? = null // 가상 타임라인 위치 업데이트용 Job
    private var lastVirtualPosition = -1L // 마지막 업데이트된 가상 위치
    private var lastVirtualDuration = -1L // 마지막 업데이트된 가상 총 시간
    private var currentGestureMode: GestureMode? = null // 현재 제스처 모드
    private var lastSeekTime = 0L // 마지막 seek 호출 시간
    private var lastSeekPosition = 0L // 마지막 seek 위치

    // 배속 재생 관련 변수
    private var isSpeedPlaybackActive = false
    private var originalPlaybackSpeed = 1.0f

    enum class GestureMode {
        BRIGHTNESS, VOLUME, SEEK, NONE
    }

    enum class FolderType {
        EXTRACTION, SEGMENT_FILE
    }

    // 자막 관련 변수
    private var currentVideoPath: String? = null
    private var availableSubtitles = mutableListOf<SubtitleInfo>()
    private var currentSubtitleUri: Uri? = null
    private var embeddedSubtitleTracks = mutableListOf<TrackInfo>()
    private var selectedEmbeddedTrackIndex = -1 // -1은 비활성화

    // 밝기/볼륨 표시 UI
    private var brightnessIndicator: View? = null
    private var volumeIndicator: View? = null
    private var brightnessProgressBar: ProgressBar? = null
    private var volumeProgressBar: ProgressBar? = null
    private var brightnessText: TextView? = null
    private var volumeText: TextView? = null

    // 배속 표시 UI
    private var speedIndicator: TextView? = null

    // 재생 위치 표시 UI (컨트롤바 형식)
    private var seekPreviewContainer: FrameLayout? = null
    private var seekProgressBar: ProgressBar? = null
    private var seekThumb: View? = null
    private var seekTimeText: TextView? = null
    private var seekTotalTimeText: TextView? = null
    private var seekProgressPercentText: TextView? = null // 화면 중앙 진행률(%)
    private var seekHideJob: kotlinx.coroutines.Job? = null

    // 코덱 정보 변수
    private var currentVideoCodec: String = ""
    private var currentAudioCodec: String = ""
    private var currentVideoWidth: Int = 0
    private var currentVideoHeight: Int = 0
    private var codecInfoTextView: TextView? = null
    private var subtitleCodecInfoTextView: TextView? = null

    // 소리 on/off 설정
    private var isSoundEnabled = true
    private var skipSeconds = 10 // 기본 스킵 시간 (초)
    private var startWithMute = false // 시작시 무음으로 재생
    private var startWithMaxBrightness = false // 시작시 최대 밝기
    private var isSubtitleEnabled = true // 자막 ON/OFF
    private var isSubtitleCacheEnabled = false // 자막 캐시 ON/OFF (기본: OFF)
    private var swipeSensitivity = 10 // 스와이프 민감도 (1~20, 10이 기본)
    private var isBrightnessSwipeEnabled = true // 밝기 스와이프 ON/OFF
    private var isVolumeSwipeEnabled = true // 볼륨 스와이프 ON/OFF
    private var isContinuousPlayEnabled = false // 폴더 내 연속 재생 ON/OFF
    private var decoderMode = DECODER_MODE_HW_PLUS // 디코더 모드 (HW/HW+/SW)
    private var bufferMode = BUFFER_MODE_STABLE // 버퍼링 모드 (안정/빠른시작)
    private var seekMode = SEEK_MODE_ACCURATE // 시크 모드 (정확/빠름)
    private val prefs by lazy {
        getSharedPreferences("splayer_settings", Context.MODE_PRIVATE)
    }

    // VLC 엔진 관련 변수
    private var useVlcEngine = false
    private var isEngineFallback = false // ExoPlayer 실패 → VLC 자동 전환 중 여부
    private var libVlc: LibVLC? = null
    private var vlcMediaPlayer: VlcMediaPlayer? = null
    private var vlcControllerView: View? = null
    private var isVlcControllerVisible = false
    private val vlcControllerHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val vlcControllerHideRunnable = Runnable { hideVlcController() }
    private var vlcPlayPauseButton: ImageButton? = null

    // auto-hide 타이머용 Job
    private var brightnessHideJob: kotlinx.coroutines.Job? = null
    private var volumeHideJob: kotlinx.coroutines.Job? = null

    // 구간 재생 관련 변수
    private lateinit var segmentManager: com.splayer.video.util.SegmentManager
    private var isSegmentPlaybackEnabled = false
    private var currentSegments = listOf<com.splayer.video.data.model.PlaybackSegment>()
    private var currentSegmentIndex = 0
    private var segmentPlaybackToggleButton: Button? = null
    private var btnSegmentStart: Button? = null
    private var btnSegmentEnd: Button? = null
    private var btnSegmentEndAlways: Button? = null  // F 누른 후 항상 표시되는 T 버튼
    private var btnSegmentList: ImageButton? = null  // 구간 리스트 버튼
    private var btnContinuousSegment: ImageButton? = null  // 구간연속재생 버튼
    private var segmentNavigationLayout: LinearLayout? = null
    private var tvSegmentSequence: TextView? = null
    private var btnSegmentPrevious: ImageButton? = null
    private var btnSegmentNext: ImageButton? = null

    // 캐스팅 (DLNA + Chromecast)
    private var castManager: CastManager? = null
    private var chromecastManager: ChromecastManager? = null
    private var isCastingActive = false
    private var castingMode: String? = null  // "dlna" or "chromecast"

    // 자막 파일 선택 런처
    private val subtitlePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            loadSubtitle(it)
        }
    }

    // 폴더 선택 런처
    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            try {
                // 영구 권한 요청
                contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )

                selectedFolderUri = it
                // URI를 파일 경로로 변환
                val path = getPathFromTreeUri(it)
                currentDialogEditText?.setText(path)

                // 폴더 타입에 따라 저장
                when (currentFolderType) {
                    FolderType.EXTRACTION -> {
                        // 추출 파일 저장 경로
                        prefs.edit().putString("segment_save_path", path).apply()
                    }
                    FolderType.SEGMENT_FILE -> {
                        // 구간 정보 파일 경로
                        prefs.edit().putString("segment_file_path", path).apply()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("PlayerActivity", "폴더 권한 요청 실패", e)
                android.widget.Toast.makeText(
                    this,
                    "폴더 접근 권한 요청 실패: ${e.message}",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // 구간파일 백업 런처
    private val backupSegmentFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        uri?.let {
            try {
                val sourceFile = File(segmentManager.getSegmentFilePath())
                if (sourceFile.exists()) {
                    contentResolver.openOutputStream(it)?.use { outputStream ->
                        sourceFile.inputStream().use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("PlayerActivity", "백업 실패", e)
            }
        }
    }

    // 구간파일 복원 런처
    private val restoreSegmentFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val targetFile = File(segmentManager.getSegmentFilePath())
                targetFile.parentFile?.mkdirs()

                contentResolver.openInputStream(it)?.use { inputStream ->
                    targetFile.outputStream().use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("PlayerActivity", "복원 실패", e)
            }
        }
    }

    // 비디오 합치기 런처
    private val videoMergePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.size < 2) {
            Toast.makeText(this, "2개 이상의 비디오를 선택해주세요.", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        uris.forEach { uri ->
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
        }
        showMergeOrderDialog(uris)
    }

    private var selectedFolderUri: Uri? = null
    private var currentDialogEditText: EditText? = null
    private var currentFolderType: FolderType = FolderType.EXTRACTION

    data class SubtitleInfo(
        val uri: Uri,
        val name: String,
        val mimeType: String
    )

    data class TrackInfo(
        val trackIndex: Int,
        val language: String,
        val label: String
    )

    private val viewModel: PlayerViewModel by viewModels {
        PlayerViewModelFactory(
            VideoRepository(contentResolver),
            PlaybackRepository(SPlayerDatabase.getInstance(this).playbackPositionDao())
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // AudioManager 초기화
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // SegmentManager 초기화
        segmentManager = com.splayer.video.util.SegmentManager(applicationContext)
        castManager = CastManager(applicationContext)
        chromecastManager = ChromecastManager(applicationContext).also { it.initialize() }

        // 소리 설정 로드
        isSoundEnabled = prefs.getBoolean("sound_enabled", true)
        skipSeconds = prefs.getInt("skip_seconds", 10)
        startWithMute = prefs.getBoolean("start_with_mute", false)
        startWithMaxBrightness = prefs.getBoolean("start_with_max_brightness", false)
        isSubtitleEnabled = prefs.getBoolean("subtitle_enabled", true)
        isSubtitleCacheEnabled = prefs.getBoolean("subtitle_cache_enabled", false)
        swipeSensitivity = prefs.getInt("swipe_sensitivity", 10)
        isContinuousPlayEnabled = prefs.getBoolean("continuous_play_enabled", false)

        // 밝기/볼륨 스와이프 설정 로드
        isBrightnessSwipeEnabled = prefs.getBoolean("brightness_swipe_enabled", true)
        isVolumeSwipeEnabled = prefs.getBoolean("volume_swipe_enabled", true)

        decoderMode = prefs.getInt("decoder_mode", DECODER_MODE_HW_PLUS)
        bufferMode = prefs.getInt("buffer_mode", BUFFER_MODE_STABLE)
        seekMode = prefs.getInt("seek_mode", SEEK_MODE_ACCURATE)
        useVlcEngine = prefs.getBoolean("use_vlc_engine", false)

        // 밝기/볼륨 인디케이터 초기화
        setupIndicators()

        // 제스처 감지기 초기화
        setupGestureDetector()

        // 전체화면 설정
        setupFullscreen()

        // 저장된 밝기 설정 적용
        applySavedBrightness()

        // 외부 URI로 열린 경우 (파일 탐색기에서 열기)
        externalVideoUri = intent.getStringExtra(EXTRA_VIDEO_URI)
        externalSubtitleUris = intent.getStringArrayListExtra("subtitle_uris") ?: emptyList()
        if (externalSubtitleUris.isNotEmpty()) {
            Log.d("PlayerActivity", "외부 자막 URI ${externalSubtitleUris.size}개 수신")
        }
        if (externalVideoUri != null) {
            // 외부 URI로 직접 재생
            val fileName = getFileNameFromUri(externalVideoUri!!)
            initializePlayer(externalVideoUri!!, 0L, fileName)
            return
        }

        // 내부에서 비디오 ID로 열린 경우
        currentVideoId = intent.getLongExtra(EXTRA_VIDEO_ID, -1)
        if (currentVideoId == -1L) {
            finish()
            return
        }

        // 비디오 로드
        viewModel.loadVideo(currentVideoId)
        setupObservers()
    }

    private fun setupIndicators() {
        // 게이지 형태의 심플한 인디케이터 (아이콘 없이)
        val indicatorWidth = 60
        val indicatorHeight = 300

        // 밝기 인디케이터 컨테이너 (오른쪽)
        val brightnessContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                indicatorWidth,
                indicatorHeight
            ).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.END
                marginEnd = 50
            }
            // 반투명 둥근 배경
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 12f
                setColor(Color.argb(180, 0, 0, 0))
            }
            setPadding(8, 12, 8, 12)
            visibility = View.GONE
        }

        // 밝기 내부 레이아웃
        val brightnessLayout = LinearLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }

        // 밝기 게이지 컨테이너 (배경)
        val brightnessGaugeContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                36,
                0
            ).apply {
                weight = 1f
                bottomMargin = 6
            }
            // 게이지 배경 (어두운 회색)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 6f
                setColor(Color.argb(100, 255, 255, 255))
            }
        }

        // 밝기 게이지 바 (채워지는 부분)
        val brightnessGaugeBar = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                0
            ).apply {
                gravity = android.view.Gravity.BOTTOM
            }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 6f
                setColor(Color.WHITE)
            }
        }
        brightnessGaugeContainer.addView(brightnessGaugeBar)
        brightnessProgressBar = ProgressBar(this).apply {
            setTag(R.id.playerView, brightnessGaugeBar)
        }

        // 밝기 수치 텍스트
        brightnessText = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
        }

        brightnessLayout.addView(brightnessGaugeContainer)
        brightnessLayout.addView(brightnessText)
        brightnessContainer.addView(brightnessLayout)

        // 볼륨 인디케이터 컨테이너 (왼쪽)
        val volumeContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                indicatorWidth,
                indicatorHeight
            ).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
                marginStart = 50
            }
            // 반투명 둥근 배경
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 12f
                setColor(Color.argb(180, 0, 0, 0))
            }
            setPadding(8, 12, 8, 12)
            visibility = View.GONE
        }

        // 볼륨 내부 레이아웃
        val volumeLayout = LinearLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }

        // 볼륨 게이지 컨테이너 (배경)
        val volumeGaugeContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                36,
                0
            ).apply {
                weight = 1f
                bottomMargin = 6
            }
            // 게이지 배경 (어두운 회색)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 6f
                setColor(Color.argb(100, 255, 255, 255))
            }
        }

        // 볼륨 게이지 바 (채워지는 부분)
        val volumeGaugeBar = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                0
            ).apply {
                gravity = android.view.Gravity.BOTTOM
            }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 6f
                setColor(Color.WHITE)
            }
        }
        volumeGaugeContainer.addView(volumeGaugeBar)
        volumeProgressBar = ProgressBar(this).apply {
            setTag(R.id.playerView, volumeGaugeBar)
        }

        // 볼륨 수치 텍스트
        volumeText = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
        }

        volumeLayout.addView(volumeGaugeContainer)
        volumeLayout.addView(volumeText)
        volumeContainer.addView(volumeLayout)

        // 재생 위치 표시 UI (컨트롤바 형식) - 상단으로 이동
        seekPreviewContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.TOP
                topMargin = 120 // 총 시간이 보이도록 더 아래로
            }
            visibility = View.GONE
        }

        // 프로그레스 바 (시크바 배경)
        seekProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                20 // 높이 20px
            ).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
                marginStart = 50
                marginEnd = 50
            }
            max = 10000 // 0.01% 단위로 정밀하게
            progress = 0
        }

        // 타원형 썸네일 (현재 시간 표시)
        seekThumb = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(120, 40).apply { // 가로로 긴 타원
                gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
                marginStart = 50
            }
            // 타원형 배경
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#DD000000"))
            }
        }

        // 현재 시간 텍스트 (타원형 안에 표시)
        seekTimeText = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setTextColor(Color.WHITE)
            textSize = 10f
            gravity = android.view.Gravity.CENTER
        }

        // 타원형 안에 텍스트 추가
        (seekThumb as? FrameLayout)?.addView(seekTimeText)

        // 총 시간 텍스트 (맨 우측 위에 표시)
        seekTotalTimeText = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.END or android.view.Gravity.TOP
                marginEnd = 60
                topMargin = 70 // 프로그레스 바 위로
            }
            setBackgroundColor(Color.parseColor("#CC000000"))
            setTextColor(Color.WHITE)
            textSize = 12f // 1.5배 증가 (8f -> 12f)
            setPadding(10, 5, 10, 5)
        }

        seekPreviewContainer?.apply {
            addView(seekProgressBar)
            addView(seekThumb)
            addView(seekTotalTimeText)
        }

        // 화면 정중앙에 현재시간/총시간 표시
        seekProgressPercentText = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.CENTER
            }
            setBackgroundColor(Color.TRANSPARENT)  // 배경 제거
            setTextColor(Color.WHITE)
            textSize = 24f // 크기 절반으로 축소 (48f -> 24f)
            setShadowLayer(8f, 0f, 0f, Color.BLACK)  // 텍스트 그림자 추가 (가독성)
            setPadding(20, 10, 20, 10)
            visibility = View.GONE
        }

        // 코덱 정보 표시 TextView (상단 왼쪽)
        codecInfoTextView = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                topMargin = 60
                leftMargin = 20
            }
            setBackgroundColor(Color.parseColor("#AA000000"))  // 반투명 검정
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(15, 10, 15, 10)
            visibility = View.GONE
        }

        // 컨테이너들을 binding.root에 추가
        (binding.root as? FrameLayout)?.apply {
            addView(brightnessContainer)
            addView(volumeContainer)
            addView(seekPreviewContainer)
            addView(seekProgressPercentText)
            addView(codecInfoTextView)
        }

        // 인디케이터 참조 저장
        brightnessIndicator = brightnessContainer
        volumeIndicator = volumeContainer
    }

    private fun setupGestureDetector() {
        gestureDetector = GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // 싱글 탭: 컨트롤 바 표시/숨김
                engineToggleController()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                // 더블탭 시 컨트롤 바 숨김
                engineHideController()

                val screenWidth = touchSurface.width
                val screenHeight = touchSurface.height

                // 화면을 상하로 5등분
                val fifthHeight = screenHeight / 5f

                // 맨 아래 1/5 영역에서 더블탭하면 종료
                if (e.y >= fifthHeight * 4) {
                    // 더블탭 종료 시 다음 재생은 처음부터 시작하도록 설정
                    prefs.edit()
                        .putString("exit_type", "tap")
                        .apply()
                    Log.d("PlayerActivity", "더블탭 종료 - 다음 재생은 처음부터 시작")
                    finish()
                    return true
                }

                // 화면을 가로로 2등분 - 좌우 스킵
                val leftHalf = screenWidth / 2f

                when {
                    // 좌측 - 뒤로 스킵
                    e.x < leftHalf -> {
                        skipBackward()
                        return true
                    }
                    // 우측 - 앞으로 스킵
                    else -> {
                        skipForward()
                        return true
                    }
                }
            }

            override fun onDown(e: MotionEvent): Boolean {
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                // 길게 누르기: 화면을 상하로 7등분하여 배속 재생
                val screenHeight = touchSurface.height
                val seventhHeight = screenHeight / 7f

                // VLC는 최대 4x, ExoPlayer는 128x까지
                val speed = if (useVlcEngine) {
                    when {
                        e.y < seventhHeight * 2 -> 1.5f
                        e.y < seventhHeight * 4 -> 2.0f
                        e.y < seventhHeight * 6 -> 3.0f
                        else -> 4.0f
                    }
                } else {
                    when {
                        e.y < seventhHeight -> 2.0f
                        e.y < seventhHeight * 2 -> 4.0f
                        e.y < seventhHeight * 3 -> 8.0f
                        e.y < seventhHeight * 4 -> 16.0f
                        e.y < seventhHeight * 5 -> 32.0f
                        e.y < seventhHeight * 6 -> 64.0f
                        else -> 128.0f
                    }
                }

                if (!isSpeedPlaybackActive) {
                    originalPlaybackSpeed = enginePlaybackSpeed
                    isSpeedPlaybackActive = true
                }
                engineSetSpeed(speed)
                showSpeedIndicator(speed)
                Log.d("PlayerActivity", "배속 재생 시작: ${speed}x")
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (e1 == null) return false

                // 스와이프 시작 시 컨트롤 바 숨김
                engineHideController()

                val deltaX = e2.x - e1.x
                val deltaY = e2.y - e1.y
                val screenWidth = touchSurface.width
                val screenHeight = touchSurface.height

                // 제스처 모드가 아직 결정되지 않았다면, 매우 낮은 임계값으로 즉시 결정
                if (currentGestureMode == null) {
                    val totalMovement = abs(deltaX) + abs(deltaY)
                    // 최소 이동 거리 (10px 이상 움직였을 때만 모드 결정)
                    if (totalMovement > 10) {
                        // 화면을 좌우로 2등분
                        val isLeftHalf = e1.x < screenWidth * 0.5f   // 왼쪽 절반
                        val isRightHalf = e1.x >= screenWidth * 0.5f // 오른쪽 절반

                        currentGestureMode = when {
                            // 세로 움직임이 가로의 30%보다 크면 상하 스와이프로 판단 (즉각 반응)
                            abs(deltaY) > abs(deltaX) * 0.3f -> {
                                // 좌우 절반에 따라 밝기/볼륨 조절 (설정이 켜져있을 때만)
                                when {
                                    isLeftHalf && isBrightnessSwipeEnabled -> GestureMode.BRIGHTNESS   // 왼쪽 절반 - 밝기
                                    isRightHalf && isVolumeSwipeEnabled -> GestureMode.VOLUME      // 오른쪽 절반 - 볼륨
                                    else -> null
                                }
                            }
                            // 가로 움직임이 세로의 30%보다 크면 좌우 스와이프로 판단
                            // 전체 영역에서 좌우 스와이프 허용 (컨트롤바 영역 제외는 터치 이벤트에서 처리)
                            abs(deltaX) > abs(deltaY) * 0.3f -> {
                                GestureMode.SEEK  // 전체 영역에서 가로 스와이프 허용
                            }
                            else -> null
                        }
                    }
                }

                // 결정된 제스처 모드에 따라 동작
                // distanceY: 프레임 간 이동량 (위로 스와이프 시 양수)
                return when (currentGestureMode) {
                    GestureMode.BRIGHTNESS -> {
                        adjustBrightness(-deltaY / screenHeight)
                        true
                    }
                    GestureMode.VOLUME -> {
                        adjustVolume(-deltaY / screenHeight)
                        true
                    }
                    GestureMode.SEEK -> {
                        seekVideo(deltaX)
                        true
                    }
                    else -> false
                }
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false

                val screenHeight = touchSurface.height
                val deltaY = e2.y - e1.y

                // 빠른 속도로 아래로 스와이프하고 화면 맨 아래에 도달하면 종료
                if (velocityY > 2000 && deltaY > 0 && e2.y >= screenHeight * 0.9f) {
                    // 스와이프 종료 시 현재 재생 위치 저장
                    val currentPos = enginePosition
                    prefs.edit()
                        .putLong("last_exit_position", currentPos)
                        .putString("exit_type", "swipe")
                        .apply()
                    Log.d("PlayerActivity", "스와이프 종료 - 재생 위치 저장: ${formatTime(currentPos)}")
                    finish()
                    return true
                }

                return false
            }
        })

        // 터치 리스너 설정 (ExoPlayer 또는 VLC 터치 영역)
        val touchListener = View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = event.x
                    initialY = event.y
                    initialBrightness = window.attributes.screenBrightness.let {
                        if (it < 0) getCurrentBrightness() else it
                    }
                    initialVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    currentGestureMode = null
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isSeeking) {
                        isSeeking = false
                        seekStartPosition = 0L
                        hideSeekPreview()

                        // 시크 후 무조건 재생
                        engineSetPlayWhenReady(true)
                        wasPlayingBeforeSeek = false
                    }

                    // 밝기/볼륨 UI 즉시 숨김
                    brightnessHideJob?.cancel()
                    volumeHideJob?.cancel()
                    brightnessIndicator?.visibility = View.GONE
                    volumeIndicator?.visibility = View.GONE

                    // 배속 재생 복원
                    if (isSpeedPlaybackActive) {
                        engineSetSpeed(originalPlaybackSpeed)
                        isSpeedPlaybackActive = false
                        hideSpeedIndicator()
                        Log.d("PlayerActivity", "배속 재생 종료: ${originalPlaybackSpeed}x로 복원")
                    }

                    if (useVlcEngine) updateVlcPlayPauseIcon()

                    currentGestureMode = null
                }
            }
            gestureDetector.onTouchEvent(event)
        }
        binding.playerView.setOnTouchListener(touchListener)
        binding.vlcTouchOverlay.setOnTouchListener(touchListener)
    }

    private fun getCurrentBrightness(): Float {
        return try {
            Settings.System.getInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS
            ) / 255f
        } catch (e: Settings.SettingNotFoundException) {
            0.5f
        }
    }

    private fun adjustBrightness(delta: Float) {
        // 초기 밝기 + 누적 delta로 직접 SET (딜레이 없음)
        val brightness = (initialBrightness + delta).coerceIn(0.01f, 1.0f)

        val layoutParams = window.attributes
        layoutParams.screenBrightness = brightness
        window.attributes = layoutParams

        prefs.edit().putFloat("saved_brightness", brightness).apply()
        showBrightnessIndicator(brightness)
    }

    private fun showBrightnessIndicator(brightness: Float) {
        val percentage = (brightness * 100).toInt()

        brightnessIndicator?.visibility = View.VISIBLE
        brightnessText?.text = "$percentage%"

        // 게이지 바 높이 업데이트
        val gaugeBar = brightnessProgressBar?.getTag(R.id.playerView) as? View
        gaugeBar?.let {
            val gaugeContainer = it.parent as? FrameLayout
            gaugeContainer?.let { container ->
                // 게이지 컨테이너 높이를 기준으로 바 높이 계산
                container.post {
                    val containerHeight = container.height
                    val newHeight = (containerHeight * percentage / 100f).toInt()
                    val params = it.layoutParams as FrameLayout.LayoutParams
                    params.height = newHeight
                    it.layoutParams = params
                }
            }
        }

        // 이전 타이머 취소
        brightnessHideJob?.cancel()

        // 1.5초 후 자동으로 숨김 (새 타이머)
        brightnessHideJob = lifecycleScope.launch {
            delay(1500)
            brightnessIndicator?.visibility = View.GONE
        }
    }

    private fun adjustVolume(delta: Float) {
        // 초기 볼륨 + 누적 delta로 직접 SET (딜레이 없음)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val initialRatio = initialVolume.toFloat() / maxVolume
        val newRatio = (initialRatio + delta).coerceIn(0f, 1f)
        val newVolume = (newRatio * maxVolume + 0.5f).toInt().coerceIn(0, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)

        showVolumeIndicator(newVolume, maxVolume)
    }

    private fun showVolumeIndicator(volume: Int, maxVolume: Int) {
        val percentage = ((volume.toFloat() / maxVolume) * 100).toInt()

        volumeIndicator?.visibility = View.VISIBLE
        volumeText?.text = "$percentage%"

        // 게이지 바 높이 업데이트
        val gaugeBar = volumeProgressBar?.getTag(R.id.playerView) as? View
        gaugeBar?.let {
            val gaugeContainer = it.parent as? FrameLayout
            gaugeContainer?.let { container ->
                // 게이지 컨테이너 높이를 기준으로 바 높이 계산
                container.post {
                    val containerHeight = container.height
                    val newHeight = (containerHeight * percentage / 100f).toInt()
                    val params = it.layoutParams as FrameLayout.LayoutParams
                    params.height = newHeight
                    it.layoutParams = params
                }
            }
        }

        // 이전 타이머 취소
        volumeHideJob?.cancel()

        // 1.5초 후 자동으로 숨김 (새 타이머)
        volumeHideJob = lifecycleScope.launch {
            delay(1500)
            volumeIndicator?.visibility = View.GONE
        }
    }

    private fun showSpeedIndicator(speed: Float) {
        if (speedIndicator == null) {
            // 배속 인디케이터 생성
            speedIndicator = TextView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = android.view.Gravity.TOP or android.view.Gravity.START
                    setMargins(40, 35, 0, 0)  // 5픽셀 위로 이동 (40 -> 35)
                }
                textSize = 12f
                setTextColor(android.graphics.Color.WHITE)
                (binding.root as? ViewGroup)?.addView(this)
            }
        }

        speedIndicator?.apply {
            text = "${speed.toInt()}x"
            visibility = View.VISIBLE
        }
    }

    private fun hideSpeedIndicator() {
        speedIndicator?.visibility = View.GONE
    }

    private fun seekVideo(deltaX: Float) {
        val duration = engineDuration
        if (duration <= 0) return

        // 스와이프 시작 시 초기 위치 저장 및 일시정지
        if (!isSeeking) {
            isSeeking = true
            seekStartPosition = enginePosition
            wasPlayingBeforeSeek = engineIsPlaying
            lastSeekTime = System.currentTimeMillis()
            lastSeekPosition = seekStartPosition
            engineSetPlayWhenReady(false)
        }

        val sensitivityMultiplier = swipeSensitivity / 10.0f
        val baseSeekAmount = (deltaX / touchSurface.width) * duration * 0.015f * sensitivityMultiplier
        var newPosition = seekStartPosition + baseSeekAmount.toLong()

        if (isSegmentPlaybackEnabled && currentSegments.isNotEmpty() && currentSegmentIndex < currentSegments.size) {
            val segment = currentSegments[currentSegmentIndex]
            newPosition = newPosition.coerceIn(segment.startTime, segment.endTime)
        } else {
            newPosition = newPosition.coerceIn(0, duration)
        }

        val positionDiff = kotlin.math.abs(newPosition - lastSeekPosition)
        if (positionDiff >= 50) {
            engineSeekTo(newPosition)
            lastSeekPosition = newPosition
            Log.d("PlayerActivity", "미리보기 seekTo: ${formatTime(newPosition)} / ${formatTime(duration)}")
        }

        showSeekPreview(newPosition, duration)
    }

    private fun skipForward() {
        val currentPos = enginePosition
        val duration = engineDuration
        var newPosition = currentPos + skipSeconds * 1000L

        if (isSegmentPlaybackEnabled && currentSegments.isNotEmpty() && currentSegmentIndex < currentSegments.size) {
            val segment = currentSegments[currentSegmentIndex]
            newPosition = newPosition.coerceIn(segment.startTime, segment.endTime)
        } else {
            newPosition = newPosition.coerceIn(0, duration)
        }

        engineSeekTo(newPosition)
        showSeekPreview(newPosition, duration)

        lifecycleScope.launch {
            delay(1000)
            hideSeekPreview()
        }
    }

    private fun skipBackward() {
        val currentPos = enginePosition
        val duration = engineDuration
        var newPosition = currentPos - skipSeconds * 1000L

        if (isSegmentPlaybackEnabled && currentSegments.isNotEmpty() && currentSegmentIndex < currentSegments.size) {
            val segment = currentSegments[currentSegmentIndex]
            newPosition = newPosition.coerceIn(segment.startTime, segment.endTime)
        } else {
            newPosition = newPosition.coerceIn(0, duration)
        }

        engineSeekTo(newPosition)
        showSeekPreview(newPosition, duration)

        lifecycleScope.launch {
            delay(1000)
            hideSeekPreview()
        }
    }

    private fun showSeekPreview(position: Long, duration: Long) {
        if (duration <= 0) return

        // 화면 정중앙에 현재 시간 / 총 시간 텍스트 표시
        seekProgressPercentText?.apply {
            text = "${formatTime(position)} / ${formatTime(duration)}"  // "1:23 / 5:45" 형식
            visibility = View.VISIBLE
        }

        // 컨트롤바는 표시하지 않음 (제거)
        // seekPreviewContainer?.visibility = View.VISIBLE  // 주석 처리
    }

    private fun hideSeekPreview() {
        // 이전 타이머 취소
        seekHideJob?.cancel()

        // 화면 중앙 시간 텍스트만 숨김
        seekProgressPercentText?.visibility = View.GONE
    }

    private fun showSeekPreviewForControlBar(position: Long) {
        val duration = engineDuration
        if (duration <= 0) return

        // 화면 정중앙에 현재 시간 / 총 시간 텍스트 표시
        seekProgressPercentText?.apply {
            text = "${formatTime(position)} / ${formatTime(duration)}"
            visibility = View.VISIBLE
        }

        // 실제 위치로 이동 (미리보기)
        engineSeekTo(position)
    }

    private fun showCodecInfo() {
        if (currentVideoCodec.isEmpty() && currentAudioCodec.isEmpty()) {
            return
        }

        val codecInfo = buildString {
            append("재생 실패\n\n")
            if (currentVideoCodec.isNotEmpty()) append("Video: $currentVideoCodec\n")
            if (currentAudioCodec.isNotEmpty()) append("Audio: $currentAudioCodec")
        }

        codecInfoTextView?.apply {
            text = codecInfo
            visibility = View.VISIBLE  // 계속 표시 (숨기지 않음)
        }
    }

    private fun updateSubtitleAndCodecInfo() {
        // TextView 초기화 (최초 1회)
        if (subtitleCodecInfoTextView == null) {
            subtitleCodecInfoTextView = controllerRoot?.findViewById(R.id.subtitleCodecInfo)
        }

        // 자막 정보 수집 (간결하게)
        val subtitleInfo = buildString {
            // 자막 정보
            when {
                selectedEmbeddedTrackIndex >= 0 && selectedEmbeddedTrackIndex < embeddedSubtitleTracks.size -> {
                    append("📄 ON")
                }
                currentSubtitleUri != null -> {
                    append("📄 ON")
                }
                else -> {
                    append("📄 없음")
                }
            }

            // 코덱 정보 추가 (간결하게)
            if (currentVideoCodec.isNotEmpty() || currentAudioCodec.isNotEmpty()) {
                append(" | ")
                val codecParts = mutableListOf<String>()
                if (currentVideoCodec.isNotEmpty()) codecParts.add("V:${currentVideoCodec.take(6)}")
                if (currentAudioCodec.isNotEmpty()) codecParts.add("A:${currentAudioCodec.take(6)}")
                append(codecParts.joinToString(" "))
            }
        }

        // 텍스트만 업데이트 (가시성은 컨트롤바와 연동)
        subtitleCodecInfoTextView?.text = subtitleInfo
    }

    private fun formatTime(millis: Long): String {
        val seconds = (millis / 1000) % 60
        val minutes = (millis / (1000 * 60)) % 60
        val hours = millis / (1000 * 60 * 60)

        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }

    private fun getFileNameFromUri(uriString: String): String {
        return try {
            val uri = android.net.Uri.parse(uriString)

            // HTTP URI with path query parameter (CubbyFileServer 등)
            if ((uri.scheme == "http" || uri.scheme == "https") && uri.getQueryParameter("path") != null) {
                val filePath = uri.getQueryParameter("path")!!
                return filePath.substringAfterLast("/")
            }

            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        it.getString(nameIndex) ?: "Unknown Video"
                    } else {
                        "Unknown Video"
                    }
                } else {
                    "Unknown Video"
                }
            } ?: uri.lastPathSegment ?: "Unknown Video"
        } catch (e: Exception) {
            "Unknown Video"
        }
    }

    /**
     * 자막 파일 자동 감지
     * 1. 비디오와 같은 폴더에서 같은 이름의 자막 파일 검색 (최우선)
     * 2. /storage/emulated/0/subtitles/ 폴더에서 같은 이름 검색
     */
    private fun findSubtitles(videoUri: String): List<SubtitleInfo> {
        val subtitles = mutableListOf<SubtitleInfo>()
        val videoName = getVideoNameWithoutExtension(videoUri)

        Log.d("PlayerActivity", "자막 검색 시작 - 비디오 이름: $videoName")
        Log.d("PlayerActivity", "비디오 URI: $videoUri")

        try {
            // 비디오 파일의 실제 경로 가져오기
            val videoPath = getVideoPath(videoUri)
            Log.d("PlayerActivity", "비디오 실제 경로: $videoPath")

            val parentDir = if (videoPath != null) {
                val videoFile = File(videoPath)
                val dir = videoFile.parent
                Log.d("PlayerActivity", "부모 디렉토리: $dir")
                dir
            } else {
                Log.d("PlayerActivity", "비디오 경로를 찾을 수 없음 - ClipData URI로 자막 검색 시도")
                null
            }

            // 자막 확장자 목록
            val subtitleExtensions = listOf("srt", "vtt", "ass", "ssa", "smi")

            // 비디오 URI가 content://로 시작하면 자막도 같은 방식으로 URI 생성
            val parsedVideoUri = Uri.parse(videoUri)
            val isContentUri = parsedVideoUri.scheme == "content"

            for (ext in subtitleExtensions) {
                try {
                    val subtitleFileName = "$videoName.$ext"
                    var subtitleContent: String? = null

                    if (isContentUri) {
                        // Content URI를 사용하는 경우 (Xplore 등 외부 앱)
                        // 1차: 비디오 URI의 확장자만 바꿔서 자막 URI 생성
                        val subtitleUriString = videoUri
                            .replaceFirst(Regex("\\.(mp4|mkv|avi|mov|wmv|flv|webm)(\\?|$)"), ".$ext$2")

                        val subtitleUri = Uri.parse(subtitleUriString)
                        Log.d("PlayerActivity", "  확인 (Content URI): $subtitleFileName")

                        subtitleContent = try {
                            contentResolver.openInputStream(subtitleUri)?.use { input ->
                                val buffer = ByteArrayOutputStream()
                                val data = ByteArray(16384)
                                var totalRead = 0
                                val maxSize = 10 * 1024 * 1024

                                var nRead: Int
                                while (input.read(data, 0, data.size).also { nRead = it } != -1) {
                                    totalRead += nRead
                                    if (totalRead > maxSize) throw IOException("File too large")
                                    buffer.write(data, 0, nRead)
                                }
                                readTextWithEncoding(buffer.toByteArray(), ext)
                            }
                        } catch (e: Exception) {
                            Log.d("PlayerActivity", "    Content URI 읽기 실패: ${e.message}")
                            null
                        }

                        // 2차: 실제 경로 기반 파일 시스템 검색 (FileProvider URI에서 추출한 경로 활용)
                        if (subtitleContent == null && parentDir != null) {
                            val subtitleFile = File(parentDir, subtitleFileName)
                            Log.d("PlayerActivity", "  확인 (경로 기반 fallback): ${subtitleFile.absolutePath}, 존재: ${subtitleFile.exists()}")
                            if (subtitleFile.exists()) {
                                subtitleContent = try {
                                    readTextWithEncoding(subtitleFile.readBytes(), ext)
                                } catch (e: Exception) {
                                    Log.e("PlayerActivity", "    파일 읽기 실패: ${e.message}", e)
                                    null
                                }
                            }
                        }

                        // 3차: ClipData로 전달받은 자막 URI 시도 (Cubby SMB 등)
                        if (subtitleContent == null && externalSubtitleUris.isNotEmpty()) {
                            val matchingUri = externalSubtitleUris.find { it.endsWith(".$ext", ignoreCase = true) }
                            if (matchingUri != null) {
                                Log.d("PlayerActivity", "  확인 (ClipData URI): $matchingUri")
                                subtitleContent = try {
                                    contentResolver.openInputStream(Uri.parse(matchingUri))?.use { input ->
                                        val buffer = ByteArrayOutputStream()
                                        val data = ByteArray(16384)
                                        var totalRead = 0
                                        val maxSize = 10 * 1024 * 1024
                                        var nRead: Int
                                        while (input.read(data, 0, data.size).also { nRead = it } != -1) {
                                            totalRead += nRead
                                            if (totalRead > maxSize) throw IOException("File too large")
                                            buffer.write(data, 0, nRead)
                                        }
                                        readTextWithEncoding(buffer.toByteArray(), ext)
                                    }
                                } catch (e: Exception) {
                                    Log.d("PlayerActivity", "    ClipData URI 읽기 실패: ${e.message}")
                                    null
                                }
                            }
                        }
                    } else {
                        // 일반 파일 경로인 경우
                        val subtitleFile = File(parentDir, subtitleFileName)
                        Log.d("PlayerActivity", "  확인 (File): ${subtitleFile.name}, 존재: ${subtitleFile.exists()}")

                        if (subtitleFile.exists()) {
                            subtitleContent = try {
                                // 인코딩 자동 감지
                                readTextWithEncoding(subtitleFile.readBytes(), ext)
                            } catch (e: Exception) {
                                Log.e("PlayerActivity", "    읽기 실패: ${e.message}", e)
                                null
                            }
                        }

                        // 같은 폴더에 없으면 공용 자막 폴더에서 검색 (/storage/emulated/0/Subtitles)
                        if (subtitleContent == null) {
                            val publicSubtitlesDir = File("/storage/emulated/0/Subtitles")
                            if (publicSubtitlesDir.exists() && publicSubtitlesDir.isDirectory) {
                                val subtitleFileInPublicDir = File(publicSubtitlesDir, subtitleFileName)
                                Log.d("PlayerActivity", "  확인 (공용 자막 폴더): ${subtitleFileInPublicDir.name}, 존재: ${subtitleFileInPublicDir.exists()}")

                                if (subtitleFileInPublicDir.exists()) {
                                    subtitleContent = try {
                                        // 인코딩 자동 감지
                                        readTextWithEncoding(subtitleFileInPublicDir.readBytes(), ext)
                                    } catch (e: Exception) {
                                        Log.e("PlayerActivity", "    읽기 실패: ${e.message}", e)
                                        null
                                    }
                                }
                            }
                        }

                        // ClipData로 전달받은 자막 URI 시도 (큐비링크 HTTP 등)
                        if (subtitleContent == null && externalSubtitleUris.isNotEmpty()) {
                            val matchingUri = externalSubtitleUris.find { it.endsWith(".$ext", ignoreCase = true) }
                            if (matchingUri != null) {
                                Log.d("PlayerActivity", "  확인 (ClipData URI): $matchingUri")
                                subtitleContent = try {
                                    kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                                        val url = java.net.URL(matchingUri)
                                        url.openStream()?.use { input ->
                                            val buffer = java.io.ByteArrayOutputStream()
                                            val data = ByteArray(16384)
                                            var totalRead = 0
                                            val maxSize = 10 * 1024 * 1024
                                            var nRead: Int
                                            while (input.read(data, 0, data.size).also { nRead = it } != -1) {
                                                totalRead += nRead
                                                if (totalRead > maxSize) throw IOException("File too large")
                                                buffer.write(data, 0, nRead)
                                            }
                                            readTextWithEncoding(buffer.toByteArray(), ext)
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.d("PlayerActivity", "    ClipData URI 읽기 실패: ${e.message}")
                                    null
                                }
                            }
                        }
                    }

                    // 자막 내용을 성공적으로 읽었으면 처리
                    if (subtitleContent != null) {
                        Log.d("PlayerActivity", "  ✓ 자막 파일 발견 및 읽기 성공: $subtitleFileName")

                        // SMI 파일인 경우 자동 변환
                        if (ext in listOf("smi", "sami")) {
                            Log.d("PlayerActivity", "SMI 파일 변환 시작: $subtitleFileName")
                            val convertedFile = convertSmiContentToSrt(subtitleFileName, subtitleContent)
                            if (convertedFile != null && convertedFile.exists()) {
                                subtitles.add(
                                    SubtitleInfo(
                                        uri = Uri.fromFile(convertedFile),
                                        name = convertedFile.name,
                                        mimeType = MimeTypes.APPLICATION_SUBRIP
                                    )
                                )
                                Log.d("PlayerActivity", "SMI -> SRT 변환 성공: ${convertedFile.name}")
                            } else {
                                Log.w("PlayerActivity", "SMI 변환 실패")
                            }
                        } else if (isSubtitleCacheEnabled) {
                            // 캐시 ON: 캐시에 저장
                            val cacheDir = File(cacheDir, "subtitle_cache")
                            cacheDir.mkdirs()
                            val cachedFile = File(cacheDir, subtitleFileName)
                            cachedFile.writeText(subtitleContent)

                            subtitles.add(
                                SubtitleInfo(
                                    uri = Uri.fromFile(cachedFile),
                                    name = cachedFile.name,
                                    mimeType = getMimeTypeFromExtension(ext)
                                )
                            )
                            Log.d("PlayerActivity", "자막 캐시 저장 성공: ${cachedFile.name}")
                        } else {
                            // 캐시 OFF: 원본 파일 경로 직접 사용
                            val originalFile = if (parentDir != null) {
                                val f = File(parentDir, subtitleFileName)
                                if (f.exists()) f else null
                            } else null

                            if (originalFile != null) {
                                subtitles.add(
                                    SubtitleInfo(
                                        uri = Uri.fromFile(originalFile),
                                        name = originalFile.name,
                                        mimeType = getMimeTypeFromExtension(ext)
                                    )
                                )
                                Log.d("PlayerActivity", "자막 원본 경로 직접 사용: ${originalFile.name}")
                            } else {
                                // 원본 경로를 못 찾으면 임시 파일로 fallback
                                val tempDir = File(cacheDir, "subtitle_temp")
                                tempDir.mkdirs()
                                val tempFile = File(tempDir, subtitleFileName)
                                tempFile.writeText(subtitleContent)

                                subtitles.add(
                                    SubtitleInfo(
                                        uri = Uri.fromFile(tempFile),
                                        name = tempFile.name,
                                        mimeType = getMimeTypeFromExtension(ext)
                                    )
                                )
                                Log.d("PlayerActivity", "자막 임시 파일 사용 (원본 경로 없음): ${tempFile.name}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("PlayerActivity", "자막 처리 오류 ($ext): ${e.message}", e)
                }
            }

            Log.d("PlayerActivity", "직접 검색 완료: ${subtitles.size}개 발견")
        } catch (e: Exception) {
            Log.e("PlayerActivity", "자막 검색 오류", e)
        }

        // 자막 우선순위로 정렬 (SRT > VTT > ASS/SSA > SAMI)
        val sortedSubtitles = subtitles.sortedBy { subtitle ->
            when {
                subtitle.mimeType == MimeTypes.APPLICATION_SUBRIP -> 0  // SRT 최우선
                subtitle.mimeType == MimeTypes.TEXT_VTT -> 1             // VTT
                subtitle.mimeType == MimeTypes.TEXT_SSA -> 2             // ASS/SSA
                subtitle.mimeType.contains("sami", ignoreCase = true) -> 9  // SAMI 최하위 (인코딩 문제)
                else -> 5
            }
        }

        Log.d("PlayerActivity", "총 발견된 자막: ${sortedSubtitles.size}개")
        if (sortedSubtitles.isNotEmpty()) {
            Log.d("PlayerActivity", "우선 로드할 자막: ${sortedSubtitles[0].name}, MIME: ${sortedSubtitles[0].mimeType}")
        }
        return sortedSubtitles
    }

    /**
     * URI에서 실제 파일 경로 추출
     */
    private fun getPathFromUri(uri: Uri): String? {
        return try {
            when (uri.scheme) {
                "file" -> uri.path
                "content" -> {
                    val cursor = contentResolver.query(uri, null, null, null, null)
                    cursor?.use {
                        if (it.moveToFirst()) {
                            val columnIndex = it.getColumnIndex("_data")
                            if (columnIndex >= 0) {
                                it.getString(columnIndex)
                            } else null
                        } else null
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e("PlayerActivity", "Error getting path from URI", e)
            null
        }
    }

    /**
     * 비디오 이름에서 확장자 제거
     */
    private fun getVideoNameWithoutExtension(videoUri: String): String {
        val fileName = getFileNameFromUri(videoUri)
        return fileName.substringBeforeLast(".")
    }

    /**
     * URI에서 실제 파일 경로 가져오기
     */
    private fun getVideoPath(videoUri: String): String? {
        return try {
            val uri = Uri.parse(videoUri)

            // content:// URI인 경우
            if (uri.scheme == "content") {
                // 1차: MediaStore 쿼리
                var path: String? = try {
                    val projection = arrayOf(MediaStore.Video.Media.DATA)
                    contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                            cursor.getString(columnIndex)
                        } else null
                    }
                } catch (e: Exception) { null }

                // 2차: FileProvider URI path에서 실제 경로 추출
                // 예: content://com.cubby.provider/root/storage/emulated/0/Movies/test.mp4
                //     → /storage/emulated/0/Movies/test.mp4
                if (path == null) {
                    val uriPath = uri.path
                    if (uriPath != null) {
                        // FileProvider의 root-path 매핑: /root/ 이후가 실제 경로
                        val rootPrefix = "/root/"
                        val externalPrefix = "/external/"
                        val extractedPath = when {
                            uriPath.startsWith(rootPrefix) -> "/" + uriPath.removePrefix(rootPrefix)
                            uriPath.startsWith(externalPrefix) -> "/storage/emulated/0/" + uriPath.removePrefix(externalPrefix)
                            else -> uriPath
                        }
                        if (File(extractedPath).exists()) {
                            path = extractedPath
                            Log.d("PlayerActivity", "FileProvider URI에서 경로 추출 성공: $path")
                        }
                    }
                }

                // 3차: _display_name으로 파일 검색
                if (path == null) {
                    try {
                        val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
                        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val displayName = cursor.getString(0)
                                Log.d("PlayerActivity", "content:// display_name: $displayName")
                            }
                        }
                    } catch (e: Exception) { }
                }

                path
            }
            // file:// URI인 경우
            else if (uri.scheme == "file") {
                uri.path
            }
            // 기타 경로
            else {
                videoUri
            }
        } catch (e: Exception) {
            Log.e("PlayerActivity", "비디오 경로 가져오기 실패", e)
            null
        }
    }

    /**
     * 자막 파일 확장자 체크
     */
    private fun isSubtitleFile(fileName: String): Boolean {
        val extension = fileName.substringAfterLast(".", "").lowercase()
        return extension in listOf("srt", "smi", "ass", "ssa", "vtt", "sub", "sbv")
    }

    /**
     * SMI 파일을 SRT로 변환
     * @param smiFile 원본 SMI 파일
     * @return 변환된 SRT 파일 (실패 시 null)
     */
    private fun convertSmiToSrt(smiFile: File): File? {
        return try {
            val targetDir = if (isSubtitleCacheEnabled) {
                // 캐시 ON: 캐시 디렉토리에 저장
                File(cacheDir, "converted_subtitles")
            } else {
                // 캐시 OFF: 임시 디렉토리에 저장
                File(cacheDir, "subtitle_temp")
            }
            targetDir.mkdirs()

            // 변환된 파일 이름: 원본이름_converted.srt
            val convertedFileName = "${smiFile.nameWithoutExtension}_converted.srt"
            val convertedFile = File(targetDir, convertedFileName)

            // 캐시 ON일 때만 기존 파일 재사용
            if (isSubtitleCacheEnabled && convertedFile.exists() && convertedFile.lastModified() >= smiFile.lastModified()) {
                Log.d("PlayerActivity", "기존 변환 파일 사용: ${convertedFile.name}")
                return convertedFile
            }

            // 변환 실행
            Log.d("PlayerActivity", "SMI 변환 시작: ${smiFile.name}")
            val success = com.splayer.video.util.SamiToSrtConverter.convert(smiFile, convertedFile)

            if (success && convertedFile.exists()) {
                Log.d("PlayerActivity", "SMI 변환 완료: ${convertedFile.absolutePath}")
                convertedFile
            } else {
                Log.e("PlayerActivity", "SMI 변환 실패")
                null
            }
        } catch (e: Exception) {
            Log.e("PlayerActivity", "SMI 변환 오류", e)
            null
        }
    }

    /**
     * SMI 내용을 SRT로 변환 (Scoped Storage 대응)
     */
    private fun convertSmiContentToSrt(smiFileName: String, smiContent: String): File? {
        return try {
            val targetDir = if (isSubtitleCacheEnabled) {
                File(cacheDir, "converted_subtitles")
            } else {
                File(cacheDir, "subtitle_temp")
            }
            targetDir.mkdirs()

            // 임시 SMI 파일 생성
            val fileNameWithoutExt = smiFileName.substringBeforeLast(".")
            val tempSmiFile = File(targetDir, "temp_$smiFileName")
            tempSmiFile.writeText(smiContent)

            // 변환된 파일 이름
            val convertedFileName = "${fileNameWithoutExt}_converted.srt"
            val convertedFile = File(targetDir, convertedFileName)

            // 변환 실행
            Log.d("PlayerActivity", "SMI 내용 변환 시작: $smiFileName")
            val success = com.splayer.video.util.SamiToSrtConverter.convert(tempSmiFile, convertedFile)

            // 임시 파일 삭제
            tempSmiFile.delete()

            if (success && convertedFile.exists()) {
                Log.d("PlayerActivity", "SMI 변환 완료: ${convertedFile.absolutePath}")
                convertedFile
            } else {
                Log.e("PlayerActivity", "SMI 변환 실패")
                null
            }
        } catch (e: Exception) {
            Log.e("PlayerActivity", "SMI 변환 오류", e)
            null
        }
    }

    /**
     * 바이트 배열을 올바른 인코딩으로 읽기 (자동 감지)
     */
    private fun readTextWithEncoding(bytes: ByteArray, fileExtension: String): String {
        // BOM 감지: BOM이 있으면 인코딩 확정
        if (bytes.size >= 3) {
            val bom0 = bytes[0].toInt() and 0xFF
            val bom1 = bytes[1].toInt() and 0xFF
            val bom2 = bytes[2].toInt() and 0xFF
            if (bom0 == 0xEF && bom1 == 0xBB && bom2 == 0xBF) {
                Log.d("PlayerActivity", "BOM 감지: UTF-8")
                return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            }
            if ((bom0 == 0xFF && bom1 == 0xFE) || (bom0 == 0xFE && bom1 == 0xFF)) {
                Log.d("PlayerActivity", "BOM 감지: UTF-16")
                return String(bytes, charset("UTF-16"))
            }
        } else if (bytes.size >= 2) {
            val bom0 = bytes[0].toInt() and 0xFF
            val bom1 = bytes[1].toInt() and 0xFF
            if ((bom0 == 0xFF && bom1 == 0xFE) || (bom0 == 0xFE && bom1 == 0xFF)) {
                Log.d("PlayerActivity", "BOM 감지: UTF-16")
                return String(bytes, charset("UTF-16"))
            }
        }

        // SMI/SAMI 파일은 보통 EUC-KR 또는 UTF-8 인코딩
        val encodingsToTry = if (fileExtension in listOf("smi", "sami")) {
            listOf("EUC-KR", "MS949", "UTF-8")
        } else {
            listOf("UTF-8", "EUC-KR", "MS949")
        }

        for (encoding in encodingsToTry) {
            try {
                val text = String(bytes, charset(encoding))
                // 유효한 텍스트인지 확인 (한글이 포함되어 있는지)
                if (text.contains(Regex("[가-힣]")) || !text.contains("\uFFFD")) {
                    Log.d("PlayerActivity", "인코딩 감지 성공: $encoding")
                    return text
                }
            } catch (e: Exception) {
                Log.d("PlayerActivity", "인코딩 시도 실패 ($encoding): ${e.message}")
            }
        }

        // 모두 실패하면 UTF-8로 반환
        Log.w("PlayerActivity", "인코딩 자동 감지 실패, UTF-8 사용")
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * 확장자에서 MIME 타입 추출
     */
    private fun getMimeTypeFromExtension(extension: String): String {
        return when (extension.lowercase()) {
            "srt" -> MimeTypes.APPLICATION_SUBRIP
            "smi", "sami" -> "application/x-sami" // SAMI 자막
            "vtt" -> MimeTypes.TEXT_VTT
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "sub" -> "text/x-microdvd"
            "sbv" -> "text/x-subviewer"
            else -> MimeTypes.APPLICATION_SUBRIP // 기본값
        }
    }

    /**
     * 자막 로드 (자동 또는 수동)
     */
    @UnstableApi
    private fun loadSubtitle(subtitleUri: Uri) {
        try {
            currentSubtitleUri = subtitleUri

            if (useVlcEngine) {
                // VLC: addSlave로 자막 추가
                vlcMediaPlayer?.addSlave(IMedia.Slave.Type.Subtitle, subtitleUri, true)
                updateSubtitleAndCodecInfo()
            } else {
                player?.let { exoPlayer ->
                    // 현재 재생 위치 저장
                    val currentPosition = exoPlayer.currentPosition
                    val isPlaying = exoPlayer.isPlaying

                    // 자막 포함 MediaItem 생성
                    val videoUri = Uri.parse(externalVideoUri ?: return)
                    val subtitle = MediaItem.SubtitleConfiguration.Builder(subtitleUri)
                        .setMimeType(getMimeTypeFromUri(subtitleUri))
                        .setLanguage("ko")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_AUTOSELECT)
                        .build()

                    val mediaItem = MediaItem.Builder()
                        .setUri(videoUri)
                        .setSubtitleConfigurations(listOf(subtitle))
                        .build()

                    exoPlayer.setMediaItem(mediaItem)
                    exoPlayer.prepare()

                    // 트랙 선택 파라미터 설정 (자막 + 오디오)
                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                        .buildUpon()
                        .setPreferredTextLanguage("ko")
                        .setSelectUndeterminedTextLanguage(true)
                        .setPreferredAudioLanguages("ko", "en", "ja")
                        .build()

                    exoPlayer.seekTo(currentPosition)
                    exoPlayer.playWhenReady = isPlaying

                    // 자막 정보 업데이트
                    updateSubtitleAndCodecInfo()
                }
            }
        } catch (e: Exception) {
            Log.e("PlayerActivity", "Error loading subtitle", e)
        }
    }

    /**
     * URI에서 MIME 타입 추출
     */
    private fun getMimeTypeFromUri(uri: Uri): String {
        val fileName = uri.lastPathSegment ?: ""
        val extension = fileName.substringAfterLast(".", "")
        return getMimeTypeFromExtension(extension)
    }

    /**
     * 설정 다이얼로그 표시 (자막, 소리, 스킵 시간 설정)
     */
    private fun showSubtitleSelectionDialog() {
        // 자막 목록 생성 ("자막 끄기" + "파일에서 선택..." + 사용 가능한 자막들)
        val subtitleItems = mutableListOf<String>()
        subtitleItems.add("자막 끄기")
        subtitleItems.add("파일에서 선택...")
        availableSubtitles.forEach { subtitle ->
            subtitleItems.add(subtitle.name)
        }

        // 현재 선택된 자막 인덱스 찾기
        val currentIndex = if (!isSubtitleEnabled) {
            0 // "자막 끄기" 선택됨
        } else if (currentSubtitleUri != null) {
            // 현재 URI와 일치하는 자막 찾기
            val index = availableSubtitles.indexOfFirst { it.uri == currentSubtitleUri }
            if (index >= 0) index + 2 else 0 // +2는 "자막 끄기", "파일에서 선택..." 때문
        } else {
            0
        }

        // 다이얼로그 생성
        AlertDialog.Builder(this)
            .setTitle("자막 선택")
            .setSingleChoiceItems(
                subtitleItems.toTypedArray(),
                currentIndex
            ) { dialog, which ->
                when (which) {
                    0 -> {
                        // "자막 끄기" 선택
                        switchSubtitle(null)
                        dialog.dismiss()
                    }
                    1 -> {
                        // "파일에서 선택..." 선택
                        dialog.dismiss()
                        openSubtitleFilePicker()
                    }
                    else -> {
                        // 자막 파일 선택 (인덱스에서 2를 빼야 함)
                        val selectedSubtitle = availableSubtitles[which - 2]
                        switchSubtitle(selectedSubtitle)
                        dialog.dismiss()
                    }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun openSubtitleFilePicker() {
        try {
            // 자막 파일 타입 필터 (srt, vtt, ass, ssa, smi)
            subtitleFilePicker.launch("*/*")
            Toast.makeText(this, "자막 파일을 선택하세요", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("PlayerActivity", "파일 선택기 열기 실패", e)
            Toast.makeText(this, "파일 선택기를 열 수 없습니다", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleSelectedSubtitleFile(uri: Uri) {
        try {
            Log.d("PlayerActivity", "선택된 자막 파일 URI: $uri")

            // 파일 이름 가져오기
            var fileName = "subtitle_${System.currentTimeMillis()}"
            try {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0) {
                            fileName = cursor.getString(nameIndex) ?: fileName
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("PlayerActivity", "파일명 가져오기 실패: ${e.message}")
            }

            Log.d("PlayerActivity", "자막 파일명: $fileName")

            // MIME 타입 결정
            val mimeType = when {
                fileName.endsWith(".srt", ignoreCase = true) -> androidx.media3.common.MimeTypes.APPLICATION_SUBRIP
                fileName.endsWith(".vtt", ignoreCase = true) -> androidx.media3.common.MimeTypes.TEXT_VTT
                fileName.endsWith(".ass", ignoreCase = true) || fileName.endsWith(".ssa", ignoreCase = true) ->
                    androidx.media3.common.MimeTypes.TEXT_SSA
                fileName.endsWith(".smi", ignoreCase = true) || fileName.endsWith(".sami", ignoreCase = true) ->
                    "application/x-sami"
                else -> androidx.media3.common.MimeTypes.APPLICATION_SUBRIP
            }

            val subtitleInfo: SubtitleInfo

            // SMI/SAMI 파일인 경우: SRT로 변환 후 로드
            val isSmi = fileName.endsWith(".smi", ignoreCase = true) || fileName.endsWith(".sami", ignoreCase = true)
            if (isSmi) {
                Log.d("PlayerActivity", "SMI 파일 감지, SRT 변환 시작: $fileName")

                // 파일 내용 읽기
                val inputStream = contentResolver.openInputStream(uri)
                if (inputStream == null) {
                    Log.e("PlayerActivity", "InputStream이 null입니다!")
                    Toast.makeText(this, "파일을 열 수 없습니다", Toast.LENGTH_SHORT).show()
                    return
                }

                val bytes = inputStream.use { it.readBytes() }
                val subtitleContent = readTextWithEncoding(bytes, "smi")

                val convertedFile = convertSmiContentToSrt(fileName, subtitleContent)
                if (convertedFile != null && convertedFile.exists()) {
                    Log.d("PlayerActivity", "SMI -> SRT 변환 성공: ${convertedFile.name}")
                    subtitleInfo = SubtitleInfo(
                        uri = Uri.fromFile(convertedFile),
                        name = convertedFile.name,
                        mimeType = androidx.media3.common.MimeTypes.APPLICATION_SUBRIP
                    )
                } else {
                    Log.e("PlayerActivity", "SMI 변환 실패")
                    Toast.makeText(this, "SMI 자막 변환에 실패했습니다", Toast.LENGTH_SHORT).show()
                    return
                }
            } else if (isSubtitleCacheEnabled) {
                // 캐시 ON: 기존 방식 (캐시 폴더에 복사)
                val subtitleCacheDir = File(cacheDir, "subtitle_cache")
                Log.d("PlayerActivity", "캐시 디렉토리 생성: ${subtitleCacheDir.absolutePath}")

                val dirCreated = subtitleCacheDir.mkdirs()
                Log.d("PlayerActivity", "디렉토리 생성 결과: $dirCreated, 존재 여부: ${subtitleCacheDir.exists()}, 쓰기 가능: ${subtitleCacheDir.canWrite()}")

                val cachedFile = File(subtitleCacheDir, fileName)
                Log.d("PlayerActivity", "캐시 파일 경로: ${cachedFile.absolutePath}")

                val inputStream = contentResolver.openInputStream(uri)
                if (inputStream == null) {
                    Log.e("PlayerActivity", "InputStream이 null입니다!")
                    Toast.makeText(this, "파일을 열 수 없습니다", Toast.LENGTH_SHORT).show()
                    return
                }

                Log.d("PlayerActivity", "InputStream 열기 성공, 파일 복사 시작...")

                var bytesCopied = 0L
                inputStream.use { input ->
                    cachedFile.outputStream().use { output ->
                        bytesCopied = input.copyTo(output)
                    }
                }

                Log.d("PlayerActivity", "파일 복사 완료: $bytesCopied 바이트")
                Log.d("PlayerActivity", "자막 파일 캐시 저장 완료: ${cachedFile.absolutePath}")

                subtitleInfo = SubtitleInfo(
                    uri = Uri.fromFile(cachedFile),
                    name = fileName,
                    mimeType = mimeType
                )
            } else {
                // 캐시 OFF: Content URI 직접 사용
                Log.d("PlayerActivity", "캐시 OFF - Content URI 직접 사용: $uri")
                subtitleInfo = SubtitleInfo(
                    uri = uri,
                    name = fileName,
                    mimeType = mimeType
                )
            }

            // availableSubtitles에 추가 (중복 확인)
            val existingIndex = availableSubtitles.indexOfFirst { it.name == fileName }
            if (existingIndex >= 0) {
                // 같은 이름의 자막이 있으면 교체
                availableSubtitles[existingIndex] = subtitleInfo
                Log.d("PlayerActivity", "자막 파일 교체됨: $fileName")
            } else {
                // 새로운 자막 추가
                availableSubtitles.add(subtitleInfo)
                Log.d("PlayerActivity", "자막 목록에 추가됨: $fileName")
            }

            // 선택한 자막을 바로 적용
            Log.d("PlayerActivity", "자막 즉시 적용 시작: $fileName")
            switchSubtitle(subtitleInfo)

        } catch (e: Exception) {
            Log.e("PlayerActivity", "자막 파일 처리 실패", e)
            Toast.makeText(this, "자막 파일을 로드할 수 없습니다: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun switchSubtitle(subtitle: SubtitleInfo?) {
        try {
            // 자막 설정 저장
            if (subtitle == null) {
                isSubtitleEnabled = false
                currentSubtitleUri = null
                Log.d("PlayerActivity", "자막 끄기")
            } else {
                isSubtitleEnabled = true
                currentSubtitleUri = subtitle.uri
                Log.d("PlayerActivity", "자막 변경: ${subtitle.name}, URI: ${subtitle.uri}")
            }
            prefs.edit().putBoolean("subtitle_enabled", isSubtitleEnabled).apply()

            if (useVlcEngine) {
                // VLC 모드: addSlave로 자막 추가/제거
                if (subtitle != null) {
                    vlcMediaPlayer?.addSlave(IMedia.Slave.Type.Subtitle, subtitle.uri, true)
                    Toast.makeText(this, "자막: ${subtitle.name}", Toast.LENGTH_SHORT).show()
                } else {
                    vlcMediaPlayer?.spuTrack = -1
                    Toast.makeText(this, "자막이 꺼졌습니다", Toast.LENGTH_SHORT).show()
                }
            } else {
                val exoPlayer = player
                if (exoPlayer == null) {
                    Toast.makeText(this, "플레이어가 준비되지 않았습니다", Toast.LENGTH_SHORT).show()
                    return
                }

                // 현재 상태 저장
                val savedPosition = exoPlayer.currentPosition
                val wasPlaying = exoPlayer.isPlaying

                // 현재 재생 중인 비디오의 URI 가져오기
                val videoUri = exoPlayer.currentMediaItem?.localConfiguration?.uri
                if (videoUri == null) {
                    Toast.makeText(this, "비디오가 로드되지 않았습니다", Toast.LENGTH_SHORT).show()
                    Log.e("PlayerActivity", "현재 MediaItem의 URI가 null입니다")
                    return
                }

                Log.d("PlayerActivity", "현재 비디오 URI: $videoUri")

                // MediaItem 재생성 (자막 포함/제외)
                val mediaItem = if (subtitle != null) {
                    val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(subtitle.uri)
                        .setMimeType(subtitle.mimeType)
                        .setLanguage("ko")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_AUTOSELECT)
                        .build()

                    MediaItem.Builder()
                        .setUri(videoUri)
                        .setSubtitleConfigurations(listOf(subtitleConfig))
                        .build()
                } else {
                    MediaItem.fromUri(videoUri)
                }

                // 플레이어 완전 정지 및 재시작 (트랙 재로드 강제)
                exoPlayer.stop()
                exoPlayer.clearMediaItems()

                Log.d("PlayerActivity", "플레이어 정지 및 MediaItem 클리어 완료")

                exoPlayer.setMediaItem(mediaItem, savedPosition)
                exoPlayer.prepare()
                exoPlayer.playWhenReady = wasPlaying

                Log.d("PlayerActivity", "새 MediaItem 설정 (위치: $savedPosition ms) 및 prepare 완료")

                // 자막 표시 설정 (SELECTION_FLAG_AUTOSELECT가 자동으로 트랙 선택)
                if (subtitle != null) {
                    binding.playerView.subtitleView?.visibility = View.VISIBLE
                    Log.d("PlayerActivity", "자막 표시 활성화: ${subtitle.name}")
                    Toast.makeText(this, "자막: ${subtitle.name}", Toast.LENGTH_SHORT).show()
                } else {
                    binding.playerView.subtitleView?.visibility = View.GONE
                    Log.d("PlayerActivity", "자막 숨김")
                    Toast.makeText(this, "자막이 꺼졌습니다", Toast.LENGTH_SHORT).show()
                }

                Log.d("PlayerActivity", "자막 전환 완료 (트랙은 AUTOSELECT로 자동 처리)")
            }
        } catch (e: Exception) {
            Log.e("PlayerActivity", "자막 전환 실패", e)
            Toast.makeText(this, "자막 전환 실패: ${e.message}", Toast.LENGTH_LONG).show()
        }

        // 자막/코덱 정보 업데이트
        updateSubtitleAndCodecInfo()
    }

    private fun showSubtitleDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)

        // 컴포넌트 초기화
        val spinnerPlayerEngine = dialogView.findViewById<Spinner>(R.id.spinnerPlayerEngine)
        val switchSound = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchSound)
        val switchStartMute = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchStartMute)
        val switchStartMaxBrightness = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchStartMaxBrightness)
        val spinnerSkipTime = dialogView.findViewById<Spinner>(R.id.spinnerSkipTime)
        val spinnerSensitivity = dialogView.findViewById<Spinner>(R.id.spinnerSensitivity)
        val switchBrightnessSwipe = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchBrightnessSwipe)
        val switchVolumeSwipe = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchVolumeSwipe)
        val switchContinuousPlay = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchContinuousPlay)
        val spinnerBufferMode = dialogView.findViewById<Spinner>(R.id.spinnerBufferMode)
        val spinnerSeekMode = dialogView.findViewById<Spinner>(R.id.spinnerSeekMode)
        val layoutEmbeddedSubtitles = dialogView.findViewById<LinearLayout>(R.id.layoutEmbeddedSubtitles)
        val spinnerEmbeddedSubtitle = dialogView.findViewById<Spinner>(R.id.spinnerEmbeddedSubtitle)

        // 캐시 관리 컴포넌트
        val switchSubtitleCache = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchSubtitleCache)
        val cachePathText = dialogView.findViewById<TextView>(R.id.cachePathText)
        val cacheFileCountText = dialogView.findViewById<TextView>(R.id.cacheFileCountText)
        val btnClearCache = dialogView.findViewById<Button>(R.id.btnClearCache)

        // 구간 재생 관리 컴포넌트
        val editSegmentSavePath = dialogView.findViewById<EditText>(R.id.editSegmentSavePath)
        val editSegmentFilePath = dialogView.findViewById<EditText>(R.id.editSegmentFilePath)
        val btnSelectFolder = dialogView.findViewById<ImageButton>(R.id.btnSelectFolder)
        val btnBackupSegmentFile = dialogView.findViewById<Button>(R.id.btnBackupSegmentFile)
        val btnRestoreSegmentFile = dialogView.findViewById<Button>(R.id.btnRestoreSegmentFile)

        // 구간 추출 파일 저장 경로 설정
        val currentSavePath = prefs.getString("segment_save_path", "/storage/emulated/0/Movies") ?: "/storage/emulated/0/Movies"
        editSegmentSavePath.setText(currentSavePath)

        // 구간 정보 파일 경로 표시
        val currentSegmentFilePath = segmentManager.getSegmentFilePath()
        editSegmentFilePath.setText(currentSegmentFilePath)

        // 추출 경로 폴더 선택 버튼 클릭 리스너
        btnSelectFolder.setOnClickListener {
            currentDialogEditText = editSegmentSavePath
            currentFolderType = FolderType.EXTRACTION
            folderPickerLauncher.launch(null)
        }

        // 구간파일 백업 버튼 클릭 리스너
        btnBackupSegmentFile.setOnClickListener {
            backupSegmentFileLauncher.launch("splayer.per")
        }

        // 구간파일 복원 버튼 클릭 리스너
        btnRestoreSegmentFile.setOnClickListener {
            restoreSegmentFileLauncher.launch(arrayOf("*/*"))
        }

        // 추출 경로 텍스트 변경 리스너
        editSegmentSavePath.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val newPath = s.toString()
                prefs.edit().putString("segment_save_path", newPath).apply()
            }
        })

        // 플레이어 엔진 스피너 설정
        val engineOptions = arrayOf("ExoPlayer", "VLC")
        val engineAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, engineOptions)
        engineAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerPlayerEngine.adapter = engineAdapter
        spinnerPlayerEngine.setSelection(if (useVlcEngine) 1 else 0)

        spinnerPlayerEngine.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val newUseVlc = position == 1
                if (newUseVlc != useVlcEngine) {
                    prefs.edit().putBoolean("use_vlc_engine", newUseVlc).apply()
                    android.widget.Toast.makeText(
                        this@PlayerActivity,
                        "엔진 변경: ${if (newUseVlc) "VLC" else "ExoPlayer"}\n다음 재생부터 적용됩니다",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 현재 설정값 적용
        switchSound.isChecked = isSoundEnabled
        switchStartMute.isChecked = startWithMute
        switchStartMaxBrightness.isChecked = startWithMaxBrightness
        switchBrightnessSwipe.isChecked = isBrightnessSwipeEnabled
        switchVolumeSwipe.isChecked = isVolumeSwipeEnabled
        switchContinuousPlay.isChecked = isContinuousPlayEnabled

        // 스킵 시간 스피너 설정
        val skipTimeOptions = arrayOf("⏩ 5초", "⏩ 10초", "⏩ 15초", "⏩ 20초", "⏩ 30초", "⏩ 60초")
        val skipTimeValues = arrayOf(5, 10, 15, 20, 30, 60)
        val skipTimeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, skipTimeOptions)
        skipTimeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSkipTime.adapter = skipTimeAdapter
        spinnerSkipTime.setSelection(skipTimeValues.indexOf(skipSeconds).let { if (it == -1) 1 else it })

        // 스와이프 민감도 스피너 설정
        val sensitivityOptions = (1..20).map { level ->
            when {
                level <= 5 -> "🐢 $level"
                level <= 10 -> "🚶 $level"
                level <= 15 -> "🏃 $level"
                else -> "🚀 $level"
            }
        }.toTypedArray()

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, sensitivityOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSensitivity.adapter = adapter
        spinnerSensitivity.setSelection(swipeSensitivity - 1)

        // 버퍼링 모드 스피너 설정
        val bufferModeOptions = arrayOf("🛡️ 안정", "⚡ 빠른 시작")
        val bufferModeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, bufferModeOptions)
        bufferModeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerBufferMode.adapter = bufferModeAdapter
        spinnerBufferMode.setSelection(bufferMode)

        // 시크 모드 스피너 설정
        val seekModeOptions = arrayOf("🎯 정확", "⚡ 빠름")
        val seekModeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, seekModeOptions)
        seekModeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSeekMode.adapter = seekModeAdapter
        spinnerSeekMode.setSelection(seekMode)

        // 캐스팅 방식 스피너 설정
        val spinnerCastMode = dialogView.findViewById<Spinner>(R.id.spinnerCastMode)
        val castModeOptions = arrayOf("📺 Chromecast", "📡 DLNA")
        val castModeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, castModeOptions)
        castModeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerCastMode.adapter = castModeAdapter
        val savedCastMode = prefs.getString("cast_mode", "chromecast") ?: "chromecast"
        spinnerCastMode.setSelection(if (savedCastMode == "dlna") 1 else 0)

        spinnerCastMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val mode = if (position == 1) "dlna" else "chromecast"
                prefs.edit().putString("cast_mode", mode).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 내장 자막 스피너 설정
        if (embeddedSubtitleTracks.isNotEmpty()) {
            layoutEmbeddedSubtitles.visibility = View.VISIBLE

            // 자막 옵션 생성 ("자막 끄기" + 각 자막 트랙)
            val subtitleOptions = mutableListOf<String>()
            subtitleOptions.add("❌ 자막 끄기")
            embeddedSubtitleTracks.forEach { track ->
                subtitleOptions.add("${track.label} (${track.language})")
            }

            val subtitleAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, subtitleOptions)
            subtitleAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerEmbeddedSubtitle.adapter = subtitleAdapter

            // 한국어 자막이 있으면 기본값으로 설정
            val koreanIndex = embeddedSubtitleTracks.indexOfFirst {
                it.language?.contains("kor", ignoreCase = true) == true ||
                it.language?.contains("ko", ignoreCase = true) == true
            }

            val defaultSelection = if (koreanIndex >= 0 && selectedEmbeddedTrackIndex == -1) {
                koreanIndex + 1 // "자막 끄기"가 0번이므로 +1
            } else {
                selectedEmbeddedTrackIndex + 1 // 현재 선택된 자막 (자막 끄기는 -1이므로 +1하면 0)
            }

            spinnerEmbeddedSubtitle.setSelection(defaultSelection.coerceAtLeast(0))

            // 자막 선택 리스너
            spinnerEmbeddedSubtitle.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val trackIndex = position - 1 // "자막 끄기"가 0번이므로 -1
                    if (trackIndex != selectedEmbeddedTrackIndex) {
                        selectEmbeddedSubtitle(trackIndex)
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        } else {
            layoutEmbeddedSubtitles.visibility = View.GONE
        }

        // 다이얼로그 생성 (버튼에서 참조하기 위해 먼저 생성)
        val dialog = AlertDialog.Builder(this)
            .setTitle("⚙️ 설정")
            .setView(dialogView)
            .setNegativeButton("닫기", null)
            .create()

        // 이벤트 리스너 설정
        switchSound.setOnCheckedChangeListener { _, isChecked ->
            isSoundEnabled = isChecked
            prefs.edit().putBoolean("sound_enabled", isSoundEnabled).apply()
            engineSetVolume(if (isSoundEnabled) 1f else 0f)

            // 소리 ON일 때만 볼륨 스와이프 활성화
            isVolumeSwipeEnabled = isSoundEnabled
            switchVolumeSwipe.isChecked = isVolumeSwipeEnabled
        }

        switchStartMute.setOnCheckedChangeListener { _, isChecked ->
            startWithMute = isChecked
            prefs.edit().putBoolean("start_with_mute", startWithMute).apply()
            // startWithMute는 시작시에만 영향을 주고, 볼륨 스와이프와는 독립적
        }

        switchStartMaxBrightness.setOnCheckedChangeListener { _, isChecked ->
            startWithMaxBrightness = isChecked
            prefs.edit().putBoolean("start_with_max_brightness", startWithMaxBrightness).apply()

            // 최대밝기 OFF일 때만 밝기 스와이프 활성화
            isBrightnessSwipeEnabled = !startWithMaxBrightness
            switchBrightnessSwipe.isChecked = isBrightnessSwipeEnabled
        }

        // 밝기 스와이프 스위치 리스너
        switchBrightnessSwipe.setOnCheckedChangeListener { _, isChecked ->
            isBrightnessSwipeEnabled = isChecked
            prefs.edit().putBoolean("brightness_swipe_enabled", isBrightnessSwipeEnabled).apply()
        }

        // 볼륨 스와이프 스위치 리스너
        switchVolumeSwipe.setOnCheckedChangeListener { _, isChecked ->
            isVolumeSwipeEnabled = isChecked
            prefs.edit().putBoolean("volume_swipe_enabled", isVolumeSwipeEnabled).apply()
        }

        switchContinuousPlay.setOnCheckedChangeListener { _, isChecked ->
            isContinuousPlayEnabled = isChecked
            prefs.edit().putBoolean("continuous_play_enabled", isContinuousPlayEnabled).apply()
        }

        spinnerSkipTime.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                skipSeconds = skipTimeValues[position]
                prefs.edit().putInt("skip_seconds", skipSeconds).apply()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spinnerSensitivity.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                swipeSensitivity = position + 1
                prefs.edit().putInt("swipe_sensitivity", swipeSensitivity).apply()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spinnerBufferMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position != bufferMode) {
                    bufferMode = position
                    prefs.edit().putInt("buffer_mode", bufferMode).apply()
                    Toast.makeText(this@PlayerActivity, "버퍼링 모드: ${bufferModeOptions[position]}\n다음 재생부터 적용됩니다", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spinnerSeekMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position != seekMode) {
                    seekMode = position
                    prefs.edit().putInt("seek_mode", seekMode).apply()
                    Toast.makeText(this@PlayerActivity, "시크 모드: ${seekModeOptions[position]}\n다음 재생부터 적용됩니다", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 자막 캐시 토글
        switchSubtitleCache.isChecked = isSubtitleCacheEnabled
        switchSubtitleCache.setOnCheckedChangeListener { _, isChecked ->
            isSubtitleCacheEnabled = isChecked
            prefs.edit().putBoolean("subtitle_cache_enabled", isChecked).apply()
            Log.d("PlayerActivity", "자막 캐시 설정 변경: ${if (isChecked) "ON" else "OFF"}")
            Toast.makeText(this, "자막 캐시: ${if (isChecked) "ON" else "OFF"}\n다음 재생부터 적용됩니다", Toast.LENGTH_SHORT).show()
        }

        // 캐시 정보 업데이트
        updateCacheInfo(cachePathText, cacheFileCountText)

        // 캐시 삭제 버튼
        btnClearCache.setOnClickListener {
            val cacheDir = File(cacheDir, "converted_subtitles")
            val fileCount = cacheDir.listFiles()?.size ?: 0

            if (fileCount == 0) {
                android.widget.Toast.makeText(this, "삭제할 캐시 파일이 없습니다", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 삭제 확인 다이얼로그
            AlertDialog.Builder(this)
                .setTitle("캐시 삭제")
                .setMessage("${fileCount}개의 변환된 자막 파일을 삭제하시겠습니까?")
                .setPositiveButton("삭제") { _, _ ->
                    val deletedCount = clearSubtitleCache()
                    android.widget.Toast.makeText(this, "${deletedCount}개 파일 삭제 완료", android.widget.Toast.LENGTH_SHORT).show()
                    // 캐시 정보 다시 업데이트
                    updateCacheInfo(cachePathText, cacheFileCountText)
                }
                .setNegativeButton("취소", null)
                .show()
        }

        dialog.show()
    }

    /**
     * 구간 리스트 다이얼로그
     */
    private fun showSegmentListDialog() {
        val fileName = segmentManager.extractFileName(currentVideoPath ?: externalVideoUri ?: "")
        val segments = segmentManager.getSegmentsForFile(fileName).toMutableList()

        if (segments.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("구간 리스트")
                .setMessage("현재 파일에 대한 저장된 구간이 없습니다.")
                .setPositiveButton("확인", null)
                .show()
            return
        }

        // RecyclerView를 위한 레이아웃 생성
        val recyclerView = androidx.recyclerview.widget.RecyclerView(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@PlayerActivity)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("구간 리스트 (총 ${segments.size}개)")
            .setView(recyclerView)
            .setPositiveButton("전체 삭제") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("구간 전체 삭제")
                    .setMessage("이 파일의 모든 구간을 삭제하시겠습니까?")
                    .setPositiveButton("삭제") { _, _ ->
                        segmentManager.deleteAllSegmentsForFile(fileName)
                        // 구간 재생이 활성화되어 있으면 비활성화
                        if (isSegmentPlaybackEnabled) {
                            isSegmentPlaybackEnabled = false
                            updateSegmentPlaybackToggleUI()
                        }
                        android.widget.Toast.makeText(
                            this,
                            "모든 구간이 삭제되었습니다.",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    .setNegativeButton("취소", null)
                    .show()
            }
            .setNegativeButton("닫기", null)
            .create()

        val adapter = com.splayer.video.ui.adapter.SegmentAdapter(
            segments = segments,
            onMoveUp = { position ->
                if (position > 0) {
                    val temp = segments[position]
                    segments[position] = segments[position - 1]
                    segments[position - 1] = temp
                    segmentManager.reorderSegments(fileName, segments)
                    recyclerView.adapter?.notifyItemMoved(position, position - 1)
                }
            },
            onMoveDown = { position ->
                if (position < segments.size - 1) {
                    val temp = segments[position]
                    segments[position] = segments[position + 1]
                    segments[position + 1] = temp
                    segmentManager.reorderSegments(fileName, segments)
                    recyclerView.adapter?.notifyItemMoved(position, position + 1)
                }
            },
            onEdit = { position ->
                showEditSegmentDialog(fileName, segments, position, recyclerView)
            },
            onSave = { position ->
                val segment = segments[position]
                val msg = if (useVlcEngine)
                    "${segment.sequence}번 구간을 저장합니다.\n(VLC 모드 — ExoPlayer 권장)"
                else
                    "${segment.sequence}번 구간을 비디오 파일로 저장합니다."
                AlertDialog.Builder(this)
                    .setTitle("구간 추출")
                    .setMessage(msg)
                    .setPositiveButton("시간 기준") { _, _ ->
                        saveSegmentAsVideo(segment, useKeyframe = false)
                    }
                    .setNeutralButton("키프레임 기준") { _, _ ->
                        saveSegmentAsVideo(segment, useKeyframe = true)
                    }
                    .setNegativeButton("취소", null)
                    .show()
            },
            onDelete = { position ->
                AlertDialog.Builder(this)
                    .setTitle("구간 삭제")
                    .setMessage("${segments[position].sequence}번 구간을 삭제하시겠습니까?")
                    .setPositiveButton("삭제") { _, _ ->
                        val segment = segments[position]
                        segmentManager.deleteSegment(fileName, segment.sequence)
                        segments.removeAt(position)

                        if (segments.isEmpty()) {
                            dialog.dismiss()
                            android.widget.Toast.makeText(
                                this,
                                "모든 구간이 삭제되었습니다.",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            dialog.setTitle("구간 리스트 (총 ${segments.size}개)")
                            recyclerView.adapter?.notifyDataSetChanged()
                        }

                        // 구간 재생이 활성화되어 있으면 목록 업데이트
                        if (isSegmentPlaybackEnabled) {
                            loadSegmentsForCurrentFile()
                        }
                    }
                    .setNegativeButton("취소", null)
                    .show()
            },
            onItemClick = { position ->
                // 다이얼로그 닫기
                dialog.dismiss()

                // 해당 구간만 반복 재생
                val selectedSegment = segments[position]
                currentSegments = mutableListOf(selectedSegment)
                currentSegmentIndex = 0
                isSegmentPlaybackEnabled = true

                // 구간 시작 위치로 이동하고 재생
                engineSeekTo(selectedSegment.startTime)
                engineSetPlayWhenReady(true)

                // 순번 표시 업데이트
                updateSegmentSequenceDisplay()

                android.widget.Toast.makeText(
                    this,
                    "${selectedSegment.sequence}번 구간 반복 재생",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        )

        recyclerView.adapter = adapter
        dialog.show()
    }

    /**
     * 구간 시간 수정 다이얼로그
     */
    private fun showEditSegmentDialog(
        fileName: String,
        segments: MutableList<com.splayer.video.data.model.PlaybackSegment>,
        position: Int,
        recyclerView: androidx.recyclerview.widget.RecyclerView
    ) {
        val segment = segments[position]

        // 다이얼로그 레이아웃 생성
        val dialogView = layoutInflater.inflate(android.R.layout.simple_list_item_2, null)
        val linearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
        }

        // 시작 시간 입력
        val startTimeLabel = TextView(this).apply {
            text = "시작 시간 (HH:MM:SS)"
            textSize = 16f
        }
        val timeTextWatcher = fun(editText: EditText): android.text.TextWatcher {
            return object : android.text.TextWatcher {
                private var isFormatting = false
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    if (isFormatting || s == null) return
                    isFormatting = true
                    val digits = s.toString().replace(":", "").filter { it.isDigit() }.take(6)
                    val formatted = buildString {
                        digits.forEachIndexed { i, c ->
                            if (i == 2 || i == 4) append(":")
                            append(c)
                        }
                    }
                    if (s.toString() != formatted) {
                        editText.setText(formatted)
                        editText.setSelection(formatted.length)
                    }
                    isFormatting = false
                }
            }
        }

        val startTimeInput = EditText(this).apply {
            setText(formatTimeForEdit(segment.startTime))
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            addTextChangedListener(timeTextWatcher(this))
        }

        // 종료 시간 입력
        val endTimeLabel = TextView(this).apply {
            text = "종료 시간 (HH:MM:SS)"
            textSize = 16f
            setPadding(0, 30, 0, 0)
        }
        val endTimeInput = EditText(this).apply {
            setText(formatTimeForEdit(segment.endTime))
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            addTextChangedListener(timeTextWatcher(this))
        }

        linearLayout.addView(startTimeLabel)
        linearLayout.addView(startTimeInput)
        linearLayout.addView(endTimeLabel)
        linearLayout.addView(endTimeInput)

        AlertDialog.Builder(this)
            .setTitle("구간 ${segment.sequence} 수정")
            .setView(linearLayout)
            .setPositiveButton("저장") { _, _ ->
                try {
                    val newStartTime = parseTimeInput(startTimeInput.text.toString())
                    val newEndTime = parseTimeInput(endTimeInput.text.toString())

                    if (newStartTime >= newEndTime) {
                        android.widget.Toast.makeText(
                            this,
                            "종료 시간은 시작 시간보다 커야 합니다",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                        return@setPositiveButton
                    }

                    // 구간 업데이트
                    val updatedSegment = segment.copy(
                        startTime = newStartTime,
                        endTime = newEndTime
                    )
                    segments[position] = updatedSegment

                    // 파일에 저장
                    segmentManager.reorderSegments(fileName, segments)

                    // UI 업데이트
                    recyclerView.adapter?.notifyItemChanged(position)

                    // 구간 재생이 활성화되어 있으면 목록 업데이트
                    if (isSegmentPlaybackEnabled) {
                        loadSegmentsForCurrentFile()
                    }

                    android.widget.Toast.makeText(
                        this,
                        "구간이 수정되었습니다",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    android.widget.Toast.makeText(
                        this,
                        "시간 형식이 올바르지 않습니다 (HH:MM:SS)",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /**
     * 시간을 편집 가능한 형식으로 변환 (HH:MM:SS)
     */
    private fun formatTimeForEdit(timeMs: Long): String {
        val totalSeconds = timeMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    /**
     * 시간 입력을 밀리초로 변환 (HH:MM:SS)
     */
    private fun parseTimeInput(timeStr: String): Long {
        val parts = timeStr.split(":")
        if (parts.size != 3) throw IllegalArgumentException("Invalid time format")

        val hours = parts[0].toLong()
        val minutes = parts[1].toLong()
        val seconds = parts[2].toLong()

        return (hours * 3600 + minutes * 60 + seconds) * 1000
    }

    /**
     * DocumentTree URI를 파일 경로로 변환
     */
    private fun getPathFromTreeUri(uri: Uri): String {
        // DocumentTree URI의 경우 실제 파일 경로를 추출
        // content://com.android.externalstorage.documents/tree/primary:Movies 형태
        val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)

        // "primary:" 접두사 제거하고 슬래시로 변환
        val path = when {
            docId.startsWith("primary:") -> {
                val relativePath = docId.substring("primary:".length)
                // 빈 경로면 루트, 아니면 앞에 슬래시 추가
                if (relativePath.isEmpty()) {
                    "/storage/emulated/0"
                } else {
                    "/storage/emulated/0/$relativePath"
                }
            }
            docId.startsWith("raw:") -> {
                // raw: 접두사가 있으면 그대로 사용
                docId.substring("raw:".length)
            }
            else -> {
                // 기타 경우는 Environment를 이용하여 경로 생성
                val externalStorage = Environment.getExternalStorageDirectory().absolutePath
                "$externalStorage/$docId"
            }
        }

        return path
    }

    /**
     * 구간을 비디오 파일로 저장
     */
    private fun saveSegmentAsVideo(segment: com.splayer.video.data.model.PlaybackSegment, useKeyframe: Boolean = false) {
        // 원본 비디오 파일 이름 (한글 포함 지원)
        val videoPath = currentVideoPath ?: externalVideoUri ?: ""

        // 키프레임 모드: 시작/끝 시간을 키프레임 위치로 보정
        val actualStartTime: Long
        val actualEndTime: Long
        if (useKeyframe) {
            actualStartTime = findNearestKeyframe(videoPath, segment.startTime, seekBefore = true)
            actualEndTime = findNearestKeyframe(videoPath, segment.endTime, seekBefore = false)
            android.util.Log.d("PlayerActivity", "키프레임 보정: ${segment.startTime}→${actualStartTime}ms, ${segment.endTime}→${actualEndTime}ms")
        } else {
            actualStartTime = segment.startTime
            actualEndTime = segment.endTime
        }

        val fileName = segmentManager.extractFileName(videoPath)
        val baseFileName = fileName.substringBeforeLast(".")

        // 파일명: 재생파일명+시작시간+종료시간 (콜론 제외)
        val startTimeStr = formatTimeForEdit(actualStartTime).replace(":", "")
        val endTimeStr = formatTimeForEdit(actualEndTime).replace(":", "")
        val outputFileName = "${baseFileName}_${startTimeStr}_${endTimeStr}.mp4"

        // 취소 플래그
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)

        // 진행 다이얼로그
        val progressDialog = android.app.ProgressDialog(this).apply {
            setTitle("구간 저장 중")
            setMessage("[FFmpeg] 추출 중...\n$outputFileName")
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

        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val tempFile = File(cacheDir, "ffmpeg_temp_${System.currentTimeMillis()}.mp4")
            try {
                var success = false

                // 1차: FFmpeg 시도 (빠름)
                if (!cancelled.get()) {
                    try {
                        try {
                            // content:// (SMB 등): FFmpegKit SAF 프로토콜 사용
                            // 로컬 파일: 직접 경로
                            val ffmpegInput = if (videoPath.startsWith("content://")) {
                                val uri = android.net.Uri.parse(videoPath)
                                com.arthenica.ffmpegkit.FFmpegKitConfig.getSafParameterForRead(this@PlayerActivity, uri)
                            } else {
                                videoPath
                            }
                            val startSec = "%.3f".format(java.util.Locale.US, actualStartTime / 1000.0)
                            val durationSec = "%.3f".format(java.util.Locale.US, (actualEndTime - actualStartTime) / 1000.0)
                            val durationMs = actualEndTime - actualStartTime
                            android.util.Log.d("PlayerActivity", "FFmpeg 시도: input=$ffmpegInput ss=$startSec t=$durationSec")

                            // FFmpeg 진행률 콜백
                            com.arthenica.ffmpegkit.FFmpegKitConfig.enableStatisticsCallback { stats ->
                                if (durationMs > 0) {
                                    val progress = ((stats.time.toFloat() / durationMs) * 100).toInt().coerceIn(0, 100)
                                    lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                                        if (progressDialog.isShowing) {
                                            progressDialog.isIndeterminate = false
                                            progressDialog.max = 100
                                            progressDialog.progress = progress
                                        }
                                    }
                                }
                            }

                            // -ss를 -i 앞에 배치 (input-side seek → 빠름)
                            val session = com.arthenica.ffmpegkit.FFmpegKit.executeWithArguments(
                                arrayOf("-y", "-ss", startSec, "-i", ffmpegInput, "-t", durationSec, "-c", "copy", tempFile.absolutePath)
                            )

                            if (!cancelled.get() && com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode) && tempFile.exists() && tempFile.length() > 0L) {
                                success = true
                                android.util.Log.d("PlayerActivity", "FFmpeg 추출 성공: ${tempFile.length()} bytes")
                            } else {
                                android.util.Log.w("PlayerActivity", "FFmpeg 추출 실패: rc=${session.returnCode}, output=${session.output}")
                                tempFile.delete()
                            }
                        } finally {
                            // SAF fd는 FFmpegKit이 자동 관리
                        }
                    } catch (t: Throwable) {
                        android.util.Log.e("PlayerActivity", "FFmpeg 사용 불가, MediaExtractor로 전환", t)
                        tempFile.delete()
                    }
                }

                // 2차: MediaExtractor fallback
                if (!success && !cancelled.get()) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        progressDialog.isIndeterminate = false
                        progressDialog.max = 100
                        progressDialog.progress = 0
                        progressDialog.setMessage("[MediaExtractor] 추출 중...\n$outputFileName")
                    }
                    extractVideoSegment(
                        videoPath,
                        tempFile.absolutePath,
                        actualStartTime * 1000,
                        actualEndTime * 1000,
                        progressDialog,
                        cancelled
                    )
                    success = !cancelled.get() && tempFile.exists() && tempFile.length() > 0L
                }

                // 취소된 경우
                if (cancelled.get()) {
                    tempFile.delete()
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        progressDialog.dismiss()
                        Toast.makeText(this@PlayerActivity, "구간 저장 취소됨", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                if (!success) {
                    throw Exception("추출 실패")
                }

                // temp → MediaStore (Q+) 또는 외부 저장소
                saveToMediaStore(tempFile, outputFileName)
                tempFile.delete()

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(this@PlayerActivity, "구간 저장 완료: $outputFileName", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                tempFile.delete()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(this@PlayerActivity, "구간 저장 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
                android.util.Log.e("PlayerActivity", "Failed to save segment", e)
            }
        }
    }

    /**
     * 지정 시간 근처의 키프레임 위치를 찾는다 (밀리초 반환)
     * @param seekBefore true=이전 키프레임(시작점용), false=이후 키프레임(끝점용)
     */
    private fun findNearestKeyframe(videoPath: String, timeMs: Long, seekBefore: Boolean): Long {
        val extractor = android.media.MediaExtractor()
        try {
            if (videoPath.startsWith("content://")) {
                val uri = android.net.Uri.parse(videoPath)
                contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    extractor.setDataSource(pfd.fileDescriptor)
                }
            } else {
                extractor.setDataSource(videoPath)
            }

            // 비디오 트랙 선택
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    extractor.selectTrack(i)
                    break
                }
            }

            val timeUs = timeMs * 1000L
            val seekMode = if (seekBefore)
                android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC
            else
                android.media.MediaExtractor.SEEK_TO_NEXT_SYNC

            extractor.seekTo(timeUs, seekMode)
            val keyframeUs = extractor.sampleTime
            return if (keyframeUs >= 0) keyframeUs / 1000L else timeMs
        } catch (e: Throwable) {
            android.util.Log.e("PlayerActivity", "키프레임 탐색 실패, 원본 시간 사용", e)
            return timeMs
        } finally {
            extractor.release()
        }
    }

    /**
     * MediaMuxer를 사용하여 비디오 세그먼트 추출
     */
    private fun extractVideoSegment(
        inputPath: String,
        outputPath: String,
        startUs: Long,
        endUs: Long,
        progressDialog: android.app.ProgressDialog,
        cancelled: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(false)
    ) {
        android.util.Log.d("PlayerActivity", "Extracting segment: $inputPath -> $outputPath ($startUs - $endUs)")

        val extractor = android.media.MediaExtractor()
        var muxer: android.media.MediaMuxer? = null

        try {
            // 입력 파일 설정 (URI 또는 파일 경로 지원)
            if (inputPath.startsWith("content://")) {
                val uri = android.net.Uri.parse(inputPath)
                contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    extractor.setDataSource(pfd.fileDescriptor)
                }
            } else {
                extractor.setDataSource(inputPath)
            }

            // 출력 파일 설정
            val outputFile = File(outputPath)
            outputFile.parentFile?.mkdirs()
            muxer = android.media.MediaMuxer(outputPath, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // 트랙 정보 복사
            val trackCount = extractor.trackCount
            val trackIndexMap = mutableMapOf<Int, Int>()

            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: continue

                // 비디오 또는 오디오 트랙만 처리
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    val dstIndex = muxer.addTrack(format)
                    trackIndexMap[i] = dstIndex
                    android.util.Log.d("PlayerActivity", "Track $i ($mime) -> $dstIndex")
                }
            }

            // Muxer 시작
            muxer.start()

            // 세그먼트 추출 (인터리브 방식 - 모든 트랙 동시 처리)
            val buffer = java.nio.ByteBuffer.allocateDirect(2 * 1024 * 1024) // 2MB direct buffer (JNI 복사 제거)
            val bufferInfo = android.media.MediaCodec.BufferInfo()

            val durationUs = endUs - startUs

            // 모든 트랙을 동시에 선택
            trackIndexMap.keys.forEach { extractor.selectTrack(it) }
            extractor.seekTo(startUs, android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            var firstSampleTime = -1L
            var lastProgress = -1
            while (true) {
                if (cancelled.get()) {
                    android.util.Log.d("PlayerActivity", "Extraction cancelled by user")
                    break
                }

                val sampleTime = extractor.sampleTime
                if (sampleTime < 0 || sampleTime > endUs) {
                    break
                }

                val srcTrackIndex = extractor.sampleTrackIndex
                val dstTrackIndex = trackIndexMap[srcTrackIndex]

                if (dstTrackIndex != null) {
                    if (firstSampleTime == -1L) {
                        firstSampleTime = sampleTime
                    }

                    buffer.clear()
                    bufferInfo.size = extractor.readSampleData(buffer, 0)
                    bufferInfo.presentationTimeUs = sampleTime - firstSampleTime
                    bufferInfo.flags = extractor.sampleFlags
                    bufferInfo.offset = 0

                    if (bufferInfo.size > 0) {
                        muxer.writeSampleData(dstTrackIndex, buffer, bufferInfo)

                        // 진행률 업데이트 (1% 이상 변경 시에만)
                        val progress = ((sampleTime - startUs).toFloat() / durationUs * 100).toInt().coerceIn(0, 100)
                        if (progress != lastProgress) {
                            lastProgress = progress
                            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                                progressDialog.progress = progress
                            }
                        }
                    }
                }

                if (!extractor.advance()) {
                    break
                }
            }

            trackIndexMap.keys.forEach { extractor.unselectTrack(it) }

            if (cancelled.get()) {
                android.util.Log.d("PlayerActivity", "Extraction cancelled, deleting partial file: $outputPath")
                File(outputPath).delete()
            } else {
                android.util.Log.d("PlayerActivity", "Extraction completed: $outputPath")
            }

        } catch (e: Exception) {
            android.util.Log.e("PlayerActivity", "Extraction failed", e)
            File(outputPath).delete()
            throw e
        } finally {
            extractor.release()
            muxer?.stop()
            muxer?.release()
        }
    }

    /**
     * MediaMuxer를 사용하여 비디오 세그먼트 추출 (FileDescriptor 버전)
     */
    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.O)
    private fun extractVideoSegmentWithFd(
        inputPath: String,
        outputFd: java.io.FileDescriptor,
        startUs: Long,
        endUs: Long,
        progressDialog: android.app.ProgressDialog,
        cancelled: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(false)
    ) {
        android.util.Log.d("PlayerActivity", "Extracting segment with FD: $inputPath ($startUs - $endUs)")

        val extractor = android.media.MediaExtractor()
        var muxer: android.media.MediaMuxer? = null

        try {
            // 입력 파일 설정 (URI 또는 파일 경로 지원)
            if (inputPath.startsWith("content://")) {
                val uri = android.net.Uri.parse(inputPath)
                contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    extractor.setDataSource(pfd.fileDescriptor)
                }
            } else {
                extractor.setDataSource(inputPath)
            }

            // 출력 파일 설정 (FileDescriptor 사용)
            muxer = android.media.MediaMuxer(outputFd, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // 트랙 정보 복사
            val trackCount = extractor.trackCount
            val trackIndexMap = mutableMapOf<Int, Int>()

            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: continue

                // 비디오 또는 오디오 트랙만 처리
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    val dstIndex = muxer.addTrack(format)
                    trackIndexMap[i] = dstIndex
                    android.util.Log.d("PlayerActivity", "Track $i ($mime) -> $dstIndex")
                }
            }

            // Muxer 시작
            muxer.start()

            // 세그먼트 추출 (인터리브 방식 - 모든 트랙 동시 처리)
            val buffer = java.nio.ByteBuffer.allocateDirect(2 * 1024 * 1024) // 2MB direct buffer (JNI 복사 제거)
            val bufferInfo = android.media.MediaCodec.BufferInfo()

            val durationUs = endUs - startUs

            // 모든 트랙을 동시에 선택
            trackIndexMap.keys.forEach { extractor.selectTrack(it) }
            extractor.seekTo(startUs, android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            var firstSampleTime = -1L
            var lastProgress = -1
            while (true) {
                if (cancelled.get()) {
                    android.util.Log.d("PlayerActivity", "Extraction cancelled by user (FD)")
                    break
                }

                val sampleTime = extractor.sampleTime
                if (sampleTime < 0 || sampleTime > endUs) {
                    break
                }

                val srcTrackIndex = extractor.sampleTrackIndex
                val dstTrackIndex = trackIndexMap[srcTrackIndex]

                if (dstTrackIndex != null) {
                    if (firstSampleTime == -1L) {
                        firstSampleTime = sampleTime
                    }

                    buffer.clear()
                    bufferInfo.size = extractor.readSampleData(buffer, 0)
                    bufferInfo.presentationTimeUs = sampleTime - firstSampleTime
                    bufferInfo.flags = extractor.sampleFlags
                    bufferInfo.offset = 0

                    if (bufferInfo.size > 0) {
                        muxer.writeSampleData(dstTrackIndex, buffer, bufferInfo)

                        // 진행률 업데이트 (1% 이상 변경 시에만)
                        val progress = ((sampleTime - startUs).toFloat() / durationUs * 100).toInt().coerceIn(0, 100)
                        if (progress != lastProgress) {
                            lastProgress = progress
                            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                                progressDialog.progress = progress
                            }
                        }
                    }
                }

                if (!extractor.advance()) {
                    break
                }
            }

            trackIndexMap.keys.forEach { extractor.unselectTrack(it) }

            if (cancelled.get()) {
                android.util.Log.d("PlayerActivity", "Extraction cancelled (FD)")
            } else {
                android.util.Log.d("PlayerActivity", "Extraction completed with FD")
            }

        } catch (e: Exception) {
            android.util.Log.e("PlayerActivity", "Extraction failed", e)
            throw e
        } finally {
            try {
                muxer?.stop()
                muxer?.release()
            } catch (e: Exception) {
                android.util.Log.e("PlayerActivity", "Muxer stop/release failed", e)
            }
            extractor.release()
        }
    }

    /**
     * 캐시 정보 업데이트
     */
    private fun updateCacheInfo(pathTextView: TextView, countTextView: TextView) {
        val cacheDir = File(cacheDir, "converted_subtitles")
        val cachePath = cacheDir.absolutePath
        val fileCount = cacheDir.listFiles()?.size ?: 0

        pathTextView.text = cachePath
        countTextView.text = "${fileCount}개 파일"
    }

    /**
     * 자막 캐시 삭제
     * @return 삭제된 파일 수
     */
    private fun clearSubtitleCache(): Int {
        return try {
            val cacheDir = File(cacheDir, "converted_subtitles")
            var deletedCount = 0

            cacheDir.listFiles()?.forEach { file ->
                if (file.isFile && file.extension == "srt") {
                    if (file.delete()) {
                        deletedCount++
                        Log.d("PlayerActivity", "캐시 파일 삭제: ${file.name}")
                    }
                }
            }

            Log.d("PlayerActivity", "총 ${deletedCount}개 캐시 파일 삭제 완료")
            deletedCount
        } catch (e: Exception) {
            Log.e("PlayerActivity", "캐시 삭제 오류", e)
            0
        }
    }

    /**
     * 내장 자막 선택
     */
    private fun selectEmbeddedSubtitle(trackIndex: Int) {
        selectedEmbeddedTrackIndex = trackIndex

        if (useVlcEngine) {
            // VLC: 자막 트랙 선택 (spuTrack)
            vlcMediaPlayer?.let { vlc ->
                if (trackIndex == -1) {
                    vlc.spuTrack = -1 // 자막 비활성화
                } else if (trackIndex < (vlc.spuTracksCount)) {
                    val spuTracks = vlc.spuTracks
                    if (spuTracks != null && trackIndex < spuTracks.size) {
                        vlc.spuTrack = spuTracks[trackIndex].id
                    }
                }
            }
        } else {
            player?.let { exoPlayer ->
                if (trackIndex == -1) {
                    // 자막 비활성화
                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
                } else {
                    // 선택한 자막 활성화
                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setPreferredTextLanguage(embeddedSubtitleTracks[trackIndex].language)
                        .build()
                }
            }
        }

        // 자막 정보 업데이트
        updateSubtitleAndCodecInfo()
    }

    /**
     * 자막 없이 비디오 재로드
     */
    private fun reloadVideoWithoutSubtitle() {
        if (useVlcEngine) {
            // VLC: 자막 트랙 비활성화
            vlcMediaPlayer?.spuTrack = -1
            currentSubtitleUri = null
            selectedEmbeddedTrackIndex = -1
        } else {
            player?.let { exoPlayer ->
                val currentPosition = exoPlayer.currentPosition
                val isPlaying = exoPlayer.isPlaying

                val videoUri = Uri.parse(externalVideoUri ?: return)
                val mediaItem = MediaItem.fromUri(videoUri)

                exoPlayer.setMediaItem(mediaItem)
                exoPlayer.prepare()
                exoPlayer.seekTo(currentPosition)
                exoPlayer.playWhenReady = isPlaying
            }
        }
    }

    private fun setupFullscreen() {
        // 화면 켜짐 유지
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 전체화면 및 시스템 바 숨기기
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun applySavedBrightness() {
        val layoutParams = window.attributes

        // 시작시 최대 밝기 옵션이 켜져 있으면 최대 밝기로 설정
        if (startWithMaxBrightness) {
            layoutParams.screenBrightness = 1.0f
            window.attributes = layoutParams
        } else {
            // 저장된 밝기 값을 불러와 적용 (기본값: -1.0f는 시스템 기본 밝기 사용)
            val savedBrightness = prefs.getFloat("saved_brightness", -1.0f)
            if (savedBrightness >= 0) {
                layoutParams.screenBrightness = savedBrightness
                window.attributes = layoutParams
            }
        }
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.currentVideo.collect { video ->
                    video?.let {
                        initializePlayer(it.uri.toString(), viewModel.playbackPosition.value, it.displayName)
                    }
                }
            }
        }
    }

    @UnstableApi
    private fun initializePlayer(videoUri: String, startPosition: Long, videoTitle: String = "Video") {
        currentVideoPath = videoUri

        // 이전 종료 방식에 따라 재생 시작 위치 결정
        val exitType = prefs.getString("exit_type", "tap") ?: "tap"
        val actualStartPosition = if (exitType == "swipe") {
            // 스와이프 종료였다면 저장된 위치부터 재생
            val savedPosition = prefs.getLong("last_exit_position", 0L)
            Log.d("PlayerActivity", "스와이프 종료 이력 - 저장된 위치부터 재생: ${formatTime(savedPosition)}")
            savedPosition
        } else {
            // 더블탭 종료였다면 처음부터 재생
            Log.d("PlayerActivity", "더블탭 종료 이력 - 처음부터 재생")
            startPosition
        }

        // 자막 자동 감지
        availableSubtitles.clear()
        availableSubtitles.addAll(findSubtitles(videoUri))

        // VLC 엔진 분기
        if (useVlcEngine) {
            initializeVlcPlayer(videoUri, actualStartPosition, videoTitle)
            return
        }

        // 렌더러 팩토리 생성 - 디코더 모드에 따라 설정
        val extensionMode = when (decoderMode) {
            DECODER_MODE_HW -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF      // HW만 사용
            DECODER_MODE_SW -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON       // Extension(VP9/AV1) 우선
            else -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER              // HW+ (Extension 우선, 없으면 HW)
        }

        Log.d("PlayerActivity", "========== 디코더 설정 ==========")
        Log.d("PlayerActivity", "디코더 모드: $decoderMode (0=HW, 1=HW+, 2=SW)")
        Log.d("PlayerActivity", "Extension 모드: $extensionMode (0=OFF, 1=ON, 2=PREFER)")
        Log.d("PlayerActivity", "==================================")

        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(extensionMode)
            .setEnableDecoderFallback(true)  // 디코더 실패 시 자동 전환
            .setAllowedVideoJoiningTimeMs(5000)  // 비디오 트랙 전환 허용

        // .ts 파일 확인
        val isTsFile = videoUri.endsWith(".ts", ignoreCase = true) ||
                       videoUri.contains(".ts?", ignoreCase = true) ||
                       videoUri.contains(".ts&", ignoreCase = true)

        // 성능 최적화를 위한 LoadControl 설정 (QHD/4K 고해상도 대응)
        val isFastBuffer = bufferMode == BUFFER_MODE_FAST
        val loadControl = if (isTsFile) {
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    if (isFastBuffer) 2000 else 5000,     // minBufferMs
                    30000,    // maxBufferMs - .ts 파일용 (30초)
                    if (isFastBuffer) 300 else 1000,      // bufferForPlaybackMs
                    if (isFastBuffer) 500 else 2000       // bufferForPlaybackAfterRebufferMs
                )
                .setBackBuffer(60000, true)  // 60초 백버퍼
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        } else {
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    if (isFastBuffer) 5000 else 15000,    // minBufferMs
                    if (isFastBuffer) 60000 else 120000,  // maxBufferMs
                    if (isFastBuffer) 500 else 2500,      // bufferForPlaybackMs
                    if (isFastBuffer) 1000 else 5000      // bufferForPlaybackAfterRebufferMs
                )
                .setBackBuffer(60000, true)  // 60초 백버퍼 (되감기 시 부드럽게)
                .setPrioritizeTimeOverSizeThresholds(true)  // 시간 기반 버퍼링 우선
                .build()
        }

        player = ExoPlayer.Builder(this, renderersFactory)
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(skipSeconds * 1000L)
            .setSeekForwardIncrementMs(skipSeconds * 1000L)
            .build()
            .also { exoPlayer ->
                binding.playerView.player = exoPlayer

                // 컨트롤바 가시성 리스너 설정 (자막/코덱 정보와 연동)
                binding.playerView.setControllerVisibilityListener(
                    androidx.media3.ui.PlayerView.ControllerVisibilityListener { visibility ->
                        // narrow 모드에서는 subtitleCodecInfo를 숨긴 상태 유지
                        val isNarrow = resources.configuration.screenWidthDp < 500
                        if (!isNarrow) {
                            subtitleCodecInfoTextView?.visibility = visibility
                        }
                    }
                )

                // 자막 스타일 설정 (MX Player 스타일: 흰색 텍스트 + 검은 윤곽선, 배경 없음)
                binding.playerView.subtitleView?.apply {
                    setStyle(
                        androidx.media3.ui.CaptionStyleCompat(
                            android.graphics.Color.WHITE,  // 흰색 텍스트
                            android.graphics.Color.TRANSPARENT,  // 배경 투명
                            android.graphics.Color.TRANSPARENT,  // 윈도우 배경 투명
                            androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE,  // 윤곽선
                            android.graphics.Color.BLACK,  // 검은색 윤곽선
                            null  // 기본 폰트
                        )
                    )
                }

                // 시크 파라미터 설정
                exoPlayer.setSeekParameters(
                    if (isTsFile || seekMode == SEEK_MODE_FAST) androidx.media3.exoplayer.SeekParameters.NEXT_SYNC
                    else androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC
                )

                // 비디오 제목 설정
                binding.playerView.findViewById<TextView>(R.id.videoTitle)?.text = videoTitle

                // 디코더 모드 표시
                updateDecoderIndicator()

                // 자막이 자동으로 발견되고 자막이 ON인 경우 첫 번째 자막을 적용
                val mediaItem = if (availableSubtitles.isNotEmpty() && isSubtitleEnabled) {
                    val subtitle = availableSubtitles[0]
                    currentSubtitleUri = subtitle.uri

                    Log.d("PlayerActivity", "자막 로드 중: ${subtitle.name}, MIME: ${subtitle.mimeType}, URI: ${subtitle.uri}")

                    val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(subtitle.uri)
                        .setMimeType(subtitle.mimeType)
                        .setLanguage("ko")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_AUTOSELECT)
                        .build()

                    MediaItem.Builder()
                        .setUri(videoUri)
                        .setSubtitleConfigurations(listOf(subtitleConfig))
                        .build()
                } else {
                    Log.d("PlayerActivity", "자막 없음 - availableSubtitles: ${availableSubtitles.size}, isSubtitleEnabled: $isSubtitleEnabled")
                    MediaItem.fromUri(videoUri)
                }

                exoPlayer.setMediaItem(mediaItem)
                Log.d("PlayerActivity", "MediaItem 설정 완료, SubtitleConfigurations 수: ${mediaItem.localConfiguration?.subtitleConfigurations?.size ?: 0}")
                exoPlayer.prepare()
                Log.d("PlayerActivity", "ExoPlayer prepare 완료")

                // 자막이 있으면 SubtitleView 즉시 활성화
                if (availableSubtitles.isNotEmpty() && isSubtitleEnabled) {
                    binding.playerView.subtitleView?.visibility = View.VISIBLE
                    Log.d("PlayerActivity", "SubtitleView visibility = VISIBLE 설정 (자막 파일 로드 후)")
                } else if (!isSubtitleEnabled) {
                    binding.playerView.subtitleView?.visibility = View.GONE
                    Log.d("PlayerActivity", "SubtitleView visibility = GONE 설정 (자막 비활성화)")
                }

                // 트랙 선택 파라미터 설정
                val trackSelectionParamsBuilder = exoPlayer.trackSelectionParameters
                    .buildUpon()
                    .setPreferredAudioLanguages("ko", "en", "ja") // 한국어, 영어, 일본어 오디오 우선
                    .setSelectUndeterminedTextLanguage(true)

                // 자막 트랙 자동 선택 활성화 (자막이 있는 경우)
                if (availableSubtitles.isNotEmpty() && isSubtitleEnabled) {
                    Log.d("PlayerActivity", "자막 트랙 활성화 중")
                    trackSelectionParamsBuilder
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false) // 명시적으로 자막 트랙 활성화
                        .setPreferredTextLanguages("ko", "en", "und") // 한국어, 영어, 언어 미지정 순으로 우선순위
                        .setSelectUndeterminedTextLanguage(true) // 언어가 지정되지 않은 자막도 선택
                } else if (!isSubtitleEnabled) {
                    // 자막이 꺼져있으면 트랙 비활성화
                    Log.d("PlayerActivity", "자막 트랙 비활성화")
                    trackSelectionParamsBuilder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                }

                exoPlayer.trackSelectionParameters = trackSelectionParamsBuilder.build()
                Log.d("PlayerActivity", "트랙 선택 파라미터 설정 완료")

                // 저장된 재생 위치에서 시작
                if (actualStartPosition > 0) {
                    exoPlayer.seekTo(actualStartPosition)
                }

                // 소리 설정 적용
                exoPlayer.volume = if (isSoundEnabled) 1f else 0f

                // 시작시 무음으로 재생 기능
                if (startWithMute) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                }

                exoPlayer.playWhenReady = true

                // 플레이어 리스너
                exoPlayer.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_READY -> {
                                // 저장된 위치가 영상 길이보다 크면 처음부터 재생
                                val duration = exoPlayer.duration
                                if (actualStartPosition > 0 && duration > 0 && actualStartPosition >= duration) {
                                    Log.d("PlayerActivity", "저장된 위치(${formatTime(actualStartPosition)})가 영상 길이(${formatTime(duration)})를 초과 - 처음부터 재생")
                                    exoPlayer.seekTo(0)
                                }
                                // 플레이어 준비 완료 시 타임바 업데이트 시작
                                startVirtualPositionUpdater()
                            }
                            Player.STATE_ENDED -> {
                                // 재생 완료 시 위치 삭제 (외부 URI는 ID가 없으므로 체크)
                                if (currentVideoId != -1L) {
                                    viewModel.savePlaybackPosition(
                                        currentVideoId,
                                        0L,
                                        exoPlayer.duration
                                    )
                                }
                                // 폴더 내 연속 재생이 활성화된 경우 다음 파일 재생, 아니면 종료
                                if (isContinuousPlayEnabled) {
                                    playNextVideoInFolder()
                                } else {
                                    finish()
                                }
                            }
                            else -> {}
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        viewModel.setPlayingState(isPlaying)
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        // 내장 자막 트랙 감지
                        Log.d("PlayerActivity", "========== onTracksChanged 호출 ==========")
                        Log.d("PlayerActivity", "총 트랙 그룹 수: ${tracks.groups.size}")

                        embeddedSubtitleTracks.clear()
                        var hasSubtitleTrack = false
                        var videoCodec = ""
                        var audioCodec = ""

                        for ((groupIndex, trackGroup) in tracks.groups.withIndex()) {
                            val trackTypeString = when (trackGroup.type) {
                                C.TRACK_TYPE_VIDEO -> "VIDEO"
                                C.TRACK_TYPE_AUDIO -> "AUDIO"
                                C.TRACK_TYPE_TEXT -> "TEXT (자막)"
                                else -> "UNKNOWN(${trackGroup.type})"
                            }
                            Log.d("PlayerActivity", "트랙 그룹 [$groupIndex]: 타입=$trackTypeString, 선택됨=${trackGroup.isSelected}, 트랙 수=${trackGroup.length}")

                            // 비디오 코덱 정보 추출
                            if (trackGroup.type == C.TRACK_TYPE_VIDEO && trackGroup.length > 0) {
                                val format = trackGroup.getTrackFormat(0)
                                videoCodec = when {
                                    format.sampleMimeType?.contains("avc") == true -> "H.264/AVC"
                                    format.sampleMimeType?.contains("hevc") == true -> "H.265/HEVC"
                                    format.sampleMimeType?.contains("vp9") == true -> "VP9"
                                    format.sampleMimeType?.contains("vp8") == true -> "VP8"
                                    format.sampleMimeType?.contains("av01") == true -> "AV1"
                                    else -> format.sampleMimeType ?: "Unknown"
                                }
                                currentVideoWidth = format.width
                                currentVideoHeight = format.height
                                Log.d("PlayerActivity", "비디오 코덱: $videoCodec (${format.width}x${format.height})")
                            }

                            // 오디오 코덱 정보 추출
                            if (trackGroup.type == C.TRACK_TYPE_AUDIO && trackGroup.length > 0) {
                                val format = trackGroup.getTrackFormat(0)
                                audioCodec = when {
                                    format.sampleMimeType?.contains("mp4a") == true -> "AAC"
                                    format.sampleMimeType?.contains("ac3") == true -> "AC3"
                                    format.sampleMimeType?.contains("eac3") == true -> "E-AC3"
                                    format.sampleMimeType?.contains("opus") == true -> "Opus"
                                    format.sampleMimeType?.contains("vorbis") == true -> "Vorbis"
                                    format.sampleMimeType?.contains("flac") == true -> "FLAC"
                                    else -> format.sampleMimeType ?: "Unknown"
                                }
                                Log.d("PlayerActivity", "오디오 코덱: $audioCodec")
                            }

                            // TEXT 트랙 또는 UNKNOWN 트랙 (SAMI 자막이 UNKNOWN으로 인식될 수 있음)
                            if (trackGroup.type == C.TRACK_TYPE_TEXT || trackGroup.type == -1) {
                                Log.d("PlayerActivity", ">>> 자막 또는 UNKNOWN 트랙 발견! (타입: ${trackGroup.type}) <<<")
                                for (i in 0 until trackGroup.length) {
                                    val format = trackGroup.getTrackFormat(i)
                                    val language = format.language ?: "und"
                                    val label = format.label ?: language
                                    val isSelected = trackGroup.isTrackSelected(i)
                                    val mimeType = format.sampleMimeType

                                    Log.d("PlayerActivity", "  트랙 [$i]: language=$language, label=$label, selected=$isSelected, mimeType=$mimeType")

                                    // MIME 타입이 자막 관련인지 확인
                                    val isSubtitle = mimeType?.contains("sami", ignoreCase = true) == true ||
                                                    mimeType?.contains("subrip", ignoreCase = true) == true ||
                                                    mimeType?.contains("vtt", ignoreCase = true) == true ||
                                                    mimeType?.contains("text", ignoreCase = true) == true ||
                                                    trackGroup.type == C.TRACK_TYPE_TEXT

                                    if (isSubtitle || trackGroup.type == C.TRACK_TYPE_TEXT || mimeType == null) {
                                        Log.d("PlayerActivity", "  >>> 자막으로 추가 <<<")
                                        embeddedSubtitleTracks.add(
                                            TrackInfo(
                                                trackIndex = i,
                                                language = language,
                                                label = label
                                            )
                                        )
                                        hasSubtitleTrack = true
                                    }
                                }
                            }
                        }

                        Log.d("PlayerActivity", "총 발견된 자막 트랙: ${embeddedSubtitleTracks.size}개")

                        // 자막 트랙이 있고 자막이 활성화되어 있으면 자막 뷰만 활성화
                        // SELECTION_FLAG_AUTOSELECT가 자동으로 트랙을 선택하므로 수동 선택 불필요
                        if (hasSubtitleTrack && isSubtitleEnabled) {
                            Log.d("PlayerActivity", "자막 트랙 감지됨 - SubtitleView 활성화 (AUTOSELECT 사용)")
                            // PlayerView의 자막 뷰만 활성화
                            binding.playerView.subtitleView?.visibility = View.VISIBLE
                            Log.d("PlayerActivity", "SubtitleView visibility = VISIBLE 설정 완료")
                        }

                        // 코덱 정보 변수에 저장
                        if (videoCodec.isNotEmpty() || audioCodec.isNotEmpty()) {
                            currentVideoCodec = videoCodec
                            currentAudioCodec = audioCodec
                            Log.d("PlayerActivity", "코덱 정보 저장 완료: Video=$videoCodec, Audio=$audioCodec, Resolution=${currentVideoWidth}x${currentVideoHeight}")

                            // 자막/코덱 정보 업데이트
                            updateSubtitleAndCodecInfo()
                            // 해상도 포함 디코더 표시 업데이트
                            updateDecoderIndicator()
                        }

                        Log.d("PlayerActivity", "==========================================")
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e("PlayerActivity", "플레이어 오류 발생", error)
                        error.cause?.let {
                            Log.e("PlayerActivity", "오류 원인", it)
                        }

                        // ExoPlayer 실패 시 VLC로 자동 전환 시도
                        if (!useVlcEngine && !isEngineFallback) {
                            Log.d("PlayerActivity", "ExoPlayer 재생 실패 → VLC 엔진으로 자동 전환")
                            isEngineFallback = true
                            runOnUiThread {
                                android.widget.Toast.makeText(
                                    this@PlayerActivity,
                                    "ExoPlayer 재생 실패, VLC로 재시도합니다",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                                fallbackToVlcEngine()
                            }
                            return
                        }

                        // 사용자에게 오류 메시지 표시
                        val isEmulator = android.os.Build.PRODUCT.contains("sdk") ||
                                        android.os.Build.PRODUCT.contains("emulator")
                        val isHEVC = currentVideoCodec.contains("HEVC") || currentVideoCodec.contains("H.265")

                        val errorMessage = when {
                            error.message?.contains("Decoder failed") == true && isHEVC && isEmulator ->
                                "⚠️ 에뮬레이터는 HEVC 10bit를 지원하지 않습니다.\n\n실제 Android 기기에서는 정상 작동합니다.\n\n또는 H.264로 인코딩된 영상을 사용하세요."
                            error.message?.contains("Decoder failed") == true ->
                                "이 비디오 형식은 현재 기기에서 지원되지 않습니다."
                            error.message?.contains("NO_EXCEEDS_CAPABILITIES") == true && isHEVC && isEmulator ->
                                "⚠️ 에뮬레이터는 HEVC 10bit를 지원하지 않습니다.\n\n실제 Android 기기에서는 정상 작동합니다."
                            error.message?.contains("NO_EXCEEDS_CAPABILITIES") == true ->
                                "비디오 해상도 또는 코덱이 기기 성능을 초과합니다."
                            else -> "재생 오류: ${error.message}"
                        }

                        runOnUiThread {
                            android.widget.Toast.makeText(
                                this@PlayerActivity,
                                errorMessage,
                                android.widget.Toast.LENGTH_LONG
                            ).show()

                            // 재생 실패 시 코덱 정보 화면에 표시 (계속 유지)
                            showCodecInfo()
                        }
                    }

                    override fun onPositionDiscontinuity(
                        oldPosition: Player.PositionInfo,
                        newPosition: Player.PositionInfo,
                        reason: Int
                    ) {
                        // 재생 위치가 변경될 때 T 버튼 visibility 업데이트
                        updateSegmentButtonsVisibility()
                        // SEEK에 의한 위치 변경 시 캐스팅 동기화
                        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                            syncCastSeek(newPosition.positionMs)
                        }
                    }
                })

                // 뒤로가기 버튼 설정
                binding.playerView.findViewById<ImageButton>(R.id.exo_back)?.setOnClickListener {
                    finish()
                }

                // 소리 ON/OFF 토글 버튼 설정
                binding.playerView.findViewById<ImageButton>(R.id.btnSoundToggle)?.let { btn ->
                    updateSoundToggleIcon(btn)
                    btn.setOnClickListener {
                        isSoundEnabled = !isSoundEnabled
                        prefs.edit().putBoolean("sound_enabled", isSoundEnabled).apply()
                        engineSetVolume(if (isSoundEnabled) 1f else 0f)
                        isVolumeSwipeEnabled = isSoundEnabled
                        updateSoundToggleIcon(btn)
                    }
                }

                // 자막 선택 버튼 설정 (설정 버튼이 있다면)
                binding.playerView.findViewById<ImageButton>(R.id.exo_settings)?.setOnClickListener {
                    showSubtitleDialog()
                }

                // 자막 선택 버튼 설정
                binding.playerView.findViewById<ImageButton>(R.id.btnSubtitle)?.setOnClickListener {
                    showSubtitleSelectionDialog()
                }

                // 회전 버튼 설정
                binding.playerView.findViewById<ImageButton>(R.id.btnRotate)?.setOnClickListener {
                    toggleOrientation()
                }

                // 구간 시작(F) 버튼 설정 (custom_player_control.xml - 컨트롤바와 함께 표시)
                btnSegmentStart = binding.playerView.findViewById(R.id.btnSegmentStart)
                btnSegmentStart?.setOnClickListener {
                    onSegmentStartClicked()
                }

                // 구간 종료(T) 버튼 설정
                btnSegmentEnd = binding.playerView.findViewById(R.id.btnSegmentEnd)
                btnSegmentEnd?.setOnClickListener {
                    onSegmentEndClicked()
                }

                // 항상 표시되는 T 버튼 설정 (activity_player.xml)
                btnSegmentEndAlways = findViewById(R.id.btnSegmentEndAlways)
                btnSegmentEndAlways?.setOnClickListener {
                    onSegmentEndClicked()
                }

                // 구간 재생 토글 버튼 설정
                segmentPlaybackToggleButton = binding.playerView.findViewById(R.id.btnSegmentPlaybackToggle)
                segmentPlaybackToggleButton?.setOnClickListener {
                    toggleSegmentPlayback()
                }

                // 구간 리스트 버튼 설정 (custom_player_control.xml - 컨트롤바와 함께 표시)
                btnSegmentList = binding.playerView.findViewById(R.id.btnSegmentList)
                btnSegmentList?.setOnClickListener {
                    showSegmentListDialog()
                }

                // 구간연속재생 버튼 설정 (상단바)
                btnContinuousSegment = binding.playerView.findViewById(R.id.btnContinuousSegment)
                btnContinuousSegment?.setOnClickListener {
                    showContinuousSegmentDialog()
                }

                // DLNA 캐스트 버튼
                binding.playerView.findViewById<ImageButton>(R.id.btnCast)?.setOnClickListener {
                    if (isCastingActive) showCastRemoteDialog() else showCastDevicePickerDialog()
                }

                // 상단바 적응형 오버플로 설정
                setupTopBarResponsive(binding.playerView)

                // 구간 네비게이션 초기화
                segmentNavigationLayout = findViewById(R.id.segmentNavigationLayout)
                tvSegmentSequence = findViewById(R.id.tvSegmentSequence)
                btnSegmentPrevious = findViewById(R.id.btnSegmentPrevious)
                btnSegmentNext = findViewById(R.id.btnSegmentNext)

                // 이전 구간 버튼
                btnSegmentPrevious?.setOnClickListener {
                    navigateToPreviousSegment()
                }

                // 다음 구간 버튼
                btnSegmentNext?.setOnClickListener {
                    navigateToNextSegment()
                }

                updateSegmentPlaybackToggleUI()
                updateSegmentButtonsVisibility()

                // 시크바 미리보기 리스너 설정
                binding.playerView.findViewById<androidx.media3.ui.DefaultTimeBar>(R.id.exo_progress)?.addListener(object : androidx.media3.ui.TimeBar.OnScrubListener {
                    override fun onScrubStart(timeBar: androidx.media3.ui.TimeBar, position: Long) {
                        // 스크러빙 시작 (연속 시크 시 재생 상태 유지)
                        if (!isSeeking) {
                            // 첫 번째 시크 시작 시에만 재생 상태 저장
                            wasPlayingBeforeSeek = exoPlayer.isPlaying
                            seekStartPosition = exoPlayer.currentPosition
                        }
                        isSeeking = true
                        exoPlayer.playWhenReady = false
                    }

                    override fun onScrubMove(timeBar: androidx.media3.ui.TimeBar, position: Long) {
                        // 가상 타임라인 모드: 가상 위치를 실제 위치로 변환
                        val (segmentIndex, actualPosition) = if (isSegmentPlaybackEnabled && currentSegments.isNotEmpty()) {
                            virtualToActualPosition(position)
                        } else {
                            Pair(currentSegmentIndex, position)
                        }
                        // 스크러빙 중 - 미리보기 표시
                        showSeekPreviewForControlBar(actualPosition)
                    }

                    override fun onScrubStop(timeBar: androidx.media3.ui.TimeBar, position: Long, canceled: Boolean) {
                        // 가상 타임라인 모드: 가상 위치를 실제 위치와 구간 인덱스로 변환
                        val (segmentIndex, actualPosition) = if (isSegmentPlaybackEnabled && currentSegments.isNotEmpty()) {
                            virtualToActualPosition(position)
                        } else {
                            Pair(currentSegmentIndex, position)
                        }

                        // 스크러빙 종료
                        if (!canceled) {
                            if (isSegmentPlaybackEnabled && currentSegments.isNotEmpty()) {
                                // 구간이 변경되었으면 currentSegmentIndex 업데이트
                                if (segmentIndex != currentSegmentIndex) {
                                    currentSegmentIndex = segmentIndex
                                    updateSegmentSequenceDisplay()
                                }
                            }
                            exoPlayer.seekTo(actualPosition)
                            syncCastSeek(actualPosition)
                        }

                        hideSeekPreview()

                        // 시크 후 무조건 재생
                        exoPlayer.playWhenReady = true

                        // 연속 시크를 위해 상태 리셋을 지연 (기존 Job 취소하고 새로 시작)
                        seekResetJob?.cancel()
                        seekResetJob = lifecycleScope.launch {
                            kotlinx.coroutines.delay(500) // 500ms 대기
                            isSeeking = false
                            seekStartPosition = 0L
                            wasPlayingBeforeSeek = false
                        }
                    }
                })

                // 시작 시 컨트롤러 숨기기
                binding.playerView.hideController()
            }
    }

    override fun onPause() {
        super.onPause()
        // 가상 타임라인 업데이트 중지
        positionUpdateJob?.cancel()

        // 재생 위치 저장 (외부 URI는 저장하지 않음)
        if (currentVideoId != -1L) {
            val duration = engineDuration
            if (duration > 0) {
                viewModel.savePlaybackPosition(
                    currentVideoId,
                    enginePosition,
                    duration
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // 재생 위치 저장 (외부 URI는 저장하지 않음)
        if (currentVideoId != -1L) {
            val duration = engineDuration
            if (duration > 0) {
                viewModel.savePlaybackPosition(
                    currentVideoId,
                    enginePosition,
                    duration
                )
            }
        }
    }

    override fun finish() {
        if (externalVideoUri != null) {
            finishAndRemoveTask()
            return
        }
        super.finish()
    }

    override fun onDestroy() {
        // Handler 콜백 제거 (Activity 파괴 후 실행 방지)
        vlcControllerHandler.removeCallbacksAndMessages(null)

        // Coroutine Job 취소
        seekResetJob?.cancel()
        seekHideJob?.cancel()
        brightnessHideJob?.cancel()
        volumeHideJob?.cancel()
        positionUpdateJob?.cancel()

        // Touch listener 해제
        binding.playerView.setOnTouchListener(null)
        binding.vlcTouchOverlay.setOnTouchListener(null)

        // ControllerVisibilityListener 해제
        binding.playerView.setControllerVisibilityListener(null as androidx.media3.ui.PlayerView.ControllerVisibilityListener?)

        // 캐스팅 중이면 직접 정지 (suspend/runBlocking 금지 — 메인스레드 데드락)
        if (isCastingActive) {
            try {
                if (castingMode == "chromecast") {
                    chromecastManager?.let { cm ->
                        cm.onCastEnded = null
                        cm.stopCastingSync()
                    }
                } else {
                    val controlUrl = castManager?.currentDevice?.avTransportControlUrl
                    if (controlUrl != null) {
                        Thread { try { com.splayer.video.cast.DlnaController().stopSync(controlUrl) } catch (_: Exception) {} }.start()
                    }
                }
            } catch (_: Exception) {}
            isCastingActive = false
            castingMode = null
        }
        castManager?.release()
        castManager = null
        chromecastManager?.release()
        chromecastManager = null

        // 캐시 OFF일 때 임시 자막 파일 정리
        if (!isSubtitleCacheEnabled) {
            try {
                val tempDir = File(cacheDir, "subtitle_temp")
                if (tempDir.exists()) {
                    tempDir.listFiles()?.forEach { it.delete() }
                    Log.d("PlayerActivity", "임시 자막 파일 정리 완료")
                }
            } catch (e: Exception) {
                Log.e("PlayerActivity", "임시 자막 파일 정리 오류", e)
            }
        }

        // 플레이어 릴리즈
        engineRelease()

        super.onDestroy()
    }

    /**
     * 디코더 모드 표시 업데이트
     */
    // 해상도 카테고리 판단
    private fun getResolutionCategory(width: Int, height: Int): String {
        return when {
            width >= 7680 || height >= 4320 -> "8K"
            width >= 3840 || height >= 2160 -> "4K"
            width >= 2560 || height >= 1440 -> "QHD"
            width >= 1920 || height >= 1080 -> "FHD"
            width >= 1280 || height >= 720 -> "HD"
            else -> "SD"
        }
    }

    private fun updateDecoderIndicator() {
        val decoderText = when (decoderMode) {
            DECODER_MODE_HW -> "HW"
            DECODER_MODE_SW -> "SW"
            else -> "HW+"
        }

        // 해상도 정보 추가 (카테고리 + 픽셀)
        val resolutionText = if (currentVideoWidth > 0 && currentVideoHeight > 0) {
            val category = getResolutionCategory(currentVideoWidth, currentVideoHeight)
            "$category ${currentVideoWidth}x${currentVideoHeight} "
        } else {
            ""
        }

        val decoderIndicator = controllerRoot?.findViewById<TextView>(R.id.decoderIndicator)
        decoderIndicator?.text = "$resolutionText$decoderText"

        // 클릭하면 디코더 선택 다이얼로그 표시
        decoderIndicator?.setOnClickListener {
            showDecoderSelectionDialog()
        }

        // 엔진 토글 버튼 업데이트
        updateEngineToggle(controllerRoot)
    }

    @UnstableApi
    private fun updateEngineToggle(root: View?) {
        val toggle = root?.findViewById<TextView>(R.id.engineToggle) ?: return
        toggle.text = if (useVlcEngine) "VLC" else "EXO"
        toggle.setTextColor(
            if (useVlcEngine) android.graphics.Color.parseColor("#FFFFAB40")  // 앰버
            else android.graphics.Color.parseColor("#FF64B5F6")               // 블루
        )
        toggle.setOnClickListener { switchEngine() }
    }

    @UnstableApi
    private fun setupTopBarResponsive(root: View) {
        val screenWidthDp = resources.configuration.screenWidthDp
        val isNarrow = screenWidthDp < 500

        val subtitleCodecInfo = root.findViewById<TextView>(R.id.subtitleCodecInfo)
        val engineToggle = root.findViewById<TextView>(R.id.engineToggle)
        val decoderIndicator = root.findViewById<TextView>(R.id.decoderIndicator)
        val btnRotate = root.findViewById<ImageButton>(R.id.btnRotate)
        val btnSettings = root.findViewById<ImageButton>(R.id.exo_settings)
        val btnCast = root.findViewById<ImageButton>(R.id.btnCast)
        val btnOverflow = root.findViewById<ImageButton>(R.id.btnOverflow) ?: return

        if (isNarrow) {
            subtitleCodecInfo?.visibility = View.GONE
            engineToggle?.visibility = View.GONE
            decoderIndicator?.visibility = View.GONE
            btnRotate?.visibility = View.GONE
            btnSettings?.visibility = View.GONE
            btnCast?.visibility = View.GONE
            btnOverflow.visibility = View.VISIBLE

            btnOverflow.setOnClickListener { anchor ->
                val popup = PopupMenu(this, anchor)
                val codecText = subtitleCodecInfo?.text?.toString()?.takeIf { it.isNotBlank() }
                val engineLabel = if (useVlcEngine) "EXO로 전환" else "VLC로 전환"
                val decoderText = decoderIndicator?.text ?: "디코더"
                var order = 0
                if (codecText != null) {
                    popup.menu.add(0, 5, order++, codecText)
                }
                popup.menu.add(0, 1, order++, engineLabel)
                popup.menu.add(0, 2, order++, "디코더: $decoderText")
                popup.menu.add(0, 3, order++, "회전")
                popup.menu.add(0, 4, order++, "설정")
                popup.menu.add(0, 6, order++, if (isCastingActive) "캐스트 리모컨" else "캐스트")
                popup.menu.add(0, 7, order++, "비디오 합치기")
                popup.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        1 -> { switchEngine(); true }
                        2 -> { showDecoderSelectionDialog(); true }
                        3 -> { toggleOrientation(); true }
                        4 -> { showSubtitleDialog(); true }
                        5 -> true // 코덱 정보는 표시만
                        6 -> { if (isCastingActive) showCastRemoteDialog() else showCastDevicePickerDialog(); true }
                        7 -> { launchVideoPicker(); true }
                        else -> false
                    }
                }
                popup.show()
            }
        } else {
            // subtitleCodecInfo는 별도 로직에서 관리하므로 여기서 건드리지 않음
            engineToggle?.visibility = View.VISIBLE
            decoderIndicator?.visibility = View.VISIBLE
            btnRotate?.visibility = View.VISIBLE
            btnSettings?.visibility = View.VISIBLE
            btnCast?.visibility = View.VISIBLE
            btnOverflow.visibility = View.GONE
        }
    }

    @UnstableApi
    private fun switchEngine() {
        val videoUri = currentVideoPath ?: return
        val position = enginePosition
        val title = controllerRoot?.findViewById<TextView>(R.id.videoTitle)?.text?.toString() ?: "Video"

        // 현재 엔진 릴리즈
        engineRelease()
        subtitleCodecInfoTextView = null

        // 뷰 전환
        if (useVlcEngine) {
            // VLC → ExoPlayer: VLC 뷰 숨기고 ExoPlayer 뷰 표시
            binding.vlcVideoLayout.visibility = View.GONE
            binding.vlcTouchOverlay.visibility = View.GONE
            binding.vlcControllerContainer.visibility = View.GONE
            binding.playerView.visibility = View.VISIBLE
        } else {
            // ExoPlayer → VLC: initializeVlcPlayer()에서 처리됨
        }

        // 엔진 전환
        useVlcEngine = !useVlcEngine
        prefs.edit().putBoolean("use_vlc_engine", useVlcEngine).apply()
        isEngineFallback = false

        Log.d("PlayerActivity", "엔진 전환: ${if (useVlcEngine) "VLC" else "ExoPlayer"}, position=$position")
        android.widget.Toast.makeText(
            this,
            "엔진 전환: ${if (useVlcEngine) "VLC" else "ExoPlayer"}",
            android.widget.Toast.LENGTH_SHORT
        ).show()

        // 새 엔진으로 재시작
        if (useVlcEngine) {
            initializeVlcPlayer(videoUri, position, title)
        } else {
            initializePlayer(videoUri, position, title)
        }
    }

    /**
     * 디코더 선택 다이얼로그 표시
     */
    private fun showDecoderSelectionDialog() {
        val options = arrayOf("HW decoder", "HW+ decoder", "SW decoder")
        val currentSelection = when (decoderMode) {
            DECODER_MODE_HW -> 0
            DECODER_MODE_HW_PLUS -> 1
            DECODER_MODE_SW -> 2
            else -> 1
        }

        AlertDialog.Builder(this)
            .setTitle("디코더 선택")
            .setSingleChoiceItems(options, currentSelection) { dialog, which ->
                val newMode = when (which) {
                    0 -> DECODER_MODE_HW
                    2 -> DECODER_MODE_SW
                    else -> DECODER_MODE_HW_PLUS
                }

                if (newMode != decoderMode) {
                    decoderMode = newMode
                    prefs.edit().putInt("decoder_mode", decoderMode).apply()
                    Log.d("PlayerActivity", "디코더 모드 변경: $decoderMode")

                    // 비디오 URI 확인
                    val videoUri = currentVideoPath ?: externalVideoUri
                    if (videoUri.isNullOrEmpty()) {
                        Log.e("PlayerActivity", "비디오 URI가 없어서 디코더를 변경할 수 없습니다")
                        android.widget.Toast.makeText(
                            this@PlayerActivity,
                            "비디오 정보를 찾을 수 없습니다",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                        dialog.dismiss()
                        return@setSingleChoiceItems
                    }

                    // 비디오를 다시 로드하여 새 디코더 적용
                    try {
                        val currentPosition = enginePosition
                        val videoTitle = controllerRoot?.findViewById<TextView>(R.id.videoTitle)?.text?.toString() ?: "Video"

                        Log.d("PlayerActivity", "플레이어 재시작: URI=$videoUri, Position=$currentPosition")

                        engineRelease()

                        // 약간의 지연 후 재초기화 (안정성)
                        binding.root.postDelayed({
                            initializePlayer(videoUri, currentPosition, videoTitle)
                        }, 100)

                    } catch (e: Exception) {
                        Log.e("PlayerActivity", "디코더 변경 중 오류 발생", e)
                        android.widget.Toast.makeText(
                            this@PlayerActivity,
                            "디코더 변경 실패: ${e.message}",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /**
     * 화면 회전 토글 (세로 <-> 가로)
     */
    private fun toggleOrientation() {
        val currentOrientation = resources.configuration.orientation
        requestedOrientation = if (currentOrientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) {
            // 세로 -> 가로
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            // 가로 -> 세로
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    private var lastSegmentStartTime = 0L
    private var lastSegmentStartClickedPosition = 0L

    // 구간 시작(F) 버튼 클릭
    private fun onSegmentStartClicked() {
        val currentTime = enginePosition
        val fileName = segmentManager.extractFileName(currentVideoPath ?: externalVideoUri ?: "")

        if (fileName.isNotEmpty()) {
            // 이중 클릭 방지: 500ms 이내의 동일한 위치 클릭 무시
            val now = System.currentTimeMillis()
            if (now - lastSegmentStartTime < 500 && kotlin.math.abs(currentTime - lastSegmentStartClickedPosition) < 100) {
                android.util.Log.d("PlayerActivity", "이중 클릭 감지, 무시함")
                return
            }
            lastSegmentStartTime = now
            lastSegmentStartClickedPosition = currentTime

            val sequence = segmentManager.saveSegmentStart(fileName, currentTime)
            updateSegmentButtonsVisibility()
        }
    }

    private var lastSegmentEndTime = 0L
    private var lastSegmentEndClickedPosition = 0L

    // 구간 종료(T) 버튼 클릭
    private fun onSegmentEndClicked() {
        val currentTime = enginePosition
        val fileName = segmentManager.extractFileName(currentVideoPath ?: externalVideoUri ?: "")

        if (fileName.isNotEmpty()) {
            // 이중 클릭 방지: 500ms 이내의 동일한 위치 클릭 무시
            val now = System.currentTimeMillis()
            if (now - lastSegmentEndTime < 500 && kotlin.math.abs(currentTime - lastSegmentEndClickedPosition) < 100) {
                android.util.Log.d("PlayerActivity", "이중 클릭 감지, 무시함")
                return
            }
            lastSegmentEndTime = now
            lastSegmentEndClickedPosition = currentTime

            val success = segmentManager.saveSegmentEnd(fileName, currentTime)
            if (success) {
                // 구간 재생이 활성화되어 있으면 구간 목록 업데이트
                if (isSegmentPlaybackEnabled) {
                    loadSegmentsForCurrentFile()
                }
                updateSegmentButtonsVisibility()
            }
        }
    }

    // F/T 버튼 및 구간 관련 버튼 가시성 업데이트
    private fun updateSegmentButtonsVisibility() {
        val fileName = segmentManager.extractFileName(currentVideoPath ?: externalVideoUri ?: "")
        if (fileName.isEmpty()) return

        val hasIncomplete = segmentManager.hasIncompleteSegment(fileName)

        // 완료된 구간이 있는지 확인 (구간 토글/리스트 버튼 표시 여부 결정)
        val completedSegments = segmentManager.getSegmentsForFile(fileName)
        val hasCompletedSegments = completedSegments.isNotEmpty()

        // 구간 토글 버튼과 리스트 버튼: 완료된 구간이 있을 때만 표시
        segmentPlaybackToggleButton?.visibility = if (hasCompletedSegments) View.VISIBLE else View.GONE
        btnSegmentList?.visibility = if (hasCompletedSegments) View.VISIBLE else View.GONE

        // 구간연속재생 버튼: 현재 폴더에 구간이 있는 파일이 있을 때만 표시
        val filesInCurrentFolder = getSegmentFilesInCurrentFolder()
        btnContinuousSegment?.visibility = if (filesInCurrentFolder.isNotEmpty()) View.VISIBLE else View.GONE

        // F를 누른 상태(미완료 구간 있음)
        if (hasIncomplete) {
            btnSegmentStart?.visibility = View.GONE

            // 현재 재생 위치가 F 시간 이전이면 T 버튼 숨기기
            val currentPosition = enginePosition
            val incompleteSegments = segmentManager.getAllSegments()
                .filter { it.fileName == fileName && it.endTime == -1L }

            val shouldShowT = incompleteSegments.any { currentPosition >= it.startTime }

            // 컨트롤바 안의 T 버튼은 숨기고, 항상 표시되는 T 버튼을 표시
            btnSegmentEnd?.visibility = View.GONE
            btnSegmentEndAlways?.visibility = if (shouldShowT) View.VISIBLE else View.GONE
        } else {
            // T까지 누른 상태(미완료 구간 없음) -> F만 보이게
            btnSegmentStart?.visibility = View.VISIBLE
            btnSegmentEnd?.visibility = View.GONE
            btnSegmentEndAlways?.visibility = View.GONE
        }
    }

    // 현재 폴더에서 구간이 설정된 파일 목록 가져오기
    private fun getSegmentFilesInCurrentFolder(): List<String> {
        val currentPath = currentVideoPath ?: externalVideoUri ?: return emptyList()

        // 현재 비디오의 폴더 경로 추출
        val currentFolder = java.io.File(currentPath).parentFile ?: return emptyList()

        // splayer.per에 있는 모든 파일명 (완료된 구간만)
        val allSegments = segmentManager.getAllSegments()
        val filesWithSegments = allSegments
            .filter { it.endTime != -1L }  // 완료된 구간만
            .map { it.fileName }
            .distinct()

        // 현재 폴더에 실제로 존재하는 파일만 필터링
        return filesWithSegments.filter { fileName ->
            val file = java.io.File(currentFolder, fileName)
            file.exists()
        }
    }

    // 구간연속재생 다이얼로그 표시
    private fun showContinuousSegmentDialog() {
        val filesInCurrentFolder = getSegmentFilesInCurrentFolder()

        if (filesInCurrentFolder.isEmpty()) {
            Toast.makeText(this, "현재 폴더에 구간이 설정된 파일이 없습니다", Toast.LENGTH_SHORT).show()
            return
        }

        // 파일명을 정렬하여 표시
        val sortedFiles = filesInCurrentFolder.sorted()

        AlertDialog.Builder(this)
            .setTitle("구간연속재생 (${sortedFiles.size}개 파일)")
            .setItems(sortedFiles.toTypedArray()) { _, which ->
                val selectedFileName = sortedFiles[which]
                playFileWithSegments(selectedFileName)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // 선택한 파일의 구간 재생 시작
    private fun playFileWithSegments(fileName: String) {
        val currentPath = currentVideoPath ?: externalVideoUri ?: return
        val currentFolder = java.io.File(currentPath).parentFile ?: return
        val targetFile = java.io.File(currentFolder, fileName)

        if (!targetFile.exists()) {
            Toast.makeText(this, "파일을 찾을 수 없습니다: $fileName", Toast.LENGTH_SHORT).show()
            return
        }

        // 해당 파일의 구간 로드
        val segments = segmentManager.getSegmentsForFile(fileName)
        if (segments.isEmpty()) {
            Toast.makeText(this, "구간 정보가 없습니다", Toast.LENGTH_SHORT).show()
            return
        }

        // 새 파일 재생
        val uri = Uri.fromFile(targetFile)
        currentVideoPath = targetFile.absolutePath

        // 구간 재생 모드 활성화
        currentSegments = segments
        currentSegmentIndex = 0
        isSegmentPlaybackEnabled = true

        // 파일 재생 시작 (첫 번째 구간 시작 위치에서)
        initializePlayer(uri.toString(), segments[0].startTime, fileName)

        updateSegmentPlaybackToggleUI()
        updateSegmentButtonsVisibility()

        Toast.makeText(this, "구간연속재생: $fileName (${segments.size}개 구간)", Toast.LENGTH_SHORT).show()
    }

    // 구간 재생 토글
    private fun toggleSegmentPlayback() {
        isSegmentPlaybackEnabled = !isSegmentPlaybackEnabled

        if (isSegmentPlaybackEnabled) {
            loadSegmentsForCurrentFile()
        } else {
            currentSegments = emptyList()
            currentSegmentIndex = 0
        }

        updateSegmentPlaybackToggleUI()
        startVirtualPositionUpdater() // 가상 타임라인 업데이트 시작/중지
    }

    // 구간 재생 토글 UI 업데이트
    private fun updateSegmentPlaybackToggleUI() {
        segmentPlaybackToggleButton?.let { button ->
            if (isSegmentPlaybackEnabled) {
                button.text = "구간 ON"
                button.setTextColor(android.graphics.Color.parseColor("#FF4081"))
                // 순번 표시 보이기
                updateSegmentSequenceDisplay()
            } else {
                button.text = "구간"
                button.setTextColor(android.graphics.Color.WHITE)
                // 네비게이션 전체 숨기기 (이전/다음 아이콘 포함)
                segmentNavigationLayout?.visibility = View.GONE
            }
        }
    }

    // 구간 순번 표시 업데이트
    private fun updateSegmentSequenceDisplay() {
        if (isSegmentPlaybackEnabled && currentSegments.isNotEmpty() && currentSegmentIndex < currentSegments.size) {
            val currentNum = currentSegmentIndex + 1
            val totalNum = currentSegments.size
            tvSegmentSequence?.text = "$currentNum/$totalNum"
            tvSegmentSequence?.visibility = View.VISIBLE
            segmentNavigationLayout?.visibility = View.VISIBLE

            // 구간이 하나만 있을 때는 이전/다음 버튼 숨김
            if (totalNum == 1) {
                btnSegmentPrevious?.visibility = View.GONE
                btnSegmentNext?.visibility = View.GONE
            } else {
                btnSegmentPrevious?.visibility = View.VISIBLE
                btnSegmentNext?.visibility = View.VISIBLE
            }
        } else {
            segmentNavigationLayout?.visibility = View.GONE
        }
    }

    // 이전 구간으로 이동 (순환)
    private fun navigateToPreviousSegment() {
        if (isSegmentPlaybackEnabled && currentSegments.isNotEmpty()) {
            currentSegmentIndex = if (currentSegmentIndex > 0) {
                currentSegmentIndex - 1
            } else {
                currentSegments.size - 1 // 첫 구간에서 이전 → 마지막 구간으로
            }
            val segment = currentSegments[currentSegmentIndex]
            engineSeekTo(segment.startTime)
            updateSegmentSequenceDisplay()
        }
    }

    // 다음 구간으로 이동 (순환)
    private fun navigateToNextSegment() {
        if (isSegmentPlaybackEnabled && currentSegments.isNotEmpty()) {
            currentSegmentIndex = if (currentSegmentIndex < currentSegments.size - 1) {
                currentSegmentIndex + 1
            } else {
                0 // 마지막 구간에서 다음 → 첫 구간으로
            }
            val segment = currentSegments[currentSegmentIndex]
            engineSeekTo(segment.startTime)
            updateSegmentSequenceDisplay()
        }
    }

    // 가상 타임라인의 총 길이 계산 (모든 구간 길이의 합)
    private fun getVirtualDuration(): Long {
        if (!isSegmentPlaybackEnabled || currentSegments.isEmpty()) {
            return engineDuration
        }
        return currentSegments.sumOf { it.endTime - it.startTime }
    }

    // 실제 비디오 위치를 가상 타임라인 위치로 변환 (currentSegmentIndex 독립적)
    private fun actualToVirtualPosition(actualPosition: Long): Long {
        if (!isSegmentPlaybackEnabled || currentSegments.isEmpty()) {
            return actualPosition
        }

        var virtualPosition = 0L

        // 실제 위치가 속한 구간을 찾아서 계산 (currentSegmentIndex에 의존하지 않음)
        for (segment in currentSegments) {
            if (actualPosition >= segment.startTime && actualPosition <= segment.endTime) {
                // 현재 구간 내부 - 이전 구간들의 길이 + 현재 구간 내 위치
                virtualPosition += (actualPosition - segment.startTime)
                return virtualPosition
            } else if (actualPosition < segment.startTime) {
                // 현재 위치가 이 구간보다 앞 - 이전까지의 가상 위치 반환
                return virtualPosition
            }
            // 이 구간을 지나갔으므로 구간 길이를 더함
            virtualPosition += segment.endTime - segment.startTime
        }

        // 모든 구간을 지나갔으면 총 길이 반환
        return virtualPosition
    }

    // 가상 타임라인 위치를 실제 비디오 위치로 변환
    private fun virtualToActualPosition(virtualPosition: Long): Pair<Int, Long> {
        if (!isSegmentPlaybackEnabled || currentSegments.isEmpty()) {
            return Pair(0, virtualPosition)
        }

        var remainingVirtual = virtualPosition
        for (i in currentSegments.indices) {
            val segment = currentSegments[i]
            val segmentDuration = segment.endTime - segment.startTime

            if (remainingVirtual <= segmentDuration) {
                // 이 구간 내에 위치
                val actualPosition = segment.startTime + remainingVirtual
                return Pair(i, actualPosition)
            }

            remainingVirtual -= segmentDuration
        }

        // 범위를 벗어난 경우 마지막 구간의 끝
        val lastSegment = currentSegments.last()
        return Pair(currentSegments.size - 1, lastSegment.endTime)
    }

    // 가상 타임라인 위치 업데이트 시작
    private fun startVirtualPositionUpdater() {
        positionUpdateJob?.cancel()
        lastVirtualPosition = -1L
        lastVirtualDuration = -1L

        if (isSegmentPlaybackEnabled && currentSegments.isNotEmpty()) {
            val virtualDuration = getVirtualDuration()

            positionUpdateJob = lifecycleScope.launch(Dispatchers.Main) {
                while (isActive) {
                    if (!isSeeking) {  // 시크 중이 아닐 때만 업데이트
                        val actualPosition = enginePosition
                        val virtualPosition = actualToVirtualPosition(actualPosition)

                        val root = controllerRoot ?: continue
                        root.post {
                            val timeBar = root.findViewById<androidx.media3.ui.DefaultTimeBar>(R.id.exo_progress)
                            val positionView = root.findViewById<TextView>(R.id.exo_position)
                            val durationView = root.findViewById<TextView>(R.id.exo_duration)

                            // 가상 타임라인 값으로 덮어쓰기
                            timeBar?.setDuration(virtualDuration)
                            timeBar?.setPosition(virtualPosition)
                            positionView?.text = formatTime(virtualPosition)
                            durationView?.text = formatTime(virtualDuration)
                        }

                        lastVirtualPosition = virtualPosition
                    }

                    delay(50) // 50ms마다 업데이트 (매우 부드럽게)
                }
            }
        } else {
            // 일반 모드: 타임바 위치를 수동으로 업데이트
            positionUpdateJob = lifecycleScope.launch(Dispatchers.Main) {
                while (isActive) {
                    if (!isSeeking) {
                        val currentPosition = enginePosition
                        val duration = engineDuration
                        val bufferedPosition = if (useVlcEngine) currentPosition else player?.bufferedPosition ?: 0L

                        val root = controllerRoot
                        val timeBar = root?.findViewById<androidx.media3.ui.DefaultTimeBar>(R.id.exo_progress)
                        timeBar?.setDuration(duration)
                        timeBar?.setPosition(currentPosition)
                        timeBar?.setBufferedPosition(bufferedPosition)

                        if (useVlcEngine) {
                            val positionView = root?.findViewById<TextView>(R.id.exo_position)
                            val durationView = root?.findViewById<TextView>(R.id.exo_duration)
                            positionView?.text = formatTime(currentPosition)
                            durationView?.text = formatTime(duration)
                        }
                    }
                    delay(50) // 50ms마다 업데이트 (부드럽게)
                }
            }
        }
    }

    // 현재 파일의 구간 목록 로드
    private fun loadSegmentsForCurrentFile() {
        val fileName = segmentManager.extractFileName(currentVideoPath ?: externalVideoUri ?: "")
        if (fileName.isNotEmpty()) {
            currentSegments = segmentManager.getSegmentsForFile(fileName)
            currentSegmentIndex = 0

            if (currentSegments.isEmpty()) {
                android.widget.Toast.makeText(
                    this,
                    "이 파일에 대한 구간이 없습니다",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                isSegmentPlaybackEnabled = false
                updateSegmentPlaybackToggleUI()
            } else {
                android.widget.Toast.makeText(
                    this,
                    "구간 재생 활성화 (${currentSegments.size}개 구간)",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                // 첫 번째 구간으로 이동
                startSegmentPlayback()
            }
        }
    }

    // 구간 재생 시작
    private fun startSegmentPlayback() {
        if (currentSegments.isEmpty()) return

        val segment = currentSegments[currentSegmentIndex]
        engineSeekTo(segment.startTime)
        engineSetPlayWhenReady(true)

        // 순번 표시 업데이트
        updateSegmentSequenceDisplay()

        // 구간 재생 모니터링
        monitorSegmentPlayback()
    }

    // 구간 재생 모니터링
    private fun monitorSegmentPlayback() {
        if (!isSegmentPlaybackEnabled || currentSegments.isEmpty()) return

        lifecycleScope.launch {
            while (isSegmentPlaybackEnabled && (player != null || vlcMediaPlayer != null)) {
                delay(100)

                val currentPosition = enginePosition

                if (currentSegmentIndex < currentSegments.size) {
                    val segment = currentSegments[currentSegmentIndex]

                    // 현재 구간 종료 시간에 도달했는지 확인
                    if (currentPosition >= segment.endTime) {
                        // 다음 구간으로 이동
                        currentSegmentIndex++

                        if (currentSegmentIndex < currentSegments.size) {
                            // 다음 구간 재생
                            val nextSegment = currentSegments[currentSegmentIndex]
                            engineSeekTo(nextSegment.startTime)
                            updateSegmentSequenceDisplay()
                        } else {
                            // 모든 구간 재생 완료 - 처음부터 다시
                            currentSegmentIndex = 0
                            val firstSegment = currentSegments[0]
                            engineSeekTo(firstSegment.startTime)
                            updateSegmentSequenceDisplay()
                        }
                    }
                }
            }
        }
    }

    // 같은 폴더 내 비디오 파일 목록 가져오기
    private fun getVideoFilesInSameFolder(currentPath: String): List<File> {
        val videoExtensions = listOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "3gp", "ts", "m2ts")
        val currentFile = File(currentPath)
        val parentFolder = currentFile.parentFile ?: return emptyList()

        return parentFolder.listFiles()?.filter { file ->
            file.isFile && videoExtensions.any { ext ->
                file.name.endsWith(".$ext", ignoreCase = true)
            }
        }?.sortedBy { it.name.lowercase() } ?: emptyList()
    }

    // 다음 비디오 파일 재생
    private fun playNextVideoInFolder() {
        val currentPath = currentVideoPath
        Log.d("PlayerActivity", "연속 재생: currentVideoPath = $currentPath")

        if (currentPath == null) {
            Log.d("PlayerActivity", "연속 재생: currentVideoPath가 null")
            finish()
            return
        }

        // content:// URI인 경우 실제 파일 경로 추출 시도
        val actualPath = if (currentPath.startsWith("content://")) {
            val path = getVideoPath(currentPath)
            Log.d("PlayerActivity", "연속 재생: content URI에서 추출한 경로 = $path")
            if (path == null) {
                finish()
                return
            }
            path
        } else {
            currentPath
        }

        Log.d("PlayerActivity", "연속 재생: actualPath = $actualPath")

        val videoFiles = getVideoFilesInSameFolder(actualPath)
        Log.d("PlayerActivity", "연속 재생: 폴더 내 비디오 파일 수 = ${videoFiles.size}")

        if (videoFiles.isEmpty()) {
            Log.d("PlayerActivity", "연속 재생: 폴더에 비디오 파일 없음")
            finish()
            return
        }

        val currentFile = File(actualPath)
        val currentIndex = videoFiles.indexOfFirst { it.absolutePath == currentFile.absolutePath }
        Log.d("PlayerActivity", "연속 재생: 현재 파일 인덱스 = $currentIndex, 총 파일 수 = ${videoFiles.size}")

        if (currentIndex == -1 || currentIndex >= videoFiles.size - 1) {
            // 마지막 파일이거나 찾을 수 없음
            Log.d("PlayerActivity", "연속 재생: 마지막 파일이거나 현재 파일을 찾을 수 없음")
            finish()
            return
        }

        val nextFile = videoFiles[currentIndex + 1]
        Log.d("PlayerActivity", "연속 재생: 다음 파일 재생 - ${nextFile.name}")

        // 현재 플레이어 정리
        engineRelease()

        // 다음 파일 재생
        val nextUri = Uri.fromFile(nextFile).toString()
        initializePlayer(nextUri, 0L, nextFile.nameWithoutExtension)
    }

    // ==================== 엔진 추상화 헬퍼 ====================

    /** 현재 재생 위치 (ms) */
    private val enginePosition: Long
        get() = if (useVlcEngine) vlcMediaPlayer?.time ?: 0L else player?.currentPosition ?: 0L

    /** 전체 재생 시간 (ms) */
    private val engineDuration: Long
        get() = if (useVlcEngine) vlcMediaPlayer?.length ?: 0L else player?.duration ?: 0L

    /** 재생 중 여부 */
    private val engineIsPlaying: Boolean
        get() = if (useVlcEngine) vlcMediaPlayer?.isPlaying ?: false else player?.isPlaying ?: false

    /** 재생 속도 */
    private val enginePlaybackSpeed: Float
        get() = if (useVlcEngine) vlcMediaPlayer?.rate ?: 1.0f else player?.playbackParameters?.speed ?: 1.0f

    /** 위치 이동 */
    private fun engineSeekTo(positionMs: Long) {
        if (useVlcEngine) {
            vlcMediaPlayer?.time = positionMs
            // VLC: engineSeekTo에서 캐스트 동기화 (ExoPlayer는 onPositionDiscontinuity에서 처리)
            syncCastSeek(positionMs)
        } else {
            player?.seekTo(positionMs)
            // ExoPlayer: onPositionDiscontinuity에서 캐스트 동기화
        }
    }

    /** 캐스팅 시크 동기화 */
    private fun syncCastSeek(positionMs: Long) {
        if (!isCastingActive) return
        if (castingMode == "chromecast") {
            lifecycleScope.launch { chromecastManager?.seekTo(positionMs) }
        } else if (castingMode == "dlna") {
            lifecycleScope.launch {
                val controlUrl = castManager?.currentDevice?.avTransportControlUrl ?: return@launch
                withContext(Dispatchers.IO) { com.splayer.video.cast.DlnaController().seek(controlUrl, positionMs) }
            }
        }
    }

    /** 배속 설정 (VLC는 최대 4x) */
    private fun engineSetSpeed(speed: Float) {
        if (useVlcEngine) {
            vlcMediaPlayer?.rate = speed.coerceAtMost(4.0f)
        } else {
            player?.setPlaybackSpeed(speed)
        }
    }

    /** 재생/일시정지 */
    private fun engineSetPlayWhenReady(value: Boolean) {
        if (useVlcEngine) {
            if (value) vlcMediaPlayer?.play() else vlcMediaPlayer?.pause()
        } else {
            player?.playWhenReady = value
        }
        // 캐스팅 중이면 TV에도 재생/일시정지 동기화
        if (isCastingActive && castingMode == "chromecast") {
            lifecycleScope.launch { if (value) chromecastManager?.resume() else chromecastManager?.pause() }
        } else if (isCastingActive && castingMode == "dlna") {
            lifecycleScope.launch {
                if (value) castManager?.resume() else castManager?.pause()
            }
        }
    }

    /** 볼륨 설정 (0.0~1.0) */
    private fun engineSetVolume(volume: Float) {
        if (useVlcEngine) {
            // VLC: AudioManager로 시스템 레벨 뮤트/언뮤트
            if (volume <= 0f) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            } else {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            }
        } else {
            player?.volume = volume
        }
    }

    // ==================== 캐스팅 (DLNA + Chromecast) ====================

    private fun showCastDevicePickerDialog() {
        val castMode = prefs.getString("cast_mode", "chromecast") ?: "chromecast"
        if (castMode == "dlna") {
            showDlnaDevicePickerDialog()
        } else {
            showChromecastPicker()
        }
    }

    private fun showChromecastPicker() {
        try {
            val castCtx = com.google.android.gms.cast.framework.CastContext.getSharedInstance(this)

            // 기존 세션 정리 (재연결 안정성)
            if (castCtx.sessionManager.currentCastSession != null) {
                castCtx.sessionManager.endCurrentSession(true)
            }

            val appId = com.google.android.gms.cast.CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
            val selector = androidx.mediarouter.media.MediaRouteSelector.Builder()
                .addControlCategory(com.google.android.gms.cast.CastMediaControlIntent.categoryForCast(appId))
                .build()

            // 세션 정리 후 약간 대기한 뒤 다이얼로그 표시
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val dialog = androidx.mediarouter.app.MediaRouteChooserDialog(this)
                dialog.routeSelector = selector
                dialog.setOnDismissListener {
                    // 연결 대기: 최대 5초간 500ms 간격으로 폴링
                    var retryCount = 0
                    val handler = android.os.Handler(android.os.Looper.getMainLooper())
                    val checker = object : Runnable {
                        override fun run() {
                            if (chromecastManager?.isConnected() == true) {
                                startChromecastCasting()
                            } else if (retryCount < 10) {
                                retryCount++
                                handler.postDelayed(this, 500)
                            }
                        }
                    }
                    handler.postDelayed(checker, 500)
                }
                dialog.show()
            }, 500)
        } catch (e: Exception) {
            Log.e("PlayerActivity", "Chromecast 초기화 실패", e)
            Toast.makeText(this, "Chromecast를 사용할 수 없습니다 (Play Services 필요)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startChromecastCasting() {
        val videoPath = currentVideoPath ?: externalVideoUri ?: return
        val title = controllerRoot?.findViewById<TextView>(R.id.videoTitle)?.text?.toString() ?: "Video"
        val currentPos = enginePosition
        val mimeType = castManager?.let { getMimeTypeForCast(videoPath) } ?: "video/mp4"

        // 현재 활성 자막을 VTT로 변환
        val subtitleVtt = getCurrentSubtitleAsVtt()

        if (useVlcEngine) vlcMediaPlayer?.pause() else player?.playWhenReady = false

        lifecycleScope.launch {
            val deviceName = chromecastManager?.getDeviceName() ?: "Chromecast"
            Toast.makeText(this@PlayerActivity, "${deviceName}에 연결 중...", Toast.LENGTH_SHORT).show()

            chromecastManager?.onCastEnded = {
                isCastingActive = false
                castingMode = null
                updateCastButtonIcon()
            }

            val success = chromecastManager?.startCasting(videoPath, title, mimeType, currentPos, subtitleVtt) ?: false
            if (success) {
                isCastingActive = true
                castingMode = "chromecast"
                updateCastButtonIcon()
                Toast.makeText(this@PlayerActivity, "${deviceName}에서 재생 중", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@PlayerActivity, "캐스팅 실패", Toast.LENGTH_SHORT).show()
                if (useVlcEngine) vlcMediaPlayer?.play() else player?.playWhenReady = true
            }
        }
    }

    private fun showDlnaDevicePickerDialog() {
        val progressDialog = android.app.ProgressDialog(this).apply {
            setMessage("DLNA 디바이스 검색 중...")
            setCancelable(true)
        }
        progressDialog.show()

        lifecycleScope.launch {
            val devices = castManager?.discoverDevices() ?: emptyList()
            progressDialog.dismiss()

            if (devices.isEmpty()) {
                Toast.makeText(this@PlayerActivity, "DLNA 디바이스를 찾을 수 없습니다", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val names = devices.map { it.friendlyName }.toTypedArray()
            AlertDialog.Builder(this@PlayerActivity)
                .setTitle("DLNA 디바이스 선택")
                .setItems(names) { _, which ->
                    startDlnaCasting(devices[which])
                }
                .setNegativeButton("취소", null)
                .show()
        }
    }

    private fun startDlnaCasting(device: DlnaDevice) {
        val videoPath = currentVideoPath ?: externalVideoUri ?: return
        val title = controllerRoot?.findViewById<TextView>(R.id.videoTitle)?.text?.toString() ?: "Video"
        val currentPos = enginePosition

        if (useVlcEngine) vlcMediaPlayer?.pause() else player?.playWhenReady = false

        lifecycleScope.launch {
            Toast.makeText(this@PlayerActivity, "${device.friendlyName}에 연결 중...", Toast.LENGTH_SHORT).show()

            castManager?.onPositionUpdate = { _, _ -> }
            castManager?.onCastEnded = {
                isCastingActive = false
                castingMode = null
                updateCastButtonIcon()
            }

            val success = castManager?.startCasting(videoPath, title, device, currentPos) ?: false
            if (success) {
                isCastingActive = true
                castingMode = "dlna"
                updateCastButtonIcon()
                Toast.makeText(this@PlayerActivity, "${device.friendlyName}에서 재생 중", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@PlayerActivity, "캐스팅 실패", Toast.LENGTH_SHORT).show()
                if (useVlcEngine) vlcMediaPlayer?.play() else player?.playWhenReady = true
            }
        }
    }

    private fun showCastRemoteDialog() {
        val deviceName = when (castingMode) {
            "chromecast" -> chromecastManager?.getDeviceName() ?: "Chromecast"
            "dlna" -> castManager?.currentDevice?.friendlyName ?: "DLNA"
            else -> return
        }

        AlertDialog.Builder(this)
            .setTitle(deviceName)
            .setItems(arrayOf("일시정지/재생", "정지 (캐스팅 종료)")) { _, which ->
                lifecycleScope.launch {
                    when (which) {
                        0 -> {
                            if (castingMode == "chromecast") {
                                if (chromecastManager?.isPlaying() == true) chromecastManager?.pause() else chromecastManager?.resume()
                            } else {
                                val device = castManager?.currentDevice ?: return@launch
                                val state = withContext(Dispatchers.IO) {
                                    com.splayer.video.cast.DlnaController().getTransportInfo(device.avTransportControlUrl)
                                }
                                if (state == "PLAYING") castManager?.pause() else castManager?.resume()
                            }
                        }
                        1 -> {
                            if (castingMode == "chromecast") {
                                chromecastManager?.stopCasting()
                                // 세션 완전 종료 (재연결 안정성)
                                try {
                                    com.google.android.gms.cast.framework.CastContext
                                        .getSharedInstance(this@PlayerActivity)
                                        .sessionManager.endCurrentSession(true)
                                } catch (_: Exception) {}
                            } else {
                                castManager?.stopCasting()
                            }
                            isCastingActive = false
                            castingMode = null
                            updateCastButtonIcon()
                            if (useVlcEngine) vlcMediaPlayer?.play() else player?.playWhenReady = true
                        }
                    }
                }
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun updateCastButtonIcon() {
        val color = if (isCastingActive) android.graphics.Color.parseColor("#FF64B5F6") else android.graphics.Color.WHITE
        binding.playerView.findViewById<ImageButton>(R.id.btnCast)?.setColorFilter(color)
        vlcControllerView?.findViewById<ImageButton>(R.id.btnCast)?.setColorFilter(color)
    }

    private fun getMimeTypeForCast(path: String): String {
        val lower = path.lowercase()
        return when {
            lower.endsWith(".mp4") || lower.endsWith(".m4v") -> "video/mp4"
            lower.endsWith(".mkv") -> "video/x-matroska"
            lower.endsWith(".avi") -> "video/x-msvideo"
            lower.endsWith(".mov") -> "video/quicktime"
            lower.endsWith(".wmv") -> "video/x-ms-wmv"
            lower.endsWith(".webm") -> "video/webm"
            lower.endsWith(".ts") -> "video/mp2t"
            else -> "video/mp4"
        }
    }

    /**
     * 현재 활성 자막을 WebVTT 형식으로 변환
     * Chromecast는 VTT 형식만 안정적으로 지원
     */
    private fun getCurrentSubtitleAsVtt(): String? {
        val subtitleUri = currentSubtitleUri ?: return null

        try {
            // 자막 파일 읽기
            val bytes = if (subtitleUri.scheme == "content" || subtitleUri.scheme == "file") {
                contentResolver.openInputStream(subtitleUri)?.use { it.readBytes() }
            } else {
                val file = File(subtitleUri.path ?: return null)
                if (file.exists()) file.readBytes() else null
            } ?: return null

            val subtitle = availableSubtitles.find { it.uri == subtitleUri }
            val ext = subtitle?.name?.substringAfterLast(".", "srt")?.lowercase() ?: "srt"
            val content = readTextWithEncoding(bytes, ext)

            return when (ext) {
                "vtt", "webvtt" -> injectVttStyle(content)
                "srt" -> srtToVtt(content)
                "smi", "sami" -> {
                    // SMI → SRT → VTT
                    val srtContent = smiToSrt(content)
                    if (srtContent != null) srtToVtt(srtContent) else null
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e("PlayerActivity", "자막 VTT 변환 실패", e)
            return null
        }
    }

    /** 기존 VTT에 투명 배경 스타일 삽입 */
    private fun injectVttStyle(vtt: String): String {
        val styleBlock = "STYLE\n::cue {\n  background-color: transparent;\n  color: white;\n  text-shadow: -1px -1px 0 black, 1px -1px 0 black, -1px 1px 0 black, 1px 1px 0 black, 0 0 8px rgba(0,0,0,0.8);\n}\n\n"
        // WEBVTT 헤더 뒤에 스타일 삽입
        return vtt.replaceFirst("WEBVTT", "WEBVTT\n\n$styleBlock")
    }

    /** SRT → WebVTT 변환 (자막 배경 투명 CSS 포함) */
    private fun srtToVtt(srt: String): String {
        val sb = StringBuilder("WEBVTT\n\n")
        sb.append("STYLE\n::cue {\n  background-color: transparent;\n  color: white;\n  text-shadow: -1px -1px 0 black, 1px -1px 0 black, -1px 1px 0 black, 1px 1px 0 black, 0 0 8px rgba(0,0,0,0.8);\n}\n\n")
        // SRT 타임코드의 , 를 . 으로 변환 (VTT 형식)
        val converted = srt.replace(Regex("""(\d{2}:\d{2}:\d{2}),(\d{3})"""), "$1.$2")
        sb.append(converted)
        return sb.toString()
    }

    /** SMI 내용 → SRT 문자열 변환 (파일 없이 메모리에서) */
    private fun smiToSrt(smiContent: String): String? {
        try {
            val syncPattern = Regex("""<SYNC\s+Start=(\d+)>""", RegexOption.IGNORE_CASE)
            val matches = syncPattern.findAll(smiContent).toList()
            if (matches.isEmpty()) return null

            val sb = StringBuilder()
            var index = 1

            for (i in matches.indices) {
                val startTime = matches[i].groupValues[1].toLongOrNull() ?: continue
                val startIdx = matches[i].range.last + 1
                val endIdx = if (i < matches.size - 1) matches[i + 1].range.first else smiContent.length

                val textBlock = smiContent.substring(startIdx, endIdx)
                val text = textBlock
                    .replace(Regex("""<[^>]+>"""), "")
                    .replace("&nbsp;", " ")
                    .trim()

                if (text.isBlank()) continue

                val endTime = if (i < matches.size - 1) {
                    matches[i + 1].groupValues[1].toLongOrNull() ?: (startTime + 3000)
                } else {
                    startTime + 3000
                }

                sb.append("${index++}\n")
                sb.append("${formatSrtTime(startTime)} --> ${formatSrtTime(endTime)}\n")
                sb.append("$text\n\n")
            }

            return if (sb.isNotEmpty()) sb.toString() else null
        } catch (e: Exception) {
            return null
        }
    }

    private fun formatSrtTime(ms: Long): String {
        val h = ms / 3600000
        val m = (ms % 3600000) / 60000
        val s = (ms % 60000) / 1000
        val millis = ms % 1000
        return String.format("%02d:%02d:%02d,%03d", h, m, s, millis)
    }

    /** 리소스 해제 */
    private fun engineRelease() {
        if (useVlcEngine) {
            vlcMediaPlayer?.stop()
            vlcMediaPlayer?.detachViews()
            vlcMediaPlayer?.release()
            vlcMediaPlayer = null
            libVlc?.release()
            libVlc = null
        } else {
            player?.release()
            player = null
        }
    }

    /** ExoPlayer 재생 실패 시 VLC 엔진으로 자동 전환 */
    @UnstableApi
    private fun fallbackToVlcEngine() {
        val videoUri = currentVideoPath ?: return
        val position = try { player?.currentPosition ?: 0L } catch (_: Exception) { 0L }

        // ExoPlayer 릴리즈
        player?.release()
        player = null

        // VLC로 전환 (설정은 변경하지 않음 - 이번 재생만 VLC 사용)
        useVlcEngine = true
        val title = controllerRoot?.findViewById<TextView>(R.id.videoTitle)?.text?.toString() ?: "Video"
        Log.d("PlayerActivity", "VLC 폴백: uri=$videoUri, position=$position")
        initializeVlcPlayer(videoUri, position, title)
    }

    /** 터치 영역 뷰 */
    private val touchSurface: View
        get() = if (useVlcEngine) binding.vlcTouchOverlay else binding.playerView

    /** 컨트롤러 뷰 루트 */
    private val controllerRoot: View?
        get() = if (useVlcEngine) vlcControllerView else binding.playerView

    /** 컨트롤러 숨기기 */
    private fun engineHideController() {
        if (useVlcEngine) hideVlcController() else binding.playerView.hideController()
    }

    /** 컨트롤러 토글 */
    private fun engineToggleController() {
        if (useVlcEngine) toggleVlcController() else binding.playerView.performClick()
    }

    // ==================== VLC 컨트롤러 관리 ====================

    private fun showVlcController() {
        binding.vlcControllerContainer.visibility = View.VISIBLE
        vlcControllerView?.visibility = View.VISIBLE
        isVlcControllerVisible = true
        updateVlcPlayPauseIcon()
        // 자막/코덱 정보 표시 (narrow 모드에서는 숨긴 상태 유지)
        val isNarrow = resources.configuration.screenWidthDp < 500
        controllerRoot?.findViewById<TextView>(R.id.subtitleCodecInfo)?.let {
            subtitleCodecInfoTextView = it
            if (!isNarrow) {
                it.visibility = View.VISIBLE
            }
        }
        // 3초 후 자동 숨김
        vlcControllerHandler.removeCallbacks(vlcControllerHideRunnable)
        vlcControllerHandler.postDelayed(vlcControllerHideRunnable, 3000)
    }

    private fun hideVlcController() {
        subtitleCodecInfoTextView?.visibility = View.GONE
        vlcControllerView?.visibility = View.GONE
        binding.vlcControllerContainer.visibility = View.GONE
        isVlcControllerVisible = false
        vlcControllerHandler.removeCallbacks(vlcControllerHideRunnable)
    }

    private fun toggleVlcController() {
        if (isVlcControllerVisible) hideVlcController() else showVlcController()
    }

    private fun updateVlcPlayPauseIcon() {
        if (!useVlcEngine) return
        vlcPlayPauseButton?.setImageResource(
            if (engineIsPlaying) androidx.media3.ui.R.drawable.exo_icon_pause
            else androidx.media3.ui.R.drawable.exo_icon_play
        )
    }

    /** 소리 토글 버튼 아이콘 업데이트 */
    private fun updateSoundToggleIcon(btn: ImageButton) {
        btn.setImageResource(
            if (isSoundEnabled) R.drawable.ic_volume_on
            else R.drawable.ic_volume_off
        )
    }

    // ==================== VLC 플레이어 초기화 ====================

    @UnstableApi
    private fun initializeVlcPlayer(videoUri: String, startPosition: Long, videoTitle: String) {
        // ExoPlayer 뷰 숨기기, VLC 뷰 표시
        binding.playerView.visibility = View.GONE
        binding.vlcVideoLayout.visibility = View.VISIBLE
        binding.vlcTouchOverlay.visibility = View.VISIBLE

        // VLC 컨트롤러 오버레이 생성 (custom_player_control.xml 재사용)
        if (vlcControllerView == null) {
            vlcControllerView = layoutInflater.inflate(R.layout.custom_player_control, binding.vlcControllerContainer, false)
            binding.vlcControllerContainer.addView(vlcControllerView)
        }
        binding.vlcControllerContainer.visibility = View.GONE // 초기에는 숨김

        // LibVLC 초기화
        val args = arrayListOf(
            "--aout=opensles",
            "--audio-time-stretch"
        )
        when (decoderMode) {
            DECODER_MODE_HW -> args.add("--avcodec-hw=any")
            DECODER_MODE_SW -> args.add("--avcodec-hw=none")
        }

        libVlc = LibVLC(this, args)
        vlcMediaPlayer = VlcMediaPlayer(libVlc!!)

        // 미디어 생성
        val parsedUri = Uri.parse(videoUri)
        val media = if (parsedUri.scheme == "content") {
            val pfd = contentResolver.openFileDescriptor(parsedUri, "r")
            if (pfd != null) {
                val fdUri = Uri.parse("fd://${pfd.fd}")
                Media(libVlc!!, fdUri)
            } else {
                Toast.makeText(this, "파일을 열 수 없습니다", Toast.LENGTH_LONG).show()
                finish()
                return
            }
        } else if (parsedUri.scheme == "file" || parsedUri.scheme == null) {
            Media(libVlc!!, parsedUri)
        } else {
            // http, https 등
            Media(libVlc!!, parsedUri)
        }

        vlcMediaPlayer!!.media = media
        media.release()

        // 비디오 출력 연결
        vlcMediaPlayer!!.attachViews(binding.vlcVideoLayout, null, true, false)

        // 이벤트 리스너
        vlcMediaPlayer!!.setEventListener { event ->
            runOnUiThread {
                when (event.type) {
                    VlcMediaPlayer.Event.Playing -> {
                        binding.vlcBuffering.visibility = View.GONE
                        updateVlcPlayPauseIcon()
                        startVirtualPositionUpdater()
                        // 코덱 정보 표시 (1.5초 후)
                        vlcControllerHandler.postDelayed({ showVlcCodecInfo() }, 1500)
                    }
                    VlcMediaPlayer.Event.Paused -> {
                        updateVlcPlayPauseIcon()
                    }
                    VlcMediaPlayer.Event.Buffering -> {
                        val buffering = event.buffering
                        binding.vlcBuffering.visibility = if (buffering < 100f) View.VISIBLE else View.GONE
                    }
                    VlcMediaPlayer.Event.EndReached -> {
                        if (isContinuousPlayEnabled) {
                            playNextVideoInFolder()
                        } else {
                            finish()
                        }
                    }
                    VlcMediaPlayer.Event.EncounteredError -> {
                        Toast.makeText(this@PlayerActivity, "VLC 재생 오류가 발생했습니다", Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
            }
        }

        // 재생 시작
        vlcMediaPlayer!!.play()

        // 시작 위치 이동 (영상 길이보다 크면 처음부터 재생)
        if (startPosition > 0) {
            vlcControllerHandler.postDelayed({
                val vlcDuration = vlcMediaPlayer?.length ?: 0L
                if (vlcDuration > 0 && startPosition >= vlcDuration) {
                    Log.d("PlayerActivity", "VLC: 저장된 위치(${formatTime(startPosition)})가 영상 길이(${formatTime(vlcDuration)})를 초과 - 처음부터 재생")
                } else {
                    vlcMediaPlayer?.time = startPosition
                }
            }, 500)
        }

        // 소리 설정
        engineSetVolume(if (isSoundEnabled) 1f else 0f)
        if (startWithMute) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        }

        // 자막 적용 (VLC addSlave)
        if (availableSubtitles.isNotEmpty() && isSubtitleEnabled) {
            val subtitle = availableSubtitles[0]
            currentSubtitleUri = subtitle.uri
            vlcMediaPlayer!!.addSlave(IMedia.Slave.Type.Subtitle, subtitle.uri, true)
        }

        // VLC 컨트롤러 버튼 설정
        setupVlcControllerButtons(videoTitle)

        // 구간 관련 버튼 설정
        setupVlcSegmentButtons()

        // 시작 시 컨트롤러 숨김
        hideVlcController()
    }

    private fun setupVlcControllerButtons(videoTitle: String) {
        val controller = vlcControllerView ?: return

        // 비디오 제목
        controller.findViewById<TextView>(R.id.videoTitle)?.text = videoTitle

        // 디코더 표시
        val decoderIndicator = controller.findViewById<TextView>(R.id.decoderIndicator)
        val decoderText = when (decoderMode) {
            DECODER_MODE_HW -> "VLC HW"
            DECODER_MODE_SW -> "VLC SW"
            else -> "VLC"
        }
        decoderIndicator?.text = decoderText
        decoderIndicator?.setOnClickListener { showDecoderSelectionDialog() }

        // 엔진 토글 버튼
        updateEngineToggle(controller)

        // 뒤로가기
        controller.findViewById<ImageButton>(R.id.exo_back)?.setOnClickListener { finish() }

        // 재생/일시정지
        vlcPlayPauseButton = controller.findViewById(R.id.exo_play_pause)
        vlcPlayPauseButton?.setOnClickListener {
            if (engineIsPlaying) {
                vlcMediaPlayer?.pause()
            } else {
                vlcMediaPlayer?.play()
            }
            updateVlcPlayPauseIcon()
            // 컨트롤러 자동 숨김 타이머 리셋
            vlcControllerHandler.removeCallbacks(vlcControllerHideRunnable)
            vlcControllerHandler.postDelayed(vlcControllerHideRunnable, 3000)
        }

        // 소리 ON/OFF 토글 버튼
        controller.findViewById<ImageButton>(R.id.btnSoundToggle)?.let { btn ->
            updateSoundToggleIcon(btn)
            btn.setOnClickListener {
                isSoundEnabled = !isSoundEnabled
                prefs.edit().putBoolean("sound_enabled", isSoundEnabled).apply()
                engineSetVolume(if (isSoundEnabled) 1f else 0f)
                isVolumeSwipeEnabled = isSoundEnabled
                updateSoundToggleIcon(btn)
            }
        }

        // 설정 버튼
        controller.findViewById<ImageButton>(R.id.exo_settings)?.setOnClickListener {
            showSubtitleDialog()
        }

        // 자막 선택 버튼
        controller.findViewById<ImageButton>(R.id.btnSubtitle)?.setOnClickListener {
            showSubtitleSelectionDialog()
        }

        // 회전 버튼
        controller.findViewById<ImageButton>(R.id.btnRotate)?.setOnClickListener {
            toggleOrientation()
        }

        // 구간연속재생 버튼
        btnContinuousSegment = controller.findViewById(R.id.btnContinuousSegment)
        btnContinuousSegment?.setOnClickListener { showContinuousSegmentDialog() }

        // DLNA 캐스트 버튼 (VLC)
        controller.findViewById<ImageButton>(R.id.btnCast)?.setOnClickListener {
            if (isCastingActive) showCastRemoteDialog() else showCastDevicePickerDialog()
        }

        // 시크바 (DefaultTimeBar)
        val timeBar = controller.findViewById<androidx.media3.ui.DefaultTimeBar>(R.id.exo_progress)
        timeBar?.addListener(object : androidx.media3.ui.TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: androidx.media3.ui.TimeBar, position: Long) {
                if (!isSeeking) {
                    wasPlayingBeforeSeek = engineIsPlaying
                    seekStartPosition = enginePosition
                }
                isSeeking = true
                vlcMediaPlayer?.pause()
            }

            override fun onScrubMove(timeBar: androidx.media3.ui.TimeBar, position: Long) {
                val (_, actualPosition) = if (isSegmentPlaybackEnabled && currentSegments.isNotEmpty()) {
                    virtualToActualPosition(position)
                } else {
                    Pair(currentSegmentIndex, position)
                }
                showSeekPreviewForVlc(actualPosition)
            }

            override fun onScrubStop(timeBar: androidx.media3.ui.TimeBar, position: Long, canceled: Boolean) {
                val (segmentIndex, actualPosition) = if (isSegmentPlaybackEnabled && currentSegments.isNotEmpty()) {
                    virtualToActualPosition(position)
                } else {
                    Pair(currentSegmentIndex, position)
                }
                if (!canceled) {
                    if (isSegmentPlaybackEnabled && currentSegments.isNotEmpty() && segmentIndex != currentSegmentIndex) {
                        currentSegmentIndex = segmentIndex
                        updateSegmentSequenceDisplay()
                    }
                    engineSeekTo(actualPosition)
                }
                hideSeekPreview()
                vlcMediaPlayer?.play()
                updateVlcPlayPauseIcon()
                seekResetJob?.cancel()
                seekResetJob = lifecycleScope.launch {
                    delay(500)
                    isSeeking = false
                    seekStartPosition = 0L
                    wasPlayingBeforeSeek = false
                }
            }
        })

        // 상단바 적응형 오버플로 설정
        setupTopBarResponsive(controller)
    }

    private fun setupVlcSegmentButtons() {
        val controller = vlcControllerView ?: return

        // 구간 시작(F) 버튼
        btnSegmentStart = controller.findViewById(R.id.btnSegmentStart)
        btnSegmentStart?.setOnClickListener { onSegmentStartClicked() }

        // 구간 종료(T) 버튼
        btnSegmentEnd = controller.findViewById(R.id.btnSegmentEnd)
        btnSegmentEnd?.setOnClickListener { onSegmentEndClicked() }

        // 항상 표시되는 T 버튼 (activity_player.xml)
        btnSegmentEndAlways = findViewById(R.id.btnSegmentEndAlways)
        btnSegmentEndAlways?.setOnClickListener { onSegmentEndClicked() }

        // 구간 재생 토글 버튼
        segmentPlaybackToggleButton = controller.findViewById(R.id.btnSegmentPlaybackToggle)
        segmentPlaybackToggleButton?.setOnClickListener { toggleSegmentPlayback() }

        // 구간 리스트 버튼
        btnSegmentList = controller.findViewById(R.id.btnSegmentList)
        btnSegmentList?.setOnClickListener { showSegmentListDialog() }

        // 구간 네비게이션
        segmentNavigationLayout = findViewById(R.id.segmentNavigationLayout)
        tvSegmentSequence = findViewById(R.id.tvSegmentSequence)
        btnSegmentPrevious = findViewById(R.id.btnSegmentPrevious)
        btnSegmentNext = findViewById(R.id.btnSegmentNext)
        btnSegmentPrevious?.setOnClickListener { navigateToPreviousSegment() }
        btnSegmentNext?.setOnClickListener { navigateToNextSegment() }

        updateSegmentPlaybackToggleUI()
        updateSegmentButtonsVisibility()
    }

    private fun showSeekPreviewForVlc(position: Long) {
        val duration = engineDuration
        if (duration <= 0) return
        seekProgressPercentText?.apply {
            text = "${formatTime(position)} / ${formatTime(duration)}"
            visibility = View.VISIBLE
        }
        engineSeekTo(position)
    }

    private fun showVlcCodecInfo() {
        val mp = vlcMediaPlayer ?: return
        val media = mp.media ?: return
        try {
            for (i in 0 until media.trackCount) {
                val track = media.getTrack(i)
                if (track is IMedia.VideoTrack) {
                    val w = track.width
                    val h = track.height
                    currentVideoWidth = w
                    currentVideoHeight = h
                    val codecStr = track.codec?.let { codecToVlcName(it) } ?: "Unknown"
                    currentVideoCodec = codecStr
                    val resLabel = getResolutionCategory(w, h)
                    controllerRoot?.findViewById<TextView>(R.id.decoderIndicator)?.text = "$resLabel ${w}x${h} VLC"
                    updateSubtitleAndCodecInfo()
                    updateDecoderIndicator()
                    break
                }
            }
            // 오디오 코덱
            for (i in 0 until media.trackCount) {
                val track = media.getTrack(i)
                if (track is IMedia.AudioTrack) {
                    currentAudioCodec = track.codec?.let { codecToVlcName(it) } ?: "Unknown"
                    updateSubtitleAndCodecInfo()
                    break
                }
            }
        } catch (e: Exception) {
            Log.e("PlayerActivity", "VLC 코덱 정보 조회 실패", e)
        }
    }

    private fun codecToVlcName(codec: String): String {
        return when (codec.lowercase()) {
            "h264", "avc1", "avc3" -> "H.264"
            "hevc", "hev1", "h265", "hvc1" -> "H.265"
            "wmv3" -> "WMV3"
            "wvc1" -> "VC-1"
            "vp90", "vp09", "vp9" -> "VP9"
            "av01", "av1" -> "AV1"
            "mp4a" -> "AAC"
            "a52 ", "a52" -> "AC3"
            "opus" -> "Opus"
            "vorb" -> "Vorbis"
            "flac" -> "FLAC"
            "mpga" -> "MP3"
            "wma " -> "WMA"
            else -> codec.uppercase().trim()
        }
    }

    // =============================================
    // 비디오 합치기 / 구간 병합 기능
    // =============================================

    /**
     * MediaStore (Q+) 또는 외부 저장소에 파일 저장
     */
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

    /**
     * 비디오 파일 선택기 실행
     */
    private fun launchVideoPicker() {
        videoMergePickerLauncher.launch(arrayOf("video/*"))
    }

    /**
     * URI에서 파일명 추출
     */
    private fun getDisplayNameFromUri(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) return cursor.getString(nameIndex)
            }
        }
        return null
    }

    /**
     * URI에서 재생시간 추출
     */
    private fun getDurationFromUri(uri: Uri): Long {
        return try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(this, uri)
            val durationStr = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
            )
            retriever.release()
            durationStr?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * 선택한 비디오 순서 조정 다이얼로그
     */
    private fun showMergeOrderDialog(uris: List<Uri>) {
        val items = uris.mapNotNull { uri ->
            try {
                val name = getDisplayNameFromUri(uri) ?: uri.lastPathSegment ?: "unknown"
                val duration = getDurationFromUri(uri)
                com.splayer.video.ui.adapter.MergeItem(uri, name, duration)
            } catch (_: Exception) {
                null
            }
        }.toMutableList()

        if (items.size < 2) {
            Toast.makeText(this, "유효한 비디오가 2개 미만입니다.", Toast.LENGTH_SHORT).show()
            return
        }

        val recyclerView = androidx.recyclerview.widget.RecyclerView(this).apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@PlayerActivity)
            setPadding(0, 16, 0, 16)
        }

        val adapter = com.splayer.video.ui.adapter.VideoMergeAdapter(
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
                    mergeVideos(items)
                } else {
                    Toast.makeText(this, "2개 이상의 비디오가 필요합니다.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /**
     * 여러 비디오 파일을 하나로 합치기
     */
    private fun mergeVideos(items: List<com.splayer.video.ui.adapter.MergeItem>) {
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
                saveToMediaStore(tempOutputFile, outputFileName)

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(this@PlayerActivity, "합치기 완료: $outputFileName", Toast.LENGTH_SHORT).show()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(this@PlayerActivity, "합치기 취소됨", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(this@PlayerActivity, "합치기 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
                Log.e("PlayerActivity", "Merge failed", e)
            } finally {
                tempFiles.forEach { it.delete() }
                concatListFile.delete()
                tempOutputFile.delete()
            }
        }
    }

    /**
     * 구간들을 하나의 비디오로 병합
     */
    private fun mergeSegmentsAsVideo(segments: List<com.splayer.video.data.model.PlaybackSegment>, useKeyframe: Boolean = false) {
        val videoPath = currentVideoPath ?: externalVideoUri ?: ""
        val fileName = segmentManager.extractFileName(videoPath)
        val baseFileName = fileName.substringBeforeLast(".")
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US)
            .format(java.util.Date())
        val outputFileName = "${baseFileName}_merged_$timestamp.mp4"

        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)

        val progressDialog = android.app.ProgressDialog(this).apply {
            setTitle("구간 병합")
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

        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val tempSegmentFiles = mutableListOf<File>()
            val concatListFile = File(cacheDir, "concat_segments_${System.currentTimeMillis()}.txt")
            val tempOutputFile = File(cacheDir, "ffmpeg_merge_seg_${System.currentTimeMillis()}.mp4")

            try {
                val ffmpegInput = if (videoPath.startsWith("content://")) {
                    val uri = Uri.parse(videoPath)
                    com.arthenica.ffmpegkit.FFmpegKitConfig.getSafParameterForRead(this@PlayerActivity, uri)
                } else {
                    videoPath
                }

                // 1단계: 각 구간을 임시파일로 추출
                for ((index, segment) in segments.withIndex()) {
                    if (cancelled.get()) break

                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        progressDialog.setMessage("구간 ${index + 1}/${segments.size} 추출 중...")
                        progressDialog.progress = (index * 60 / segments.size)
                    }

                    val tempFile = File(cacheDir, "seg_${index}_${System.currentTimeMillis()}.mp4")
                    tempSegmentFiles.add(tempFile)

                    val actualStart = if (useKeyframe) findNearestKeyframe(videoPath, segment.startTime, seekBefore = true) else segment.startTime
                    val actualEnd = if (useKeyframe) findNearestKeyframe(videoPath, segment.endTime, seekBefore = false) else segment.endTime
                    val startSec = "%.3f".format(java.util.Locale.US, actualStart / 1000.0)
                    val durationSec = "%.3f".format(java.util.Locale.US, (actualEnd - actualStart) / 1000.0)

                    val session = com.arthenica.ffmpegkit.FFmpegKit.executeWithArguments(
                        arrayOf("-y", "-ss", startSec, "-i", ffmpegInput, "-t", durationSec, "-c", "copy", tempFile.absolutePath)
                    )

                    if (!com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.returnCode)
                        || !tempFile.exists()
                        || tempFile.length() == 0L
                    ) {
                        throw Exception("구간 ${index + 1} 추출 실패")
                    }
                }

                if (cancelled.get()) throw kotlinx.coroutines.CancellationException()

                // 2단계: concat list 작성 및 병합
                val concatContent = tempSegmentFiles.joinToString("\n") { "file '${it.absolutePath.replace("'", "'\\''")}'" }
                concatListFile.writeText(concatContent)

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    progressDialog.setMessage("[FFmpeg] 병합 중...\n$outputFileName")
                    progressDialog.progress = 60
                }

                val totalDurationMs = segments.sumOf { it.endTime - it.startTime }
                com.arthenica.ffmpegkit.FFmpegKitConfig.enableStatisticsCallback { stats ->
                    if (totalDurationMs > 0) {
                        val progress = 60 + ((stats.time.toFloat() / totalDurationMs) * 40).toInt().coerceIn(0, 40)
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
                    throw Exception("FFmpeg 병합 실패")
                }

                // 3단계: MediaStore에 저장
                saveToMediaStore(tempOutputFile, outputFileName)

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(this@PlayerActivity, "병합 완료: $outputFileName", Toast.LENGTH_SHORT).show()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(this@PlayerActivity, "병합 취소됨", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(this@PlayerActivity, "병합 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
                Log.e("PlayerActivity", "Segment merge failed", e)
            } finally {
                tempSegmentFiles.forEach { it.delete() }
                concatListFile.delete()
                tempOutputFile.delete()
            }
        }
    }

    companion object {
        const val EXTRA_VIDEO_ID = "extra_video_id"
        const val EXTRA_VIDEO_URI = "extra_video_uri"

        // 디코더 모드
        const val DECODER_MODE_HW = 0      // 하드웨어 디코더만
        const val DECODER_MODE_HW_PLUS = 1 // 하드웨어 우선 (기본값)
        const val DECODER_MODE_SW = 2      // 소프트웨어 디코더만

        // 버퍼링 모드
        const val BUFFER_MODE_STABLE = 0   // 안정 (기본값)
        const val BUFFER_MODE_FAST = 1     // 빠른 시작

        // 시크 모드
        const val SEEK_MODE_ACCURATE = 0   // 정확 (기본값)
        const val SEEK_MODE_FAST = 1       // 빠름

        // ExoPlayer 캐시 (싱글톤)
        private var simpleCache: SimpleCache? = null

        fun getCache(context: Context): SimpleCache {
            if (simpleCache == null) {
                val appContext = context.applicationContext
                val cacheDir = File(appContext.cacheDir, "video_cache")
                val cacheEvictor = LeastRecentlyUsedCacheEvictor(300 * 1024 * 1024) // 300MB 캐시
                simpleCache = SimpleCache(cacheDir, cacheEvictor, StandaloneDatabaseProvider(appContext))
            }
            return simpleCache!!
        }
    }
}
