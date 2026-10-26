# ClassFox 安卓端 —— 交接文档（hands-off）

> 交接时间：2026-09-09
> 交接范围：`D:\Dev\ClassFoxDroid`（纯安卓端 App）+ 其构建链 + MuMu 测试环境
> 上游参考：`D:\Dev\ClassFox`（Tauri 桌面版 ClassFox，Python 后端在 `api-service/`）

---

## 0. 一句话状态

**v0.11.1（2026-10-27）：①修复跨场跨课程复用（10-26 实测事故：服务进程存活时离散+计组 747 句混入同一转录文件）——`startRecording` 重置 `transcriptFile`/`lastLineInfo`，转录文件每场一换（与 wav 同策略；旧"同日同课续写"决策自本版废弃）；②转录浏览修复——`showTranscript` 旧实现把 TextView 直接塞进对话框（无滚动容器，长转录只能看第一屏），改为 CaptionScrollView 容器 + 对话框高 80% 屏 + `fling` 初速度 2.2 倍（监听页字幕回看共用）。实例3 验证：对话框一甩到底 ✓；注入冒烟→新会话新转录文件、7 句补录落盘、零错误 ✓。**

### v0.11.1（2026-10-27，跨场复用修复 + 转录浏览）

- RecorderService.startRecording：`transcriptFile = null; lastLineInfo = null`（每场新文件；末行偏移同步失效）。
- CaptionScrollView：`override fun fling(velocityY)` 乘 2.2——字幕回看与转录对话框共用。
- RecordsFragment.showTranscript：TextView 包进 CaptionScrollView，`window.setLayout(MATCH_PARENT, 0.8*屏高)`。

### v0.11.0（2026-10-26，S1+S2+S3 重构）

- **TranscriptBufferManager**（新文件）：`pushRecent→行id`、`recordCommitted/updateLastCommitted`、`replaceRecentText(id,…)`（精确）+`replaceRecentSuffix`（兜底）、`isNearDuplicate`（随迁）、`recentSince`、`clearAll`；全方法 synchronized。
- **RecorderService**：`tbuf` 替代散落字段；`transcriptExecutor` 单写者 + `pendingTranscriptOps`（停止轮询用）+ `lastLineInfo`（末行字节偏移，UTF-8 字节非字符）；`enqueueAppendLine/enqueueReplaceLast/createTranscriptFileIfNeeded`（懒建，零语句会话不产生文件，对齐旧行为）；全量扫描替换执行后 lastLineInfo 失效重建。
- **测试**：`app/src/test/.../TranscriptBufferManagerTest.kt` 11 用例——后续改这块先跑 `gradle testDebugUnitTest --tests TranscriptBufferManagerTest`。
- **接手注意**：tbuf 全方法主线程调用零成本但可跨线程；磁盘只许经 `runOnTranscriptWorker`（勿在主线程直接碰 transcriptFile）；行 id 与文件行一一对应（半句修正/精修替换不换 id）。

### v0.10.7（2026-10-26，WAV 注入测试钩子）

- **DiagAsrReceiver**：`--ei toservice 1`（配合 `--es wav`，忽略 stream/refine）——把 WAV 块喂给 `RecorderService.diagService.diagFeedPcm()`；用法同诊断广播（app 须在监听中）。
- **RecorderService**：`diagService` 静态钩子（onCreate 挂/onDestroy 卸）；`diagFeedPcm` 与麦克风读取、停止拆解三方经 `diagFeedLock` 互斥；`stopRequested` 后拒绝注入。
- **停止收尾 v2**：`stopRecording` 由固定延迟改条件轮询（1s 间隔，`refineBusy==false && 队列空` 或 30s 硬上限才 stopSelf）——onDestroy 的 shutdownNow 永不打断 native transcribe（v0.10.6 的固定上限在深积压下仍有截断窗口，本版根治）。
- **实测**（实例3，v0.10.7）：测试①保守模式注入→补录→AI 救场内容 ✓（截图存 D:\tmp\asr_task\ai3.png）；测试②注入中途停止：竞态首跑 SIGSEGV → 修复后复跑干净收尾 ✓；注入 30s 后停止 → 队列未积压（引擎快于注入），2.7s 收尾 ✓。
- **待真机回归**（v0.10.5/0.10.6 遗留项不变）：真实施讲下 AI 快答、连续讲课停止尾段完整、logcat 无"精修忙，丢弃一段"。

### v0.10.6（2026-10-26，评审处置）

