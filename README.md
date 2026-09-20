# 课狐 ClassFox 移动端（ClassFoxDroid）

课堂实时转写安卓端：**完全离线**的中文语音识别 + 关键词警报 + AI 救场。为"上课听不清、跟不上"的学生设计——开着 app 放讲台上，实时看字幕、事后看转录、漏了让 AI 补。

> 上游参考：Tauri 桌面版 ClassFox（本工程为移动端移植对齐）。
> 详细交接文档见 [HANDOVER.md](HANDOVER.md)（构建、测试环境、数据流、已知缺陷）。

## 功能

- **实时字幕**：流式识别逐字刷新，句末定稿；字幕区可上滑回看，新句自动回底
- **双通道精修**：流式 zipformer 出实时字幕 + SenseVoice 在句末停顿时整句重转写，取更准的版本
- **两套断句模式**：激进切分（能切就切，下游 AI 接得住） / 保守合并（对齐桌面版，只按句末标点定稿），运行时可切
- **关键词警报**：两级词表（红=危险如点名/签到，黄=提醒如作业/考试），命中即弹卡 + AI 救场/讲到哪了入口
- **课程归档**：转录与 WAV 按课程名分目录；课程名有历史下拉，免重复输入
- **AI 救场**：OpenAI 兼容 `/chat/completions`（DeepSeek、中转、本地 Ollama 均可），"我没听懂"一键生成讲解
- **运行日志**：按天落盘、保留天数可调（默认 7 天）、监听页一键查看

## 识别引擎（三选一，设置页可切）

| 引擎 | 说明 | 适用 |
|---|---|---|
| **离线**（默认） | sherpa-onnx，模型内置，完全离线 | 真机日常使用 |
| 系统 | 厂商 SpeechRecognizer | 多数国产 ROM 不可用，勿依赖 |
| 模拟 | 内置脚本产出文本 | 模拟器/演示验证链路 |

离线模型二选一：**zh2025 大模型**（encoder 154MB，默认，真实课堂明显更准）/ zh14m 小模型（21MB，干净语音可用）。

## 已知限制（先看这里）

- **远场精度**：手机离老师远时识别错误率显著上升，这是所有端侧模型的物理限制。手机放讲台/老师附近，比换任何参数都有效
- **模拟器**：MuMu 等 ARM 翻译层环境离线解码不可用（native 崩），属环境限制，真机正常；模拟器验证链路请用"模拟"引擎
- **AI 救场**：需自配接口地址与模型名，app 不内置任何账号

## 构建

```bash
cd /d/Dev/ClassFoxDroid
export JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot'
/d/Dev/gradle-8.5/bin/gradle :app:assembleDebug --no-daemon
```

细节（签名安装、日志过滤、MuMu 多实例测试守则、大文件说明）见 [HANDOVER.md](HANDOVER.md)。

> 两个 >100MB 的模型二进制不入 git（GitHub 单文件上限），clone 后需按 HANDOVER §3.4 手动取回；release 附件里的 APK 已打包全部模型，直接安装即用。

## 版本与备份约定

- Release 一律为 **beta 命名 + `preview` tag**（预览版定位）
- 三套备份：GitHub（本仓库）+ 本机裸仓库 `D:\GitRemotes\ClassFoxDroid.git`（remote `backup`）+ 重写前原始历史归档 `D:\GitRemotes\ClassFoxDroid-full-history.git`
