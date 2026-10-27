<p align="center"><img src="docs/banner.png" alt="Dango Music" width="640"></p>

# Dango Music

在 Minecraft 里听网易云音乐、酷狗音乐与酷狗音乐概念版的 mod（1.8.9 Forge / 1.20.1 Fabric · Forge / 1.21.1 · 1.21.11 · 26.2 · 26.3 Fabric · NeoForge）：液态玻璃风格的播放器、漂浮在世界里的 3D 歌词、Song card、灵动岛，还能和朋友一起听歌。

*A Minecraft mod (1.8.9 Forge / 1.20.1 Fabric & Forge / 1.21.1, 1.21.11, 26.2 & 26.3 Fabric & NeoForge) that brings NetEase Cloud Music, KuGou Music and KuGou Lite players into Minecraft — liquid-glass UI, 3D in-world lyrics, a song card HUD, a Dynamic Island and Listen Together.*

## 截图

<p align="center"><img src="docs/screenshots/lyrics-3d.jpg" alt="3D 歌词" width="100%"></p>
<p align="center"><sub>漂浮在世界里的 3D 歌词</sub></p>

| | | |
|:---:|:---:|:---:|
| <img src="docs/screenshots/home.jpg" alt="首页"> | <img src="docs/screenshots/playlist.jpg" alt="歌单列表"> | <img src="docs/screenshots/coverflow.jpg" alt="封面墙"> |
| 首页 · 推荐歌单 | 歌单 · 列表视图 | 歌单 · 封面墙视图 |

| | |
|:---:|:---:|
| <img src="docs/screenshots/settings-lyrics.jpg" alt="歌词设置"> | <img src="docs/screenshots/settings-music.jpg" alt="Music &amp; HUD 设置"> |
| 设置 · Lyrics | 设置 · Music &amp; HUD |

## 功能

- **多音乐平台支持**（播放器侧栏一键切换）：
  - **网易云音乐**：每日推荐、排行榜、我的歌单、扫码登录、VIP 会员标识
  - **酷狗音乐**：热门排行榜、推荐歌单、我创建和收藏的歌单、扫码登录、VIP 会员标识
  - **酷狗音乐概念版**：专属精选歌单、个性化推荐、我创建和收藏的歌单、扫码登录、VIP 会员标识
- **播放器**（默认按 `M` 打开）：每日推荐、排行榜、我的歌单、搜索；列表 / 封面墙两种视图；歌单内筛选、一键定位到正在播放；平滑滚动和切换动画
- **逐字歌词**：各平台均支持逐字歌词同步展示
  - 3D 歌词漂浮在世界里，跟着演唱一句句出现，暂停时自动隐藏
  - Deep Glow 发光、Wiggle 抖动、文闪、Word pop
  - 自动拆开"两句写在一行"的歌词
- **Song card**：iOS 风格的歌曲卡片，打开聊天栏可以拖动位置
- **灵动岛**：屏幕顶部显示正在播放，切歌时展开
- **一起听歌**：开房间、分享房间码，所有人自动同步到同一首歌、同一秒（基于 Firebase）
- **设置**：都在播放器侧栏的 ⚙ 里；可以绑定快捷键（播放 / 暂停、上一首、下一首、音量、开关歌词）
- **扫码登录**：支持使用对应手机 App 扫码登录，自动同步头像、昵称和歌单

## 安装

