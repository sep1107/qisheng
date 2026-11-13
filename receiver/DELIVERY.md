# 可选持久交付：柒声与小红书

Python 3.9+、Unix/Linux（使用 flock），仅标准库。它是现有接收协议的可选服务器后端，不改变 APK 内 API、保存笔记或录音逻辑。默认接收器仍为直接保存模式。

## 记录分工

- SQLite `delivery.sqlite3` 是交付状态的事实源：以 `(source,id)` 为主键，保存 SHA-256、正文、固定目标路径、状态、时间和同步尝试次数。柒声使用设备会话 ID，小红书使用完整 24 位 noteId；不同来源不会串号。
- 柒声交付台账与小红书交付台账分别从 SQLite 导出，方便查看，不手工回写。它们不等于库内实际内容清单，也不代表手机已经收到。
- 原有小红书剪藏台账依旧由 Mac 按库内笔记重算。它反映“库里有什么”，交付记录反映“发过什么”；二者不能相互替代。笔记移动或改名不改变 noteId，更换传输路径不需要废弃原台账。

## 状态与重试

`pending`（持久排队）→ CLI `put` → `staged`（本地 CLI 已写入）→ CLI `sync` → `synced`（远端同步成功）。每个阶段的记录提交后，都完成 SQLite 在线备份与完整性检查；首次 HTTP 202 在备份成功后才返回。备份失败返回 503，提交可能已经保留在队列中，重试相同 ID 可修复备份，不生成副本。

同 ID 同内容返回当前状态；同 ID 不同内容返回 409。工作进程通过独立工作锁串行操作同一 CLI 数据目录，网络等待期间释放队列写入锁，接收器仍可接受并备份新笔记，新笔记留待下一轮处理。恢复 `staged` 记录时只重新同步，不重新 `put`，避免回执丢失后覆盖用户编辑。CLI 列表里缺少待同步路径时停止，不盲目补写或误报已交付；缓存丢失、未确认同步后的移删需要人工对账。

`synced` 记录永久用于去重，不因文件删除、移动或改名而消失。若要更新同一录音的导出内容，需人工处理冲突；本版没有自动覆盖或版本合并。

## 启用前的隔离边界

先用新建的测试库、合成笔记和独立 CLI 数据目录。不要拿正式 LiveSync 设置测试，不要和 daemon、桌面同步进程共用同一个 CLI 本地数据库。

普通 CLI 是双向客户端，不是仅写入收集箱的受限接口。正式连接既有库前必须单独核验读取范围；客户端排除目录不是账号级权限隔离。不得先复制整个库再过滤受限内容。

## 接收与工作进程

队列模式不需要 `QISHENG_INBOX`，接收目录在代码 `FOLDERS` 中按来源固定，客户端不能指定任意路径。柒声新记录进入 `00.收集箱Inbox/07.柒声Qisheng/`，小红书仍进入原目录；已有交付记录保留原路径，不自动迁移或重新投递。

```sh
export QISHENG_STATE='/srv/my-note-delivery/state'
export QISHENG_DELIVERY_BACKUP='/mnt/my-backups/note-delivery'
export QISHENG_TOKEN_FILE='/srv/my-note-delivery/receiver-token'
python3 receiver/qisheng_receiver.py
```

仍只监听环回地址；外部 App 通过自己配置的 HTTPS 反向代理访问。录音笔只拿接收令牌，不拿同步凭据。不要在未配置持久备份存储时把此示例当作正式服务。

创建一个库外、权限 600 的 JSON 文件（如 `/srv/my-note-delivery/cli-command.json`），内容为管理员配置的 CLI argv 前缀，例如原生安装时：

```json
["/absolute/path/to/livesync-cli", "/srv/my-livesync/client", "--settings", "/srv/my-livesync/settings.json"]
```

若使用 Docker，前缀包括 `docker run --rm -i`、持久数据挂载、测试网络和 `--settings /data/settings.json`。参数直接传给 subprocess，不经 shell；不要把数据库密码写进命令参数或 URL。此文件是可信管理员配置，不能由 HTTP 请求提供。

每次执行进行一轮工作；失败保留队列并非零退出，下次执行继续：

```sh
python3 receiver/delivery_queue.py \
  --state '/srv/my-note-delivery/state' \
  --backup '/mnt/my-backups/note-delivery' \
  deliver --cli-command-file '/srv/my-note-delivery/cli-command.json'
```

