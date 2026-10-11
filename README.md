# Selkies 原生安卓客户端 (Selkies Native Android Client)

原生 Android 实现的 [Selkies v2](https://github.com/selkies-project/selkies) 远程桌面客户端。
**非 WebView 封装** —— 直接对接 Selkies 自研 WebSocket 流协议，视频/音频走 MediaCodec 硬件解码。

![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-green) ![Language](https://img.shields.io/badge/language-Kotlin-blue) ![Protocol](https://img.shields.io/badge/protocol-Selkies%20v2%20WebSocket-orange)

---

## 功能总览

### 连接层（针对已知问题的专项修复）
- **双端点自动探测**：优先 `/api/websockets`（Selkies v2），404 自动回退 `/api/ws`（旧部署）
- **301/302 重定向自动跟随**：Nginx `http → https` 强制跳转场景自动切换 WSS（最多 6 跳）
- **Basic Auth 自动识别**：服务端 401 + `WWW-Authenticate: Basic` 时弹出账号密码表单，认证后自动重试
- **Cloudflare 兼容**：所有请求（探测 + WS 握手）携带完整浏览器请求头（Chrome UA / 匹配 Host 的 Origin / Accept-Language），避免 CF 400 拦截
- **自签名证书支持**：LSIO 3001 端口自签 HTTPS 场景可勾选"信任自签名证书"
- **断线自动重连**：指数退避（1s→15s），尊重服务端 4029 重连防抖；可设置次数上限或手动重连

### 流媒体
- H.264 **整帧硬解码**（MediaCodec → SurfaceView），自动请求 `h264enc` 整帧编码
- HEVC(H.265)/VP8/VP9/AV1 解码支持（按帧头编码 ID 自动适配）
- JPEG 条带合成兜底（服务端强制 JPEG 编码时）
- **Opus 音频硬解码** + AudioTrack 播放（含 RED 冗余恢复、丢帧去重）；设备不支持时优雅降级
- 回压 ACK：`CLIENT_FRAME_ACK <id> <heldMs>` 50ms 节奏 + 1s 心跳（缺失会导致服务端限流卡顿）
- 分辨率协商：`SETTINGS.initialClientWidth/Height` + `r,WxH,primary`，屏幕旋转自动重设桌面分辨率（可关）
- IDR 关键帧手动请求按钮（花屏恢复）+ 管道重置自动补关键帧

### 输入系统
- **权限模式**：控制模式（键鼠全控）/ 仅查看模式（不发任何输入事件，双保险门禁）
  - 服务端 view-only 密码 / viewer 角色限制同样会被识别并强制仅查看
- **虚拟键盘**：Ctrl/Alt/Shift/Super 粘滞修饰键、Esc/Tab/Del/Home/End/PgUp/PgDn、方向键、F1–F12、退格/回车
- **文字输入条**：调起系统输入法，回车以 `co,end,<text>` 提交（中文输入法友好）
- **硬件键盘**：蓝牙/OTG 键盘按键直通（X11 Keysym 映射）
- **触摸手势**（两种模式可切换）：
  - 触摸板模式：单指滑动=相对移动（带灵敏度增益）、轻点=左键、长按拖拽、双指轻点=右键/中键
  - 直接触摸模式：单指点击/拖动=左键、长按=可配置动作
  - 双指竖直滑动=滚轮（Wayland finger_scroll 平滑滚动 / X11 滚轮脉冲自动适配）
  - 双指捏合=本地缩放（1–4x，缩放态双指拖动平移，长按 IDR 按钮重置）
- **游戏手柄**：标准布局（A/B/X/Y/LB/RB/LT/RT/Back/Start/摇杆按压/十字键/Guide），`js,c/b/a/d` 协议直通

### 工具与设置
- **多服务器管理**：增删改/重命名、默认服务器、连接测试（走与真实连接相同的探测管线）
- **剪贴板双向同步**：本地↔远端（小内容 `cw`/`clipboard,`，大内容 `cws/cwd/cwe` 分段）
- **文件上传**：唤起系统文件选择器 → POST `/api/upload`（`X-Upload-Path` 头），>48MB 自动分片（兼容 Cloudflare 100MB 限制）
- **设置页**：CRF 画质 / CBR 码率 / 帧率 / 码率控制模式 / 音频码率、手势自定义（双指轻点、长按动作、触摸模式、捏合缩放）、安全（清除全部已保存密码）、关于页（含最近崩溃日志查看）

---

## 协议实现说明

所有协议细节均核对自官方源码（**非猜测**）：

| 文件 | 用途 |
|------|------|
| `selkies-web-core/selkies-ws-core.js` | 前端握手序列 / 回压 ACK / 音频 RED 解包 |
| `selkies-web-core/lib/wire-codecs.js` | 二进制帧头格式 / 编码 ID 表 |
| `selkies-web-core/lib/input.js` | 鼠标位掩码 / 滚轮脉冲 / 手指滚动 |
| `selkies-web-core/lib/gamepad.js` | 手柄标准布局 |
| `selkies-web-core/lib/clipboard-worker-bridge.js` | 剪贴板分段协议 |
| `selkies/src/selkies/websockets_mode.py` | 服务端消息路由 / SETTINGS 解析 |
| `selkies/src/selkies/input_handler.py` | 输入动词语义 / `co,end` / 游戏手柄 |
| `selkies/src/selkies/stream_server.py` | Basic Auth / Origin 校验 / 上传端点 |

关键协议点：
- 二进制帧首字节分类：`0x01` Opus 音频、`0x03` JPEG 条带、`0x04` 视频帧、`0x05` gzip 控制文本
- `0x04` 帧头 12 字节：`[类型, 编码|关键帧, u16 帧ID, u16 条带Y, u16 宽, u16 高, u16 参考帧ID]` + Annex-B 码流
- 握手序列：`SETTINGS,{json}` → `cr` → 收到 `server_settings` 后 `r,WxH,primary` → `START_VIDEO` → `START_AUDIO`
- 仅查看模式门禁在客户端两处生效（`InputController` + `ConnectionEngine.sendInput`）

---

## 后端部署（linuxserver.io webtop / Selkies）

推荐使用 [linuxserver/docker-webtop](https://github.com/linuxserver/docker-webtop)（底层即 Selkies v2）：

```bash
docker run -d \
  --name=webtop \
  -e PUID=1000 -e PGID=1000 \
  -e TZ=Asia/Shanghai \
  -e PASSWORD=yourpassword `# 可选：开启 Basic Auth` \
  -e CUSTOM_USER=youruser `# 可选：默认 ubuntu` \
  -p 3000:3000 \
  -v /path/to/config:/config \
  --shm-size="1gb" \
  lscr.io/linuxserver/webtop:latest
```

APP 服务器配置：
| 字段 | 填写 | 说明 |
|------|------|------|
| 地址 | `192.168.1.100` 或域名 | 不带协议前缀 |
| 端口 | `3000` | LSIO 默认；Nginx https 反代后填 `443` |
| 子目录 | 留空 | 反代挂子路径时填，如 `/desktop` |
| HTTPS | 按需 | Nginx https / LSIO 3001 自签端口 |
| 信任自签名证书 | 按需 | 自签场景勾选 |
| 用户名/密码 | 按需 | 后端 `PASSWORD`/`CUSTOM_USER` 环境变量 |

公网访问：直接填公网域名 + 443 + HTTPS（Cloudflare 代理兼容）。

---

## 编译

环境要求：JDK 17+、Android SDK (Platform 34 + Build-Tools 34.0.0)。

```bash
# 1. 配置 SDK 路径
echo "sdk.dir=/path/to/android-sdk" > local.properties

# 2. Debug 构建
./gradlew assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk

# 3. Release 签名构建（首次需生成 keystore 到 keystore/ 目录）
keytool -genkeypair -v -keystore keystore/selkies-release.keystore \
  -alias selkies -keyalg RSA -keysize 4096 -validity 10950
# 密码可用环境变量覆盖：SELKIES_STORE_PASS / SELKIES_KEY_ALIAS / SELKIES_KEY_PASS
./gradlew assembleRelease
# 产物: app/build/outputs/apk/release/app-release.apk
```

Android Studio 直接打开项目根目录即可（File → Open）。

---

## 项目结构

```
app/src/main/java/com/selkies/client/
├── SelkiesApp.kt              # 应用入口 + 全局崩溃日志（crash/ 目录落盘）
├── data/
│   ├── ServerConfig.kt        # 服务器配置 + 仓库（JSON 持久化）
│   └── SettingsStore.kt       # 全局设置（画质/手势/行为）
├── net/
│   ├── Protocol.kt            # 协议常量与帧解析（0x01/0x03/0x04/0x05、Opus RED）
│   ├── EndpointProber.kt      # 端点探测（双路径/301/Basic Auth/浏览器头）
│   └── ConnectionEngine.kt    # 连接引擎（状态机/握手/回压 ACK/自动重连）
├── media/
│   ├── VideoDecoder.kt        # MediaCodec H264 硬解（仅关键帧配置/自愈/JPEG 合成）
│   └── AudioPlayer.kt         # Opus 硬解 + AudioTrack（不可用时优雅降级）
├── input/
│   ├── Keysyms.kt             # X11 Keysym 表 + Android 按键映射
│   ├── InputController.kt     # 输入统一出口（权限门禁/粘滞修饰键/组合键）
│   └── GestureHandler.kt      # 触摸手势→鼠标（两种模式/滚轮双路径/捏合缩放）
└── ui/
    ├── ServerListActivity.kt  # 服务器列表
    ├── ServerEditActivity.kt  # 服务器编辑 + 连接测试
    ├── SettingsActivity.kt    # 设置页
    └── session/
        └── SessionActivity.kt # 会话页（装配引擎/解码器/输入 + 面板/键盘/上传/手柄）
```

## 防闪退设计

针对"每次连接闪退"问题的专项加固：
1. **零信任消息解析**：每条 WS 消息都在 try/catch 中处理，畸形消息只记日志不崩溃
2. **解码器只在关键帧配置**：乱序/超前增量帧直接丢弃并请求关键帧（协议要求的行为）
3. **解码异常自愈**：MediaCodec 任何异常 → 重置 → `REQUEST_KEYFRAME`，会话不中断
4. **媒体帧不经过主线程**：视频/音频直投各自工作线程的有界队列，杜绝主线程卡死/ANR
5. **Surface 生命周期解耦**：切后台只解绑，回前台自动重绑 + 补关键帧
6. **旋转不重建**：`configChanges` 全量声明，会话全程单实例
7. **全局崩溃日志**：未捕获异常落盘 `filesDir/crash/`，关于页可查看最近记录

## License

MPL-2.0（与上游 Selkies 一致）
