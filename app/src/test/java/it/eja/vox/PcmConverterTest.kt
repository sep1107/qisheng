package it.eja.vox

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class PcmConverterTest {
    @Test fun stereo44100ProducesExactMono16kLengthAcrossUnevenBuffers() {
        val out = ByteArrayOutputStream()
        val converter = PcmConverter(44100, 2) { bytes, n -> out.write(bytes, 0, n) }
        repeat(44100 * 3) { converter.sample(1000); converter.sample(3000) }
        converter.flush()
        val bytes = out.toByteArray()
        assertEquals(16000 * 3 * 2, bytes.size)
        assertEquals(2000, (bytes[0].toInt() and 255) or ((bytes[1].toInt() and 255) shl 8))
    }
    @Test fun upsamplingAndNativeRateRetainDuration() {
        for (rate in listOf(8000, 16000, 48000)) {
            var bytes = 0
            val converter = PcmConverter(rate, 1) { _, n -> bytes += n }
            repeat(rate) { converter.sample((-100).toShort()) }
            converter.flush()
            assertEquals(32000, bytes)
        }
    }
}
