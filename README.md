# 柒声 QiSheng（Android 7.0+）

基于 [eja/vox](https://github.com/eja/vox) 的 GPL-3.0 衍生项目，针对低内存安卓录音设备增加长时间录音。上游版权与许可证保持不变。柒声的长录音改动与品牌由本仓库维护；本项目不代表上游官方版本。

## 使用

1. 安装柒声 APK（包名 `io.github.sep1107.qisheng`，与上游 Vox 可并存）。
2. 设置 ASR：完整 HTTPS `/v1/audio/transcriptions` 地址、自己的 Key 和模型 ID。硅基流动可使用 `https://api.siliconflow.cn/v1/audio/transcriptions` 与 `XingChenAGI/XingChenASR-V3.2-Ultra`；是否可用以账号权限及接口实测为准。
3. 设置 LLM：完整 HTTPS `/chat/completions` 地址、自己的 Key、模型和输出语言。没有内置密钥，也不需要柒声账号。
4. 点麦克风开始，授予录音权限。通知栏可停止；锁屏和切后台由前台服务与唤醒锁维持录音。
5. 每两分钟保存一个 16 kHz、16-bit、单声道 WAV（3.84 MB），依次上传；停止时保存最后不足两分钟的片段。
6. 网络或服务失败不丢弃录音。进入「录音历史」选择会话，点「补传未完成分段」。只有成功落盘的分段文本才跳过重传；请求成功但进程在保存前退出时，该段可能再次计费。
7. 全部分段转写完成后，点左上 AI 按钮 → 生成总结（已有总结时直接查看缓存）。长文本按块总结后合并，原转写与音频保留，摘要存入会话目录。

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
- **录音历史**：可改备注名；删除须确认，柒声音频、转写和总结一起移入应用内回收站并可恢复。回收站支持逐条或全部彻底清除，需再次确认且不可恢复。
- **实时字幕**：默认开启，在设置中可关闭；麦克风录音时通过已启用的百炼 `paraformer-realtime-v2` 显示字幕预览。录音先写盘，再非阻塞投递副本；缓冲满、网络失败或额度不可用则停止字幕，原录音和分段转写继续。
- 实时预览会额外消耗百炼免费时长，和完整分段转写是独立请求。字幕仅保留最近 3000 字，不代替完整逐字稿；完整结果以历史中保存的分段转写为准。实时连接失败后本次录音不自动重连。
- AI 操作、总结、翻译、纠正语法、执行、取消等菜单已中文化。

### 在线更新和签名

在设置中点“检查更新”，查询 `sep1107/qisheng` 的 GitHub 正式 Release。APK 须命名为 `qisheng-<版本号>.apk`，标签为 `v<主版本>.<次版本>.<修订号>`，版本编码规则如 `v0.6.9` 对应 609。资产必须提供 GitHub 的 SHA-256 `digest`。

下载直接复用 MIT 开源 [jenly1314/AppUpdater 2.2.0](https://github.com/jenly1314/AppUpdater)，不自动安装。柒声校验大小、SHA-256、包名、版本递增和与当前安装一致的签名后，才请求安卓安装器，最终由用户确认。需要时由用户授权安装来源。GitHub 无法访问时检查或下载会失败。

[0.6.10 更新包](https://github.com/sep1107/qisheng/releases/tag/v0.6.10) 沿用现有安装签名，供覆盖安装试用；本版新界面尚未完成真机验收。覆盖安装保留现有 API 配置与历史，请勿卸载旧版或清除应用数据。CI 仅验证构建，不发布使用临时调试签名的 APK。后续更新须保留首次安装的签名密钥，不能换用 CI 自动生成的另一把调试密钥。

## 长录音边界

- 没有 15 分钟的应用录音上限；不等于无限资源。WAV 每小时约 115 MB，空间低于 16 MB 时停止并保留已写入音频。
- 是「每两分钟分段转写」，实时字幕是独立预览。分段没有重叠，切分边界附近的单词可能影响识别。
- 一次失败后暂停本轮后续上传，录音继续；联网后手动补传。成功分段无需重新识别。
- 音频直接写入私有目录 `files/recordings/<会话时间>/`，不放临时缓存、不自动删除。进程被杀不会自动重新开启麦克风；再次打开历史可修复中断分段并补传。没有写入存储的尾部数据无法恢复。
- 默认保留音频、转写和摘要。支持回收站式删除、Markdown 与可选纯音频 ZIP 导出，尚未增加音频回放；卸载会删除私有记录。备份后再卸载。
- 云端处理会把音频或文字发送给你配置的服务商；Key 只保存在设备应用私有配置，不提交到仓库。系统云备份已关闭。
- 点顶部“系统录音”选择文件，或从其他 App 分享音频到柒声；导入音轨在本机解码并转为 16 kHz 单声道 PCM，每两分钟保存一段，导入全部完成后自动转写。原文件不修改。
- WAV、MP3、M4A/AAC 等格式依赖设备系统解码器；不支持的编码会保留已写入部分并提示重新导入，不会把未完整导入的内容拿去总结。长音频导入会占用额外空间（约 115 MB/小时）和解码时间。
- 导入/转写由前台服务执行，可切后台；导入被中断后须重新选择原文件，不支持从中断位置续解码。导入完整、转写失败的会话可只补传未完成段。
- 安卓厂商的后台限制、断电或强制停止仍可能打断录音。先在目标设备验证，再用于重要会议。

## 构建与验证

需要 JDK 17、Android SDK 36，执行：

```sh
./gradlew testDebugUnitTest assembleDebug lintDebug
```

本地构建 APK：`app/build/outputs/apk/debug/app-debug.apk`。正式更新资产命名为 `qisheng-0.6.10.apk`，须使用与现有安装相同的签名；公开源码仓库为 [sep1107/qisheng](https://github.com/sep1107/qisheng)。
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

## 0.6.6 修复

空转写不再计为完成，也不会只凭时间戳调用总结；旧版空分段会重新列为待补传。历史和完成页面同时显示转写原文与总结。回收站增加逐条彻底清除和一键清空，确认后永久删除应用内副本，不删除系统原录音；覆盖更新保留 API 配置。

## 0.6.7 空结果备用切换

主接口返回空文字时，按现有免费额度和冷却规则尝试下一个接口；不把空文字作为网络故障封禁整个平台，也不保存为空结果。所有接口仍为空时保留音频待补传。

## 0.6.8：处理进度与总结复用

录音结束后显示已完成/总分段数、当前分段、接口和处理阶段。上传进度按实际发送的音频字节计，100% 仅代表本段音频发送完毕，随后仍等待识别；接口未提供服务端识别百分比，使用等待指示。百炼按近实时速度传输，较长等待不代表录音丢失。

只有存在未完成分段时显示补传按钮。原文与已保存总结可以直接切换；已有总结时“总结”读取本地文件、不调用 API。“重新生成总结”单独标明消耗额度并二次确认，成功后替换，失败保留原总结。历史记录也复用已保存总结。

## 0.6.9：保存笔记、可选音频与自己的接收端

“检查更新”和“实时字幕”开关移入设置，实时字幕默认开启；已有手动关闭的选择继续保留。主界面移除这两个操作入口。

选中录音历史后，在正文上方点「保存笔记」打开笔记与发送窗口。本机录音和系统录音导入后的会话都支持；可以直接发送已有文字，不需要先另存文件。

- 默认保存标准 UTF-8 Markdown：备注名、会话时间、已有总结、分段原文。尚未完成转写或导入时标明不完整，不重新调用 AI。
- 界面只提供「保存笔记」；音频 ZIP 选项默认不勾选。勾选后保存到新导出目录，生成两个独立文件；ZIP 内只有 `*.wav` 音频分段，没有笔记、总结或其他文本。音频是柒声保存的 16 kHz 单声道 WAV 分段，不是系统录音机的原始压缩文件；原文件不改动。
- 保存使用安卓系统文件选择器。如需分享，可在文件管理器选择已保存的 Markdown 或音频 ZIP，再通过系统分享交给其他 App。
- 可选接收配置默认关闭，没有内置服务器或令牌。可选 Mac/服务器，填自己的完整 HTTPS 接收地址和令牌。用户点击发送并确认目的地后，只发送已有 Markdown，不发送音频、不调用 AI、不携带 ASR/LLM 密钥。
- Mac 模式需要 Mac 在线并运行接收端；服务器模式需要用户自行准备后续同步。不会自动配置、接入或读取任何现有 LiveSync 库。接收端「保存成功」不等于手机已同步。

可直接复用的 Python 标准库接收示例和协议见 [receiver/README.md](receiver/README.md)。默认直接保存模式支持持久去重，遇到已编辑、移动、删除或同 ID 不同内容时返回冲突，不覆盖用户笔记。另有[可选持久交付队列](receiver/DELIVERY.md)：柒声与小红书按来源隔离记录、各自导出台账，共用 CLI 同步工作进程；备份成功后确认排队，断网保留状态，已同步记录不重复写回。接收令牌与数据库同步密钥分开，接收端凭据由每位用户自行配置。未自动部署或切换任何现有同步。

## 0.6.10：自动发送与紧凑阅读

原文和总结在同一个滚动页面连续显示，不再来回切换。「保存笔记」位于原查看原文的位置，不额外占用一排工具栏；打开后可以直接发送已有文字，无需先另存文件。

发送进度与结果固定在弹窗标题下方，不随表单滚动。发送中显示等待进度，匹配回执后显示「发送成功」并区分排队或已保存；失败、回执未确认及忙碌阻止操作时显示明显提示。进度条表示正在发送或等待回执，不代表 Obsidian 同步百分比。

在设置中统一填写接收端类型、HTTPS 地址与令牌，旧配置自动沿用。可开启「总结完成后自动发送」（默认关闭）：

- 开启后新完成的总结连同原文一起发送；不追溯旧总结，不发送音频，不自动调用 AI 生成总结。
- 本机录音手动生成总结后，以及系统录音勾选自动总结并成功完成后，都触发同一发送流程。已有旧记录可手动发送。
- 后台调度复用 [Android 官方 WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)，使用联网约束、唯一任务和指数退避。录音或转写期间暂缓；网络、超时、429 和服务端临时错误可重试，配置错误或内容冲突需要核对后重试。系统省电可能推迟执行；被用户强制停止后需重新打开 App。
- 发送前冻结本条正文和目标地址，重试不会改写已排队内容。已接收记录不自动覆盖；重新生成总结或改换地址后需人工核对。修改同一地址的令牌并保存设置，可以重试未完成的记录。
- 关闭自动发送会取消等待任务，已经在途的请求可能完成。录音移入回收站后，尚未开始的任务跳过；待发副本随录音移动和清除，不另留库外正文副本。
- 自动发送的等待、重试和结果显示在当前记录正文上方；重新打开记录仍可查看。
