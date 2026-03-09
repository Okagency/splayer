package com.splayer.video.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.splayer.video.ui.player.PlayerActivity
import com.splayer.video.utils.CrashLogger

/**
 * 단순한 런처 액티비티
 * - 외부에서 비디오 열기: PlayerActivity로 전달
 * - 직접 실행: 앱 종료 (파일 탐색기를 통해서만 사용)
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            Log.d(TAG, "onCreate called")
            CrashLogger.logInfo(this, TAG, "MainActivity onCreate started")

            // 외부에서 비디오 파일을 열려고 하는 경우 처리
            if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
                val videoUri = intent.data
                Log.d(TAG, "Opened with URI: $videoUri")
                CrashLogger.logInfo(this, TAG, "External video opened: $videoUri")

                // ClipData에서 자막 URI 추출 (Cubby SMB 등)
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

                // PlayerActivity로 바로 이동
                val playerIntent = Intent(this, PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.EXTRA_VIDEO_URI, videoUri.toString())
                    if (subtitleUris.isNotEmpty()) {
                        putStringArrayListExtra("subtitle_uris", subtitleUris)
                    }
                    // URI 권한 전달
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    clipData = intent.clipData
                }
                startActivity(playerIntent)
                finish()
                return
            }

            // 직접 실행된 경우 - 앱을 종료 (파일 탐색기에서만 사용)
            CrashLogger.logInfo(this, TAG, "App launched directly - closing (use file explorer to open videos)")
            finish()

        } catch (e: Exception) {
            CrashLogger.logError(this, TAG, "Error in onCreate", e)
            finish()
        }
    }
}
