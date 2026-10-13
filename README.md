# Selkies 原生安卓客户端（Native Android Client for Selkies 2.0）

[![APK](https://img.shields.io/badge/APK-v1.0.0-blue)](./apk) ![Min SDK](https://img.shields.io/badge/minSDK-26%20(Android%208.0)-green) ![Lang](https://img.shields.io/badge/Kotlin-native%20WebSocket%20client-orange)

一个**原生 Android** 的 Selkies 2.0 远程桌面客户端。**不使用 WebView**，直接以 Kotlin + OkHttp 实现
Selkies 自研 WebSocket 协议，视频走 **MediaCodec H.264 硬解码**，音频走 **MediaCodec Opus 解码**，
适配 linuxserver.io **webtop / msedge**（底层 `baseimage-selkies`，Selkies 2.0）后端。

> 协议实现逐行对照官方源码校对（`selkies-project/selkies` 的 `websockets_mode.py`、`input_handler.py`
> 与官方 Web 前端 `addons/selkies-web-core/selkies-ws-core.js`、`lib/input.js` 等），
> 校对结论见 [docs/PROTOCOL.md](docs/PROTOCOL.md)。

---

## 目录
1. [功能清单](#功能清单)
2. [后端部署注意事项](#后端部署注意事项)
3. [APP 配置服务器指南](#app-配置服务器指南)
4. [编译 APK 步骤](#编译-apk-步骤)
5. [签名密钥说明](#签名密钥说明)
6. [协议实现要点](#协议实现要点)
7. [已知限制与常见问题](#已知限制与常见问题)

---

## 功能清单

### 连接与网络
- **自动路径探测**：优先 `/api/websockets`，失败自动回退旧路径 `/api/ws`
- **301/302/308 重定向自动跟随**（Nginx http→https 重定向场景，OkHttp WS 握手不跟随 3xx 的坑已在客户端手动逐跳解决）
- **Basic Auth 自动识别**：HTTP 401 + `WWW-Authenticate: Basic` 自动弹账号密码框，凭据随 WebSocket 握手以 `Authorization: Basic base64(user:pass)` 携带
- **Cloudflare 兼容**：GET/WS 全部使用与官方网页前端一致的浏览器请求头（User-Agent / Origin / Accept-Language 等），避免 CF 400 拦截；检测到 CF 拦截时给出针对性提示
- **局域网（http 明文）与公网（https/wss）** 均支持；断线指数退避自动重连（1/2/4/8/10s，最多 5 次）

### 视频
- **MediaCodec H.264 (Annex-B) 硬解码** → TextureView 渲染
- 12 字节帧头解析（codec id / 关键帧标记 / frameId / 宽高 / 参考帧）
- **自动适配屏幕、横竖屏自动切换**（configChanges 免重建，旋转可同步远端分辨率 `r,WxH`）
- **双指捏合缩放**（0.5×–8×，围绕枢轴）+ 平移，一键恢复"适配"
- **REQUEST_KEYFRAME 关键帧请求按钮**（花屏恢复），解码丢帧自动上报 `LOST_FRAME`
- **CLIENT_FRAME_ACK 回压**：每 50ms 确认最新帧 id（含 heldMs），对齐官方客户端节奏

### 音频
- **Opus 解码播放**（MediaCodec `audio/opus` + AudioTrack 低延迟）
- 0x01 音频消息完整解析（含 **RED 冗余布局**：n_red 计数、pts、冗余块、主块，按 ts 去重）
- 音量键/设置页音量、启停（START_AUDIO / STOP_AUDIO）

### 输入（可控制模式）
- **触摸手势**：单指点击=左键、单指拖动=按住左键拖拽、双击=双击、长按=右键（可配置右/中/无）、双指滑动=滚轮（垂直+水平、可调步长/方向）、双指捏合=缩放
- **虚拟键盘**：
  - 修饰键粘性开关：Ctrl / Alt / Shift / Win(Super) / AltGr，一键清修饰
  - 符号键：Esc Tab ⌫ ⌦ 方向键 Home End PgUp PgDn Ins PrtSc Menu NumLk ScrLk ↵
  - 功能键 F1–F12（横向滚动）+ Ctrl+Alt+Del 一键组合
  - **系统输入法接入**（EditText + IME）：文本以 `co,end,<text>` 直投远端，**支持中文输入法**；退格映射 BackSpace
- **X11 keysym 全表映射**（kd/ku），按住键 500ms `kh` 心跳防服务端回收
- **滚轮协议**：官方三连发上升沿语义（`m2,0,0,mask,mag` 置位→复位）

### 剪贴板
- **双向同步**：远端复制 → 手机剪贴板（`clipboard,` / `clipboard_binary,` / 分块 `clipboard_start/data/finish` 全解析）；手机复制 → 远端（`cw,` 小文本 + `cws/cwd/cwe` 分块）
- 手动推送/拉取对话框；仅查看模式下自动禁用发送

### 文件上传
- 唤起系统文件选择器（SAF），**HTTP POST `/api/upload`**（与官方前端一致，不走 WS）
- `X-Upload-Path` 头携带目标文件名；>64MB 自动分片（`X-Upload-Id/Offset/Total/Final`，规避 Cloudflare 100MB 限制）
- 进度显示（百分比）

### 手柄（游戏模式）
- Android 手柄事件 → Selkies `js` 协议（`js,c/d/b/a/h`），标准 16 键/6 轴布局
- 十字键转按钮 12–15，触发键转按钮 6/7，按住期间 100ms `js,h` 心跳
- 服务端 `system,{"action":"rumble,..."}` → 手机震动

### 多服务器管理
- 新增 / 编辑 / 删除 / 重命名 / 设为默认；每条保存地址、端口、https、账号密码（可选保存）、默认权限（仅查看/可控制）
- **测试连接**：完整探测（重定向/401/路径回退/CF），结果标记在列表
- 打开 APP 快速连接默认服务器

### 其它
- 连接状态实时提示、错误弹窗、统计面板（分辨率/fps/码率/RTT/待发帧，`_stats,1` 订阅）
- 手动断开、隐藏工具栏（点状态条恢复）
- 画质设置（码率 2–40Mbps、帧率 30–120）、手势自定义、清除已存密码、关于页
- 中英双语界面；沉浸式全屏

---

## 后端部署注意事项

本项目针对 **linuxserver.io webtop**（`ghcr.io/linuxserver/webtop`，基于 `baseimage-selkies`）与
Selkies 2.0 官方实现（`selkies-project/selkies`）。要点：

### 1. 端口
| 场景 | 端口 |
|---|---|
| webtop 默认 HTTP | `3000` |
| webtop 默认 HTTPS（自签） | `3001` |
| 旧版/自定义 | `6900` |
| Nginx 反代后 | `443`（https） |

### 2. 编码器（重要！）
后端必须输出 **H.264** 流（0x04 帧，codec id 1）。设置环境变量：
```yaml
environment:
  - SELKIES_ENCODER=h264enc
```
> 若后端无可用 GPU 且配置不当，可能降级为 JPEG 条带（0x03）或 VP8/VP9/H265——
> 本客户端检测到后会弹窗提示并给出上述修复建议。webtop 容器内置编码探测，通常自动选择 h264enc。

### 3. Basic 认证
```yaml
environment:
  - SELKIES_BASIC_AUTH_USER=abc
  - SELKIES_BASIC_AUTH_PASSWORD=123456
```
未认证请求返回 `401 + WWW-Authenticate: Basic realm="..."`，APP 会自动弹账号密码框。

### 4. HTTPS / Nginx 反代（301 场景）
Nginx 将 `http://host` 301 到 `https://host` 时，本客户端会**自动跟随重定向**后重建 WSS 连接，
用户无需手动改配置。反代需放行：
```
location / {
    proxy_pass http://127.0.0.1:3000;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;    # WebSocket 必须
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
    proxy_read_timeout 3600s;                  # 长连接
    proxy_send_timeout 3600s;
    client_max_body_size 0;                    # 文件上传分片
}
```

### 5. Cloudflare
- 后台 Network → WebSockets：**开启**
- 只代理标准端口（443/8443 等），非标准端口直连或用 DNS only
- SSL/TLS 模式 Full以上
- 本客户端已按官方网页前端原样模拟请求头（User-Agent/Origin 等），正常配置不会被 400 拦截

### 6. 文件上传目录
```yaml
environment:
  - SELKIES_FILE_TRANSFERS=upload,download   # 允许上传
  - SELKIES_UPLOAD_DIR=~/Desktop             # 上传落地目录（默认）
```

### 7. 局域网直连
`http://192.168.x.x:3000` 直连即可（APP 已允许明文流量）。公网建议套 HTTPS。

---

## APP 配置服务器指南

1. 打开 APP → 右下角 **+** 新增服务器
2. 填写：
   - **名称**：任意（如"家里台式机"）
   - **主机地址**：IP 或域名（如 `192.168.1.10`、`desk.example.com`）
   - **端口**：3000 / 3001 / 6900 / 443（按上表）
   - **HTTPS**：直连 3000/6900 关闭；3001/443 反代开启
   - **账号/密码**：后端开了 Basic 认证才填；可勾选"保存"（明文存本地，风险自担）
   - **权限**：仅查看 / 可控制（会保存在这条配置里）
   - **设为默认**：下次打开快速连接
3. **测试连接** → 显示 `✓ 连接成功（使用 /api/websockets）` 即可
4. 列表点击条目 → 进入远程会话
5. 会话内底部工具栏：键盘 / 关键帧 / 声音 / 查看⇄控制切换 / 剪贴板 / 上传 / 适配 / 统计 / 隐藏 / 断开
6. **设置页**：码率/帧率、手势（长按动作、滚动方向、步长）、音频/剪贴板/重连/常亮、清除密码、关于

---

## 编译 APK 步骤

### 方式一：Android Studio（推荐）
1. 安装 [Android Studio](https://developer.android.com/studio)（Iguana 以上，内置 JDK 17+）
2. `File → Open` 打开本项目根目录 `selkies-android/`
3. 首次同步会自动下载 Gradle 8.7 与依赖（AGP 8.4.2 / Kotlin 1.9.24 / compileSdk 34）
4. `Build → Build Bundle(s)/APK(s) → Build APK(s)` → `app/build/outputs/apk/release/app-release.apk`
5. 直接安装到手机：`adb install -r app-release.apk`

### 方式二：命令行
```bash
cd selkies-android
cp local.properties.example local.properties   # 改成你的 SDK 路径
./gradlew assembleRelease                      # Linux/macOS
# gradlew.bat assembleRelease                  # Windows
# 产物: app/build/outputs/apk/release/app-release.apk
```

### 前置要求
- JDK 17 或 21（`java -version` 检查）
- Android SDK Platform 34 + Build-Tools 34.0.0（Android Studio SDK Manager 安装，或命令行）
  ```bash
  sdkmanager "platforms;android-34" "build-tools;34.0.0"
  ```

### 命令行环境从零搭建（无 Android Studio）
```bash
# 1. 命令行工具
curl -LO https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip cmdtools.zip -d $ANDROID_HOME/cmdline-tools
mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest
# 2. 许可与组件
yes | sdkmanager --licenses
sdkmanager "platforms;android-34" "build-tools;34.0.0"
# 3. 构建
./gradlew assembleRelease
```

---

## 签名密钥说明

| 项 | 值 |
|---|---|
| 文件 | `keystore/selkies-release.keystore`（仓库内附带，方便自行编译同签名升级） |
| 别名 | `selkies` |
| 库密码 / 密钥密码 | `selkies2025` |
| 算法 | RSA 2048，有效期 10000 天 |
| 签名方案 | APK Signature Scheme v2（`apksigner verify` 通过） |

自行换签名：替换 `keystore/` 下文件并修改 `app/build.gradle.kts` 中 `signingConfigs.release` 四项。

> ⚠️ 这是个人发布密钥（供旁加载/自编译），请勿用于商店级正式分发。

---

## 协议实现要点

全部消息格式与节奏**逐行对照官方源码**校对（详见 [docs/PROTOCOL.md](docs/PROTOCOL.md)）：

- 握手时序：`_gz,1` → `SETTINGS,{json}`（initialClientWidth/Height、framerate、keyboardLayout、displayId、audioRedundancy…）→ `cr` → `_stats,1` → `MODE websockets` → `START_VIDEO` → `server_settings` → `START_AUDIO`
- 二进制帧：`0x01` Opus(RED)、`0x03` JPEG 条带、`0x04` 视频（12 字节头）、`0x05` gzip 控制文本（双向，≥512B）
- 输入：`kd/ku/kh/kr`、`m/m2`（含按钮掩码位 0-7 与滚轮上升沿三连发）、`co,end,<text>`、`js,c/d/b/a/h`
- 回压：每 50ms `CLIENT_FRAME_ACK <frameId> <heldMs>`；丢帧 `LOST_FRAME <id>`；花屏 `REQUEST_KEYFRAME`
- 指标：每 500ms `_f,<fps>` / `_l,<rtt>`
- 剪贴板：`cw/cb`（小包）与 `cws/cwd/cwe`、`cbs/cbd/cbe`（分块，16KB）；接收 `clipboard,` / `clipboard_binary,` / `clipboard_start,data,finish` / `clipboard_reply,cr`
- 上传：`POST /api/upload`（`X-Upload-Path`；>64MB 分片 `X-Upload-Id/Offset/Total/Final`）

---

## 已知限制与常见问题

| 现象 | 说明 |
|---|---|
| 提示"JPEG 降级模式" | 后端在发 0x03 条带。设 `SELKIES_ENCODER=h264enc` 后重连 |
| 提示"视频编码不支持 VP9/H265…" | 同上，仅支持 H.264 |
| 提示"此设备无 Opus 硬解码器" | 极少数老设备 MediaCodec 无 opus；音频自动禁用，视频不受影响 |
| 二进制剪贴板（图片） | v1.0 仅文本双向同步；远端图片复制会提示但不落本地剪贴板 |
| 断开后不重连 | 设置页开启"断线自动重连"；或服务端 `KILL`（按设计不重连） |
| 443 反代连不上 | 确认反代放行 `Upgrade`/`Connection` 头（见上文 Nginx 配置） |
| Cloudflare 400 | 检查 CF WebSockets 开关、代理端口、SSL 模式（客户端已模拟浏览器头） |

## 许可

客户端代码按 MPL-2.0 精神开源（与 Selkies 主项目一致）；依赖 OkHttp（Apache-2.0）与 AndroidX（Apache-2.0）。
