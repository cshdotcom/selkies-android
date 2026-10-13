# Selkies 2.0 WebSocket 协议规格（对照官方源码逐条校对）

> 本文档由 Selkies 官方源码逐条校对得出，校对基线：
> - 服务端：`selkies-project/selkies`（`src/selkies/websockets_mode.py`、`src/selkies/input_handler.py`、`src/selkies/stream_server.py`、`src/selkies/stream_stats.py`、`src/selkies/display_utils.py`）
> - 官方 Web 客户端：`selkies/addons/selkies-web-core/selkies-ws-core.js`（10626 行）及 `lib/input.js`、`lib/wire-codecs.js`、`lib/file-upload.js`、`lib/session-token.js`、`lib/stream-stats.js`
> 本 Android 客户端的实现严格以下表为准，未做任何脑补。

## 1. 连接与路由

| 项 | 值 |
|---|---|
| 数据 WebSocket（新版路径） | `GET {http|https}://host:port/api/websockets`（允许尾随 `/`） |
| 旧版路径（兼容回退） | `/api/ws`（WebRTC 信令模式同路径） |
| 服务端注册 | `WEBSOCKET_ROUTES = ("/api/websockets", "/api/webrtc/signaling", "/api/ws")` |
| 认证 | ① Basic：`Authorization: Basic base64(user:pass)`，401 时响应 `WWW-Authenticate: Basic realm="...", charset="UTF-8"`；② Bearer 会话令牌：`Authorization: Bearer <token>`；③ WS 子协议 `selkies.token.<base64url>`（服务端选择 `selkies`）；④ 查询参数 `?token=`；⑤ Cookie `selkies_token`；⑥ 主令牌头 `Selkies-Authorization: Bearer <token>` |
| 反向代理 | Nginx 301/302 → 客户端必须手动跟随重定向后重建 WS URL（OkHttp WS 握手不会自动跟随 3xx，这正是官方浏览器不存在、原生客户端常见 BUG #1 的成因） |
| Cloudflare | 需与浏览器一致的请求头（User-Agent、Origin、Accept-Language 等），异常头会被 CF 直接 400 |

## 2. 连接建立时序（对齐 onSocketOpen）

1. WS 握手成功（open）
2. 客户端发送 `_gz,1`（声明支持 gzip；服务端回显 `_gz,1` 后，≥512 字节的控制文本会以 0x05 gzip 二进制帧下发）
3. 客户端发送初始设置：`SETTINGS,{json}`（**逗号 + JSON**）
4. 客户端发送 `cr`（cache-only 剪贴板快照请求）
5. 周期任务：
   - 每 50ms：`CLIENT_FRAME_ACK <frameId> <heldMs>`（空格分隔；frameId 为最新已收/已呈现帧 id；重复 id 即心跳）
   - 每 500ms：客户端指标 `_f,<fps>`、`_l,<latencyMs>`
6. 收到 `MODE websockets` → 角色初始化；控制器角色可直接发 `START_VIDEO`（服务端将返回 `VIDEO_STARTED` + `PIPELINE_RESETTING primary` + IDR）
7. 收到首个 `server_settings` JSON 后发送 `START_AUDIO`（若启用音频）
8. 收流、解码、回 ACK

### 初始 SETTINGS JSON 字段（对齐官方 onSocketOpen 构造）
```json
{
  "manual_resolution": false,
  "initialClientWidth": 1280,     // 偶数对齐（-x%2）
  "initialClientHeight": 720,
  "scaling_dpi": 96,
  "framerate": 60,
  "keyboardLayout": "us",
  "useCssScaling": true,
  "displayId": "primary",
  "tabId": "<任意稳定id>",
  "displayScale": 1.0,
  "audioRedundancy": true,
  "video_bitrate": 8000           // 可选，用户设置时才带
}
```

## 3. 二进制帧（首字节为类型标记）

| 首字节 | 方向 | 含义 | 载荷格式（均大端） |
|---|---|---|---|
| `0x01` | S→C | Opus 音频 | `[0]=0x01`，`[1]=flags`（bit7=AUDIO_QUIET=0x80，低 7 位=n_red）。n_red=0：`[2..]`=单个 Opus 包；n_red>0：`[2..5]`=pts u32，随后 n_red×4 字节 RED 头（每条：1 保留字节 + 3 字节字段，`offset=(field>>10)&0x3fff`、`len=field&0x3ff`），再 1 字节保留，随后 n_red 个冗余块（各自 len 字节，ts=pts-offset），最后为主块（余下全部） |
| `0x02` | C→S | 麦克风 Opus | pcmflux 头 + Opus |
| `0x03` | S→C | JPEG 条带 | `[0]=0x03`，`[1]=保留`，`[2..3]=frameId u16`，`[4..5]=stripeY u16`，`[6..]=JPEG 数据` |
| `0x04` | S→C | 视频帧（全帧/条带） | 12 字节头：`[0]=0x04`，`[1]=类型字节`，`[2..3]=frameId u16`，`[4..5]=stripeY u16`，`[6..7]=width u16`，`[8..9]=height u16`，`[10..11]=referenceFrameId u16`，`[12..]=H264 Annex-B` |
| `0x05` | 双向 | gzip 压缩的控制文本 | `[0]=0x05`，`[1..]=gzip(raw 文本)` |
| `0x06` | C→S | 摄像头帧 | — |

