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

当前可优先验证的组合：硅基流动星辰 ASR + 星辰文本总结；百炼作为国内备用、Groq 作为需代理的额外备用；均需先验证设备网络及账户限额。资料：[硅基流动价格](https://www.siliconflow.cn/pricing)、[Groq 限额](https://console.groq.com/docs/rate-limits)。实际免费资格、额度和付费切换均以各账号控制台为准。

## 百炼 Paraformer 国内备用（0.6.3）

支持 `paraformer-realtime-v2` 的 WebSocket 协议。填写完整地址 `wss://<WorkspaceId>.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference`、该业务空间的专用 Key 和模型 ID，即可放入转写接口槽位。Key 只授权此模型即可，不需要开通 OSS。

这里使用实时接口传输已经保存的 WAV 分段，界面仍是每两分钟分段转写，不提供逐字字幕。按 100 ms 音频帧发送，百炼备用时传输耗时约等于音频长度，可能晚于主接口出字；网络线程与录音线程独立。

北京地域该模型目前每月有 36,000 秒免费额度，须在百炼控制台确认并开启该模型的“免费额度用完即停”。它和 `paraformer-v2` 录音文件接口是不同模型，额度与开关须分别核对。免费耗尽、限流或网络失败时尝试下一个接口，全部不可用则保留音频。专用域名、账号凭据和实际余额不写入公开仓库。

参考：[官方 WebSocket 协议](https://help.aliyun.com/zh/model-studio/websocket-for-paraformer-real-time-service)、[模型价格与每月额度](https://help.aliyun.com/zh/model-studio/model-pricing)。

## 0.6.5：录音列表、历史管理、实时字幕与在线更新

- **系统录音**：首次授权录音目录；搜狗 Android 8.1 可选择内部存储，自动扫描年月目录，按录音文件名时间（缺失时按修改时间）倒序排列。支持刷新、更换目录和手动选文件，回到 App 时自动刷新。系统原文件不改动、不删除。
- **已导入标记**：根据来源 URI、大小及修改时间标记完整导入的文件，点已导入条目可查看历史。0.6.4 旧导入没有来源信息，无法自动标记。
- **自动总结**：从系统录音列表选择文件后，可勾选“转写完成后自动总结”。转写未全部成功时保留音频，补传后可在 AI 操作中手动总结。
- **录音历史**：可改备注名；删除须确认，柒声音频、转写和总结一起移入应用内回收站并可恢复。回收站仍占空间，本版不提供永久清空操作。
- **实时字幕**：麦克风录音时通过已启用的百炼 `paraformer-realtime-v2` 显示字幕预览，主界面可开关。录音先写盘，再非阻塞投递副本；缓冲满、网络失败或额度不可用则停止字幕，原录音和分段转写继续。
- 实时预览会额外消耗百炼免费时长，和完整分段转写是独立请求。字幕仅保留最近 3000 字，不代替完整逐字稿；完整结果以历史中保存的分段转写为准。实时连接失败后本次录音不自动重连。
- AI 操作、总结、翻译、纠正语法、执行、取消等菜单已中文化。

### 在线更新和签名

“检查更新”查询 `sep1107/qisheng` 的 GitHub 正式 Release。APK 须命名为 `qisheng-<版本号>.apk`，标签为 `v<主版本>.<次版本>.<修订号>`，版本编码规则如 `v0.6.5` 对应 605。资产必须提供 GitHub 的 SHA-256 `digest`。

下载直接复用 MIT 开源 [jenly1314/AppUpdater 2.2.0](https://github.com/jenly1314/AppUpdater)，不自动安装。柒声校验大小、SHA-256、包名、版本递增和与当前安装一致的签名后，才请求安卓安装器，最终由用户确认。需要时由用户授权安装来源。GitHub 无法访问时检查或下载会失败。

0.6.5 已按用户授权发布 [正式更新包](https://github.com/sep1107/qisheng/releases/tag/v0.6.5)，供用户自行覆盖安装试用；新功能尚未完成真机验收。覆盖安装保留现有配置，请勿卸载旧版或清除应用数据。CI 仅验证构建，不发布使用临时调试签名的 APK。后续更新须保留首次安装的签名密钥，不能换用 CI 自动生成的另一把调试密钥。

## 长录音边界

- 没有 15 分钟的应用录音上限；不等于无限资源。WAV 每小时约 115 MB，空间低于 16 MB 时停止并保留已写入音频。
- 是「每两分钟分段转写」，实时字幕是独立预览。分段没有重叠，切分边界附近的单词可能影响识别。
- 一次失败后暂停本轮后续上传，录音继续；联网后手动补传。成功分段无需重新识别。
- 音频直接写入私有目录 `files/recordings/<会话时间>/`，不放临时缓存、不自动删除。进程被杀不会自动重新开启麦克风；再次打开历史可修复中断分段并补传。没有写入存储的尾部数据无法恢复。
- 默认保留音频、转写和摘要。已增加回收站式删除，尚未增加导出和回放界面；卸载会删除私有记录。备份应用私有目录后再卸载。
- 云端处理会把音频或文字发送给你配置的服务商；Key 只保存在设备应用私有配置，不提交到仓库。系统云备份已关闭。
- 点顶部“导入”选择系统录音，或从其他 App 分享音频到柒声；导入音轨在本机解码并转为 16 kHz 单声道 PCM，每两分钟保存一段，导入全部完成后自动转写。原文件不修改。
- WAV、MP3、M4A/AAC 等格式依赖设备系统解码器；不支持的编码会保留已写入部分并提示重新导入，不会把未完整导入的内容拿去总结。长音频导入会占用额外空间（约 115 MB/小时）和解码时间。
- 导入/转写由前台服务执行，可切后台；导入被中断后须重新选择原文件，不支持从中断位置续解码。导入完整、转写失败的会话可只补传未完成段。
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

应用图标沿用小肥鱼角色的淡蓝米白水彩画风，加入麦克风；原图与生成说明见 [artwork](artwork/README.md)。Android 8 以上提供自适应图标。
