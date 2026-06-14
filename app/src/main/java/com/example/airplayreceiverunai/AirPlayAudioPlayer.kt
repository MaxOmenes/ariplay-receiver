package com.example.airplayreceiverunai

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer

/**
 * Decodes AirPlay audio (AAC-ELD ct=8, or ALAC ct=2) and plays via AudioTrack.
 *
 * ct=8  AAC-ELD  → MediaCodec AAC decoder → PCM → AudioTrack
 * ct=2  ALAC     → MediaCodec ALAC decoder → PCM → AudioTrack
 */
class AirPlayAudioPlayer {

    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    @Volatile private var activeCt: Int = -1

    // AAC-ELD AudioSpecificConfig for 44100 Hz, stereo, 480 samples/frame
    // AOT=39 -> 5 bits (31) + 6 bits (7) = 11111 000111
    // SampleRate=44100 (4) -> 4 bits = 0100
    // Channels=2 (stereo) -> 4 bits = 0010
    // ELD frame length flag=1 (480 samples) -> 1 bit = 1
    // ELD flags (resilience, etc) -> 4 bits = 0000
    // Binary: 11111 000 111 0100 0010 1 0000 = 11111000 11101000 01010000
    // Hex: 0xF8, 0xE8, 0x50, 0x00
    private val aacEldConfig = byteArrayOf(0xF8.toByte(), 0xE8.toByte(), 0x50.toByte(), 0x00.toByte())

    fun onAudioData(data: ByteArray, ct: Int) {
        // Lazily start the right codec when we first know the type
        if (activeCt != ct) {
            Log.i(TAG, "Audio format changed or initialized. ct=$ct")
            release()
            activeCt = ct
            startForCodec(ct)
        }
        feedAndPlay(data)
    }

    private fun startForCodec(ct: Int) {
        try {
            val (mime, fmt) = when (ct) {
                8 -> {   // AAC-ELD
                    val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS)
                    f.setInteger(MediaFormat.KEY_AAC_PROFILE, AAC_PROFILE_ELD)
                    f.setByteBuffer("csd-0", ByteBuffer.wrap(aacEldConfig))
                    f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                    Pair(MediaFormat.MIMETYPE_AUDIO_AAC, f)
                }
                2 -> {   // ALAC (audio/alac available since API 21 as a string)
                    val f = MediaFormat.createAudioFormat("audio/alac", SAMPLE_RATE, CHANNELS)
                    f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                    Pair("audio/alac", f)
                }
                else -> {
                    Log.w(TAG, "Unknown ct=$ct, defaulting to AAC-ELD")
                    val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS)
                    f.setInteger(MediaFormat.KEY_AAC_PROFILE, AAC_PROFILE_ELD)
                    f.setByteBuffer("csd-0", ByteBuffer.wrap(aacEldConfig))
                    f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                    Pair(MediaFormat.MIMETYPE_AUDIO_AAC, f)
                }
            }

            codec = MediaCodec.createDecoderByType(mime).also {
                it.configure(fmt, null, null, 0)
                it.start()
            }
            Log.i(TAG, "Decoder started: $mime ct=$ct")

            val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track?.play()
            Log.i(TAG, "AudioTrack started, minBuf=$minBuf")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio for ct=$ct", e)
            release()
        }
    }

    private fun feedAndPlay(data: ByteArray) {
        val c = codec ?: return
        val t = track ?: return
        try {
            val inIdx = c.dequeueInputBuffer(10_000L)
            if (inIdx >= 0) {
                val buf = c.getInputBuffer(inIdx) ?: return
                buf.clear()
                val len = minOf(data.size, buf.remaining())
                buf.put(data, 0, len)
                c.queueInputBuffer(inIdx, 0, len, System.nanoTime() / 1_000L, 0)
            }

            val info = MediaCodec.BufferInfo()
            var outIdx = c.dequeueOutputBuffer(info, 0L)
            while (outIdx >= 0) {
                val outBuf = c.getOutputBuffer(outIdx)
                if (outBuf != null && info.size > 0) {
                    val pcm = ByteArray(info.size)
                    outBuf.position(info.offset)
                    outBuf.get(pcm, 0, info.size)
                    val res = t.write(pcm, 0, pcm.size)
                    if (res < 0) Log.e(TAG, "AudioTrack write error: $res")
                }
                c.releaseOutputBuffer(outIdx, false)
                outIdx = c.dequeueOutputBuffer(info, 0L)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio decode error: ${e.message}", e)
        }
    }

    fun release() {
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        activeCt = -1
    }

    companion object {
        private const val TAG          = "AirPlayAudioPlayer"
        private const val SAMPLE_RATE  = 44100
        private const val CHANNELS     = 2
        private const val AAC_PROFILE_ELD = 39   // MediaCodecInfo.CodecProfileLevel.AACObjectELD
    }
}