### 0x04 类型字节（`[1]`）
- 高 4 位 = codec id：`1=h264, 2=vp8, 3=vp9, 4=av1, 5=h265`
- 低 4 位 = 帧类型：`0x01` = 关键帧 / 解码入口点
- `referenceFrameId == frameId` 表示无参考（关键帧）

### JPEG 条带模式（0x03）
后端无 H264 硬解或拒绝时的降级模式，本客户端按帧头识别并提示用户（v1.0 仅完整支持 0x04 H264 流，0x03 显示提示）。

## 4. 文本控制消息

### 4.1 客户端 → 服务端

| 消息 | 格式 | 说明 |
|---|---|---|
| 设置 | `SETTINGS,{json}` | 初始及后续设置（如 `SETTINGS,{"keyboardLayout":"de"}`） |
| 视频启停 | `START_VIDEO` / `STOP_VIDEO` | |
| 音频启停 | `START_AUDIO` / `STOP_AUDIO` | |
| 帧确认 | `CLIENT_FRAME_ACK <frameId> [heldMs]` | 空格分隔；回压窗口 |
| 关键帧请求 | `REQUEST_KEYFRAME` | 花屏/断流恢复 |
| 丢帧上报 | `LOST_FRAME <frameId>` | 解码器丢帧时 |
| 解码能力 | `DECODE_PACE <fps>` | 0 = 不限 |
| gzip 声明 | `_gz,1` | |
| 键按下/抬起 | `kd,<keysym>` / `ku,<keysym>` | X11 keysym |
| 键盘心跳 | `kh,<keysym>,<keysym>,...` | 刷新已按住键（防服务端回收） |
| 键盘重置 | `kr` | |
| 鼠标（绝对） | `m,<x>,<y>,<mask>,<scrollMag>[,<seq>]` | |
| 鼠标（相对） | `m2,<dx>,<dy>,<mask>,<scrollMag>[,<seq>]` | |
| 触控板滚动 | `sf,<dx>,<dy>` / `sfe`（抬起） | 仅 Wayland 且能力可用 |
| 指针可见 | `p,<0|1>` / `SET_NATIVE_CURSOR_RENDERING,<0|1>` | |
| 流画面位置 | `vp,<originX>,<originY>,<scaleX>,<scaleY>` | 多显示器拖拽定位 |
| 视频码率 | `vb,<kbps>` | |
| 音频码率 | `ab,<kbps>` | |
| 分辨率请求 | `r,<WxH>` | 客户端请求改变远端分辨率 |
| 缩放请求 | `s,<scale>` | DPI |
| 文本输入 | `co,end,<text>` | IME/软键盘文本直投 |
| 剪贴板写（小文本） | `cw,<base64(utf8)>` | |
| 剪贴板写（小二进制） | `cb,<mime>,<base64>` | |
| 剪贴板分块（文本） | `cws,<id>,<total>` → `cwd,<id>,<b64chunk>`×N → `cwe,<id>` | |
| 剪贴板分块（二进制） | `cbs,<id>,<mime>,<total>` → `cbd,<id>,<b64chunk>`×N → `cbe,<id>` | |
| 剪贴板读 | `cr` / `REQUEST_CLIPBOARD` | |
| 二进制剪贴板开关 | `_ebc,<true|false>` | |
| 手柄连接 | `js,c,<idx>,<b64(latin1 name)>,<numBtns>,<numAxes>` | |
| 手柄断开 | `js,d,<idx>` | |
| 手柄按键 | `js,b,<idx>,<btn>,<val 0..1>` | |
| 手柄轴 | `js,a,<idx>,<axis>,<val -1..1>` | |
| 手柄心跳 | `js,h,<idx>` | |
| 指针回显 | `_pointer_echo,<0|1>` | 触控板模式 |
| 客户端指标 | `_f,<fps>` / `_l,<latencyMs>` | 每 500ms |
| 统计订阅 | `_stats,1` / `_stats,0` | |
| 帧率 | `_arg_fps,<fps>` | |
| 码控模式 | `_rc,<mode>` / `_crf,<int>` | |
| 命令执行 | `cmd,<command>` | 服务端开启时有效 |
| 心跳应答 | `pong` | 应答服务端 `ping` |

