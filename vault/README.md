# vault — 加密备份（签名密钥）

这里是 App 签名材料的**加密**备份。明文文件（`muse-recorder.keystore`、`keystore-info.txt`）
按根目录 `.gitignore` 的规则永远不入库；本目录里只有密文。

| 文件 | 内容 |
|---|---|
| `muse-recorder.keystore.enc` | 签名密钥库（解密后是构建 APK 必须用的 keystore） |
| `keystore-info.txt.enc` | 密钥库密码与别名说明 |

加密方式：AES-256-CBC + PBKDF2（200,000 次迭代，带盐），口令由机主本人保管，不写在本仓库任何地方。

解密（在本项目根目录执行，会提示输入口令）：

```bash
openssl enc -d -aes-256-cbc -salt -pbkdf2 -iter 200000 \
  -in vault/muse-recorder.keystore.enc -out muse-recorder.keystore
openssl enc -d -aes-256-cbc -salt -pbkdf2 -iter 200000 \
  -in vault/keystore-info.txt.enc -out keystore-info.txt
```

为什么重要：没有这个 keystore 签名的新 APK 无法覆盖安装到已装旧版的手机上，
等于 App 的“身份证”。仓库一旦改为公开，本目录的安全就完全取决于口令强度。
