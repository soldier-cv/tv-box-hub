# BoxHub

把斐讯 N1（Android 7.1）变成局域网可控的设备：**手机浏览器当遥控器 + 文件管理器**，电视上打开 App 时才开端口。

零第三方依赖，release APK 约 730 KB。

---

## 它做什么

| 能力 | 说明 |
|---|---|
| 遥控器 | D-pad / 返回 / 主页 / 菜单 / 音量 / 播放控制 / 文字输入 / 触摸板 |
| 触摸板 | 在手机上滑动 → 焦点连续移动；轻点 → 确认 |
| 截屏预览 | 1 fps 低帧率画面，用来确认当前焦点在哪（不是投屏） |
| 文件管理 | 浏览、上传（拖拽）、重命名、新建目录、删除 |
| 浏览器播放 | 视频/音频/图片直接在手机浏览器里播，**盒子不解码** |
| 安装 APK | 点「安装」→ 拉起系统安装器 → 电视上点确认 |
| 实时状态 | WebSocket 推送日志；断线自动降级为 3 秒轮询 |

## 端口行为

端口 `8790` 的生命周期由**持有者计数**决定，不是"谁调了 start"：

| 持有者 | 何时持有 |
|---|---|
| `MainActivity` | App 打开期间。**按 HOME 不会释放**（Android 不因 HOME 销毁 Activity），所以遥控过程中端口一直在 |
| `HubService` | 仅当你在电视上勾选「保持运行」时 |

两种模式：

- **保持运行 关（默认）** —— 严格按最初的字面要求：App 打开才有端口。
  按 HOME 回桌面后端口**仍然可用**，遥控完不需要再碰 BoxHub；
  但切走久了被系统回收进程、或从最近任务划掉，端口就关。
- **保持运行 开** —— 额外启动前台服务（常驻通知），端口不再依附于 Activity，
  进程被内存回收的概率大幅下降。这是 2GB 的 N1 上更实用的默认用法。
  通知里可以直接「停止」。

无论哪种，都没有开机自启、没有 `RECEIVE_BOOT_COMPLETED`：盒子重启后需要手动打开一次。

前端只能表达请求，不能维持端口——所以**盒子上的 BoxHub 被关掉，手机端会立刻断线**。
界面会显示「保持运行 已开启/未开启」并提示确认电视上的 App 是否还在运行。

---

## 安装

1. 取 `app/build/outputs/apk/release/BoxHub-1.0.0-release.apk`
   （文件名由 `app/build.gradle.kts` 顶部的 `appName` / `appVersionName` 生成，
   改版本号后重新 `assembleRelease` 即可，无需手动改名）
2. 传到盒子（U 盘 / 盒子自带文件管理 / 一次性 ADB 均可）
3. 电视上打开 BoxHub，允许「存储写入」权限
4. **建议勾选「保持运行」**（否则长时间使用可能被系统回收进程）
5. 记下屏幕上的 4 位配对码
6. 手机连同一局域网，浏览器打开 `http://<盒子IP>:8790`，输入配对码

首次打开页面会弹出配对码键盘，输完 4 位即自动进入。提示语区分两种失败：
「配对码不正确」是码错了，「连不上盒子」是电视上的 App 已经退出 —— 后者重输配对码
没有意义。盒子重启换码后，手机上会自动重新弹门，照新码输入即可。

日常用法：**打开一次 → 按 HOME 回桌面 → 之后在手机上操作，不必再碰电视。**
遥控其他应用时，只要别从最近任务里把 BoxHub 划掉，端口就在。

---

## 装到盒子上后**第一件要看的事**

电视上「设备能力检测」区域会直接告诉你这台机器的能力：

| 行 | 含义 | 不通过时怎么办 |
|---|---|---|
| 远程注入 | 遥控能不能用 | 显示 `✗` 则本机固件禁止注入，遥控不可用 |
| input 命令 | `/system/bin/input` 能否执行 | 不可执行时自动降级到框架注入路径 |
| root (su) | 有没有 root | `✗` 说明只能用 adb 遥控，详见「已知限制」 |
| 截屏预览 | `screencap` 能否执行 | 不可用只影响预览，遥控不受影响 |
| 存储写入 | 权限是否已授予 | 未授予则上传会失败 |

遥控启动时实测，按顺序选用第一条可用路径：

