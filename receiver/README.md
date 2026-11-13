# 柒声可选接收端

Python 3.9 以上，仅标准库。此目录是可选示例，不是柒声使用或 Markdown 导出的前提。APK 不包含任何预设接收地址、令牌、Obsidian 数据库凭据或用户服务器信息。

## 选择 Mac 或服务器

- **Mac**：将 `QISHENG_INBOX` 指向自己 Obsidian 库内的接收文件夹。接收成功后，现有 Obsidian/LiveSync 负责后续同步。需要 Mac 开机、接收程序运行，外网访问还需要自己配置的 HTTPS 反向代理或安全隧道。
- **服务器**：默认仍只保存到指定目录；可另外启用[持久交付队列](DELIVERY.md)，由独立工作进程调用用户配置的 LiveSync CLI。不会自动接入现有库，也不保证“服务器收到即手机收到”。

App 的两种类型使用同一个协议，类型只显示对应说明。本版由用户点击“发送文字”触发发送，不在录音结束后静默上传。“同时导出音频 ZIP”默认关闭，勾选后在保存笔记时附加一个独立的纯音频 ZIP，接收端发送默认且仅包含 Markdown 文字。

## 启动

### 两种目标的实际配置量

柒声中的 Mac/服务器选择只改变说明文字，不会自动安装接收端、设置域名或接管同步。App 最终只需要自己的完整 HTTPS 接收地址和接收令牌。

| 环节 | Mac 直接接收 | 服务器接收并同步 |
|---|---|---|
| 接收程序 | Python 3.9+，本示例即可 | Python 3.9+，本示例即可 |
| 目标目录 | 自己库里的收集文件夹 | 服务器指定目录；它还不是手机上的 Obsidian 库 |
| 网络入口 | 外网使用需要可达的 HTTPS 入口，例如自建隧道或反向代理 | 域名、HTTPS、反向代理到本地端口 |
| 常驻方式 | 用户级启动任务，Mac 必须醒着并在线 | 系统服务或容器，自动启动与失败重启 |
| 后续同步 | Mac 上已有 Obsidian 同步程序运行并完成同步 | 必须另选同步器；LiveSync CLI 还需 Node.js 22.22+ 或其 Docker 镜像 |
| 凭据 | 接收令牌，库外的去重状态目录 | 接收令牌；若连接加密 LiveSync，还需同步和解密配置，不得交给录音笔 |
| 主要维护点 | 睡眠/关机、网络切换、文件夹访问权限、启动任务、HTTPS 入口 | 证书、服务、备份、队列与重试，以及同步器的版本和数据范围 |

仅接收 Markdown 不需要 GPU，也不运行语音识别或大模型。完整 CLI 需要持久化本地数据库，空间和资源不能只按新导入的几篇笔记估算；本项目没有测定它的最低硬件要求。

默认直接保存模式没有服务器离线队列或 Mac 自动拉取任务。Mac 不在线时，App 显示发送未确认，已有文字保留本机，需用户之后重试。可选队列模式通过 HTTP 202 确认已持久排队并完成备份；工作进程同步成功后，再次提交才返回已交付的重复回执。配置、恢复边界和独立台账见 [DELIVERY.md](DELIVERY.md)。

普通 CLI 的 put/push 先写本地数据库，之后还要完成远端同步。此前仅用合成笔记的隔离验证确认普通 sync 也会接收其他文件夹；它不是天然的“只写收集箱”权限接口。未验证范围隔离前，不应把默认 CLI 直接连到既有主库。服务器返回 saved 只确认接收目录写入，不确认手机收到。

接收 HTTPS 入口需要能由 App 的 Bearer 令牌直接调用，不应返回网页登录页或验证码。当前客户端不支持额外的 Cloudflare Access 服务令牌请求头；如有此要求，需单独适配，不能把同步数据库密码放进接收 URL。

先生成自己的令牌，保存在库外的私有目录，权限设为 600；不要提交 Git、放进库或粘贴到公共聊天。可用 Python 的 `secrets.token_urlsafe(32)` 生成。接收状态目录也应在库外，不能与 Markdown 目录混用。

设置以下环境变量后运行：

```sh
export QISHENG_INBOX='/absolute/path/to/your/vault/00.收集箱Inbox/07.柒声Qisheng'
export QISHENG_STATE='/absolute/path/outside/vault/qisheng-state'
export QISHENG_TOKEN_FILE='/absolute/path/outside/vault/qisheng-token'
python3 receiver/qisheng_receiver.py
```

默认只监听 `127.0.0.1:8769`。由你自己的 HTTPS 入口反向代理到该端口；柒声填写完整地址，如 `https://your-domain.example/v1/notes`，不能只填域名。保持证书验证，不直接将该明文端口开放到互联网。不需要让客户端保存整库的读写或解密凭据。

令牌至少 24 位可打印 ASCII 字符，不得有空白。收件目录中的笔记为 Markdown 明文，只保护指定文件夹；此程序不是端到端加密服务。

直接保存模式重启后重复发送同一录音仍可去重。若同一录音内容改变，或用户已修改、删除或移动生成的笔记，返回 409，不覆盖、不静默重建；请自行核对后决定如何处理。不要删除库外的 receipts.sqlite3，否则会丢失 ID 到文件名的去重记录。队列模式另用 delivery.sqlite3 保存来源、内容和交付状态，已交付记录不会因用户移动或删除笔记而被清除。

## 接收协议 v1

`POST /v1/notes`，HTTPS，`Content-Type: application/json; charset=utf-8`，`Authorization: Bearer <自己的令牌>`。接收端默认上限 4 MiB。

```json
{
  "schema": 1,
  "id": "device-uuid-session-id",
  "title": "会议备注名",
  "markdown": "# 会议\n\n已有总结和原文……\n",
  "sha256": "markdown UTF-8 字节的 SHA-256 小写十六进制"
}
```

服务端固定目标目录，不能让客户端指定任意路径。标题只用于安全文件名，不执行其中内容。成功回执必须包含匹配的 `id`、`sha256`：

- HTTP 201 + `status: saved`：文件已写入并同步到磁盘。
- HTTP 200 + `status: duplicate`：同一录音相同内容，且文件仍存在、未被修改。
- 可选队列模式返回 HTTP 202 + `status: queued`，App 只显示排队，不宣称已入库；已同步的相同记录返回 HTTP 200 + `duplicate`，不重新写入原文。
- HTTP 409：已有不同内容，未覆盖。其他非成功状态均不会被 App 宣称成功。

无凭据的重定向及带凭据跳转均不受支持；App 禁止自动重定向。不要在 URL 查询参数中放令牌。

## 验证

```sh
python3 -m unittest discover -s receiver -p 'test_*.py' -v
```

测试仅使用临时文件夹和本机环回 HTTP，涵盖鉴权、请求上限、路径输入、哈希、去重和用户编辑保护。没有写入或连接任何真实 Obsidian 库。
