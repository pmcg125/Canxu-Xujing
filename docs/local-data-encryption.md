# 本地资料加密与恢复

更新：2026-11-10。版本：`0.2.1`（versionCode 3）。适用于 `D:\Canxu-project\Xujing`。

本文说明本地资料的加密方式、解密失败时如何判断、以及真机上已经验证过的恢复路径。核心原则：**解密失败时数据仍在，不要清除应用数据，也不要覆盖写入。**

## 1. 加密结构

本地数据分两部分，使用同一把 Android Keystore 密钥：

| 数据 | 位置 | 内容 |
| --- | --- | --- |
| 资料库、会话、消息、偏好 | `databases/xujing.db` | `records` 表，gzip 后 AES-GCM 加密，1 MiB 分块 |
| API Key 等连接密钥 | `shared_prefs/connection-secrets.xml` | Base64 的 AES-GCM 密文 |

实现位于 `app/src/main/java/app/xujing/data/AppStore.kt`：

```text
SecretVault
├── AndroidKeyStore 别名：xujing-local-v1
├── AES/GCM/NoPadding，128 位 tag
└── 密文格式：[1 字节 IV 长度][IV][ciphertext+tag]

AppStore
├── SQLiteOpenHelper，数据库版本 2
├── records(kind, id, parent, sequence, chunk, payload)
├── payload = seal(gzip(JSON))，超过 1 MiB 分块
└── vectors 表同样用 SecretVault 加密
```

密钥由系统 Keystore 保管，不可导出。应用只能通过别名取用，因此 **密钥的存在性和可用性由系统和底层 keymaster/keymint 决定**。

## 2. 启动读取流程

`AppController.init` → `AppStore.load()`：

1. 查询 `records` 中 `chunk=0` 的记录
2. 逐条 `vault.open` → `gunzip` → JSON 反序列化
3. 组装 `AppState`
4. 成功后才设置 `storageReady=true`，之后才允许 `save()`

任一步失败都会进入保护模式：

```text
读取本地资料失败，为保护原数据暂不写入：<阶段>（<异常类型>）
```

失败时 `storageReady` 保持 `false`，`persist()` 和所有 `change()` 都不会写库。

## 3. 常见失败与含义

| 日志/提示 | 含义 | 数据是否还在 |
| --- | --- | --- |
| `解密 role/...（AEADBadTagException）` | 当前 Keystore 密钥 ≠ 加密数据时的密钥 | 在，密文未被修改 |
| `XujingStore: AndroidKeyStore 缺少 xujing-local-v1，正在生成新密钥` | 别名丢失，应用新建了一把密钥 | 旧密文还在，但旧密钥可能已不可用 |
| `本地数据格式无效` | 密文头长度字节异常或记录被截断 | 需要检查分块 |
| `解析 ...（SerializationException）` | 解密成功但 JSON 结构异常 | 明文可恢复 |

### 3.1 判断是“密钥不符”还是“数据损坏”

```powershell
$adb = 'D:\REA-Android\platform-tools\adb.exe'
$d = '192.168.8.80:5555'
& $adb -s $d logcat -d | Select-String 'XujingStore|XujingController|密钥自检'
```

`密钥自检：可解密 0/5` 说明 `connection-secrets.xml` 也解不开，问题在密钥层，不是数据库坏块。

数据库本身可以离线验证（不解密也能看结构）：

```powershell
& $adb -s $d exec-out run-as app.xujing cat databases/xujing.db > phone-xujing.db
```

确认：

- 文件头是 `SQLite format 3`
- `PRAGMA user_version` = 2
- `PRAGMA quick_check` = ok
- `records` 有数据且每个 `(kind,id)` 的 `chunk` 从 0 连续

### 3.2 确认 Keystore 里是否还有旧密钥

需要 root：

```powershell
& $adb -s $d shell su -c "cat /data/misc/keystore/persistent.sqlite" > ks-persistent.sqlite
```

用 SQLite 打开后查询：

```sql
SELECT id, domain, namespace, alias, state, km_uuid
FROM keyentry WHERE alias = 'xujing-local-v1';
```

`namespace` 是应用 UID（`dumpsys package app.xujing` 里的 `userId`），`state=1` 表示正常。`keymetadata` 里 tag `0x0000` 的整数是创建时间（毫秒时间戳），可以和数据库/日志时间对照。

