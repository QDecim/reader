package ua.vatsuo.chytalka.bench

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.LinkedBlockingQueue

/** Plays float PCM chunks from a queue on its own thread, so generation is not throttled to real time. */
class PcmPlayer(private val rate: Int) {
    private val q = LinkedBlockingQueue<FloatArray>()
    private val end = FloatArray(0)
    @Volatile private var aborted = false
    private val th = Thread({ loop() }, "bench-player")

    fun start() = th.start()
    fun offer(s: FloatArray) { q.put(s) }
    fun finishAndWait() { q.put(end); th.join() }
    fun abort() { aborted = true; q.clear(); q.put(end); th.join(2000) }

    private fun loop() {
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(min, rate * 4))
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        try {
            t.play()
            while (!aborted) {
                val c = q.take()
                if (c === end) break
                var off = 0
                while (off < c.size && !aborted) {
                    val w = t.write(c, off, minOf(4096, c.size - off), AudioTrack.WRITE_BLOCKING)
                    if (w < 0) return
                    off += w
                }
            }
            if (!aborted) { t.stop(); Thread.sleep(1000) }
        } finally { runCatching { t.pause(); t.flush() }; t.release() }
    }
}