1. `su -c "input ..."` —— 有 root 时唯一真正管用的路
2. `input keyevent` —— 和 ADB 同一条路，但 App 身份通常没有执行/注入权限
3. `Instrumentation.sendKeyDownUpSync`
4. `UiAutomation.injectInputEvent`（反射 `connect()`）

触摸板优先用框架注入路径，因为每次手势都 `exec` 一个进程太慢。

> 探测只看**退出码**。早先的判定把「有任何 stderr 输出」也算成功，于是
> `input` 打印一句 `SecurityException` 就会被报成 `✓ 遥控可用`，而实际按键全部
> 无效 —— 比报「不可用」更糟，因为它让用户以为是自己没对准。

---

## 安全

三层，从外到内：

**1. 仅局域网。** 监听 `0.0.0.0` 才能被手机访问，但 `LanScope` 会在读到任何请求之前
先检查来源地址：只有私有网段（10/8、172.16/12、192.168/16、169.254/16、
100.64/10 CGNAT、IPv6 的 fc00::/7 与 fe80::/10）与回环地址会被服务，其余一律
**403 拒绝，WebSocket 升级同样拒绝**。这样即使路由器把端口转到了公网，接口也不会应答。

**2. 配对码。** 所有 `/api/*` 与 `/dl/*` 需要 4 位码，固定长度比较。失败后按
0.4s → 0.8s → 1.2s …递增退避，上限 5s，挡住同网段的穷举。

**退避只作用于猜错。** 配对码正确时永远立即放行 —— 早退避是为了限速穷举，
不是为了拦下正在照着电视屏幕重输配对码的人（见「测试抓到的真实缺陷」第 6 条）。

**3. 路径。** 所有路径经 `canonicalPath` 校验并限制在 `/storage/emulated/0` 内，
`..`、绝对路径、符号链接逃逸全部拒绝；文件名拒绝 `/ \ :` 与 NUL。同名文件自动
加 `(1)` 后缀，不覆盖。

其他：FileProvider 只暴露 `Download/`，`exported=false` + 仅通过 grant 授权。
**不要把 8790 端口转发到公网。**需要远程访问请用 Tailscale / WireGuard。

刻意不提供「执行任意 shell」接口 —— 一旦开放就等价于 5555 端口（斐讯 N1 正是
ADB.Miner 挖矿木马的重灾区）。

---

## 针对 Android 7 的取舍

`minSdk 24` / **`targetSdk 28`**，刻意停在 Android 9 及更早的行为：

- 无 Scoped Storage → 直接读写 `/storage/emulated/0`
- 无前台服务类型声明要求
- 无通知权限
- 装包无需 `REQUEST_INSTALL_PACKAGES`（Android 8 才引入）
- 「未知来源」是系统里的**一个总开关**，不是逐 App 授权

代价：应用无法上 Google Play（Play 要求 targetSdk ≥ 33）。仅侧载使用，不影响。

另外 Android 7 没有 `PackageInstaller.Session`（API 26+），所以装包走
`Intent.ACTION_INSTALL_PACKAGE` + 自建 FileProvider，APK 需要先落盘。

---

## 开发

```powershell
# 构建
.\gradlew.bat assembleDebug assembleRelease

# 跑 HTTP/WebSocket/文件层/配对码的 159 项 socket 级测试（JVM，不需要设备）
.\gradlew.bat :servertest:run

# 跑手机端 WebUI 的 120 项行为测试（jsdom 加载真实 index.html）
cd servertest; npm install; cd ..
.\gradlew.bat :servertest:uitest

# 在桌面上预览手机端 WebUI（mock 后端，含合成截屏图 + 请求日志）
node servertest\uimock.js 8791
# 浏览器打开 http://127.0.0.1:8791/
# 配对码 1357；查看 UI 实际发出了哪些请求： http://127.0.0.1:8791/mock/log

# 改了配色/图标后重新生成 PNG（提交生成的 PNG 本身，脚本只留作可复现的来源）
java tools\IconGen.java app\src\main\res
```

### 端到端设备测试（模拟器 / 真机）

前两套测试都 stub 掉了网络和 WebSocket，**恰好绕开了 socket 鉴权这个接缝**，
所以缺陷 12 是在这里才抓到的。`devicetest.js` 不做任何 stub：

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

