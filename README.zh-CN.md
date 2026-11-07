# Muse Daily Recorder

[English](README.md) · 简体中文 · [日本語](README.ja.md) · [한국어](README.ko.md) · [Español](README.es.md) · [Français](README.fr.md) · [Deutsch](README.de.md) · [Português](README.pt.md) · [Русский](README.ru.md)

**当前版本：v1.14.0**

一个为全天候日常录音而生的 Android 配套 App。手机随身带，它就是你的录音笔：录音自动分段、自动备份到你自己的云存储，再配上事件标记、转写和每日总结，录完就能查、能找——不用导线，不怕丢文件。

Muse 本身没有持续监听麦克风的能力，所以这套系统由三部分组成：

1. **Android App**（`MuseRecorder.apk`）：前台服务录音，每 60 分钟自动切一段，每段录完自动上传到你自己的 S3 兼容存储。转写和总结在 Muse 这边完成。
2. **事件标记**：录音时随手标记重要时刻，标记会和录音一起传到桶里。
3. **接收端 + 每日总结**：`receiver/` 里的脚本从桶里拉取一天的录音和标记，转写后把分段转写、分析和每日总结存进 Supabase。

## 功能

- **分段录音 + 自动上传**——每 60 分钟一段 .m4a（约 21 MB），自动上传到 S3 兼容对象存储（Cloudflare R2、Backblaze B2、阿里云 OSS、腾讯云 COS、AWS S3、七牛云 Kodo，或任意自定义 Endpoint，App 里都有预置）。失败自动排队重试，待上传列表随时可查。
- **事件标记**——「对话」和「会议」两个开关框住重要时段，日报会按标记分段整理。
- **按住自述**——想到什么就按住按钮说一句：录音中是标记这一刻，空闲时则单独录一条语音笔记。
- **自动快照（可选，默认关）**——把手机从平放抬到竖直，自动悄悄拍一张（后摄、前摄或双摄）。录音运行期间，熄屏也能拍。摄像头选择改完即时生效。
- **云端页**——按日期浏览你的桶：存储用量卡（对照 R2 免费档 10 GB）、每天的照片/录音计数、照片缩略图宫格、点开即播的语音列表。
- **日报页**——来自 Supabase 的每日总结，外加每天一张「活动图」：把当天最清晰的快照按活动时段拼成一张约 1 MB 的图，每段配一句说明。
- **深色模式**——跟随系统 / 始终浅色 / 始终深色（配置 → 语言与系统）。
- **9 种语言界面**——英文、简体中文、日文、韩文、西班牙文、法文、德文、葡萄牙文、俄文，切换即时生效。App 名称在所有语言下都是 "Muse Daily Recorder"。

## 设计

设计版本二「云白极简」——干净的卡片、单一蓝色点缀、底部标签栏：

![设计 v2 浅色](docs/images/design-v2-light.png)

![设计 v2 深色](docs/images/design-v2-dark.png)

同一套设计的浅色与深色两版。注意：录音页用的是图中这套经典紧凑布局（录音按钮与波形同一行、底部固定卡片）——早先的高卡片改版太占竖向空间，已在 v1.14.0 改回。

## 快速开始

### 1. 准备一个 S3 桶（以 Cloudflare R2 为例）

1. 在 cloudflare.com 注册并开通 R2（免费档 10 GB、下载免费；开通可能要求绑卡，免费额度内不扣费）。创建一个 bucket，例如 `recordings`。
2. 在「管理 R2 API 令牌」里**创建一个只对这个桶有读写权限的令牌**（Object Read & Write，指定桶），记下 Access Key ID、Secret Access Key 和你的 Account ID（Endpoint 是 `https://<AccountID>.r2.cloudflarestorage.com`）。
3. App 里预置选 **Cloudflare R2**，把 Endpoint 里的 `<AccountID>` 换成真实 ID，填桶名和两个 Key，保存。

其他厂商同理（App 里都有预置）。阿里云/腾讯云/AWS/七牛需要勾「虚拟主机风格」，R2/B2 不用。腾讯云桶名带 `-APPID` 后缀，用 SecretId/SecretKey。

### 2. 安装和日常使用