- **F1 残留**：`commitSentence` 半句修正分支回写转录但不喂 `recentLines` → 此后精修替换匹配必失败、AI 快答停在旧半句。修：新增 `replaceRecentSuffix(old,new)` helper，半句修正与 `applyRefined` 替换分支共用。
- **F2 残留**：停止时 `submit{}.get(2500ms)` 固定上限在精修队列积压时截断尾段。修：等待按 `(队列余量*2000).coerceIn(2500,15000)` 动态放宽（`stopDrainWaitMs` 字段），`stopRecording` 的 `stopSelf` 延迟按 `((余量+1)*2000).coerceIn(2800,15300)` 对齐。**行为变化**：队列积压时停止后通知栏多停留数秒（排空在真实落盘，属预期）。
- **核对不成立**（勿再当 bug 修）：`recentNorm` 无跨线程访问（三引擎 ingest 全经 mainHandler.post）；LLM 超时已配（connect 15s/read timeoutMs）；停止排空无死锁（`.get` 在录音线程）。
- **暂缓重构**（留档）：Line ID 精确替换、磁盘 I/O 移出主线程 + 行偏移 O(1) 覆写、`TranscriptBufferManager` 抽取 + JUnit。

### v0.10.5（2026-10-07，AI 快答/落盘及时性）

- **F1（高）**：`recentTranscript`（AI 快答唯一数据源）读内存 `recentLines`，它只在流式 `commitSentence` 填充；保守模式流式不 commit、`applyRefined` 补录/替换都不碰它 → AI 面板恒空。修：新增 `pushRecent` 统一入口，`applyRefined` 补录分支 `pushRecent`（wall clock）、替换分支同步改写对应条目（激进模式 AI 也读到精修后文本）。
- **F2（高）**：`submitRefine` 原设计"忙则丢整段"，保守模式下被丢段**永久缺失**（流式不写盘、精修是唯一写盘口）→ 改有界队列（16 段，满丢最旧）+ `drainRefine` 串行排空；退出持锁确认队空再放 busy 位（防提交方漏排空竞态）；停止路径的 `submit{}.get(2500ms)` 哨兵排在排空任务后自然覆盖队列，无需改。
- **接手注意**：RecorderService 的 `ArrayDeque` 是 `java.util` 导入——没有 `removeFirstOrNull`（用 `pollFirst`）、没有索引访问（快照-改-回写）。
- **待真机回归**：见修复文档 §4（AI 快答有内容、连续讲课段无缺失、logcat 无"精修忙，丢弃一段"）。

### v0.10.4（2026-09-23，全量静态审查后整改）

- **两个高危**：
  1. 保守模式精修补录静默不落盘（`applyRefined` 写 `transcriptFile?.` 恒 null 被吞）→ 兜底 `openTranscriptFile()` + 推进 `lastCommitted` + 近重复过滤；`flushTranscript` 按模式区分（保守只写结束标记）。
  2. 二次监听复用"死"离线引擎（服务绑定存活不销毁，`finish()` 后引擎不可逆，`startOfflineEngine` 复用分支直接返回）→ 录音线程收尾 `release() + asrEngine = null`；配套 `refineEngine?.flushPending()` 清 VAD 尾音。
- **中危**：CaptionBuffer 写入上移到 `MainActivity.eventReceiver`（退后台 sink 注销也不断流，Fragment sink 只渲染）；启动失败 `error` 事件复位 UI；两个 Fragment 补 `onHiddenChanged`（切回刷新/切走停播）；停止时序修复（收尾落盘移主线程 FIFO + 精修排空有界等待 2500ms + stopSelf 2800ms）；LLM Key 从云备份/设备迁移排除（两个 res/xml 规则文件）；DiagAsrReceiver 输出路径收敛到应用私有目录。
- **低/微**：模型文件逐个校验重拷、精修回写保留原时间戳、关键词句也上字幕、坏引擎不登记、final 帧不闪 partial、AI 总结 15k 截断+可取消、监听中禁诊断/自检、LogStore 锁统一、Mock 停止即中断等，共 23 项。
- **关键行为变化（接手注意）**：`sentence` 事件关键词命中时也发；final 帧不再发 partial；字幕缓冲只有 `MainActivity.eventReceiver` 一个写入方；保守模式落盘唯一入口是精修补录。
- 全部明细、验证步骤、回退方式：`docs/修复_v0.10.4_2026-09-23.md`；审查依据：`docs/审查_字幕识别与落盘链路_2026-09-23.md`。


### v0.10.3（2026-09-22，用户真机数据回归 + 精度实验）
- **实验工具**：`DiagAsrReceiver`（manifest exported，广播 `com.classfox.droid.DIAG_ASR`，须加 `-f 0x1000000` 且 app 已启动；MuMu 后台广播被拦）。参数：`--es wav --es out --ef silence(默认2.0) --ei mode(1保守) --ei stream --ei refine --ef vadsil(0.75) --ef vadthr(0.5)`。流式读盘防 OOM（60MB WAV 整载会 OOM）；输出带音频相对时间戳（注意 `Date()` 本地时区，分析时 +8h 校正）。
- **精度实验结论**（组成原理 31 分钟真实课堂 WAV vs 桌面端 raw 参考，`cer.py` 分分钟对齐）：
  - 参考文本自身有漏录 gap（15:39 段桌面几乎无输出）与专名噪声（R4→二四/儿四），绝对 CER 只能看趋势；
  - SenseVoice 精修通道召回式 CER ≈33%，VAD 0.75s vs 1.2s 无显著差异 → **保留 0.75s（延迟更低）**；
  - 逐句人工对照：精修输出质量接近桌面云识别水平（含标点/ITN）。