按游戏版本选 jar（在 [Releases](https://github.com/PaidaxingTuT/DangoMusic/releases) 或 GitHub Actions Artifacts 下载）：

| 版本 | 文件 | 需要 |
|---|---|---|
| 1.8.9 | `DangoMusic-x.x.x+1.8.9.jar` | Forge 1.8.9（11.15.1.2318），或支持 Forge mod 的客户端（如 Lunar） |
| 1.20.1 Fabric | `DangoMusic-x.x.x+1.20.1-fabric.jar` | Fabric Loader + [Fabric API](https://modrinth.com/mod/fabric-api) |
| 1.20.1 Forge | `DangoMusic-x.x.x+1.20.1-forge.jar` | Forge 47.x |
| 1.21.1 Fabric | `DangoMusic-x.x.x+1.21.1-fabric.jar` | Fabric Loader + [Fabric API](https://modrinth.com/mod/fabric-api) |
| 1.21.1 NeoForge | `DangoMusic-x.x.x+1.21.1-neoforge.jar` | NeoForge 21.1.x |
| 1.21.11 Fabric | `DangoMusic-x.x.x+1.21.11-fabric.jar` | Fabric Loader + [Fabric API](https://modrinth.com/mod/fabric-api) |
| 1.21.11 NeoForge | `DangoMusic-x.x.x+1.21.11-neoforge.jar` | NeoForge 21.11.x |
| 26.2 Fabric | `DangoMusic-x.x.x+26.2-fabric.jar` | Fabric Loader + [Fabric API](https://modrinth.com/mod/fabric-api) |
| 26.2 NeoForge | `DangoMusic-x.x.x+26.2-neoforge.jar` | NeoForge 26.2.x |
| 26.3 Fabric | `DangoMusic-x.x.x+26.3-fabric.jar` | Fabric Loader + [Fabric API](https://modrinth.com/mod/fabric-api) |
| 26.3 NeoForge | `DangoMusic-x.x.x+26.3-neoforge.jar` | NeoForge 26.3.x |

1. 把对应的 jar 放进 `.minecraft/mods`（Fabric 版还要放 Fabric API）
2. 进游戏按 `M` 打开播放器，可在侧栏切换平台并点击左下角头像扫码登录

配置、登录凭证、缓存都存在 `.minecraft/config/dangomusic/`，各个版本通用。

26.x 的歌词和播放器界面只支持 OpenGL：视频设置里把图形 API 选成 Vulkan 时只能放音乐，进游戏会在聊天栏提示。

## 构建

### GitHub Actions CI 自动构建
仓库配置了全版本自动构建工作流（`.github/workflows/build.yml`），每次推送或发版会自动打包涵盖 1.8.9 到 26.3 的所有 Forge / Fabric / NeoForge 模组 jar，并在工作流制品（Artifacts）中归档。

### 本地构建
需要 JDK 8（1.8.9）、JDK 21（1.20.1～1.21.11）和 JDK 25（26.x）。`build-all.bat` 默认用 `C:\Program Files\Java\jdk-21`
和 `C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot`，装在别处的话先设置环境变量 `DANGO_JDK21` / `DANGO_JDK25`。

```bash
build-all.bat                  # 构建所有版本，jar 都收集到 dist/
```

只构建 1.8.9：

```bash
cd legacy
gradlew build                  # 输出 legacy/build/libs/DangoMusic-x.x.x+1.8.9.jar
gradlew runClient              # 开发环境里直接运行
```

### 项目结构

```
version.properties   版本号（所有 Minecraft 版本共用）
common/core          核心：音乐平台支持、设置、播放器、一起听歌（不依赖 Minecraft）
common/ui            界面：多平台切换侧栏、播放器窗口、歌词、Song card、灵动岛（只通过 Gfx / Platform 接口画）
legacy/              Forge 1.8.9 平台层：实现 Gfx / Platform，OpenGL 2 着色器、字体
modern/              1.20.1～1.21.11 的平台层（一个 Gradle 项目，每个版本 + 加载器一个子项目）
  src/main/            所有高版本共用：OpenGL 3.2 core 着色器、字体、按键、播放器界面
  src/mc<版本>/        某个 Minecraft 版本专用的几处写法
  versions/<版本-加载器>/  Fabric / Forge / NeoForge 入口和 mod 信息文件
modern26/            26.x 的构建（Minecraft 不再混淆，换了一套构建插件和 JDK 25）
                     代码和 modern/ 共用（modern/src/main + modern/src/mc26.x），这里只有构建配置和入口
  versions/<版本-加载器>/  Fabric / NeoForge 入口、mixin 和 mod 信息文件
```

`common` 不单独构建，由各平台层用 `includeBuild("../common")` 引进去，打进同一个 jar。
`common` 的依赖里没有 Minecraft，所以写错了地方（在共用代码里用了游戏的类）会直接编译不过。

### 一起听歌（Firebase）

源码里不带 Firebase 配置，不配置也能构建、能用，只是"一起听歌"会提示未配置。要启用：

1. 在 [Firebase 控制台](https://console.firebase.google.com/) 新建项目
   - Authentication → 登录方法 → 开启 **匿名**
   - 创建 **Realtime Database**
2. 把 `firebase.properties.example` 复制成 `common/core/src/main/resources/firebase.properties`，填上 `apiKey` 和 `databaseUrl`
3. 数据库规则设成下面这样：只有登录用户能读写；房间只能由房主改，别人只能改自己的成员信息；不能覆盖别人正在用的房间（房主超过 10 分钟没心跳的废弃房间除外）：

```json
{
  "rules": {
    "rooms": {
      "$code": {
        ".read": "auth != null",
        ".write": "auth != null && (!data.exists() || data.child('host').val() === auth.uid || data.child('members').child(data.child('host').val()).child('lastSeen').val() < now - 600000)",
        ".validate": "newData.child('host').val() === auth.uid || newData.child('host').val() === data.child('host').val()",
        "members": {
          "$uid": {
            ".write": "auth != null && auth.uid === $uid && root.child('rooms').child($code).exists()"
          }
        }
      }
    },
    "clock": {
      "$uid": {
        ".read": "auth != null && auth.uid == $uid",
        ".write": "auth != null && auth.uid == $uid"
      }
    }
  }
}
```

注意：只有连的是同一个 Firebase 项目的人才能一起听歌。

## 说明

- 本模组使用各音乐平台的公开接口，仅供个人学习交流；歌曲与歌词版权归各平台和版权方所有
- 部分海外地区或 VIP 专享歌曲需要对应平台的会员权限

## 第三方与致谢

- [MoeKoeMusic](https://github.com/MoeKoeMusic/MoeKoeMusic)（GPL-2.0）：酷狗音乐接口与协议参考
- [EchoMusic](https://github.com/hoowhoami/EchoMusic)（GPL-3.0）：酷狗音乐概念版接口与协议参考
- [KuGouMusicApi](https://github.com/MakcRe/KuGouMusicApi)（MIT）：酷狗音乐相关接口参考
- [Inter](https://github.com/rsms/inter)（SIL Open Font License 1.1），[Roboto Slab](https://fonts.google.com/specimen/Roboto+Slab)（Apache License 2.0）
- [JLayer](http://www.javazoom.net/javalayer/javalayer.html)（LGPL）：MP3 解码
- [ZXing](https://github.com/zxing/zxing)（Apache License 2.0）：登录二维码
- Kotlin 标准库（Apache License 2.0）

## 许可证

[GPL-3.0](LICENSE)：可以自由使用、修改和分发；分发修改后的版本时也必须以 GPL-3.0 开源。
