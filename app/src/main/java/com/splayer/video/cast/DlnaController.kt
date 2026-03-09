package com.splayer.video.cast

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.OutputStreamWriter
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL

class DlnaController {

    companion object {
        private const val TAG = "DlnaController"
        private const val AVT_SERVICE = "urn:schemas-upnp-org:service:AVTransport:1"
        private const val TIMEOUT_MS = 5000
    }

    suspend fun setAVTransportURI(
        controlUrl: String,
        mediaUrl: String,
        title: String,
        mimeType: String
    ): Boolean = withContext(Dispatchers.IO) {
        val didlLite = buildDidlLite(mediaUrl, title, mimeType)
        val body = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:SetAVTransportURI xmlns:u="$AVT_SERVICE">
<InstanceID>0</InstanceID>
<CurrentURI>${escapeXml(mediaUrl)}</CurrentURI>
<CurrentURIMetaData>${escapeXml(didlLite)}</CurrentURIMetaData>
</u:SetAVTransportURI>
</s:Body>
</s:Envelope>"""
        sendSoapAction(controlUrl, "SetAVTransportURI", body)
    }

    suspend fun play(controlUrl: String): Boolean = withContext(Dispatchers.IO) {
        sendSoapAction(controlUrl, "Play", buildAction("Play", "<Speed>1</Speed>"))
    }

    suspend fun pause(controlUrl: String): Boolean = withContext(Dispatchers.IO) {
        sendSoapAction(controlUrl, "Pause", buildAction("Pause"))
    }

    suspend fun stop(controlUrl: String): Boolean = withContext(Dispatchers.IO) {
        sendSoapAction(controlUrl, "Stop", buildAction("Stop"))
    }

    /** 동기 stop — onDestroy 등에서 Thread로 호출 가능 */
    fun stopSync(controlUrl: String): Boolean {
        return sendSoapAction(controlUrl, "Stop", buildAction("Stop"))
    }

    suspend fun seek(controlUrl: String, positionMs: Long): Boolean = withContext(Dispatchers.IO) {
        val time = formatDlnaTime(positionMs)
        sendSoapAction(controlUrl, "Seek", buildAction("Seek", "<Unit>REL_TIME</Unit><Target>$time</Target>"))
    }

    suspend fun getPositionInfo(controlUrl: String): Pair<Long, Long>? = withContext(Dispatchers.IO) {
        try {
            val responseBody = sendSoapActionWithResponse(controlUrl, "GetPositionInfo", buildAction("GetPositionInfo"))
                ?: return@withContext null

            var relTime: String? = null
            var trackDuration: String? = null

            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(StringReader(responseBody))
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "RelTime" -> relTime = parser.nextText()
                        "TrackDuration" -> trackDuration = parser.nextText()
                    }
                }
                eventType = parser.next()
            }

            val pos = relTime?.let { parseDlnaTime(it) } ?: return@withContext null
            val dur = trackDuration?.let { parseDlnaTime(it) } ?: return@withContext null
            Pair(pos, dur)
        } catch (e: Exception) {
            Log.e(TAG, "GetPositionInfo 파싱 실패", e)
            null
        }
    }

    suspend fun getTransportInfo(controlUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            val responseBody = sendSoapActionWithResponse(controlUrl, "GetTransportInfo", buildAction("GetTransportInfo"))
                ?: return@withContext null

            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(StringReader(responseBody))
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "CurrentTransportState") {
                    return@withContext parser.nextText()
                }
                eventType = parser.next()
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "GetTransportInfo 파싱 실패", e)
            null
        }
    }

    private fun buildAction(action: String, extraParams: String = ""): String {
        return """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:$action xmlns:u="$AVT_SERVICE">
<InstanceID>0</InstanceID>
$extraParams
</u:$action>
</s:Body>
</s:Envelope>"""
    }

    private fun buildDidlLite(url: String, title: String, mimeType: String): String {
        return """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="0" parentID="-1" restricted="1"><dc:title>${escapeXml(title)}</dc:title><upnp:class>object.item.videoItem</upnp:class><res protocolInfo="http-get:*:$mimeType:*">$url</res></item></DIDL-Lite>"""
    }

    private fun sendSoapAction(controlUrl: String, action: String, body: String): Boolean {
        return try {
            val conn = URL(controlUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
            conn.setRequestProperty("SOAPAction", "\"$AVT_SERVICE#$action\"")
            conn.doOutput = true
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            val code = conn.responseCode
            Log.d(TAG, "$action → $code")
            conn.disconnect()
            code in 200..299
        } catch (e: Exception) {
            Log.e(TAG, "$action 실패", e)
            false
        }
    }

    private fun sendSoapActionWithResponse(controlUrl: String, action: String, body: String): String? {
        return try {
            val conn = URL(controlUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
            conn.setRequestProperty("SOAPAction", "\"$AVT_SERVICE#$action\"")
            conn.doOutput = true
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return null
            }
            val result = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            result
        } catch (e: Exception) {
            Log.e(TAG, "$action 실패", e)
            null
        }
    }

    private fun formatDlnaTime(millis: Long): String {
        val totalSec = millis / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    private fun parseDlnaTime(time: String): Long {
        val parts = time.split(":")
        if (parts.size != 3) return 0
        val h = parts[0].toLongOrNull() ?: 0
        val m = parts[1].toLongOrNull() ?: 0
        val s = parts[2].split(".")[0].toLongOrNull() ?: 0
        return (h * 3600 + m * 60 + s) * 1000
    }

    private fun escapeXml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
