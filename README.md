# Raven Mobile（raven-android）

Raven TUI 的 Android 手机端：一个**全屏 WebView 壳**，网关地址在登录界面输入（首次可预填构建期默认值）。

真正的 Raven 能力（会话、流式输出、审批弹窗、文件交付）全部由网关 Web UI 提供；
本工程不实现任何 Raven 协议，只做容器层适配。

## 工程结构

```
raven-android/
├── settings.gradle.kts          # 仓库与 module 声明
├── build.gradle.kts             # 插件版本（AGP 8.1.4 / Kotlin 1.9.22）
├── gradle.properties
├── gradlew / gradlew.bat        # Gradle 8.5 wrapper
├── gradle/wrapper/
├── local.properties             # sdk.dir（本机路径，勿提交）
├── raven-icon-source.png        # 网关图标源文件（来自 http://127.0.0.1:18792/assets/raven.png）
├── tools/make_icons.py          # 由源图标生成各密度 mipmap / 自适应图标
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/raven/mobile/MainActivity.kt
        └── res/
```

## 首启登录流程

Raven 网关 Web UI 是**同源 SPA，页面本身没有 token 输入框**。远程浏览器拿到会话的唯一途径是网关的
一次性 nonce 链接流程，本工程在 `GatewayAuth.kt` 里用 `HttpURLConnection` 实现（未引入 OkHttp）：

```
1) POST <网关地址>/auth/nonce
    头：X-Raven-Token: <64 位 token>
        Authorization: Basic base64(raven:<密码>)
    → {"nonce":"..."}（一次性，约 30 分钟有效）

2) POST <网关地址>/auth/exchange
    体：{"nonce":"<上一步的 nonce>"}，同样带 Basic Auth
    → 200 + Set-Cookie: raven_session_<端口>=<值>; HttpOnly; Max-Age=2592000; Path=/; SameSite=Strict

3) 从 Set-Cookie 响应头解析 name=value（端口会变，只匹配 raven_session 前缀，不硬编码端口），
    CookieManager.setCookie("<网关地址>", "<name>=<value>") + flush()
    → 之后加载首页即完整可用（WebSocket 升级 /file 等）
```

- **网关地址**：登录界面第一个输入框。规范化（trim、去尾斜杠、必须 https://）后与用户名、
  密码、token 一起加密保存，之后启动直接用存档地址；不填则无法登录（会提示
  「网关地址必须以 https:// 开头」）。
- **凭据存储**：四项都进 `EncryptedSharedPreferences`（`raven_gateway_auth`，
  AES256-GCM，`MasterKey`）——`login_gateway_url` / `login_basic_user` / `login_basic_pass` /
  `login_raven_token`。勾选「不保存 token」时 token 只留在进程内存里，杀进程即失效。
  **APK 内不含任何真实凭据**，只有预填的 Basic Auth 用户名 `raven`。
- **冷启动**：有存档凭据 → 开后台线程跑 1→2→3，期间顶部进度条按 10%→45%→80% 推进，
  成功后写 cookie 并加载首页；任何一步失败走错误页 + 重试（重试会重跑整个认证）。
- **刷新 = 重新认证 + reload**：网关每次重启会重新生成 cookie（token 不变），
  旧 cookie 失效后点一下刷新钮就换新的，不用杀 App。没有凭据时刷新会退回登录界面。
- **token 过期/变化**（exchange 返回 403）：清掉存档的 token，回到登录界面并提示
  「token 不正确或已变化，请重新输入」。
- **Basic Auth 错**（401/407）：登录界面提示用户名或密码不正确。
- **网络不通**：错误页「连不上 Raven 网关 / 无法连接 <当前网关地址>。
  请检查手机网络，或等网关在线。」+ 重试。
- **外来 /auth 链接**：`MainActivity` 注册了 `<构建期配置的 host>/*` 的 VIEW
  intent-filter（无 autoVerify）。从桌面复制 `<网关地址>/auth#<nonce>` 到手机打开，
  App 直接 `loadUrl` 这个完整 URL——网关自己的 /auth 页面会完成 exchange 并跳转 `/`，
  cookie 落 WebView jar，不需要 App 持有 token。已在运行时则走 `onNewIntent`。
- **Basic Auth 兜底对话框**（`onReceivedHttpAuthRequest`）保留：登录成功时会把同一份
  用户名/密码也喂给它，让 WebView 侧的 401 挑战能自动 proceed。

