# MyVidHub —— 安卓手机 + TV 双端视频播放器

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

## 三、编译

### Android Studio（推荐）
1. Android Studio Hedgehog (2023.1.1) 或更新
2. `File → Open` 选择 `MyVidHub` 目录
3. 等待 Gradle 同步（首次下载依赖约 400–600MB）
4. 连接设备/模拟器，点 ▶ 运行

### 命令行
```bash
# 需要 JDK 17 + Android SDK Platform 34
export ANDROID_HOME=$HOME/Android/Sdk
echo "sdk.dir=$ANDROID_HOME" > local.properties

# 仓库未内置 gradle-wrapper.jar（二进制），首次补全
gradle wrapper --gradle-version 8.7

./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

> 未内置 wrapper jar 是刻意的：二进制文件不经过文本编辑流程，交给 Gradle/IDE 自动补全更可靠。

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