### 鼠标按键掩码（bit 位）
- bit0 = 左键（1），bit1 = 中键（2），bit2 = 右键（4）
- bit3 = 滚轮下（8），bit4 = 滚轮上（16）【与物理前进/后退键共享】
- bit5 = 橡皮擦/后退（32），bit6 = 滚轮左（64），bit7 = 滚轮右（128）
- 滚动脉冲（上升沿语义，三连发）：
  ```
  m2,0,0,<mask去掉滚轮位>,<mag>
  m2,0,0,<mask|滚轮位>,<mag>
  m2,0,0,<原mask>,<mag>
  ```

### 4.2 服务端 → 客户端

| 消息 | 说明 |
|---|---|
| `MODE websockets` | 传输模式确认（客户端据此初始化输入并 START_VIDEO） |
| `AUTH_SUCCESS,{json:role,slot}` | 认证成功与角色 |
| `ROLE_UPDATE,{json}` | 角色变更（controller/viewer） |
| `MK_ACCESS,<0|1>` | 鼠标键盘权限授予/回收 |
| `VIDEO_STARTED` / `VIDEO_STOPPED` | |
| `AUDIO_STARTED` / `AUDIO_STOPPED` / `AUDIO_DISABLED` | |
| `MICROPHONE_DISABLED` / `WEBCAM_DISABLED` / `WEBCAM_KEYFRAME` | |
| `CAPTURE_DEMAND <subject> <0|1>` | 按需采集开关 |
| `PIPELINE_RESETTING <displayId>` | 解码器应清空状态（收到 IDR 前丢弃残帧） |
| `DISPLAY_CONFIG_UPDATE,{json}` | 显示器布局 |
| `cursor,{json}` | 光标图：`curdata`(b64 PNG)、`width/height`、`hotx/hoty`、`handle`(去重 id；空/0=隐藏)。位置由客户端本地跟踪 |
| `system,{json}` | action: `reload`、`rumble,<slot>,<strong>,<weak>,<ms>`、`pointer,<display>,<x>,<y>,<scale>,<seq>`、`command_error,..`、`command_done,..` 等 |
| `KILL <reason>` | 服务端强制断开 |
| `clipboard,<b64>` | 小文本剪贴板 |
| `clipboard_binary,<mime>,<b64>` | 小二进制剪贴板 |
| `clipboard_start,<mime>,<total>` → `clipboard_data,<b64chunk>`×N → `clipboard_finish` | 分块剪贴板（服务端块 16383 字节） |
| `clipboard_reply,<verb>` | 应答标记（cr/REQUEST_CLIPBOARD 请求的载荷前缀） |
| `clipboard_secret` | 密文提示 |
| JSON `{"type":"server_settings","settings":{...}}` | 服务端设置（含 start policy） |
| JSON `{"type":"server_apps",...}` | 应用目录 |
| JSON `{"type":"pipeline_status",...}` | 管线状态 |
| JSON `{"type":"stream_resolution",...}` | 当前分辨率 |
| JSON `{"type":"stream_info","info":{...}}` | 流信息 |
| JSON `{"type":"stream_stats","stats":{...}}` | 统计（订阅 `_stats,1` 后周期推送） |
| `ping` | 应用层心跳（回 `pong`） |

## 5. 文件上传（独立 HTTP 通道，不走 WS）

- `POST {base}/api/upload`，`Content-Type: application/octet-stream`
- 头：`X-Upload-Path: <urlencoded 远端目标路径>`、`Authorization`（Basic/Bearer）
- 小文件（≤64MB）一次 POST 整包
- 大文件分片：每片 ≤64MB，附 `X-Upload-Id`（本次传输 id）、`X-Upload-Offset`（绝对偏移）、`X-Upload-Total`（总大小）、最后一片 `X-Upload-Final: 1`；服务端写 `.part` 后原子改名
- 上传目录由服务端 `SELKIES_UPLOAD_DIR` 决定（默认 `~/Desktop`）

## 6. 回压与 ACK 细节

- 视频块 frameId 为 u16 回绕计数
- 拥有该显示器的客户端每 50ms ACK 一次最新已收（全帧模式）/已呈现（条带模式）帧 id：`CLIENT_FRAME_ACK <id> <heldMs>`
- 重复 id 即存活心跳；服务端据此计算平滑 RTT 并调整发送窗口
- 解码器丢帧时应发 `LOST_FRAME <id>`，编码器将避免跨该帧预测；配合 `REQUEST_KEYFRAME` 恢复

## 7. 仅查看模式（本客户端实现方式）

- 客户端侧强制拦截：所有输入动词（kd/ku/kh/kr/m/m2/sf/sfe/co/cmd/js 等）在仅查看模式下一律不发送，仅维持视频/音频/统计/ACK 通道
- 服务端对 viewer 角色亦有 `VIEWER_ALLOWED_PREFIXES` 输入白名单网关

## 8. 参考
- 官方 monorepo：https://github.com/selkies-project/selkies
- 官方 Web 客户端核心：`selkies/addons/selkies-web-core/`（本协议表逐行对照源）
- LSIO webtop 集成：https://github.com/linuxserver/docker-webtop（基于 `baseimage-selkies`）