- **真机转录重复/乱序根因（v0.10.1~0.10.2 手机数据实锤）**：保守模式下 `ingestAsrText` 每个 ASR final 都调 `flushRemaining()`，把无标点流式半句整段写进转录；同段音频的精修正文随后再补一遍 → 转录内容双份、时间戳乱序（手机日志 15:31:48 那条 100 字 blob 即流式垃圾）。**修复：仅激进模式冲刷残留；保守模式未闭合尾巴交给精修通道**。UI 同步：保守模式不渲染流式 partial（杜绝"即出+精修"双显）。
- **字幕抗重建**：历史/partial 迁入 `CaptionBuffer` 进程级单例（tab 切换 show/hide 已由 v0.10.2 保活，Activity 重建也不丢）。
- **精修替换防错行**：`sentence_corrected` 广播新增 `text_orig`（被替换原句），UI 按句匹配替换（`CaptionBuffer.replaceLine`），找不到原句即跳过、绝不追加。
- **AI 课堂笔记**（对齐桌面版 `generate_class_summary` 6 章节提示词）：记录页转录对话框「AI 总结」→ 后台生成 → 存课程目录 `summary_<转录名>.md` → 记录页黄色「总结」类型点开查看。`LlmClient.chat` 增加 `timeoutMs`（总结 180s）。空内容防护（思考型模型截断→报错不写文件）。
- **已验证**（实例3，手机真实数据 43 文件 md5 一致拷入）：模拟两模式字幕流、切 tab 字幕保留、日志打开即到底、AI 总结真实调用 DeepSeek 生成 6KB 笔记（内容与课堂例题吻合）。**注意**：shell 拷入的目录属主是 shell，app 写入 EACCES——测试环境需 `chmod -R 777`；真机 app 自建目录无此问题。
- **坑**：服务字段初始化器读 `Prefs` 会 NPE（Context 未 attach）→ 必须挪 `onCreate`（v0.10.3 已修）。
端侧离线中文 ASR 真实链路保留（sherpa-onnx 双通道：流式 zipformer + SenseVoice 精修）；模拟器验证链路用「模拟识别」，真机用离线识别。

### v0.10.x 更新（2026-09-20~21，用户真机试用反馈整改）
- v0.9.x：课程归档（`classfox/<课程名>/`）+ 课程历史下拉 + AI 救场补齐（/chat/completions）+ 双通道精修（SenseVoice+VAD 句末重转写，见 OfflineAsrEngine / RefineEngine.kt）
- **v0.10.2（2026-09-21 第二轮反馈）**：见 §7-P1.5 第 11/12 条修复记录。
- **v0.10.1 六项整改（对应用户反馈 1~5，第 6 项 AI 暂不动）：**
  1. **出字过快**：endpoint Rule1 静音 1.2s→**2.0s**、Rule2 0.9s→1.5s、Rule3 20s→30s（都在 OfflineAsrEngine.init() 的 EndpointConfig 一处）。代价：定稿延迟 +0.8s，partial 实时字不受影响。真机如仍偏快/偏慢，调这三个值即可。
  2. **字幕滚动（关键修复，两层根因）**：
     - 根因 A：androidx.core NestedScrollView 构造器在 MuMu 翻译层上 NPE（mChildHelper 未初始化，崩溃行号 297=layout-land 变体）→ 已弃用；
     - 根因 B（真机同样会踩）：字幕 TextView `textIsSelectable="true"` 会消费 DOWN，内层 ScrollView 的 `onTouchEvent` 不执行 → 自定义 `CaptionScrollView`（framework ScrollView 子类）把 `requestDisallowInterceptTouchEvent(true)` 放在 **onInterceptTouchEvent 的 ACTION_DOWN**，UP/CANCEL 归还；
     - 自动跟随从 `fullScroll(FOCUS_DOWN)`（依赖焦点链，不可靠）改为显式 `scrollTo(0, child.height - sv.height)`（ListenFragment.scrollToBottom）。
     - 已验证（实例3 截图对比）：字幕区内滑动只滚字幕、外层页面不动；滑动回看后再来新句自动回底。
  3. **阈值只留设置页**：监听页阈值卡/滑块整体删除（两个布局），设置页 sliderThreshold 保留，拖动结束 Toast「已保存」。
  4. **日志迁移**：新建 `LogStore.kt`（单例：内存环形 500 行 + 按天落盘 `logs/log_yyyyMMdd.txt` + 保留天数 1~30 默认 7 天自动清理）。监听页日志卡删除，句子/警报/引擎状态/权限结果全部 append 到 LogStore；设置页新增「运行日志」卡：保留天数滑块 + 「查看今日日志」对话框（monospace 可选中复制）。已验证：对话框完整展示句子/警报/启停时间线。
  5. **保存反馈**：设置页顶部固定说明「✓ 所有设置修改后立即自动保存」（fragment_settings.xml 首元素）；LLM 三个输入框失焦 Toast「已保存：AI 救场配置」；阈值/日志滑块 onStopTrackingTouch Toast；词表保存沿用原 Toast。
  6. AI 功能未改动（用户反馈"没把握可以先放着"）。