# 1. 装到设备上并打开（电视端会显示配对码，就在最上面）
& $adb install -r app\build\outputs\apk\release\BoxHub-1.0.0-release.apk
& $adb shell am start -n com.boxhub/.MainActivity
# 建议先在界面上勾上「保持运行」：否则切到浏览器后系统可能冻结进程，
# 端口还在监听但请求全部挂起（缺陷 14）

# 2. 读配对码（电视界面第一屏就是），或从通知栏 / uiautomator dump 里取
& $adb shell uiautomator dump /sdcard/ui.xml; & $adb shell cat /sdcard/ui.xml

# 3. 转发端口，用设备自带浏览器打开控制台
& $adb forward tcp:8790 tcp:8790
& $adb forward tcp:9333 localabstract:chrome_devtools_remote
& $adb shell am start -a android.intent.action.VIEW -d http://<盒子IP>:8790

# 4. 跑测试：填配对码 → 应关掉配对码门、连上 socket、显示设备信息
node servertest\devicetest.js 4821 9333
```

失败时退出码非 0；`PROBE=1` 会额外打印页面实际发出的 fetch / 定时器 / socket 帧。
**Chrome 的首个"通知"弹窗会吃掉模拟点击**，先手动点掉「No thanks」。

### 代码结构

```
app/src/main/
├─ assets/index.html            手机端 WebUI（单文件，零构建）
├─ java/com/boxhub/
│  ├─ App.kt                    持有者计数、配对码、设备信息、事件广播
│  ├─ HubService.kt             可选前台服务（「保持运行」开关）
│  ├─ MainActivity.kt           电视端唯一界面（配对码 + 能力检测 + 开关）
│  ├─ Routes.kt                 HTTP 路由（query 驱动，无请求体解析）
│  ├─ Ctx.kt
│  ├─ http/
│  │  ├─ MiniServer.kt          手写 HTTP/1.1 + Range + chunked + 访问门
│  │  ├─ Ws.kt                  手写 RFC 6455 WebSocket（文本帧）
│  │  ├─ PinGuard.kt            配对码校验 + 穷举退避（纯逻辑，可单测）
│  │  └─ LanScope.kt            私有网段判定（纯函数，可单测）
│  ├─ device/
│  │  ├─ Shell.kt               带超时的命令执行，stdout/stderr 双线程排空
│  │  ├─ KeyInjector.kt         按键/滑动/文字，三层降级
│  │  └─ ScreenCapture.kt       screencap → PNG
│  ├─ fs/FileOps.kt             路径守卫 + 文件操作（纯 java.io，可单测）
│  ├─ install/ApkInstaller.kt
│  └─ provider/SimpleFileProvider.kt
├─ res/
│  ├─ mipmap-*/ic_launcher.png    启动图标，5 档密度（由 tools/IconGen.java 生成）
│  ├─ drawable-*/ic_stat_pin.png  通知栏小图标，仅 alpha 剪影
│  └─ drawable-nodpi/ic_banner.png  Android TV 横幅 320x180
tools/IconGen.java                 图标生成器（Java2D，无第三方依赖）
servertest/
├─ build.gradle.kts             复用 app 的 http/ 与 fs/ 源码在 JVM 上编译
├─ package.json                 jsdom（仅 UI 行为测试用）
├─ src/main/kotlin/boxhubtest/ 159 项断言
├─ devicetest.js                端到端设备测试（真实浏览器 + 真实服务端，经 CDP 驱动）
├─ uitest.js                    120 项 dashboard 行为断言（加载真实 index.html）
└─ uimock.js                    桌面预览 WebUI 用的 mock 后端
```

### 测试抓到的真实缺陷

#### JVM socket / 文件系统 / 配对码测试（159 项）

`MiniServer`、`Ws`、`FileOps`、`LanScope`、`PinGuard` 都只依赖 `java.*`，因此直接编进
JVM 测试模块跑真实 socket 与真实文件系统：

1. **chunked 请求体三连坑** —— 块尾 CRLF 未被消费（导致所有 chunked 上传只收到第一个
   块，而浏览器发大文件正好就是单 chunk）、批量读路径漏置 `needCrlf`、块大小未做
   Int 溢出防护。
2. **`denied()` 手数 Content-Length 写成 11 而实际 12 字节** —— 多出的那个字节会被
   当成下一个请求行解析，keep-alive 连接直接错位。修法不是改数字，而是让
   `writeResponse` **以字节数为准**，从根上消灭这一类错误；并加了回归测试。
3. `Routes` 里 401 分支原本不带 `return`，`when` 当语句用时分支值被丢弃。

#### 浏览器行为测试（jsdom，120 项，加载真实 index.html）

更早一轮用真实浏览器（OpenChamber）点按钮时抓到两个**会让首次使用直接卡死**的缺陷，
两者都只做视觉检查时完全看不出来：

1. **PIN 键盘根本没绑事件。** 界面渲染正常、按钮可见，但点下去什么都不发生；隐藏的
   输入框又是 `pointer-events:none`，配对码**根本没法输入**。
2. **`submitPin()` 用当前 `KEY`（还是空串）去校验**，而不是用户刚输入的码。于是无论
   输入什么都收到 401 —— 配对永远不可能成功。

为此写了 jsdom 行为测试（驱动真实 DOM、只 stub 网络）。它继续抓到的问题里有两个是
**只有做异步时序测试才会暴露**的：

3. **鉴权竞态** —— 页面加载时那次无 key 的探测，若在配对成功**之后**才 reject，会把
   PIN 门重新弹到已认证的会话上；用户明明配对成功却盯着配对码看。修法不是加标志位，
   而是**消除重复探测**，只保留 `boot()` 里的一条鉴权路径，从根上消除时序依赖。
4. **音量键没有 click 处理器** —— 它是唯一只绑 `pointerdown` 的按钮，键盘与无障碍
   激活完全不响应。

修完 3 后又补了一条相反方向的用例：电视重启换码后，已配对的会话**必须还能**退回 PIN 门
（避免把修复做成"门永不出现"）。

（另：测试台本身也出过问题 —— 测试里调用 `window.close()` 后仍有未决 Promise 回调跑向
已销毁的 document，导致打印 "ALL GREEN" 之后才崩溃、退出码 1。已改为不关闭窗口并显式
退出。这也是为什么 Gradle 任务要求退出码为 0。）

#### 功能缺口审计发现的问题

5. **目录能建、但永远删不掉** —— UI 不给目录提供删除，服务端又拒绝非空目录，尽管
   `FileOps.delete` 已实现 `deleteRecursively()` 且路径经过 chroot 校验。两头堵死。
   现在服务端允许递归删除并返回被删项数，UI 提供"删除目录"并强制二次确认、明确写出
   后果（"会同时永久删除该目录下的全部内容"）。

#### 真机反馈：输对配对码也进不去（缺陷 6-10, 12-14）

装到盒子上、手机第一次连上来后报的：**输入配对码后手机界面刷新，进不了控制台。**
四个独立缺陷叠在一起，每一个都足以造成这个现象：

6. **退避把配对码正确的人也挡在门外（主因）。** `authorized()` 先看时钟、
   **后比对配对码**：`if (now - lastAuthAttempt < authLockoutMs()) return false` ——
   在退避窗口内，**哪怕配对码完全正确也直接判 401**。而页面加载时会并发打出
   info / list / shot / ws 四个请求，电视重启换码后这四个全部 401，一次页面加载
   就把窗口顶到 1.6s。更要命的是每次拒绝都让窗口再宽 0.4s，而人重新输一遍 4 位码
   约 1~2s：窗口一旦涨过人的打字时间，**无论重试多少次都不可能通过**，只能退出
   应用重开。退避本来是限速穷举的，却正好拦下"照着电视屏幕重输配对码"这个人。
   现在抽成 `PinGuard`：**先比对、正确就放行**，退避只罚猜错；同一页并发的那几个
   请求只算一次猜错；已服完的惩罚会过期，不累积到把用户永久锁死。穷举仍被限速
   （每多猜一次多等 0.4s，上限 5s），且这段逻辑现在可在 JVM 上用假时钟逐毫秒验证。
7. **旧 WebSocket 从没被关过。** `wsOpen()` 直接覆盖全局 `ws`，旧连接继续活着，
   它迟到的 `unauthorized` 帧就把 PIN 门重新拍到**已经配对成功**的会话上 ——
   用户看到的就是"输完码页面刷新"。它的 `onclose` 还会把新的 `ws` 置空并顺带弄死
   新连接。现在开新连接前先关旧的，且每个回调都校验"我还是当前连接吗"。
8. **轮询降级是死代码。** `wsPoll()` 把定时器挂在 `ws._t` 上，而 `onclose` 里
   `ws = null` 之后才调它 —— `clearInterval(ws._t)` 抛 TypeError，
   文档里写的"断线自动降级为 3 秒轮询"**从来没生效过**，断线后页面永久僵死。
   定时器改为独立句柄，并补上 socket 重连（原来 `wsBackoff` 算了也从没用过）。
9. **拒绝配对的路径把责任推给用户。** 盒子不可达和配对码错误都提示"配对码不正确"，
   于是用户对着一个完全正确的码反复重输。另外 `requirePin()` 不清 sessionStorage，
   废码留在里面，下次打开页面直接进入一个必然失败的会话。

第 6 条的回归测试直接照着真机行为建模：照电视上的码输、中间手滑输错一次、
每次间隔 1.2s —— 旧逻辑下这个循环**永远出不来**，新逻辑第一次就对。
6-9 每条都有一个反向用例（把修复退回去，测试必须失败），避免"修成门永不出现"。

10. **修 6 时自己引入的回归：电视显示的码和服务器校验的码对不上。** 把校验逻辑搬进
    `PinGuard` 时留了一个 `App.pin` 字段做缓存，而 `start()` 只改这个字段、没通知
    `guard`。于是**每一次启动都必然对不上**：电视上写新码，服务器还在拿进程启动时
    生成的那个旧码做比对，症状是"永远是密码不匹配"。JVM 测试全绿也没抓到 ——
    它们测的是 `PinGuard` 自己，"谁把码传进去"这一段在 Android 侧、根本没有覆盖。
    修法不是补一行赋值，而是**删掉重复状态**：`App.pin` 变成 `guard.code` 的只读视图，
    换码只有 `newPin()` 一条路径，两份数据在结构上不可能再分叉。并补了一条断言
    "guard 暴露什么就接受什么"，覆盖 50 次连续换码。教训：重构时把数据搬到新家，
    要先确认**搬的是唯一一份**，否则就是拿一个编译不过的 bug 换一个测试抓不到的 bug。

#### 真机反馈：应用图标看不见（缺陷 11）

11. **启动图标是一块看不见的深色方块。** 三个独立问题叠在一起：

    - **视觉上等于不存在。** 原图标是满幅 `#171614`（近黑）方块 + `strokeWidth=4`
      的白色描边，viewport 108。N1 那个深色电视桌面上，图标底色和桌面底色几乎同色，
      而描边栅格化到 48px 槽位只剩约 2px 细线 —— 用户看到的"图标展示不出来"就是这个。
      现在改成**米白圆角底 + 近黑粗描边六边形 + 绿色实心条**，深色浅色桌面上都有边。
    - **只有一个 VectorDrawable，没有兜底。** 放在 `res/drawable/` 的矢量图当启动图标，
      对不肯跨进程 inflate 矢量的桌面就是空白；而 API 26+ 的自适应图标也没有。
      改为 5 档密度的真 PNG（mdpi→xxxhdpi），API 24 起通吃。
    - **通知栏是个纯白色方块。** `HubService` 拿启动图标当 `setSmallIcon`，而那张图
      第一条 path 是 `M0,0h108v108h-108z` 不透明满幅。Android 只取 alpha 通道再染白，
      所以通知里显示的是一整块白方块。已单独给一张**仅剪影**的通知图标
      `ic_stat_pin`。

    图标改由 `tools/IconGen.java`（Java2D，无第三方依赖）生成并提交 PNG。
    顺带一个自己踩的坑：横幅版式最初把字号写死，`BoxHub` 被挤出 320px 画布右侧被裁掉
    —— 改成先测量再居中排版。**所以图标这种东西必须真的看一眼渲染结果**，
    `aapt dump badging` 只能证明它被打包了，证明不了它看得见。

