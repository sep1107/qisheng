# 验证记录

日期：2026-11-11。范围：Vox 长录音分支，目标 Android 8.1 / 2 GB 搜狗 S1 AI Recorder。

## 自动验证

- `RecordingStoreTest`：中断 WAV 修复（包括半个采样）、分段顺序、成功分段跳过、文件保留、分段大小、长总结输入完整性，以及音频缓冲跨分段时逐字节无丢失/重复。
- `testDebugUnitTest assembleDebug lintDebug`：成功；5 项单元测试通过，0 失败。
- Android lint：0 errors、17 warnings，主要为上游依赖版本、图标目录和风格建议；保留 Android 8.1 兼容实现。系统备份在目标 Android 8.1 上关闭，Android 12+ 的设备迁移策略未另行定制。
- 16 分 1 秒等量 PCM 数据模拟：生成 8 个完整两分钟 WAV 和 1 个一秒尾段，数据长度完全一致。此项不是按真实时间运行的锁屏录音测试。
- APK：`app/build/outputs/apk/debug/app-debug.apk`，约 17 MB，`it.eja.vox.longrecord` / `0.6.0-longrecord` / minSdk 24。
- APK SHA-256：`3ce7feac4bddfd0cf953843bb15534da7ddb07af755fa05ce3c3119549263834`。

## API 验证（合成内容）

- 硅基流动 `XingChenAGI/XingChenASR-V3.2-Ultra`：16 kHz 单声道 PCM WAV 返回正确的中文识别文本。
- 硅基流动 `XingChenAGI/Xing4.0-29B`：OpenAI 兼容 chat/completions 返回中文总结及待办。测试内容为人工合成会议语句，不使用用户录音。
- 这些验证确认服务及音频格式可用，尚不代表安卓应用端到端验收通过。

## 真机验收

录音笔目前不在 ADB 设备列表中。以下均未完成，不能声称真机长录音已通过：

- 安装、启动、麦克风权限与首次录音。
- 超过 15 分钟的连续录音，锁屏及切后台不中断；逐段核对 WAV 时长与采样总数。
- 中断进程后历史恢复和失败补传，成功分段不重新调用。
- 安卓设备上完成在线转写与总结，并重启核对持久保存。

## 已知边界

- 无逐字实时字幕；每两分钟出一个分段。
- 无采样重叠；边界词可能影响识别。
- 断电时尚未写入磁盘的尾部不可恢复。
- 超时或进程在服务端成功、客户端保存前退出时，补传可能重复计费。
- 录音原文件保存在私有目录，未新增回放/导出/删除界面。