### v0.8.x 更新（2026-09-13 下午）
- v0.8.0：新增系统识别引擎（厂商 SpeechRecognizer，三选一：模拟/系统/离线）
- v0.8.1：默认强制竖屏 + 横屏双栏布局（layout-land）
- v0.8.2：修复停止闪退（finish 解码门控）；系统识别不可用不再回退离线；新增真实音频自检（SAF 选 WAV 直接喂识别器）
- **v0.8.3（重要）**：修复"识别越滚越长的重复"真根因——endpoint 触发后未调用 `recognizer.reset(stream)`，上下文持续累积；endpoint 规则收紧（静音 1.2s 断句 / 20s 硬上限）；finish 循环冲刷；接入 2025 大模型 `sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30`（encoder 153MB，assets/zh2025，默认），旧 14M 模型保留可切
- **环境结论修正**：MuMu 实例3（新建，Android 12）上离线解码不崩，exit(255) 是旧实例环境特例；用户 89 分钟真实课堂录音三段自检识别连贯、分句正常、无重复
- 测试资产：`mp3/标准录音 53.mp3`（用户真实录音）、`selftest_wav/`（切片）、设置页「真实音频自检」入口

### v0.7 主要变化
- 代码重构：`MainActivity`（外壳+底部导航）+ `ListenFragment`（监听）/`RecordsFragment`（记录：转录查看+WAV播放）/`SettingsFragment`（设置）
- 新增：`MockAsrEngine`（脚本模拟识别）、`Keywords`（两级词表文件化可编辑热重载）、`Prefs`（SharedPreferences）
- 对齐桌面版：转录文件头尾标记（开始于/结束于/课程名）、近重复过滤（完全相等/包含≥0.8/相似度≥0.88，窗口12）、危险/提醒两级关键词
- 修复：WAV 与转录统一存 `files/classfox/`（原来 WAV 在 `files/Music/classfox/` 导致记录页看不到）、事件单通道、死代码清理、按钮禁用态视觉、dB 显示钳制 -90
- 验证证据（实例3）：`句:`/`关键词命中(危险/提醒):`/`近重复丢弃:`/`WAV 完成` 均在 logcat 出现；暂停期间无提交；记录页转录对话框+WAV 播放（NuPlayer started）正常；全程无 FATAL

---

## 1. 铁律（接手前必读）

| # | 规则 |
|---|---|
| 1 | **只操作被明确指定的模拟器实例**。`adb` 列出的其他设备一律只读：不安装、不卸载、不改数据、不 force-stop。 |
| 2 | 用户会另开新实例给你测；**动手前先确认哪台是新实例**（见 §5 实例识别）。 |
| 3 | **不要给现有 MuMu 实例刷系统/换镜像**——可能清空该实例的应用与数据。要换镜像只能**新建实例**。 |
| 4 | 不要读取或扩散与本任务无关的用户文件、其他项目目录。 |
| 5 | 报告必须区分「已验证（附 logcat/截图证据）」与「未验证/推测」。 |

---

## 2. 资产清单

### 2.1 工程（无版本控制！）
```
D:\Dev\ClassFoxDroid\                      ← 工程根，注意：**不是 git 仓库**
├─ settings.gradle                          jitpack 仓库已加
├─ build.gradle
├─ gradle.properties
├─ local.properties                         sdk.dir=D:\Dev\android-sdk
├─ app\build.gradle                         applicationId com.classfox.mobile.clean / minSdk26 / targetSdk34
├─ app\src\main\AndroidManifest.xml         权限 + foregroundServiceType=microphone
├─ app\src\main\java\com\classfox\droid\
│   ├─ MainActivity.kt                      UI、权限、按钮、滑块、广播接收
│   ├─ RecorderService.kt                   前台服务：录音 + RMS + WAV + 分句 + 关键词 + 转录落盘
│   ├─ OfflineAsrEngine.kt                  sherpa-onnx 封装（模型解包、流式 decode、回调）
│   └─ SentenceSplitter.kt                  双模式分句器（运行时可切）
├─ app\src\main\assets\zh14m\               模型（25MB）★必需
├─ app\src\main\jniLibs\arm64-v8a\          sherpa .so（30MB）★必需
└─ release\                                 APK 产物
```