#### 在模拟器上跑真机之后（缺陷 12-14）

12. **这才是「输完配对码页面刷新」的真正原因。WebSocket 永远鉴权失败。**
    `MiniServer` 把请求行拆成 `Req.path`（**不含 query**）和 `Req.query`，而
    `App` 却拿 `req.path` 去找 `?k=` —— 永远找不到，`extractKey()` 恒返回空串，
    于是服务端对**每一次** socket 升级都回 `{"event":"unauthorized"}` 并关闭。
    前端收到后认为「配对码失效」，把 PIN 门重新弹到**刚刚才配对成功的会话上**，
    同时清掉 sessionStorage。用户看到的就是：码输对了 → 闪一下 → 又回到配对码页。
    之所以一直没被发现：HTTP 全部走 `req.q("k")`，**功能全都正常**，只有 socket 是死的；
    而 jsdom 测试把 `window.WebSocket` 整个替换掉了，JVM 测试又换掉了 App 的 socket
    处理器 —— **两套测试都恰好绕开了这个接缝**。修法：改用 `req.q("k")`，
    并补了一条 JVM 测试断言「升级请求的 `Req.query` 里有码、`Req.path` 里没有」。

13. **电视上根本看不到配对码。** 那段说明文字写的是 `setPadding(0,0,dp(560),0)`：
    固定 560dp 的右内边距把文字挤成窄条、段落高出好几行，把配对码和访问地址
    整个顶到屏幕外 —— 在模拟器上 uiautomator 明确报
    `Skipping invisible child ... boundsInScreen: Rect(300,1236 - 2712,1208)`，
    也就是**必须滚动才看得见那个唯一需要读的号码**。改法不只是把 padding 换成
    `setMaxWidth`，而是**调整信息层级**：配对码和地址直接放到标题下面置顶，
    说明文字和能力检测挪到下面。诊断信息可以折叠，用户必须读的数字不行。

