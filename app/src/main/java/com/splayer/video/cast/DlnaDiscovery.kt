package com.splayer.video.cast

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

class DlnaDiscovery {

    companion object {
        private const val TAG = "DlnaDiscovery"
        private const val SSDP_ADDRESS = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val SEARCH_TIMEOUT_MS = 3000
        private const val SEARCH_TARGET = "urn:schemas-upnp-org:device:MediaRenderer:1"
    }

    suspend fun discover(): List<DlnaDevice> = withContext(Dispatchers.IO) {
        val locations = mutableSetOf<String>()

        try {
            val message = buildSearchMessage()
            val data = message.toByteArray(Charsets.UTF_8)
            val address = InetAddress.getByName(SSDP_ADDRESS)

            DatagramSocket().use { socket ->
                socket.soTimeout = SEARCH_TIMEOUT_MS
                socket.broadcast = true

                // M-SEARCH 전송 (2회 — 패킷 유실 대비)
                repeat(2) {
                    val packet = DatagramPacket(data, data.size, address, SSDP_PORT)
                    socket.send(packet)
                }

                // 응답 수집
                val buf = ByteArray(4096)
                try {
                    while (true) {
                        val response = DatagramPacket(buf, buf.size)
                        socket.receive(response)
                        val text = String(response.data, 0, response.length, Charsets.UTF_8)
                        parseLocationFromResponse(text)?.let { locations.add(it) }
                    }
                } catch (_: SocketTimeoutException) {
                    // 타임아웃 = 검색 완료
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "SSDP 검색 실패", e)
        }

        Log.d(TAG, "발견된 LOCATION: ${locations.size}개")

        // 각 디바이스 XML 파싱
        val devices = mutableListOf<DlnaDevice>()
        val seenUdns = mutableSetOf<String>()

        for (location in locations) {
            try {
                val device = fetchAndParseDeviceXml(location)
                if (device != null && seenUdns.add(device.udn)) {
                    devices.add(device)
                }
            } catch (e: Exception) {
                Log.e(TAG, "디바이스 XML 파싱 실패: $location", e)
            }
        }

        Log.d(TAG, "DLNA 렌더러: ${devices.size}개")
        devices
    }

    private fun buildSearchMessage(): String {
        return "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 3\r\n" +
                "ST: $SEARCH_TARGET\r\n" +
                "\r\n"
    }

    private fun parseLocationFromResponse(response: String): String? {
        for (line in response.lines()) {
            if (line.trim().startsWith("LOCATION:", ignoreCase = true)) {
                return line.substringAfter(":", "").trim()
            }
        }
        return null
    }

    private fun fetchAndParseDeviceXml(locationUrl: String): DlnaDevice? {
        val conn = URL(locationUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000

        val xml = try {
            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }

        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))

        var friendlyName: String? = null
        var udn: String? = null
        var avTransportControlUrl: String? = null
        var inAvTransportService = false
        var currentServiceType: String? = null

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "friendlyName" -> {
                            if (friendlyName == null) friendlyName = parser.nextText()
                        }
                        "UDN" -> {
                            if (udn == null) udn = parser.nextText()
                        }
                        "serviceType" -> {
                            currentServiceType = parser.nextText()
                            inAvTransportService = currentServiceType == "urn:schemas-upnp-org:service:AVTransport:1"
                        }
                        "controlURL" -> {
                            if (inAvTransportService && avTransportControlUrl == null) {
                                avTransportControlUrl = parser.nextText()
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "service") {
                        inAvTransportService = false
                        currentServiceType = null
                    }
                }
            }
            eventType = parser.next()
        }

        if (friendlyName == null || udn == null || avTransportControlUrl == null) return null

        val absoluteControlUrl = resolveControlUrl(locationUrl, avTransportControlUrl)

        return DlnaDevice(
            friendlyName = friendlyName,
            location = locationUrl,
            avTransportControlUrl = absoluteControlUrl,
            udn = udn
        )
    }

    private fun resolveControlUrl(locationUrl: String, controlUrl: String): String {
        if (controlUrl.startsWith("http")) return controlUrl
        val base = URL(locationUrl)
        return URL(base, controlUrl).toString()
    }
}
