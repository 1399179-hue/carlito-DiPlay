package com.shilapi.xcertplay.media

import androidx.media3.container.NalUnitUtil
import com.shilapi.xcertplay.airplay.VideoCodec

/**
 * Coded size and colour description parsed straight out of the stream's SPS/VUI.
 *
 * Media3 parses the (possibly emulation-escaped) SPS rather than letting the decoder guess the
 * colour range from resolution or codec. Ported from xcertplay-nomfi: DiPlay previously only read
 * the colour keys back from the decoder's output format and never told the decoder what the
 * bitstream says, so decoders that guess wrong produced washed-out or crushed video.
 */
internal data class VideoParameters(
    val width: Int,
    val height: Int,
    val colorStandard: Int,
    val colorRange: Int,
    val colorTransfer: Int,
) {
    companion object {
        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        fun parse(codec: VideoCodec, csd: ByteArray): VideoParameters {
            val units = MediaCodecSupport.annexBNalUnits(csd)
            return if (codec == VideoCodec.H265) {
                val sps = units.first { (it[0].toInt() ushr 1) and 0x3f == 33 }
                val parsed = NalUnitUtil.parseH265SpsNalUnit(sps, 0, sps.size, null)
                VideoParameters(parsed.width, parsed.height, parsed.colorSpace, parsed.colorRange, parsed.colorTransfer)
            } else {
                val sps = units.first { it[0].toInt() and 0x1f == 7 }
                val parsed = NalUnitUtil.parseSpsNalUnit(sps, 0, sps.size)
                VideoParameters(parsed.width, parsed.height, parsed.colorSpace, parsed.colorRange, parsed.colorTransfer)
            }.also { require(it.width > 0 && it.height > 0) { "Invalid SPS dimensions" } }
        }
    }
}