1. 从 [Releases](https://github.com/skyvense/muse-recorder/releases) 下载 APK（滚动发布的 `latest` 永远是最新构建，版本发布永久保留），发到手机上安装（个人侧载应用，需允许一次「未知来源」）。覆盖安装会保留设置。
2. 打开 App → ⚙️ 配置：填好 S3 保存（保存会立刻触发一次上传）→ 允许后台运行（关掉电池优化；三星手机还要把本应用电池设为「不限制」）→ 回到录音页点红色圆按钮开始录音（录音中变成方形，带实时波形和本段进度）。
3. **上传全自动**：待上传列表显示每段状态，上传时有实时进度，完成后结果留在屏幕上，失败会显示服务器的报错信息。只有改过设置、或自动重试失败超过 30 分钟时，才会出现手动「立即重试上传」按钮。
4. 右上角 ⓘ 是内置帮助页（录音、上传、标记、设置、FAQ、隐私）。

标记按天存在桶里的 `markers/events-<date>.jsonl`。开关状态会被记住，关掉重开 App 也不会乱。

## 每日总结管道（Muse 这边）

```bash
# 环境变量：S3_ENDPOINT / S3_BUCKET / S3_ACCESS_KEY / S3_SECRET_KEY / S3_REGION / S3_STYLE (path|virtual)
# 可选：S3_DELETE_AFTER=1 → 拉取后删掉云端原文件（转写和报告都还在）
python3 receiver/fetch_day_from_s3.py --date 2026-10-23
python3 receiver/summarize_day.py   --date 2026-10-23
```

转写两种方式：设 `OPENAI_API_KEY`（Whisper API），或 `pip install faster-whisper` 在本地免费转写。告诉 Muse 一个日报时间（比如每天早上 7:30），就能设定时任务自动跑完整条管道并发到聊天里。

## 隐私与安全

- 录音**只进你自己的桶**——整个链路里没有第三方服务器。
- 转写可以**完全在本地**完成（faster-whisper），除了你选的存储，数据不需要离开你的硬件。
- **本仓库里没有任何凭据。** S3 密钥只存在手机 App 的设置里；接收端脚本用的 Supabase 密钥放在仓库之外的本地 env 文件里。存储令牌请只授权单个桶，万一泄漏也只影响一个桶。
- 录别人的声音在很多地方需要对方同意——请只在合法且对方知情的场景录音。

## 现实提醒

- **电量**：全天录音大约每小时 3–6%，能插电过夜录最好。可用「仅 Wi-Fi 上传」。
- **空间**：每天约 500 MB。本地副本上传成功后默认删除；云端可用 `S3_DELETE_AFTER=1` 或桶的生命周期规则只保留最近几天。
- **稳定性**：Android 对后台录音管得很严，常驻状态栏通知是系统要求（也是它确实在录的证明）。

## 构建与发布

- `build-apk.sh` 直接用 Android SDK build-tools 构建 APK（发布构建不需要 Gradle）。
- 每次推送到 `master` 都会触发 GitHub Actions，构建**已签名**的 APK 并发布两个 Release（内容相同）：滚动的 `latest` 和版本化的 `vX.Y.Z`。从 [Releases](https://github.com/skyvense/muse-recorder/releases) 安装。
- `muse-recorder.keystore` + `keystore-info.txt` 是签名密钥——**务必保管好**。升级必须用同一把密钥签名，否则装不上覆盖不了。加密副本在 `vault/`（AES-256-CBC + PBKDF2）。

## 仓库结构

- `app-project/`：App 完整源码（Java + Android 框架 API；S3 签名是纯 Java 的 SigV4 实现，已用 AWS 官方测试向量验证）。
- `receiver/`：Muse 这边的管道——`fetch_day_from_s3.py`（从桶里拉录音 + 标记 + 快照；只用 Python 标准库，签名同样过了官方测试向量）、`summarize_day.py`（转写 + 报告草稿）、Supabase 写入脚本、快照拼图脚本。`receiver/server.py` 和 `fetch_day_from_supabase.py` 是 v1.0/v1.1 老方案的存档。
- `docs/images/`：上面用到的设计图。
- `README.<语言>.md`：本文件的其他 8 种语言版本。

v1.2 起 App **只支持 S3 模式**（v1.0 的自建接收端、v1.1 的 Supabase 直传已从 App 移除；脚本仍保留在 `receiver/` 供参考）。
