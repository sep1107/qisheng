# 柒声可选接收端

Python 3.9 以上，仅标准库。此目录是可选示例，不是柒声使用或 Markdown 导出的前提。APK 不包含任何预设接收地址、令牌、Obsidian 数据库凭据或用户服务器信息。

## 选择 Mac 或服务器

- **Mac**：将 `QISHENG_INBOX` 指向自己 Obsidian 库内的接收文件夹。接收成功后，现有 Obsidian/LiveSync 负责后续同步。需要 Mac 开机、接收程序运行，外网访问还需要自己配置的 HTTPS 反向代理或安全隧道。
- **服务器**：接收程序写入服务器上的指定目录。要进入手机或电脑的 Obsidian，需用户另外准备同步链路。此示例不读取 LiveSync 凭据、不实现或启动 CLI，也不保证“服务器收到即手机收到”。

App 的两种类型使用同一个协议，类型只显示对应说明。本版由用户点击“发送文字”触发发送，不在录音结束后静默上传。“同时导出音频 ZIP”默认关闭，勾选后在保存/分享笔记时附加一个独立的纯音频 ZIP，接收端发送默认且仅包含 Markdown 文字。

## 启动

先生成自己的令牌，保存在库外的私有目录，权限设为 600；不要提交 Git、放进库或粘贴到公共聊天。可用 Python 的 `secrets.token_urlsafe(32)` 生成。接收状态目录也应在库外，不能与 Markdown 目录混用。

设置以下环境变量后运行：

```sh
export QISHENG_INBOX='/absolute/path/to/your/vault/00.收集箱Inbox/柒声'
export QISHENG_STATE='/absolute/path/outside/vault/qisheng-state'
export QISHENG_TOKEN_FILE='/absolute/path/outside/vault/qisheng-token'
python3 receiver/qisheng_receiver.py
```

默认只监听 `127.0.0.1:8769`。由你自己的 HTTPS 入口反向代理到该端口；柒声填写完整地址，如 `https://your-domain.example/v1/notes`，不能只填域名。保持证书验证，不直接将该明文端口开放到互联网。不需要让客户端保存整库的读写或解密凭据。

令牌至少 24 位可打印 ASCII 字符，不得有空白。收件目录中的笔记为 Markdown 明文，只保护指定文件夹；此程序不是端到端加密服务。

重启后重复发送同一录音仍可去重。若同一录音内容改变，或用户已修改、删除或移动生成的笔记，返回 409，不覆盖、不静默重建；请自行核对后决定如何处理。不要删除库外的 receipts.sqlite3，否则会丢失 ID 到文件名的去重记录。

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
- 自建异步接收器可返回 HTTP 202 + `status: queued`，App 只显示排队，不宣称已入库。示例接收器没有排队模式。
- HTTP 409：已有不同内容，未覆盖。其他非成功状态均不会被 App 宣称成功。

无凭据的重定向及带凭据跳转均不受支持；App 禁止自动重定向。不要在 URL 查询参数中放令牌。

## 验证

```sh
python3 -m unittest discover -s receiver -p 'test_*.py' -v
```

测试仅使用临时文件夹和本机环回 HTTP，涵盖鉴权、请求上限、路径输入、哈希、去重和用户编辑保护。没有写入或连接任何真实 Obsidian 库。