## 版本组合

| 组件 | 版本 |
|------|------|
| JDK | Temurin 17.0.20.1（`~/jdk17`） |
| Gradle | 8.5（`~/gradle-8.5`，同时生成 wrapper） |
| Android Gradle Plugin | 8.1.4 |
| Kotlin | 1.9.22 |
| compileSdk / targetSdk | 34 |
| minSdk | 24 |
| applicationId | `com.raven.mobile` |

## 功能清单

- 全屏 WebView 加载当前网关地址（构建期默认值由 `local.properties` 的 `gateway.url` 经
  `resValue` 注入 `home_url`，运行时可被登录界面输入的值覆盖）
- WebSettings：`javaScriptEnabled`、`domStorageEnabled`（SPA 必需）、`databaseEnabled`、
  `mediaPlaybackRequiresUserGesture=false`、`cacheMode=LOAD_DEFAULT`、`mixedContentMode=NEVER_ALLOW`
- `CookieManager.setAcceptCookie(true)` + `setAcceptThirdPartyCookies`，`onPause` 时 `flush()`，
  登录态 cookie 跨启动持久化
- `WebChromeClient.onShowFileChooser`：接住 `<input type="file>`，经
  `ActivityResultContracts.StartActivityForResult` 回传，支持多选
- `WebViewClient.shouldOverrideUrlLoading`：同域（当前网关 host 及其子域）留在 WebView，
  异域 http(s) 交系统浏览器，`mailto/tel/sms/geo` 等 scheme 交系统
- `onReceivedHttpAuthRequest`：nginx Basic Auth 第二层认证。首次弹窗输入，存入
  `EncryptedSharedPreferences`（androidx.security-crypto，AES256-GCM），之后自动 `proceed()`；
  连续失败会自动清除旧凭证重新询问
- `DownloadListener` → 系统 `DownloadManager`（带 cookie/user-agent，通知栏可见）。
  **同站 URL 额外带 `Authorization: Basic` 头**：`/files/download` 要同时过 nginx 的
  Basic Auth 和网关的会话 cookie，而 DownloadManager 是独立系统服务，不会像 WebView
  那样弹对话框协商 Basic Auth——不带这个头，每次下载都会被 nginx 401 挡死
  （`enqueue()` 照样成功，失败发生在它内部，用户只看到通知栏一个失败条目）。
  凭据从 `EncryptedSharedPreferences` 读，经 `GatewayAuth.basicAuthValue` 拼装；
  读不到就不加头（保持原行为，不崩）。外部站点的下载不带自家凭据（`isSameSite` 把关）。
- **刷新钮走 JS 桥**：`RavenAndroid.refresh()`（`setupWebView` 里 `addJavascriptInterface`
  注册，`onDestroy` 时移除），点击后跑与冷启动同一套认证流程再 reload。
  APK 侧已无下拉刷新手势——`SwipeRefreshLayout` 移除、根布局改回 `FrameLayout`，
  下拉就是普通滚动；刷新钮由移动优化层注入到页面（`raven-mobile/inject/`），不在本工程内。
- 顶部 3dp 细 `ProgressBar` 显示加载进度（`onProgressChanged`）
- 加载失败 / SSL 错误 / 无网络：内置中文错误页 + 「重试」按钮，不白屏
- 返回键：`canGoBack()` 则 `goBack()`，否则退出（`OnBackPressedDispatcher`）
- 深色适配：`Theme.AppCompat.DayNight` + `values-night` 配色 + 状态栏/导航栏明暗随夜间模式切换

## 构建

### 环境准备（本机 WSL 实际做法）

`sudo apt install openjdk-17-jdk-headless` 需要 root 密码、当前会话拿不到，所以 JDK 17 走的是
无 root 的两条路之一（本机用了 Temurin 压缩包，解压即用）：

