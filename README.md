# DouDouTV —— 安卓手机 + TV 双端视频播放器

自研的安卓个人影音客户端，**只对接两类来源：Emby 与 TVBox**。
界面与交互形态参照 VidHub 的成熟做法（沉浸式大图首页、海报墙、剧集详情页、平滑转场），
但代码为完全原创实现，基于公开的开源组件，不含任何对第三方商业应用的逆向或反编译产物。

---

## 一、能力总览

| 模块 | 实现 |
|---|---|
| 来源类型 | **Emby**（服务器+账号）、**TVBox**（苹果CMS 风格 JSON 接口） |
| 播放内核 | Media3 / ExoPlayer 1.4.1，支持 MKV、HEVC、AV1、HLS、DASH |
| Emby 鉴权 | `Users/AuthenticateByName` 换 AccessToken，落库复用，无需反复登录 |
| Emby 影库 | `Users/{id}/Items` 拉电影+剧集，`Shows/{id}/Episodes` 拉分集 |
| Emby 播放 | `Videos/{id}/stream?static=true` 原码流直传，局域网不转码 |
| TVBox 接口 | `ac=videolist` 列表 / `ac=detail` 详情 / `ac=list` 分类 |
| TVBox 解析 | `vod_play_from` + `vod_play_url` 按 `$$$` `#` `$` 三级拆解多线路 |
| 首页 | Hero 大图轮播（自动 6.5s）→ 继续观看横滑 → 电影 → 剧集 |
| 详情页 | backdrop 沉浸头图 + 海报信息栏 + 季切换 + 分集卡 |
| 转场动画 | `AnimatedContent`：进详情右推淡入、返回左滑浮现、切标签交叉淡化 |
| 断点续播 | Room 记录进度，首页「继续观看」按最近播放排序 |
| 自动连播 | 单集播完自动接 `seriesKey` 内下一集 |
| 播放缓存 | 512MB LRU 磁盘缓存 |
| 双端适配 | 单 APK 双入口；手机底部导航 / 电视左侧导航；遥控器焦点放大描边 |

---

## 二、工程结构

```
MyVidHub/
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
├── README.md
└── app/
    ├── build.gradle.kts / proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/
        │   ├── values/{strings,colors,themes}.xml
        │   └── drawable/{ic_app_icon,tv_banner}.xml
        └── java/com/tabbit/myvidhub/
            ├── MyVidHubApp.kt              # Application，持有 Room 单例
            ├── MainActivity.kt             # 单 Activity + 路由 + 转场动画
            ├── core/Prefs.kt               # 应用级偏好
            ├── data/
            │   ├── MediaRepository.kt      # Emby/TVBox 入库、详情、进度
            │   ├── db/
            │   │   ├── AppDatabase.kt      # v2，破坏性迁移
            │   │   ├── SourceDao.kt
            │   │   ├── MediaDao.kt
            │   │   └── Converters.kt
            │   ├── model/Entities.kt       # SourceEntity / MediaEntity
            │   ├── emby/EmbyClient.kt      # ★ Emby REST 客户端
            │   └── tvbox/TvBoxClient.kt    # ★ TVBox/CMS 客户端
            ├── player/PlayerFactory.kt     # 缓存 + 缓冲策略 + ExoPlayer 装配
            └── ui/
                ├── LibraryViewModel.kt
                ├── theme/Theme.kt          # AppColors 深色影音配色
                ├── components/
                │   ├── PosterCard.kt       # 2:3 海报卡（焦点放大+描边）
                │   ├── ContinueCard.kt     # 16:9 继续观看卡
                │   └── HeroBanner.kt       # 首页大图轮播
                ├── home/HomeScreen.kt      # 首页
                ├── detail/DetailScreen.kt  # 剧集/电影详情
                ├── sources/SourcesScreen.kt# 来源增删（仅 Emby / TVBox）
                ├── settings/SettingsScreen.kt
                └── player/ViewerActivity.kt# 播放页
```

---

## 三、如何得到可安装的 APK

> 本仓库已包含：完整源码、Gradle Wrapper（`gradlew`，无需预装 Gradle）、一键脚本 `build.sh`、
> 云端构建配置 `.github/workflows/build-apk.yml`。三条路任选一条，产物都是
> `app/build/outputs/apk/debug/app-debug.apk`。

| 方式 | 适用场景 | 你需要准备 | 首次耗时 |
|---|---|---|---|
| **A. GitHub Actions 云端构建** | 不想在电脑上装任何 Android 环境 | 一个 GitHub 账号 | 约 6–10 分钟 |
| **B. `./build.sh` 一键构建** | 已有 JDK 与 Android SDK | JDK 17+、Android SDK 34 | 约 10 分钟（含下载依赖） |
| **C. Android Studio** | 想改代码、看 Compose 预览、断点调试 | 安装 Android Studio | 约 5 分钟同步 |

