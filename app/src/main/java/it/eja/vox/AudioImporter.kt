package it.eja.vox

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.nio.ByteOrder

/** Bounded PCM conversion, retaining sample phase across decoder buffers. */
internal class PcmConverter(private val rate: Int, private val channels: Int, private val emit: (ByteArray, Int) -> Unit) {
    private val buffer = ByteArray(8192)
    private var buffered = 0
    fun flush() { if (buffered > 0) { emit(buffer, buffered); buffered = 0 } }
    private var phase = 0L
    private var sum = 0L
    private var count = 0
    fun sample(value: Short) {
        sum += value; count++
        if (count != channels) return
        val mono = (sum / channels).toInt()
        phase += 16000
        while (phase >= rate) {
            buffer[buffered++] = mono.toByte(); buffer[buffered++] = (mono shr 8).toByte()
            if (buffered == buffer.size) flush()
            phase -= rate
        }
        sum = 0; count = 0
    }
}

object AudioImporter {
    fun import(context: Context, uri: Uri, folder: File, progress: (Long) -> Unit) {
        require(uri.scheme in listOf("content", "file")) { "请选择本机音频" }
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        val writer = SegmentWriter(folder, {})
        var bytes = 0L
        var lastSecond = -1L
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("文件不包含可识别音轨")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            var converter: PcmConverter? = null
            fun configure(output: MediaFormat) {
                val encoding = if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) output.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                require(encoding == AudioFormat.ENCODING_PCM_16BIT) { "暂不支持此 PCM 编码" }
                val rate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                require(rate > 0 && channels in 1..8)
                converter = PcmConverter(rate, channels) { data, n -> writer.write(data, n); bytes += n }
            }
            if (format.getString(MediaFormat.KEY_MIME) == "audio/raw") {
                configure(format)
                val buffer = java.nio.ByteBuffer.allocate(262144).order(ByteOrder.LITTLE_ENDIAN)
                while (true) {
                    check(!Thread.currentThread().isInterrupted)
                    check(folder.usableSpace > 16 * 1024 * 1024) { "存储不足" }
                    buffer.clear()
                    val n = extractor.readSampleData(buffer, 0)
                    if (n < 0) break
                    require(n % 2 == 0)
                    buffer.position(0); buffer.limit(n)
                    while (buffer.remaining() >= 2) converter!!.sample(buffer.short)
                    extractor.advance()
                }
                converter!!.flush()
                check(bytes > 0)
                progress(bytes / RecordingStore.BYTES_PER_SECOND)
                return
            }
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            decoder = codec
            codec.configure(format, null, null, 0); codec.start()
            var inputDone = false
            var outputDone = false
            val info = MediaCodec.BufferInfo()
            var lastOutput = System.nanoTime()
            while (!outputDone) {
                check(!Thread.currentThread().isInterrupted)
                check(folder.usableSpace > 16 * 1024 * 1024) { "存储不足" }
                check(System.nanoTime() - lastOutput < 60_000_000_000L) { "解码超时" }
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10000)
                    if (index >= 0) {
                        val input = codec.getInputBuffer(index)!!; input.clear()
                        val n = extractor.readSampleData(input, 0)
                        if (n < 0) { codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(index, 0, n, extractor.sampleTime, 0); extractor.advance() }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) configure(codec.outputFormat)
                else if (index >= 0) {
                    lastOutput = System.nanoTime()
                    try {
                        if (info.size > 0) {
                            if (converter == null) configure(codec.outputFormat)
                            val output = codec.getOutputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN)
                            output.position(info.offset); output.limit(info.offset + info.size)
                            require(info.size % 2 == 0)
                            while (output.remaining() >= 2) converter!!.sample(output.short)
                            val second = bytes / RecordingStore.BYTES_PER_SECOND
                            if (second != lastSecond) { writer.sync(); progress(second); lastSecond = second }
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally { codec.releaseOutputBuffer(index, false) }
                }
            }
            converter?.flush()
            progress(bytes / RecordingStore.BYTES_PER_SECOND)
            check(bytes > 0) { "没有有效音频" }
        } finally {
            runCatching { decoder?.stop() }; decoder?.release(); extractor.release(); writer.close()
        }
    }
}
