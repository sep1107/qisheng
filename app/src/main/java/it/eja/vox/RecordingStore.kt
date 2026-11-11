package it.eja.vox

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Durable, bounded PCM segments; no recording is held in memory in full. */
object RecordingStore {
    const val SAMPLE_RATE = 16000
    const val BYTES_PER_SECOND = SAMPLE_RATE * 2
    const val SEGMENT_BYTES = BYTES_PER_SECOND * 120

    fun segments(session: File): List<File> = session.listFiles()
        ?.filter { it.extension == "wav" }?.sortedBy { it.name } ?: emptyList()

    fun transcript(session: File): String = segments(session).mapIndexedNotNull { index, audio ->
        val text = File(session, audio.nameWithoutExtension + ".txt")
        if (!text.exists()) null else "[${index * 2}:00]\n${text.readText()}"
    }.joinToString("\n\n")

    fun pending(session: File) = segments(session).filter {
        !File(session, it.nameWithoutExtension + ".txt").exists()
    }

    fun atomicText(file: File, text: String) {
        val staging = File(file.parentFile, file.name + ".writing")
        staging.outputStream().use { stream ->
            stream.write(text.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        check(staging.renameTo(file)) { "无法保存文字" }
    }

    fun finish(part: File): File? {
        if (part.length() <= 44) return null
        RandomAccessFile(part, "rw").use { out ->
            // A crash can leave half a sample; retain complete 16-bit samples only.
            val audioBytes = (out.length() - 44) / 2 * 2
            out.setLength(audioBytes + 44)
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt((audioBytes + 36).toInt())
                put("WAVEfmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
                putInt(SAMPLE_RATE); putInt(BYTES_PER_SECOND); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(audioBytes.toInt())
            }.array()
            out.seek(0); out.write(header); out.fd.sync()
        }
        val ready = File(part.parentFile, part.nameWithoutExtension + ".wav")
        check(part.renameTo(ready)) { "无法保存录音分段" }
        return ready
    }

    fun recover(session: File) {
        session.listFiles()?.filter { it.extension == "part" }?.forEach { finish(it) }
    }

    fun promptChunks(text: String, limit: Int = 12000): List<String> =
        text.chunked(limit)
}

class SegmentWriter(private val folder: File, private val ready: (File) -> Unit,
                    private val segmentBytes: Int = RecordingStore.SEGMENT_BYTES) : AutoCloseable {
    private var output: FileOutputStream? = null
    private var part: File? = null
    private var size = 0
    private var index = 0

    fun write(buffer: ByteArray, count: Int) {
        var offset = 0
        while (offset < count) {
            if (output == null) {
                part = File(folder, "%06d.part".format(index++))
                output = FileOutputStream(part).apply { write(ByteArray(44)) }
                size = 0
            }
            val bytes = minOf(count - offset, segmentBytes - size)
            output!!.write(buffer, offset, bytes)
            offset += bytes; size += bytes
            if (size == segmentBytes) finish()
        }
    }

    fun sync() { output?.fd?.sync() }

    private fun finish() {
        output?.let { it.fd.sync(); it.close() }
        output = null
        part?.let { RecordingStore.finish(it)?.let(ready) }
        part = null
    }

    override fun close() = finish()
}