### 2.2 APK 产物
```
D:\Dev\ClassFoxDroid\release\
├─ ClassFoxDroid-clean-v0.4.apk   56MB   已构建，未装机验证
├─ ClassFoxDroid-clean-v0.5.apk   56MB   已构建，未装机验证
└─ ClassFoxDroid-clean-v0.6.apk   56MB   最新，已构建，未装机验证
```
包名统一为 `com.classfox.mobile.clean`——**故意换包名**，避免继承旧包 `com.classfox.droid` 的转录数据（用户曾被旧测试数据污染困扰）。

### 2.3 构建链（本机已就绪，无需重装）
| 组件 | 路径 / 说明 |
|---|---|
| Android SDK | `D:\Dev\android-sdk`（platforms 33/34、build-tools 33.0.2、platform-tools） |
| Gradle | `D:\Dev\gradle-8.5`（解压版，**不走 wrapper**） |
| JDK | `C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot` |
| MuMu | `D:\MuMu Player 12`，管理器 `nx_main\MuMuManager.exe` |
| 模型中间产物 | `D:\Dev\jniLibs\`（各 ABI .so）、`D:\Dev\sherpa-onnx-v1.13.7-android-*` |

---

## 3. 构建与安装（可直接复制）

### 3.1 构建
```bash
cd /d/Dev/ClassFoxDroid
export JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot'
/d/Dev/gradle-8.5/bin/gradle :app:assembleDebug --no-daemon
```
产物：`app/build/outputs/apk/debug/app-debug.apk`

### 3.2 安装（★路径必须是 Windows 风格）
```bash
adb -s <serial> install -r 'D:\Dev\ClassFoxDroid\app\build\outputs\apk\debug\app-debug.apk'
adb -s <serial> shell pm grant com.classfox.mobile.clean android.permission.RECORD_AUDIO
```
`adb` 是原生 Windows 程序，**不认 MSYS 的 `/d/...` 路径**——传 `/d/...` 会报 `failed to stat`。

### 3.3 关键日志过滤
```bash
adb -s <serial> logcat -d -s ClassFoxAsr:I ClassFoxRec:I | tail -40
```
- `ClassFoxRec`：`句:` / `关键词命中:` / `录音开始/结束` / `WAV 完成` ← **端到端铁证**
- `ClassFoxAsr`：模型解包、`识别中:`、初始化结果

### 3.4 版本备份（2026-09-21 起三套并存）
- **GitHub（异地）**：remote `github` → `git@github.com:Infe1/ClassFoxDroid.git`（SSH 直连 IP 配置见 `~/.ssh/config`，Deploy key 只授权此仓库）。推送后作为异地主备份。
- **本机裸仓库**：remote `backup` → `D:\GitRemotes\ClassFoxDroid.git`（零账号，防误删/坏提交）。日常提交后 `git push backup main`（+ GitHub）。
- **原始完整历史归档**：`D:\GitRemotes\ClassFoxDroid-full-history.git` —— 2026-09-21 大文件清理**前**的未重写历史（含两个大模型二进制的全部历史版本），只读保存。
- 异地冷备：`git bundle create ClassFoxDroid_$(date +%Y%m%d).bundle --all` 单文件放网盘/U盘；恢复 `git clone`。重写后 bundle 约 37MB。
- `release/*.apk` 不进 git，历来做磁盘直存。
- **Release 约定**：版本名用 beta（如 `v0.10.2-beta`），tag 固定 `preview`，勾 pre-release；Release 网页创建与 APK 附件上传由用户完成（账号零操作约束）。
- ⚠️ **公开发布前置门禁（2026-09-21 用户要求，未完成不得转公开）**：入库文档含个人信息（用户名/机器名 `Infe1`、`Infel`、`ClassAssistant`；本机路径 `D:\Dev`、`C:\Users\Infel`、`D:\GitRemotes`、MuMu 路径、端口 16480、GitHub 固定 IP；本文件自身即内部交接文档）。公开发布前必须全面排查清洗并向用户确认完成，交接者/后续会话勿跳过。
- **大文件清理（2026-09-21）**：`sensevoice/model.int8.onnx`(228MB)、`zh2025/encoder.int8.onnx`(154MB) 超 GitHub 单文件 100MB 硬限，已用 filter-branch 从全部历史移除（pack 306→36.6MB），工作区文件**仍在磁盘**（已入 .gitignore）。⚠️ 教训：filter-branch 结尾 `read-tree -u` 会把重写结果同步到工作区，重写前必须先归档历史——本次靠归档库恢复了被带掉的文件。
- **重建开发环境时需手动取回的两个模型**（clone 后 assets 里没有）：
  - `app/src/main/assets/sensevoice/model.int8.onnx`（228MB）：sherpa-onnx SenseVoice Small int8，hf-mirror.com/csukuangfj/k2fsa-zipformer-chinese-english-mixed（或 sherpa-onnx 官方 sense-voice release）下载后重命名
  - `app/src/main/assets/zh2025/encoder.int8.onnx`（154MB）：`sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30` 的 encoder，hf-mirror.com/csukuangfj 同名仓库
  - 校验：构建后设置页「引擎诊断」能初始化即文件正确；其余 assets（zh14m 全套、sensevoice tokens、zh2025 decoder/joiner/tokens）都在 git 里

---

## 4. 架构与数据流

```
麦克风(AudioRecord 16k/mono/16bit)
   │
   ├─→ 写 WAV（外部私有目录 classfox/classfox_<时间戳>.wav）
   │
   ├─→ 算 RMS（平滑）→ 声浪条 + 人声事件（阈值可调）
   │
   └─→ OfflineAsrEngine.feedPcm()
          └─ 每 ~300ms + isReady 门控 → decode
                └─ onText(text, isEndpoint)
                      └─ ingestAsrText()
                           ├─ partial → 仅更新「实时字幕」区（不落盘、不检关键词）
                           └─ final/endpoint → SentenceSplitter → commitSentence()
                                  ├─ 写转录文件（每会话独立）
                                  ├─ 关键词检测 → 红/黄警报
                                  └─ 广播事件 → UI
```

### 4.1 关键设计点
- **双模式分句器**（`SentenceSplitter`）：`MODE_SPLIT_FAST`（默认，能切就切、半句也切）/ `MODE_MERGE_CONSERVATIVE`（对齐桌面版，仅句末标点定稿）。运行时可 `setSplitMode()` 切换——**用户明确要求保留两条路线做调优**。
- **半句修正**：FAST 模式切出的半句被后续延伸时，回写替换上一行转录，避免流式重复。
- **关键词分级**：红色 = `点名/提问/签到`；黄色 = `作业/考试/注意/重点`。
- **人声事件阈值**：默认 **-30 dB**，可在界面滑块调整（-60 ~ -10 dB），事件最小间隔 3s。
- **暂停/继续**：`paused` 标志控制；暂停时不处理音频、不 ASR、不检关键词，但**不结束 WAV 会话**。
- **停止时序**：`stopRequested` 让录音线程自然收尾 → 冲刷最后半句 → 写结束标记 → finalize WAV。避免"停止瞬间丢最后一段"。
- **事件单通道**：句子/警报/暂停等只走广播；仅 `voice` 事件额外走 `stateListener`（防双份日志）。

### 4.2 会话产物目录（设备上）
```
/sdcard/Android/data/com.classfox.mobile.clean/files/
├─ classfox/classfox_<yyyyMMdd_HHmmss>.wav            录音
├─ classfox/class_transcript_<yyyyMMdd_HHmmss>.txt    转录（每会话独立文件）
└─ files/sherpa-onnx-streaming-zipformer-zh-14M-int8/ 首跑解包的模型
```

---

## 5. 测试环境（MuMu 12）

### 5.1 实例 ↔ adb 端口映射
读 `D:\MuMu Player 12\vms\MuMuPlayer-12.0-<n>\configs\vm_config.json` 的 `port_forward.adb.host_port`。
- 已知旧实例：`127.0.0.1:16384`（vm0）、`emulator-5554`（vm1）
- **曾用的测试实例：`127.0.0.1:16448`（vm2，"设备-2"）——当前已离线**

### 5.2 识别新实例
```bash
adb connect 127.0.0.1:<host_port>
adb devices -l
# 对照窗口标题区分：
powershell -NoProfile -Command "Get-Process MuMuNxDevice | Select-Object Id,MainWindowTitle"
```

### 5.3 环境事实（实测）
- 模拟器 `uname -m` = **aarch64**，但 `getprop ro.product.cpu.abi` 报 `x86_64` → **按内核真实架构选 .so（arm64-v8a）**，否则走 houdini 翻译层。
- **锁横屏 1280×720**，`wm size` 却报 720x1280，`user_rotation` 设置不生效 → **点击坐标必须按横屏算**。
- 无任何系统语音识别/TTS 引擎（`query-services android.speech.RecognitionService` 为空）。
- 声卡回采主机声音**时好时坏**（PowerShell `SoundPlayer` 播系统音效有时能采到 -18dB，播 TTS wav 常失败）。
- UI 高频刷新时 `uiautomator dump` 报 `could not get idle state` 且**返回陈旧树**；实时状态以 logcat + `screencap` 为准。

---

## 6. 已验证 vs 未验证（严格区分）

### ✅ 已验证（有 logcat/截图为证）
| 项 | 证据 |
|---|---|
| 构建链可用 | `BUILD SUCCESSFUL`（多次，~30-50s） |
| 录音 + 前台服务 + 通知 | 新实例 16448：长时间连续录音无崩溃，`isForeground=true` |
| WAV 落盘与 finalize | 停止后 `WAV 完成: N bytes`，RIFF 头正确，可解析 |
| 声浪/RMS | 实测采到 -11.5dB 尖峰（主机播声音） |
| UI 生命周期 | 开始→监听中 / 停止→未监听，按钮 enabled 状态正确 |
| 分句 + 关键词 + 转录落盘 | 注入文本流实测：`句:` / `关键词命中:` 正常，半句切分无重复 |
| 模型解包 + 识别器初始化 | 模拟器实测成功（~0.8s 解包，初始化 OK） |

### ⚠️ 未验证 / 有问题
| 项 | 状态 |
|---|---|
| **v0.4/v0.5/v0.6 的任何改动** | 全部**未装机验证**（测试实例离线后就没再测） |
| **模拟器端真实识别** | ❌ **decode 必崩**（native exit 255），多节奏/多 ABI 全试过，未解决 |
| **真机识别质量** | 用户反馈「效果不好」，识别文本有重复/错乱（如断句重复、"有有"） |
| 暂停/继续 | 代码已写，**未验证** |
| 阈值滑块 | 代码已写，**未验证** |
| 实时字幕区 | 代码已写，**未验证** |
| 红黄分级 | 代码已写，**未验证** |
| 断句「在什么情况下截断」 | 用户明确质疑过；当前策略见 §4.1，**需实测调参** |

---

## 7. 已知缺陷 / 待办（按优先级）

### P0 — 阻塞类
1. **模拟器 decode native 崩溃**（未解决）
   - 现象：`录音开始` 后 ~0.2-4s 进程 `prcp FGS` 死，无 Java 栈，无 tombstone（模拟器阉割了 debuggerd）
   - 已排除：模型损坏、ABI（x86_64/arm64 都试）、decode 节奏（100/300/400/500ms、isReady 门控、一次性）、release/init 竞争（改懒加载后仍崩）、内存（3GB 空闲 2GB）
   - 判定：**MuMu aarch64+翻译层上 onnxruntime 跑 zipformer 的硬性不兼容**，非代码问题
   - 下一步建议：**真机验证**；或换 MuMu 的 ARM 原生镜像 / 新建 Android 13+ 实例再试

2. **真机识别质量差**
   - 当前模型 `sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23`（int8，encoder 仅 21MB）——**体积小的代价就是精度低**
   - 备选（已下载过但被删）：`zh-int8-2025-06-30`（encoder 161MB，精度更高，体积大 8 倍）
   - 注意：**模型与库版本要匹配**；sherpa v1.13.7 的 android .so 是 ORT **1.27.1**，而 JitPack 的桌面 native 是 ORT **1.17**（报 `requested API version [27]` 是**假失败**，别用它验证安卓行为）

### P1 — 体验类（用户已明确抱怨）
3. **延迟**：当前每 ~300ms 尝试 decode，但只在引擎返回 partial/endpoint 时更新 UI。用户感觉「延迟相当大」。→ 需实测 `isEndpoint` 触发频率与 UI 更新时机。
4. **断句策略不透明**：用户问「准确度不知道你什么情况下截断的」。→ 当前 FAST 模式：句末标点 / 句中停顿标点且≥8字 / ≥30字强切。**需按真实课堂语料调参并可视化**。
5. **识别重复**：曾出现同句重复、`有有` 类重复 → 已加去重（`SentenceSplitter.addSentence` 的 recent 去重 + 半句修正），**未验证效果**。
6. **UI 观感差**：用户评价「界面确实做的也太差一点」。当前是极简单 Activity，**没有任何设计投入**。

### P1.5 — v0.10.1 真机回归新增（2026-09-21 用户反馈）
11. ~~**日志没法快速点开**~~ → **v0.10.2 已修**：`LogStore.showTodayDialog(activity)` 提为共享入口（原 SettingsFragment 私有实现删除）；监听页字幕卡右上角加轻量「日志」文字按钮（竖/横屏两布局），一键弹今日日志；设置页按钮同调共享入口。已验证（实例3 截图）。
12. ~~**主页字幕在切换 tab 后消失**~~ → **v0.10.2 已修**：根因是 MainActivity `show()` 用 `replace()`+每次 new Fragment，切回即重建、`captionHistory` 内存态全丢。改为 add/show/hide 复用三实例（TAG listen/records/settings），隐藏期间 sink 仍挂、后台句子字幕不丢；Activity 重建路径按 bottomNav 恢复的选中 tab 归位。已验证（实例3：监听中切记录页再切回，字幕/计时/警报完整保留，截图 DIFF 证据）。**真机滑动效果仍待用户确认**（CaptionScrollView 修复在 v0.10.1，模拟器已验证滚动，真机手感未测）。

### P2 — 工程卫生
7. **无版本控制**：`D:\Dev\ClassFoxDroid` 不是 git 仓库。**建议第一步就 `git init`**，否则改动不可回溯。
8. **包体 56MB**：模型 25MB + jniLibs 30MB（只留 arm64-v8a）。若只发真机可接受；若要减，考虑模型量化/裁剪或按 ABI 分包。
9. **`decodeTick` 字段已无用途**（重构残留），可清理。
10. `MainActivity.updateUi()` 的 `event`/`text` 参数已不再使用（编译警告），可清理。

---

## 8. 下一步建议（给接手者）

**建议顺序：**

1. `git init` + 首次提交（工程当前无版本控制，风险最高）。
2. **在真机上装 v0.6 验证 P1 全部改动**——这是最快拿到结论的路径：
   ```bash
   adb -s <真机serial> install -r 'D:\Dev\ClassFoxDroid\release\ClassFoxDroid-clean-v0.6.apk'
   adb -s <真机serial> shell pm grant com.classfox.mobile.clean android.permission.RECORD_AUDIO
   adb -s <真机serial> logcat -c && adb -s <真机serial> logcat -s ClassFoxAsr:I ClassFoxRec:I
   ```
   重点观察：字幕是否实时、红黄是否分级、暂停是否生效、停止是否丢尾、`句:` 行是否重复。
3. **调参**（模型/断句/阈值三处）：
   - 若精度仍是主要问题 → 换 `zh-int8-2025-06-30`（161MB）对比
   - 断句延迟大 → 调 `SentenceSplitter` 的 `minHalfLen` / `maxLenForceCut`，或调 `OfflineAsrEngine` 的 decode 间隔（注意 <300ms 在模拟器上会崩）
4. **模拟器问题**（若要继续用模拟器）：新建一个 **ARM 原生镜像**或 Android 13+ 实例再试；**不要刷现有实例**。
5. UI/交互按用户反馈重做（当前是占位级）。

---

## 9. 环境坑速查（踩过的）

| 坑 | 现象 | 解法 |
|---|---|---|
| MSYS vs 原生路径 | `adb install /d/...` 报 `failed to stat` | 传 `D:\...` |
| JAVA_HOME 风格 | `sdkmanager.bat` 报 invalid directory | 用 `C:\...` 而非 `/c/...` |
| gradle wrapper | 探测 services.gradle.org → 307 到 GitHub（不通） | 用解压版 gradle 直接构建 |
| gradle 发行版下载 | services.gradle.org 不通 | 走 `mirrors.cloud.tencent.com/gradle/` |
| GitHub / raw.githubusercontent | 全不通 | 用 `gh-proxy.com` 前缀；模型走 `hf-mirror.com` |
| androidx 版本 | 报 requires compileSdk 34 | 装 platform-34 并同步改 build.gradle |
| 模拟器 abi 假象 | `cpu.abi=x86_64` 但内核是 aarch64 | 按 `uname -m` 选 .so |
| 横屏锁死 | tap 坐标全 miss | 按 1280×720 横屏算坐标 |
| 模拟器 native 崩溃 | 无栈无 tombstone | 模拟器阉割了 debuggerd，改用真机定位 |
| 停止丢尾 | 最后一段 ASR/转录丢失 | `stopRequested` 让线程自然收尾（v0.5 已修，未验证） |
| 事件双份 | 同一句日志出现两次 | stateListener 与广播只留一条通道（v0.5 已修，未验证） |

---

## 10. 相关参考

- **上游桌面版**：`D:\Dev\ClassFox`（Tauri + React + FastAPI）
  - 分句/合并逻辑参考：`api-service/services/monitor_service.py`
    - `_is_sentence_closed()` / `_append_or_merge_local_entry_locked()` / `_normalize_text()` / `_is_meaningful_text()` / `_is_near_duplicate_locked()`
  - 转录格式：`[HH:MM:SS] 文本`
  - 资料引用/救场/摘要等能力在 `api-service/services/` 下，**安卓端尚未移植**
- **技能文档**：`~/AppData/Local/hermes/skills/software-development/android-development/`
  （含 `references/offline-asr-sherpa-onnx.md`、`mumu-adb-qa.md`、`recording-asr-split.md`、`toolchain-cn-windows.md`）

---

## 11. 尚未做的功能（桌面版有、安卓端没有）

- 课程资料上传与引用（PPT/PDF/Word 解析）
- AI 救场（一键应答）
- 滚动课堂摘要（每 N 条压缩）
- 课后总结（Markdown 笔记）
- 悬浮窗形态（安卓端受「悬浮窗权限」限制，需另设计）
- 警报音/震动提醒（当前只有界面日志）
