package it.eja.vox

import android.content.Context
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit opt-in only: uses device-owned keys with synthetic test content. */
@RunWith(AndroidJUnit4::class)
class RecorderDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val prefs get() = context.getSharedPreferences("vox_prefs", Context.MODE_PRIVATE)

    @Before fun requireExplicitLiveTestConsent() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveApis") == "true")
    }

    private fun report(label: String, value: String) {
        instrumentation.sendStatus(0, Bundle().apply { putString("qisheng_probe", "$label=$value") })
    }

    private fun audio(): File = File(context.cacheDir, "qisheng-synthetic-probe.wav").apply {
        instrumentation.context.assets.open("qisheng-synthetic-probe.wav").use { source -> outputStream().use { source.copyTo(it) } }
    }

    private fun probe(kind: String) {
        val temporary = context.getSharedPreferences("qisheng_device_probe", Context.MODE_PRIVATE)
        val failures = mutableListOf<Int>()
        try {
            ApiProfiles.load(prefs, kind).forEachIndexed { index, profile ->
                temporary.edit().clear().putBoolean("free_only", true).commit()
                ApiProfiles.save(temporary, listOf(profile.copy(id = "${kind}_0", enabled = true)))
                var phase = "starting"
                try {
                    assertTrue(profile.key.isNotBlank() && profile.model.isNotBlank())
                    val cloud = CloudApi(temporary) { phase = it }
                    val answer = if (kind == "asr") cloud.transcribe(audio())
                    else cloud.chat("请用中文一句话总结以下合成测试内容，不超过20字：明天下午三点开会，讨论门店视频，请准备三条方案。")
                    assertTrue(answer.isNotBlank())
                    report("${kind}_${index + 1}", "OK model=${profile.model} chars=${answer.length}")
                } catch (error: Exception) {
                    failures.add(index + 1)
                    report("${kind}_${index + 1}", "FAIL type=${error.javaClass.simpleName} phase=$phase")
                }
            }
        } finally {
            temporary.edit().clear().commit() // Only this test-created temporary configuration.
        }
        assertTrue("Unavailable $kind slots: $failures", failures.isEmpty())
    }

    @Test fun summaryApisOnRecorder() = probe("llm")
    @Test fun transcriptionApisOnRecorder() = probe("asr")

    @Test fun syntheticRecordingSummaryQueuesAndSendsAutomatically() {
        assertTrue("Enable the configured isolated receiver before this opt-in test", prefs.getBoolean("receiver_auto_send", false))
        assertTrue(prefs.getBoolean("receiver_enabled", false))
        val session = File(context.filesDir, "recordings/${System.currentTimeMillis()}").apply { mkdirs() }
        RecordingStore.atomicText(File(session, "name.txt"), "柒声实机自动发送测试-${session.name}")
        val sound = File(session, "000000.wav")
        audio().inputStream().use { source -> sound.outputStream().use { source.copyTo(it) } }
        val cloud = CloudApi(prefs) { report("pipeline_phase", it) }
        val text = RecordingStore.requireTranscript(cloud.transcribe(sound))
        RecordingStore.atomicText(File(session, "000000.txt"), text)
        val summary = RecordingStore.summary(session) { RecordingSummary.generate(cloud, text, "中文") }
        assertTrue(summary.isNotBlank())
        NoteDelivery.queue(context, session)
        report("automatic_session", session.name)
        val deadline = System.currentTimeMillis() + 150_000
        while (!NoteDeliveryStore.accepted(session) && System.currentTimeMillis() < deadline) Thread.sleep(500)
        assertTrue("Automatic delivery not accepted: ${NoteDeliveryStore.status(session)}", NoteDeliveryStore.accepted(session))
        assertTrue(NoteDeliveryStore.status(session).contains("发送成功"))
        report("automatic_delivery", "OK session=${session.name} transcript_chars=${text.length} summary_chars=${summary.length}")
    }

    @Test fun prepareOfflineAutoDelivery() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("offlineTest") == "true")
        assertTrue(prefs.getBoolean("receiver_auto_send", false))
        val session = File(context.filesDir, "recordings/${System.currentTimeMillis()}").apply { mkdirs() }
        RecordingStore.atomicText(File(session, "name.txt"), "柒声实机断网重试测试-${session.name}")
        audio().inputStream().use { input -> File(session, "000000.wav").outputStream().use { input.copyTo(it) } }
        RecordingStore.atomicText(File(session, "000000.txt"), "这是合成断网测试：明天下午三点开会。")
        RecordingStore.atomicText(File(session, "summary.txt"), "测试：断网时保留待发内容，联网后补发。")
        NoteDelivery.queue(context, session)
        Thread.sleep(8000)
        assertTrue(NoteDeliveryStore.ready(session))
        assertFalse("Disconnect device network before this explicit test", NoteDeliveryStore.accepted(session))
        report("offline_session", session.name)
        report("offline_pending", "OK snapshot retained without acceptance")
    }
}
