package ua.vatsuo.chytalka.bench

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteOrder

/** Float PCM (mono) -> AAC-LC in an .m4a file via MediaCodec + MediaMuxer. Encoding time is part of the measurement. */
class AacEncoder(private val file: File, private val rate: Int, bitrate: Int = 48_000) {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var muxStarted = false
    private var samplesIn = 0L

    init {
        val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1)
        f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    fun write(pcm: FloatArray) {
        var off = 0
        while (off < pcm.size) {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx < 0) { drain(false); continue }
            val buf = codec.getInputBuffer(idx)!!
            buf.clear(); buf.order(ByteOrder.LITTLE_ENDIAN)
            val n = minOf(pcm.size - off, buf.capacity() / 2)
            for (i in 0 until n) buf.putShort((pcm[off + i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            val pts = samplesIn * 1_000_000L / rate
            codec.queueInputBuffer(idx, 0, n * 2, pts, 0)
            samplesIn += n; off += n
            drain(false)
        }
    }

    private fun drain(eos: Boolean) {
        while (true) {
            val o = codec.dequeueOutputBuffer(info, if (eos) 10_000 else 0)
            when {
                o == MediaCodec.INFO_TRY_AGAIN_LATER -> if (eos) continue else return
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start(); muxStarted = true }
                o >= 0 -> {
                    val b = codec.getOutputBuffer(o)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxStarted) { b.position(info.offset); b.limit(info.offset + info.size); muxer.writeSampleData(track, b, info) }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /** Finishes the file; returns its size in bytes. */
    fun finish(): Long {
        try {
            var idx = codec.dequeueInputBuffer(10_000)
            while (idx < 0) { drain(false); idx = codec.dequeueInputBuffer(10_000) }
            codec.queueInputBuffer(idx, 0, 0, samplesIn * 1_000_000L / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
        } finally {
            runCatching { codec.stop() }; codec.release()
            runCatching { if (muxStarted) muxer.stop() }; runCatching { muxer.release() }
        }
        return file.length()
    }

    fun abort() { runCatching { codec.stop() }; codec.release(); runCatching { muxer.release() } }
}
