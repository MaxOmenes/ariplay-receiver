package com.example.airplayreceiverunai

interface AirPlayCallback {
    fun onVideoData(data: ByteArray, isH265: Boolean)
    fun onAudioData(data: ByteArray, ct: Int)
    fun onConnected()
    fun onDisconnected()
}