## 4. 已确认的真实案例：keymaster 模块劫持

真机 `192.168.8.80:5555`（M2012K11AC / Android 13）出现过一次：

- 现象：启动即报 `AEADBadTagException`，`connection-secrets.xml` 也解不开
- 数据：`xujing.db` 3,391,488 字节，结构完整，`records` 114 条
- 旧密钥：`persistent.sqlite` 中 `xujing-local-v1` 仍存在，创建于 2026-11-05 14:21
- 触发：2026-11-10 11:49–11:57 安装了 OhMyKeymint、PlayIntegrityFix、Shamiko、LSPosed 等模块
- 机制：OhMyKeymint 的 `injector.toml` 把 `app.xujing` 放进 `scoop`，Keystore 请求被路由到模块自己的密钥库；模块 `keymaster.db` 中出现同名密钥，创建于 11-10 12:55

也就是说，**应用没有丢数据，也没有写坏数据；是 Keystore 被换成了另一套密钥库**。

## 5. 恢复步骤

### 5.1 先备份（必须）

```powershell
$qa = 'C:\Users\Administrator\AppData\Local\Temp\opencode\xujing-qa'
& $adb -s $d exec-out run-as app.xujing cat databases/xujing.db > "$qa\phone-xujing.db"
& $adb -s $d exec-out run-as app.xujing cat databases/xujing.db-journal > "$qa\phone-xujing.db-journal"
& $adb -s $d exec-out run-as app.xujing cat shared_prefs/connection-secrets.xml > "$qa\phone-connection-secrets.xml"
```

`exec-out` 是二进制安全的；不要用 PowerShell 的 `>` 直接重定向 `adb shell cat`。

### 5.2 把应用从 keymaster 模块中排除

OhMyKeymint 的实时配置在：

```text
/data/misc/keystore/omk/injector.toml
```

需要：

1. 从 `scoop = [ ... ]` 中删除 `app.xujing`
2. 设置 `deny_packages = ["app.xujing"]`
3. 触发注入重启：

```powershell
& $adb -s $d shell su -c "touch /data/adb/omk/restart.injector"
```

该操作会让模块重启 `keystore2` 并重新注入。重启后手机可能进入锁屏，需要解锁。

模块的 `post-fs-data.sh` 只在目标配置不存在时用模块模板覆盖，因此这份配置会保留到重启之后；但如果在模块 WebUI 里重新保存名单，需要再次确认 `app.xujing` 仍在排除名单。

### 5.3 验证

```powershell
& $adb -s $d shell am force-stop app.xujing
& $adb -s $d shell am start -n app.xujing/.MainActivity
& $adb -s $d shell pidof app.xujing
& $adb -s $d logcat -d --pid=<pid> | Select-String 'XujingStore|XujingController'
```

正常情况下不再出现 `读取本地资料失败`，会话、预设、世界书和 API Key 都会恢复。

### 5.4 如果密钥确实已经不存在

如果 `persistent.sqlite` 里没有 `xujing-local-v1`，或应用已生成新密钥并覆盖了旧别名，则旧密文在密码学上不可恢复。此时：

1. 保留数据库和 `connection-secrets.xml` 原档
2. 明确告知用户数据无法解密
3. 只有用户明确同意后，才允许清除应用数据
4. 之后重新导入预设/角色卡

## 6. 预防

- 使用 keymaster / keymint 替身类模块时，把 `app.xujing` 放进排除名单，不要放进 scoop。
- 换机、刷机、恢复备份前，先在应用内导出明文资料；Android Keystore 密钥不会随普通备份迁移。
- 看到 `读取本地资料失败` 时不要点“清除数据”，先按第 3、4 节确认密钥状态。
- 应用侧已增加：分阶段错误定位、完整堆栈日志、密钥缺失警告和只读密钥自检，便于下次快速判断。

## 7. 相关代码与文档

| 文件 | 说明 |
| --- | --- |
| `app/src/main/java/app/xujing/data/AppStore.kt` | `SecretVault`、`AppStore.load/save`、分块与迁移 |
| `app/src/main/java/app/xujing/AppController.kt` | `init` 读取、保护模式、错误提示 |
| `docs/build-status.md` | 版本 0.2.1 与本次真机事件记录 |
| `docs/environment.md` | 真机无线调试流程 |
| `docs/preset-panel-compat.md` | 预设脚本与面板兼容 |
