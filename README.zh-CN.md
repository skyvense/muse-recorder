# Muse Daily Recorder v1.8.2（S3 直传 + 事件标记 + 每日总结）

[English](README.md) | 中文 | [日本語](README.ja.md) | [한국어](README.ko.md) | [Español](README.es.md) | [Français](README.fr.md) | [Deutsch](README.de.md) | [Português](README.pt.md) | [Русский](README.ru.md)

Muse 本身没有持续监听麦克风的能力，所以这套方案由三部分组成：

1. **Android App**（`MuseRecorder.apk`）：前台服务录音，**每 60 分钟自动切一段**（.m4a，约 21 MB/段），每段结束就自动上传到你自己的 **S3 兼容对象存储**；失败排队重试，App 里有**待上传列表**可以看。
2. **事件标记**：录音时可以随手标记重要时刻（见下），标记会一起传到桶里。
3. **每日总结**（在 Muse 这边）：用 `receiver/fetch_day_from_s3.py` 从桶里拉前一天的录音和标记 → 转写 → Muse 整理成日报。

v1.2 起 App **只支持 S3 模式**（v1.0 的自建接收端、v1.1 的 Supabase 直传已移除；接收端脚本还保留在 `receiver/` 里供参考）。

## 一、准备一个 S3 桶（以 Cloudflare R2 为例）

1. cloudflare.com 注册并开通 R2（免费档 10 GB、下载免费；开通可能要求绑卡，免费额度内不扣费）。创建一个 bucket，例如 `recordings`。
2. 在 R2 的“管理 R2 API 令牌”里**创建一个只对这个桶有读写权限的令牌**（Object Read & Write，指定桶），记下 Access Key ID、Secret Access Key 和你的 Account ID（Endpoint 是 `https://<AccountID>.r2.cloudflarestorage.com`）。
3. App 里预置选 **Cloudflare R2**，把 Endpoint 里的 `<AccountID>` 换成真实 ID，填桶名和两个 Key，保存。

其他厂商同样可以（App 里都有预置）：Backblaze B2、阿里云 OSS（要用 S3 兼容模式 + 云账号 AccessKey；桶在控制台建）、腾讯云 COS（桶名带 `-APPID` 后缀；用 SecretId/SecretKey）、AWS S3、七牛云 Kodo，或任意 S3 兼容服务选“自定义”。阿里/腾讯/AWS/七牛要勾选“虚拟主机风格”，R2/B2 不勾。

> 安全提示：Secret Key 只填在手机 App 里，不要发到聊天、不要贴到任何公开地方。给令牌只开单个桶的权限，泄露了影响也可控。

## 二、安装与日常使用

1. 把 `MuseRecorder.apk` 传到手机点开安装（自用 App 未上架，允许一次“未知来源”）；已装旧版可直接覆盖，设置会保留。
2. 打开 App → 点“⚙️ 配置”填好 S3 并保存（保存后会自动上传一次）→ 点“允许后台运行（关闭电池优化）” → 点红色圆形按钮开始录音（录音中按钮变方形，下方有实时波形和本段剩余分钟），并允许麦克风/通知权限。三星手机还建议把本 App 的电池设为“不受限制”。
3. **上传是全自动的**：待上传列表会显示每段的状态，上传时有实时进度，传完或失败后结果会留在原地显示，失败时带服务器返回的错误信息。断网或失败的段会留在列表里，恢复后自动重试。“立即重试上传”按钮只在改了配置、或自动重试失败 30 分钟以上时才会出现。S3 与录音设置默认收起，点“⚙️ 配置”展开。
4. **语言**：设置顶部的语言选择器提供跟随系统 + 英/中/日/韩/西/法/德/葡/俄 9 种语言，切换立即生效。App 名在所有语言下都固定是 Muse Daily Recorder。
5. 右上角蓝色的 ⓘ 按钮打开内置帮助页（录音、上传、标记、配置、常见问题、隐私）。

## 三、事件标记（给日报“划重点”）

- **对话开关**：开始一段重要对话时打开，聊完关掉。
- **会议开关**：开会前打开，散会关掉。
- **📌 自我记录按钮**：想到重要的事、灵感、待办时按住不放，标记此刻；录音时按住只标记时刻，空闲时按住会录一段独立的语音备忘。
- 标记按天存在桶的 `markers/events-<日期>.jsonl` 里；日报会按标记切分对话/会议，并特别留意“自我记录”附近的内容。开关状态会记住，关掉 App 重开不会乱。

## 四、每日总结（Muse 这边）

```bash
# 环境变量：S3_ENDPOINT / S3_BUCKET / S3_ACCESS_KEY / S3_SECRET_KEY / S3_REGION / S3_STYLE(path|virtual)
# 可选：S3_DELETE_AFTER=1 → 拉完自动删云端录音原件（省云端空间，转写稿和日报在 Muse 这边留存）
python3 receiver/fetch_day_from_s3.py --date 2026-10-23
python3 receiver/summarize_day.py   --date 2026-10-23
```

转写引擎二选一：环境变量 `OPENAI_API_KEY`（Whisper API），或在 Muse 的电脑上 `pip install faster-whisper`（本地免费）。配好后告诉 Muse 一个日报时间（比如每天 7:30），它会建定时任务自动跑并发到聊天里。

## 五、现实提醒

- **电量**：全天录音约每小时 3–6% 电量，建议夜间充电时录；可开“仅 Wi‑Fi 上传”。
- **空间**：一天约 500 MB。默认“上传成功后删本地”；云端建议开 `S3_DELETE_AFTER=1` 或桶的生命周期规则，只留最近几天。
- **稳定性**：Android 对后台录音限制严格；状态栏常驻通知是系统要求（也是在录的凭证）。睡前/起床瞄一眼 App 里“已持续 X 分钟”即可。
- **合规**：录下与他人对话在很多地方需要对方同意，请在合法、对方知情的场景使用。

## 六、目录

- `app-project/`：App 完整源码（Java + Android 框架 API，S3 签名是纯 Java 实现，已通过 AWS 官方 SigV4 测试向量验证）。
- `receiver/fetch_day_from_s3.py`：从 S3 桶拉取录音+标记（Python 标准库，签名实现同样通过官方测试向量）。
- `receiver/summarize_day.py`：转写 + 日报草稿；`receiver/server.py`、`fetch_day_from_supabase.py` 为旧方案存档。
- `build-apk.sh`：用 Android SDK build-tools 直接构建 APK。
- `.github/workflows/build.yml`：CI 在每次推送到 master 后自动构建，并发布两份同样的 APK：一份是滚动更新的 `latest` release（永远是最新版），一份是按版本打 tag 的正式 release（`vX.Y.Z`，永久保留）。最新版随时在这里下载：https://github.com/skyvense/muse-recorder/releases/tag/latest
- `muse-recorder.keystore` + `keystore-info.txt`：签名密钥，**务必保留**，升级必须用它重新签名，否则无法覆盖安装。加密副本在 `vault/` 里（AES-256-CBC + PBKDF2）。
