# 柒声 QiSheng（Android 7.0+）

基于 [eja/vox](https://github.com/eja/vox) 的 GPL-3.0 衍生项目，针对低内存安卓录音设备增加长时间录音。上游版权与许可证保持不变。柒声的长录音改动与品牌由本仓库维护；本项目不代表上游官方版本。

## 使用

1. 安装柒声 APK（包名 `io.github.sep1107.qisheng`，与上游 Vox 可并存）。
2. 设置 ASR：完整 HTTPS `/v1/audio/transcriptions` 地址、自己的 Key 和模型 ID。硅基流动可使用 `https://api.siliconflow.cn/v1/audio/transcriptions` 与 `XingChenAGI/XingChenASR-V3.2-Ultra`；是否可用以账号权限及接口实测为准。
3. 设置 LLM：完整 HTTPS `/chat/completions` 地址、自己的 Key、模型和输出语言。没有内置密钥，也不需要柒声账号。
4. 点麦克风开始，授予录音权限。通知栏可停止；锁屏和切后台由前台服务与唤醒锁维持录音。
5. 每两分钟保存一个 16 kHz、16-bit、单声道 WAV（3.84 MB），依次上传；停止时保存最后不足两分钟的片段。
6. 网络或服务失败不丢弃录音。进入「录音历史」选择会话，点「补传未完成分段」。只有成功落盘的分段文本才跳过重传；请求成功但进程在保存前退出时，该段可能再次计费。
7. 全部分段转写完成后，点左上 AI 按钮 → Summary。长文本按块总结后合并，原转写与音频保留，摘要存入会话目录。

## 多接口与免费额度（0.6.2）

- 转写和总结各有 3 个独立接口槽，按 1 → 2 → 3 顺序尝试。每槽单独配置地址、Key、模型和启用开关；音频/文字仅发送到已启用且符合当前模式的接口。
- 默认开启「仅使用我确认仍有免费额度的接口」。各槽默认未标记免费，必须先到服务商控制台确认额度再手动勾选；不会因为保存了 Key 就自动认为免费。关闭此模式后可能使用付费接口。
- 免费试用可填写截止日期（UTC、包含当天），到期或日期格式无效会在免费模式下自动跳过，不等服务商扣费后才切换。留空表示未设置到期保护。
- 可选本机每日请求上限，按 UTC 日期重置，发出请求前计数（失败和超时也计入）。这是本机的请求次数保护，不是账号的剩余分钟数/余额，不统计其他客户端的用量，也不保证请求免费。
- HTTP 429 尊重 `Retry-After`（至少冷却 60 秒）；402 冷却至少 6 小时；401/403 至少冷却 60 秒，留出修正密钥的时间；5xx 与网络失败短暂冷却。相同主机下的 Key 和模型共享冷却，不通过换 Key 绕过同一服务商限制。冷却跨应用重启保留。
- HTTP 400/413、响应格式错误等配置/输入问题直接停止该次处理，不向其他平台重复发送错误请求。
- 每次依次调用，最多尝试各槽一次，不并发、不无限重试。全部失败则保留音频并暂停本轮上传，后续在历史中补传。
- 超时可能发生在服务端已经完成之后，切换或补传可能重复计费；柒声无法取消服务商计费。请在服务商设置消费上限、关闭自动充值。免费政策会变，不承诺永久免费，不自动注册账号。
- 转写使用 OpenAI 兼容 `audio/transcriptions`，总结使用 `chat/completions`。只配置能提供对应接口的模型；百炼 `qwen3-asr-flash` 的聊天式音频接口不能直接填写为转写接口。

当前可优先验证的组合：硅基流动星辰 ASR + 星辰文本总结；Groq 作为转写备用需先验证设备网络及账户限额。资料：[硅基流动价格](https://www.siliconflow.cn/pricing)、[Groq 限额](https://console.groq.com/docs/rate-limits)。实际免费资格、额度和付费切换均以各账号控制台为准。

## 长录音边界

- 没有 15 分钟的应用录音上限；不等于无限资源。WAV 每小时约 115 MB，空间低于 16 MB 时停止并保留已写入音频。
- 是「每两分钟分段转写」，不是逐字实时字幕。分段没有重叠，切分边界附近的单词可能影响识别。
- 一次失败后暂停本轮后续上传，录音继续；联网后手动补传。成功分段无需重新识别。
- 音频直接写入私有目录 `files/recordings/<会话时间>/`，不放临时缓存、不自动删除。进程被杀不会自动重新开启麦克风；再次打开历史可修复中断分段并补传。没有写入存储的尾部数据无法恢复。
- 默认保留音频、转写和摘要。尚未增加导出、回放和删除界面；卸载会删除私有记录。备份应用私有目录后再卸载。
- 云端处理会把音频或文字发送给你配置的服务商；Key 只保存在设备应用私有配置，不提交到仓库。系统云备份已关闭。
- 保留上游分享音频导入功能，但导入文件仍整段上传；自动分段仅适用于本应用录音。
- 安卓厂商的后台限制、断电或强制停止仍可能打断录音。先在目标设备验证，再用于重要会议。

## 构建与验证

需要 JDK 17、Android SDK 36，执行：

```sh
./gradlew testDebugUnitTest assembleDebug lintDebug
```

APK：`app/build/outputs/apk/debug/app-debug.apk`（开发签名）。当前不创建 Tag 或 Release；公开源码仓库为 [sep1107/qisheng](https://github.com/sep1107/qisheng)。
单元测试覆盖中断 WAV 修复、分段顺序、补传判定、单段大小和长总结输入不丢失。真机验证记录见仓库 `VALIDATION.md`。

---

## 上游说明（以下为原项目文档，商店及发布链接指向上游 Vox）





**Vox** is a highly configurable Android application designed for seamless audio recording, speech-to-text transcription, and advanced AI-driven text processing. By leveraging external Automatic Speech Recognition (ASR) and Large Language Model (LLM) APIs, Vox empowers users to transcribe voice memos and immediately process the text for summarization, translation, grammatical correction, and custom contextual prompts.

## Features

* **High-Fidelity Audio Recording**: Captures audio in 16kHz, 16-bit PCM WAV format to ensure optimal compatibility with modern ASR engines.
* **Customizable ASR Integration**: Interfaces with OpenAI's Whisper API by default, but can be configured to communicate with any compatible external or self-hosted ASR endpoint.
* **AI Text Processing**: Integrated LLM actions allow users to seamlessly:
  * Summarize transcriptions.
  * Translate text into specified target languages.
  * Correct grammar and spelling.
  * Execute custom user-defined prompts against the transcribed text.
* **Intent Sharing Support**: Accepts audio files shared from other applications via the native Android sharing menu (`Intent.ACTION_SEND`), processing them instantly.
* **Markdown Rendering**: Formats and presents LLM responses using built-in HTML/Markdown rendering for high readability.
* **Complete Data Control**: Users can define their own API URLs, authentication tokens, and model identifiers directly within the application settings.

## Installation

Vox is available for download across the following platforms:

* **Google Play Store**: [Download Vox on Google Play](https://play.google.com/store/apps/details?id=it.eja.vox)
* **GitHub Releases**: Pre-compiled APK files for the latest versions can be found on the [Releases page](https://github.com/eja/vox/releases).

## Configuration and Usage

Upon launching Vox for the first time, you will need to configure your API credentials. 

1. Tap the **Settings** (gear) icon in the top right corner.
2. Under the **ASR** section:
   * **API URL**: Enter your transcription endpoint (e.g., `https://api.openai.com/v1/audio/transcriptions`).
   * **Auth Token**: Enter your API Bearer token.
   * **Model**: Specify the model name (e.g., `whisper-1`).
3. Under the **LLM** section:
   * **API URL**: Enter your LLM chat completion endpoint (e.g., `https://api.openai.com/v1/chat/completions`).
   * **Auth Token**: Enter your API Bearer token.
   * **Model**: Specify the text model name (e.g., `gpt-4o` or `gpt-3.5-turbo`).
   * **Language**: Specify your preferred default language for translations and summarizations.
4. Tap **Save**.

### Recording and Processing

* Tap the **Microphone** icon to begin recording. You will be prompted to grant audio recording permissions upon first use.
* Tap the **Stop** icon to end the recording. The app will automatically upload the audio to your configured ASR endpoint.
* Once the transcription is complete, tap the **AI** icon (located in the top left corner of the app bar) to open the LLM action menu and select your desired text processing operation.

## Permissions

* `RECORD_AUDIO`: Required to capture voice input via the device microphone.
* `INTERNET`: Required to communicate with the configured external APIs.

## Building from Source

To build the project locally:

1. Clone the repository:
   ```bash
   git clone https://github.com/eja/vox.git
   ```
2. Open the project in Android Studio.
3. Sync the Gradle files to download dependencies (Jetpack Compose, OkHttp, CommonMark, etc.).
4. Build and deploy to your emulator or physical device.
