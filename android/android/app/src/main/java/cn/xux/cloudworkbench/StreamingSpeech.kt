package cn.xux.cloudworkbench

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

internal const val VOICE_SAMPLE_RATE = 16000

internal class StreamingSpeech(assets: AssetManager) : AutoCloseable {
    private val recognizer = OnlineRecognizer(
        assetManager = assets,
        config = OnlineRecognizerConfig(
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "voice/encoder.int8.onnx",
                    decoder = "voice/decoder.onnx",
                    joiner = "voice/joiner.int8.onnx"
                ),
                tokens = "voice/tokens.txt",
                numThreads = 2
            ),
            enableEndpoint = true,
            decodingMethod = "modified_beam_search"
        )
    )

    fun stream() = Stream()

    inner class Stream : AutoCloseable {
        private val stream = recognizer.createStream()

        fun accept(samples: FloatArray): String {
            stream.acceptWaveform(samples, VOICE_SAMPLE_RATE)
            return decode()
        }

        fun isEndpoint(): Boolean = recognizer.isEndpoint(stream)
        fun reset() = recognizer.reset(stream)

        fun finish(): String {
            stream.acceptWaveform(FloatArray(VOICE_SAMPLE_RATE / 2), VOICE_SAMPLE_RATE)
            stream.inputFinished()
            return decode()
        }

        private fun decode(): String {
            while (recognizer.isReady(stream)) recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        }

        override fun close() = stream.release()
    }

    override fun close() = recognizer.release()
}
