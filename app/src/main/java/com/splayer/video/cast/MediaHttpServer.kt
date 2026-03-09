package com.splayer.video.cast

import android.content.Context
import android.net.Uri
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.NetworkInterface

class MediaHttpServer(
    private val context: Context,
    port: Int = 0
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "MediaHttpServer"
    }

    private var currentFilePath: String? = null
    private var currentUri: Uri? = null
    private var currentMimeType: String = "video/mp4"
    private var currentFileSize: Long = -1

    // 자막 데이터 (VTT 형식으로 변환하여 메모리에 보관)
    private var subtitleData: ByteArray? = null

    fun serveFile(filePath: String, mimeType: String) {
        currentFilePath = filePath
        currentUri = null
        currentMimeType = mimeType
        currentFileSize = File(filePath).length()
    }

    fun serveContentUri(uri: Uri, mimeType: String) {
        currentUri = uri
        currentFilePath = null
        currentMimeType = mimeType
        currentFileSize = try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1
        } catch (e: Exception) {
            Log.e(TAG, "파일 크기 조회 실패", e)
            -1
        }
    }

    /**
     * 자막 데이터 설정 (VTT 형식)
     */
    fun setSubtitleData(vttContent: String) {
        subtitleData = vttContent.toByteArray(Charsets.UTF_8)
    }

    fun getSubtitleUrl(): String? {
        if (subtitleData == null) return null
        return "http://${getLocalIpAddress()}:$listeningPort/subtitle.vtt"
    }

    override fun serve(session: IHTTPSession): Response {
        if (session.uri == "/subtitle.vtt") {
            val data = subtitleData
                ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "No subtitle")
            val response = newFixedLengthResponse(Response.Status.OK, "text/vtt", data.inputStream(), data.size.toLong())
            response.addHeader("Access-Control-Allow-Origin", "*")
            return response
        }

        if (session.uri != "/video") {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
        }

        val fileSize = currentFileSize
        if (fileSize <= 0) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "No file")
        }

        val rangeHeader = session.headers["range"]
        return if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            servePartialContent(rangeHeader, fileSize)
        } else {
            serveFullContent(fileSize)
        }
    }

    private fun serveFullContent(fileSize: Long): Response {
        val inputStream = openInputStream(0)
            ?: return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Cannot open file")

        val response = newFixedLengthResponse(Response.Status.OK, currentMimeType, inputStream, fileSize)
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Content-Length", fileSize.toString())
        response.addHeader("transferMode.dlna.org", "Streaming")
        response.addHeader("contentFeatures.dlna.org", "DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01700000000000000000000000000000")
        return response
    }

    private fun servePartialContent(rangeHeader: String, fileSize: Long): Response {
        val rangeSpec = rangeHeader.removePrefix("bytes=")
        val parts = rangeSpec.split("-")
        val start = parts[0].toLongOrNull() ?: 0
        val end = if (parts.size > 1 && parts[1].isNotBlank()) {
            parts[1].toLongOrNull() ?: (fileSize - 1)
        } else {
            fileSize - 1
        }
        val contentLength = end - start + 1

        val inputStream = openInputStream(start)
            ?: return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Cannot open file")

        val response = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, currentMimeType, inputStream, contentLength)
        response.addHeader("Content-Range", "bytes $start-$end/$fileSize")
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Content-Length", contentLength.toString())
        response.addHeader("transferMode.dlna.org", "Streaming")
        response.addHeader("contentFeatures.dlna.org", "DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01700000000000000000000000000000")
        return response
    }

    private fun openInputStream(offset: Long): InputStream? {
        return try {
            val filePath = currentFilePath
            if (filePath != null) {
                // 로컬 파일: RandomAccessFile로 효율적 seek
                val raf = RandomAccessFile(File(filePath), "r")
                if (offset > 0) raf.seek(offset)
                RafInputStream(raf)
            } else {
                // content:// URI
                val uri = currentUri ?: return null
                val stream = context.contentResolver.openInputStream(uri) ?: return null
                if (offset > 0) {
                    var skipped = 0L
                    while (skipped < offset) {
                        val s = stream.skip(offset - skipped)
                        if (s <= 0) break
                        skipped += s
                    }
                }
                stream
            }
        } catch (e: Exception) {
            Log.e(TAG, "파일 열기 실패", e)
            null
        }
    }

    fun getServingUrl(): String {
        return "http://${getLocalIpAddress()}:$listeningPort/video"
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                val addresses = ni.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: continue
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "IP 주소 조회 실패", e)
        }
        return "127.0.0.1"
    }

    /** RandomAccessFile을 InputStream으로 래핑 */
    private class RafInputStream(private val raf: RandomAccessFile) : InputStream() {
        override fun read(): Int = raf.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = raf.read(b, off, len)
        override fun close() = raf.close()
        override fun available(): Int = (raf.length() - raf.filePointer).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