需要无人值守重试时，将上述命令配置为 systemd oneshot，用 timer 的 `OnBootSec=30s`、`OnUnitInactiveSec=30s` 串行调度，接收器另作为常驻服务。应设置服务用户、私有目录权限和启动限制。当前只完成隔离测试，**没有安装或启用正式服务/定时器**，不会替代或停止现有 Mac 同步。

## 小红书接入

`RednoteFavHarvest/server-pipeline/deliver_notes.py` 复用同一个 QueueStore，只读取指定目录与剪藏完成清单；校验完整 noteId 和 frontmatter source 一致，再入队。它不采集账号、不访问浏览器、不移动或删除原产物、不更新 clipped.jsonl，不改变现有 Mac 拉取脚本。

```sh
python3 /path/to/RednoteFavHarvest/server-pipeline/deliver_notes.py \
  --receiver-code /path/to/qisheng/receiver \
  --state /srv/my-note-delivery/state \
  --backup /mnt/my-backups/note-delivery \
  --notes /path/to/isolated-xhs/out/notes \
  --manifest /path/to/isolated-xhs/out/clipped.jsonl
```

本轮仅接合成完成清单。正式切换前需从原台账、实际笔记和已有弃用名单核验历史来源 ID，建立交付基线，再决定从哪批新内容切换；不能把多年旧产物无差别重新排队。对同一正式目的地同时开启两套写入会造成重复，因此并行验证期间新链路只指向测试库，原链路照常写正式库。

## 各自的 Markdown 台账

```sh
python3 receiver/delivery_queue.py --state /srv/my-note-delivery/state \
  --backup /mnt/my-backups/note-delivery ledger qisheng > /your/export/柒声交付台账.md
python3 receiver/delivery_queue.py --state /srv/my-note-delivery/state \
  --backup /mnt/my-backups/note-delivery ledger rednote > /your/export/小红书交付台账.md
```

导出位置由管理员明确选择；不会自动将台账写进现有库或 App 界面。不要把导出台账当作可恢复队列，正文、哈希和确认状态以 SQLite 为准。

## 备份与恢复

完整 `.sqlite3` 快照通过 SQLite backup API 创建、完整性检查、文件 fsync 和目录 fsync 后才可用；`.partial` 不可用于恢复。状态目录和备份目录均为 700，数据库与快照为 600。内容没有变化时复用已检查的快照，避免重复提交或空轮询不断产生副本。

备份包含笔记明文与历史交付记录，不包含接收令牌或 LiveSync 配置。`--backup` 必须是状态目录之外的独立目录；同盘目录只能防逻辑损坏，不能防服务器/磁盘丢失。正式使用应指向独立存储，并对离机备份加密。当前隔离测试会另存一份合成快照到 Mac 并核验，不会上传真实内容。

快照是完整副本，实际状态变化仍会增加空间；本版不自动删除任何历史备份。正式上线前需确认磁盘容量、离机目标和保留周期，使用备份存储自身的生命周期策略。没有设置定时备份、自动清理或云存储账号。

恢复操作应先停接收器与工作进程，保留损坏原件，在**新建状态目录**中放入选定快照并命名 `delivery.sqlite3`，先跑 `PRAGMA integrity_check`。配合原 CLI 数据目录核验，再启动。发现备份目录有快照、状态库却缺失时，程序拒绝自动创建空库，防止丢失历史去重记录。

不要直接回滚到旧快照继续生产：旧快照之后的接收与同步可能已成功，回滚会丢失这些凭证。需对照较新快照、接收方和 CLI 数据完成对账；不能以“找不到旧路径”为由重放已发正文。恢复 `staged` 状态且 CLI 缓存缺失时同样需要对账，不会自动覆盖文件。

## 自动验证

```sh
python3 -m unittest discover -s receiver -p 'test_*.py' -v
```

显式选择的新 `/tmp` 目录才可运行 Docker 端到端测试。测试使用合成账号、合成笔记、新内部网络，无公开数据库端口，结束停止测试 CouchDB，保留数据供核验：

```sh
python3 receiver/evaluate_livesync.py \
  --root /tmp/my-new-isolated-delivery-test \
  --adapter /path/to/RednoteFavHarvest/server-pipeline/deliver_notes.py
```

集成测试包括 HTTP 接收、实际小红书适配器、真实双 CLI 加密同步、断网、进程重启、回执丢失后用户编辑、移动/删除后重试、备份故障与恢复，以及 CLI 缓存丢失保护。它不等于录音笔 APK、外网 HTTPS 或手机 Obsidian 真机验收。