### 方式 A：GitHub Actions 云端构建（最省事，推荐给只想拿安装包的人）

1. 在 GitHub 新建一个空仓库（Private 也可以，Actions 免费额度足够）
2. 把 `MyVidHub` 目录内容推到该仓库的 `main` 分支：
   ```bash
   cd MyVidHub
   git init && git add . && git commit -m "init"
   git remote add origin https://github.com/<你的用户名>/<仓库名>.git
   git push -u origin main
   ```
3. 推送后自动触发构建（`main`、`master`、`dev` 分支均已配置，也可在仓库 **Actions** 页点 `Build APK` → `Run workflow` 手动触发）
4. 构建完成（绿色 ✓）后，在仓库 **Releases** 页下载 `DouDouTV-v2.0-<分支>.apk`
5. 下载到的就是原始 APK 文件，无需解压 → 传到手机 / U 盘拷到电视盒子安装

CI 已固定 JDK 11、Gradle 7.5、AGP 7.4.2、SDK Platform 34 与 Build-Tools 34.0.0，
直接复用 ubuntu-latest 预装的 Android SDK，不额外安装 cmdline-tools，
并开启 `if-no-files-found: error`——若编译失败会直接报红，不会给你一个空包。

### 方式 B：命令行一键构建

前置条件（缺一不可）：

- **JDK 11 或 17**（本工程以 JDK 11 编译通过为基准；AGP 7.4.2 不支持 JDK 21）
- **Android SDK**，含 `platforms/android-34` 与 `build-tools/34.0.0`
- 网络能访问 `dl.google.com` 与 `repo.maven.apache.org`

```bash
cd MyVidHub
bash build.sh          # Windows 用 Git Bash 或 WSL
```

脚本会依次校验 Java 版本、定位 SDK 并写 `local.properties`、缺失组件时自动调
`sdkmanager` 安装、最后执行 `gradlew assembleDebug`，并打印 APK 路径与 `adb install` 命令。

### 方式 C：Android Studio（推荐用于开发）
1. Android Studio Hedgehog (2023.1.1) 或更新
2. `File → Open` 选择 `MyVidHub` 目录
3. 等待 Gradle 同步（首次下载依赖约 400–600MB）
4. 连接设备/模拟器，点 ▶ 运行

### IntelliJ IDEA（Ultimate 版）
本工程是标准 Gradle + AGP 项目，IDEA 可以打开，但需要区分版本能力：

**IntelliJ IDEA Ultimate**（含 Android 插件）
1. `File → Open` 选择 `MyVidHub` 的 `build.gradle.kts`（或整个目录），选 **Open as Project**
2. `Settings → Plugins → Marketplace` 搜 **Android**，安装并重启（
   该插件自 2026.2 起仍在更新，兼容构建号 262.*）
3. `Settings → Languages & Frameworks → Android SDK` 指定 SDK 路径（需 Platform 34）
4. `Settings → Build Tools → Gradle → Gradle JVM` 设为 **JDK 11 或 17**
5. 首次 Sync 会下载依赖；完成后可直接选设备 Run

**IntelliJ IDEA Community**（无 Android 插件）
- 可以正常打开、浏览与编辑代码（Kotlin / Compose 语法高亮、跳转、补全均可用）
- 但没有 Android SDK 集成、没有 Run 配置、没有 Compose Preview、没有 Layout Inspector
- 想构建 APK 只能用终端跑 `./gradlew assembleDebug`

> 纯 Android 开发仍建议用 Android Studio：Compose Preview、Layout Inspector、APK Analyzer、
> 设备镜像等工具链比 IDEA 领先数个版本。

### 命令行
```bash
# 需要 JDK 11（或 17）+ Android SDK Platform 34
export ANDROID_HOME=$HOME/Android/Sdk          # macOS/Linux
# Windows: set ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk

echo "sdk.dir=$ANDROID_HOME" > local.properties

./gradlew assembleDebug                         # macOS/Linux
gradlew.bat assembleDebug                       # Windows
# → app/build/outputs/apk/debug/app-debug.apk
```

Wrapper 已随工程提供（`gradlew` / `gradlew.bat` / `gradle/wrapper/gradle-wrapper.jar`），
无需预装 Gradle，首次运行会自动下载 Gradle 7.5。

### 版本矩阵（改版本前请对照）

