package ua.vatsuo.chytalka.bench

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.EnumSet

/** StyleTTS2 ONNX (inputs: tokens int64[n], speed float scalar, s_prev float[1,256]; output: 24 kHz waveform). */
class OrtRunner(modelPath: String, threads: Int, val provider: String) : AutoCloseable {
    companion object { const val RATE = 24000 }

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inTokens: String
    private val inSpeed: String
    private val inStyle: String
    val loadSeconds: Double

    init {
        val t0 = System.nanoTime()
        val so = OrtSession.SessionOptions()
        so.setIntraOpNumThreads(threads)
        so.setInterOpNumThreads(1)
        so.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        when (provider) {
            "xnnpack" -> so.addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
            "nnapi" -> so.addNnapi(EnumSet.of(NNAPIFlags.USE_FP16))
        }
        session = env.createSession(modelPath, so)
        val names = session.inputNames.toList()
        fun pick(n: String) = names.firstOrNull { it == n } ?: throw IllegalStateException("У моделі нема входу '$n'; є: $names")
        inTokens = pick("tokens"); inSpeed = pick("speed"); inStyle = pick("s_prev")
        loadSeconds = (System.nanoTime() - t0) / 1e9
    }

    fun run(tokens: LongArray, speed: Float, style: FloatArray): FloatArray {
        val tt = OnnxTensor.createTensor(env, LongBuffer.wrap(tokens), longArrayOf(tokens.size.toLong()))
        val ts = OnnxTensor.createTensor(env, speed)            // boxed Float -> scalar tensor
        val ty = OnnxTensor.createTensor(env, FloatBuffer.wrap(style), longArrayOf(1, style.size.toLong()))
        try {
            session.run(mapOf(inTokens to tt, inSpeed to ts, inStyle to ty)).use { r ->
                val fb = (r[0] as OnnxTensor).floatBuffer
                return FloatArray(fb.remaining()).also { fb.get(it) }
            }
        } finally { tt.close(); ts.close(); ty.close() }
    }

    override fun close() { session.close() }
}