```bash
# JDK 17：解压到 ~/jdk17（本机实际使用）
mkdir -p ~/jdk17 && tar -xzf temurin-17.tar.gz -C ~/jdk17 --strip-components=1

# 或：apt 免 root 下载 deb 后解包
apt-get download openjdk-17-jdk-headless openjdk-17-jre-headless
mkdir -p ~/jdk17 && for d in *.deb; do dpkg-deb -x "$d" ~/jdk17/; done
# JAVA_HOME=~/jdk17/usr/lib/jvm/java-17-openjdk-amd64

# Android SDK
mkdir -p ~/android-sdk/cmdline-tools
curl -O https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q commandlinetools-linux-11076708_latest.zip -d ~/android-sdk/cmdline-tools
mv ~/android-sdk/cmdline-tools/cmdline-tools ~/android-sdk/cmdline-tools/latest
export JAVA_HOME=~/jdk17
yes | ~/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root="$HOME/android-sdk" --licenses
~/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root="$HOME/android-sdk" \
    "platform-tools" "platforms;android-34" "build-tools;34.0.0"

# Gradle 8.5
curl -O https://services.gradle.org/distributions/gradle-8.5-bin.zip
mkdir -p ~/gradle-8.5 && unzip -q gradle-8.5-bin.zip -d ~/gradle-8.5
```

> 注意 `--sdk_root=~/android-sdk` 这种写法：`~` 不在词首，shell 不展开，会按字面相对路径落到
> 当前目录下。要么写绝对路径，要么用 `--sdk_root="$HOME/android-sdk"`。

### 构建 APK

```bash
cd raven-android
export JAVA_HOME=~/jdk17
export ANDROID_SDK_ROOT=~/android-sdk
echo "sdk.dir=$ANDROID_SDK_ROOT" > local.properties

# 方式一：wrapper（首次会自动下载 Gradle 8.5）
./gradlew assembleDebug

# 方式二：直接用本机解压的 Gradle
~/gradle-8.5/bin/gradle assembleDebug

# 产物
ls -l app/build/outputs/apk/debug/app-debug.apk
```

### 验证

```bash
APK=app/build/outputs/apk/debug/app-debug.apk
~/android-sdk/build-tools/34.0.0/aapt dump badging "$APK" | grep -E "^package:|sdkVersion|launchable-activity"
~/android-sdk/build-tools/34.0.0/aapt dump xmltree "$APK" AndroidManifest.xml | grep -i cleartext
~/android-sdk/build-tools/34.0.0/apksigner verify "$APK"
```

当前构建结果：`app-debug.apk`，`com.raven.mobile`，`MainActivity`，
minSdk 24 / targetSdk 34，`usesCleartextTraffic=false`，深链 host 为构建期配置的网关 host。

### 构建与配置（网关地址）

网关地址有两个来源，二选一同时生效、运行时输入优先：

1. **构建期预填默认值**（可选）：在 `local.properties`（已被 `.gitignore`，永不入库）里加

   ```properties
   gateway.url=https://your-gateway.example.com
   gateway.host=your-gateway.example.com
   ```

   `app/build.gradle.kts` 读这两个键：`gateway.url` 注入 `home_url` 字符串资源（缺省
   `https://gateway.example.com`），`gateway.host` 注入 `AndroidManifest.xml` 的
   intent-filter `android:host`（缺省从 `gateway.url` 解析）。自己本地构建时填真实地址，
   首启登录框就是预填好的；公开 clone 没有这两行，默认占位地址。

2. **运行时输入**（始终可用）：登录界面第一个输入框「网关地址」，手输完整地址
   （必须以 `https://` 开头），规范化后与用户名/密码/token 一起加密保存在本机，
   之后启动自动使用。改地址就重新登录一次。

   - 没有 `local.properties` 也能构建、能用：首启手输网关地址即可。
   - **深链（从别处点开 `<网关地址>/auth#<nonce>` 一次性链接直接进 App）只对构建期
     配置的 host 生效**——intent-filter 的 host 是打包期固定的，运行时不改。

已知遗留：`app/build.gradle.kts` 里 `androidx.swiperefreshlayout:swiperefreshlayout:1.1.0`
依赖未删（代码里已无任何引用，库类仍会打进 dex）。要彻底清掉需再改构建文件。

## Release 签名

当前只用 debug keystore（`~/.android/debug.keystore`，首次构建自动生成）。
如需发布签名：在 `app/build.gradle.kts` 增加 `signingConfigs`，配置 `storeFile/storePassword/
keyAlias/keyPassword`（建议放在 `~/.gradle/gradle.properties` 或环境变量，不要提交进仓库），
然后 `./gradlew assembleRelease`。

## 图标

`raven-icon-source.png` 来自网关 `http://127.0.0.1:18792/assets/raven.png`。
重新生成各密度图标：

```bash
python3 tools/make_icons.py
```