14. **Android 会「冻结」后台进程，端口还占着但没人应答。** 模拟器上把 BoxHub
    切到后台后 logcat 出现 `ActivityManager: freezing <pid> com.boxhub`，
    此时 8790 仍被内核 accept 队列监听，但工作线程被冻结，**所有请求无限挂起**
    （实测 90 秒无响应，而不是被拒绝）。勾上「保持运行」起前台服务后立刻恢复
    （121ms）。N1 的 Android 7.1 没有这么激进的冻结，但这解释了「切走一会儿就连不上」，
    也是推荐默认勾「保持运行」的一个实打实的理由。

**为什么之前一直查不出来：jsdom 和 Node mock 都把网络与 WebSocket 换成了桩。**
现在补了 `servertest/devicetest.js`（见下），它用 Chrome DevTools Protocol 驱动
**设备上真实浏览器**里的**真实页面**，连的是**真实 MiniServer**。缺陷 12 就是它抓到的，
而且是抓在「配对其实成功了、随后门被弹回来」这个动作上 —— 只看最终截图会误判成
「页面没动」。

---

## 已知限制

- **没有开机自启。** 盒子重启后要手动打开一次 BoxHub；勾「保持运行」也不会自动启动
- 「保持运行」用 `START_NOT_STICKY`：配对码每次启动都会变，若让服务被系统静默拉起，
  手机上已存的配对码会无声失效。因此宁可让断线真实发生，由界面明确告知。
  电视上的 App 一旦被系统销毁再重建（内存回收、最近任务划掉）也会换码；手机上会
  自动重新弹出配对码门，照新码输入即可
