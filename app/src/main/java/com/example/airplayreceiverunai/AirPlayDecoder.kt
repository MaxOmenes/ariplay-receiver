package com.example.airplayreceiverunai

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

class AirPlayDecoder(
    private val surface: Surface,
    private val dimensionListener: ((width: Int, height: Int) -> Unit)? = null
) {

    private var codec: MediaCodec? = null
    private var configured = false

    fun onVideoData(data: ByteArray, isH265: Boolean) {
        if (!configured) {
            tryConfigureFromAnnexB(data, isH265)
        }
        if (configured) feedAnnexB(data)
    }

    private fun tryConfigureFromAnnexB(data: ByteArray, isH265: Boolean) {
        val nals = splitAnnexB(data)

        var sps: ByteArray? = null
        var pps: ByteArray? = null

        for (nal in nals) {
            if (nal.isEmpty()) continue
            val nalType = nal[0].toInt() and 0x1F
            when (nalType) {
                7 -> sps = nal
                8 -> pps = nal
            }
        }

        if (!isH265 && (sps == null || pps == null)) return

        val mime = if (isH265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC

        // Parse real dimensions from SPS before creating the format
        val (parsedW, parsedH) = if (!isH265 && sps != null) parseSpsSize(sps) else Pair(0, 0)
        val initW = if (parsedW > 0) parsedW else 1920
        val initH = if (parsedH > 0) parsedH else 1080

        Log.i(TAG, "SPS parsed dimensions: ${parsedW}x${parsedH}, using ${initW}x${initH}")

        val fmt = MediaFormat.createVideoFormat(mime, initW, initH)

        if (!isH265 && sps != null && pps != null) {
            val startCode = byteArrayOf(0, 0, 0, 1)
            fmt.setByteBuffer("csd-0", ByteBuffer.wrap(startCode + sps))
            fmt.setByteBuffer("csd-1", ByteBuffer.wrap(startCode + pps))
        }

        try {
            codec = MediaCodec.createDecoderByType(mime).also {
                it.configure(fmt, surface, null, 0)
                it.start()
            }
            configured = true
            Log.i(TAG, "MediaCodec configured: ${if (isH265) "H.265" else "H.264"} ${initW}x${initH}")
            // Dimensions will be reported via INFO_OUTPUT_FORMAT_CHANGED which includes
            // rotation metadata — this gives us the correct display orientation.
        } catch (e: Exception) {
            Log.e(TAG, "MediaCodec configure failed", e)
        }
    }

    /**
     * Parse width and height from an H.264 SPS NAL unit (without start code).
     * Uses a minimal Exp-Golomb bitstream reader — covers the common cases.
     * Returns (0, 0) on any parse failure.
     */
    private fun parseSpsSize(sps: ByteArray): Pair<Int, Int> {
        return try {
            val bits = BitReader(sps, 1) // skip nal_unit_type byte

            val profileIdc = bits.readBits(8)
            bits.skipBits(8) // constraint flags
            bits.skipBits(8) // level_idc
            bits.readUE()    // seq_parameter_set_id

            if (profileIdc in setOf(100, 110, 122, 244, 44, 83, 86, 118, 128)) {
                val chromaFormat = bits.readUE()
                if (chromaFormat == 3) bits.skipBits(1)
                bits.readUE() // bit_depth_luma_minus8
                bits.readUE() // bit_depth_chroma_minus8
                bits.skipBits(1) // qpprime_y_zero_transform_bypass_flag
                if (bits.readBit() == 1) { // seq_scaling_matrix_present_flag
                    val count = if (chromaFormat != 3) 8 else 12
                    for (i in 0 until count) {
                        if (bits.readBit() == 1) { // seq_scaling_list_present_flag
                            val sz = if (i < 6) 16 else 64
                            var last = 8; var next = 8
                            for (j in 0 until sz) {
                                if (next != 0) next = (last + bits.readSE()) and 0xFF
                                last = if (next == 0) last else next
                            }
                        }
                    }
                }
            }

            bits.readUE() // log2_max_frame_num_minus4
            val picOrderCntType = bits.readUE()
            if (picOrderCntType == 0) bits.readUE()      // log2_max_pic_order_cnt_lsb_minus4
            else if (picOrderCntType == 1) {
                bits.skipBits(1)
                bits.readSE(); bits.readSE()
                val n = bits.readUE()
                for (i in 0 until n) bits.readSE()
            }
            bits.readUE() // max_num_ref_frames
            bits.skipBits(1) // gaps_in_frame_num_value_allowed_flag

            val picWidthInMbs = bits.readUE() + 1
            val picHeightInMapUnits = bits.readUE() + 1
            val frameMbsOnly = bits.readBit()

            val w = picWidthInMbs * 16
            val h = picHeightInMapUnits * 16 * (2 - frameMbsOnly)

            Pair(w, h)
        } catch (e: Exception) {
            Log.w(TAG, "SPS parse failed: ${e.message}")
            Pair(0, 0)
        }
    }

    // Split Annex B stream into NAL units (without start codes)
    private fun splitAnnexB(data: ByteArray): List<ByteArray> {
        val nals = mutableListOf<ByteArray>()
        var start = -1
        var i = 0
        while (i <= data.size - 3) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (i + 3 < data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                    if (start >= 0) nals.add(data.copyOfRange(start, i))
                    start = i + 4; i += 4; continue
                } else if (data[i + 2] == 1.toByte()) {
                    if (start >= 0) nals.add(data.copyOfRange(start, i))
                    start = i + 3; i += 3; continue
                }
            }
            i++
        }
        if (start >= 0 && start < data.size) nals.add(data.copyOfRange(start, data.size))
        return nals
    }

    private fun feedAnnexB(data: ByteArray) {
        val c = codec ?: return
        val idx = c.dequeueInputBuffer(10_000L)
        if (idx < 0) return

        val buf = c.getInputBuffer(idx) ?: return
        buf.clear()
        val len = minOf(data.size, buf.remaining())
        buf.put(data, 0, len)
        c.queueInputBuffer(idx, 0, len, System.nanoTime() / 1_000L, 0)

        val info = MediaCodec.BufferInfo()
        var out = c.dequeueOutputBuffer(info, 0L)
        while (out != MediaCodec.INFO_TRY_AGAIN_LATER) {
            when (out) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // The codec reports real decoded dimensions + rotation here.
                    // iPhone encodes portrait pixels even in landscape and adds a 90°
                    // rotation flag — we must swap w/h for 90° and 270° rotations.
                    val outFmt = c.outputFormat
                    var w = outFmt.getInteger(MediaFormat.KEY_WIDTH)
                    var h = outFmt.getInteger(MediaFormat.KEY_HEIGHT)
                    val rotation = try { outFmt.getInteger(MediaFormat.KEY_ROTATION) } catch (_: Exception) { 0 }
                    if (rotation == 90 || rotation == 270) { val tmp = w; w = h; h = tmp }
                    if (w > 0 && h > 0) {
                        Log.i(TAG, "Output format changed: ${w}x${h} rotation=${rotation}")
                        dimensionListener?.invoke(w, h)
                    }
                }
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> { /* ignored */ }
                else -> if (out >= 0) c.releaseOutputBuffer(out, true)
            }
            out = c.dequeueOutputBuffer(info, 0L)
        }
    }

    fun release() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        configured = false
    }

    companion object { private const val TAG = "AirPlayDecoder" }
}

/** Minimal Exp-Golomb / raw bit reader for SPS parsing. */
private class BitReader(private val data: ByteArray, startByte: Int = 0) {
    private var bytePos = startByte
    private var bitPos = 0

    fun readBit(): Int {
        if (bytePos >= data.size) throw IndexOutOfBoundsException("End of SPS data")
        val bit = (data[bytePos].toInt() ushr (7 - bitPos)) and 1
        if (++bitPos == 8) { bitPos = 0; bytePos++ }
        return bit
    }

    fun readBits(n: Int): Int {
        var v = 0
        repeat(n) { v = (v shl 1) or readBit() }
        return v
    }

    fun skipBits(n: Int) { repeat(n) { readBit() } }

    /** Unsigned Exp-Golomb */
    fun readUE(): Int {
        var leadZeros = 0
        while (readBit() == 0) leadZeros++
        return (1 shl leadZeros) - 1 + readBits(leadZeros)
    }

    /** Signed Exp-Golomb */
    fun readSE(): Int {
        val v = readUE()
        return if (v and 1 == 1) (v + 1) / 2 else -(v / 2)
    }
}
