package com.splayer.video.cast

data class DlnaDevice(
    val friendlyName: String,
    val location: String,
    val avTransportControlUrl: String,
    val udn: String
)