- 遥控能力取决于盒子固件对 `input` 的 SELinux 策略，上机后看「设备能力检测」
- **遥控需要 root。** Android 把「注入按键」设为 `INJECT_EVENTS`（签名级）权限，
  普通第三方 App 一律拿不到 —— 这不是 BoxHub 的问题，`adb shell input` 能用只是因为
  adb 跑在 `shell` UID 上。实测报错原文：
  `java.lang.SecurityException: Injecting input events requires the caller to be privileged`。
  启动时会探测 `su`（`/system/bin/su`、`/system/xbin/su`、`/su/bin/su`、`/system/sbin/su`），
  有 root 就自动走 `su -c "input ..."`，遥控直接恢复；没有 root 则电视和手机端都会
  明确写出原因和三条可行的出路（root / 装成系统应用 / 电脑上用 adb）。
  文件管理、播放、装包不受影响，照常可用
- 截屏是 1 fps 预览，不是投屏。真投屏需要接入 scrcpy（未实现）
- 浏览器播放走 HTTP Range，`.mkv` 在手机浏览器里能否播取决于浏览器编码支持
  （Chrome 不支持 MKV，需转码或用 mp4）
- 单文件上传上限 16 GiB
- 4 位配对码只防同网段邻居，不是强认证；同网段的可信度靠 LAN 网关承担