| 组件 | 本工程版本 | 最低要求 |
|---|---|---|
| Gradle | 7.5 | AGP 7.4.2 要求 ≥ 7.5 |
| Android Gradle Plugin | 7.4.2 | 需 JDK 11 或 17（不支持 21） |
| Kotlin | 1.9.22 | 与 Compose Compiler 严格配对 |
| Compose Compiler | 1.5.8 | 必须匹配 Kotlin 1.9.22 |
| KSP | 1.9.22-1.0.17 | 版本号必须与 Kotlin 严格一致 |
| JDK | 11 | 17 亦可（AGP 7.4.2 不支持 21+） |
| jvmTarget | 1.8 | 编译产物兼容 Java 8 字节码，最终打包为 DEX |
| compileSdk / targetSdk | 34 | minSdk 21 |

> ⚠️ Kotlin 与 Compose Compiler 是强绑定关系，单独升级其中一个必定编译失败。
> KSP 版本号也必须和 Kotlin 主版本精确对齐（1.9.22-X.Y.Z 对应 Kotlin 1.9.22）。
> 若要升到 Kotlin 2.x，需同时启用 `org.jetbrains.kotlin.plugin.compose` 插件并移除
> `composeOptions { kotlinCompilerExtensionVersion }`。

---

## 四、使用流程

### 1. 添加 Emby 来源
- 来源页 → 「添加来源」→ 选 **Emby**
- 服务器地址：`https://emby.yourdomain.com`（不带 `/web`）
- 填用户名密码 → 点添加，程序会自动 `AuthenticateByName` 换 token
- 若显示「未登录」，点该卡片右侧刷新按钮重新登录

### 2. 添加 TVBox 来源
- 选 **TVBox**，填接口地址，需满足 `?ac=videolist` 返回 JSON
  例如形如 `https://example.com/api.php/provide/vod/`
- 添加后同步即可拉到首页列表

### 3. 同步与播放
- 首页右上角 ⟳ 同步全部来源
- 点海报进详情页；剧集页可切季、选集
- 详情页「继续观看」按钮会接着上次的进度
- 播放页：确认键暂停、左右键 ±10s、返回退出

### TV 端
- 若盒子未被识别为 TV：设置 → 打开「强制电视模式」
- 遥控器焦点会在海报上放大并显示蓝色描边

---

## 五、几个设计取舍

### 1. TVBox 为什么分两次请求
CMS 接口的列表（`ac=videolist`）**不含播放地址**，只有 `ac=detail` 才返回 `vod_play_url`。
因此列表阶段只入库「剧集壳」，进入详情页才按需拉分集 —— 否则同步几百部剧会打出几百次请求。
同理，只有 http(s) 直链线路会被保留，迅雷/磁力等客户端专用协议直接丢弃，避免塞给 ExoPlayer 报错。

### 2. 唯一索引为什么放在 remoteId
剧集壳条目的 `uri` 是空字符串。如果唯一索引建在 `uri` 上，第二个壳就会因重复而插入失败。
改用 `remoteId`（`emby:来源id:itemId` / `tvbox:来源id:vodId:集数`）天然唯一且稳定。

### 3. Emby 用 static=true
`static=true` 表示不转码、直接原码流传输。局域网千兆下这是画质与延迟的最优解，
代价是客户端必须能解码（Media3 对 HEVC/MKV 支持良好）。若遇到不支持的编码，
把 `EmbyClient.playbackUrl` 的 `static` 改为 `false` 即可让服务器转码。

### 4. 转场动画的"方向感"
VidHub 的转场之所以舒适，是因为方向与层级一致：
- 进详情 = 下钻 → 新页面从右侧小幅推入（`it/6`）并淡入
- 回列表 = 上浮 → 详情向左退（`it/4`），列表从左侧轻滑回来
- 切底部标签 = 平级 → 只做交叉淡化，不引入方向，避免"左右乱跳"

---

## 六、可继续扩展

1. **Emby 播放进度回写**：目前进度只存本地。可加 `Sessions/Playing/Progress` 上报，实现多端同步
2. **媒体键/遥控器完整控制条**：自定义 `PlayerView` 控制器，适配 TV 十英尺 UI
3. **TVBox 多线路切换 UI**：`loadTvBoxDetail` 已返回全部线路，加个线路选择弹窗即可
4. **搜索**：`TvBoxClient.search` 与 `EmbyClient.search` 均已实现，缺页面
5. **演员/相似推荐**：Emby `Items/{id}/Similar` 与 `People` 字段
6. **海报墙性能**：条目过千时给 Room 加分页（Paging 3），Coil 配 memory/disk 双层缓存

---

## 七、许可与合规

- 本项目代码可自由使用、修改、分发
- 依赖：Media3 (Apache-2.0)、OkHttp (Apache-2.0)、Room (Apache-2.0)、Coil (Apache-2.0)、Compose (Apache-2.0)
- 请仅用于播放你拥有合法权利的内容；第三方接口的可用性与合法性由使用者自行判断
