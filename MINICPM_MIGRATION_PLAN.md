# MiniCPM-V 4.6 端侧部署与架构演进计划 (Android)

## 1. 架构选型与分析

### 1.1 推理框架对比分析 (vLLM vs SGLang vs Ollama vs llama.cpp)
在官方支持的四个框架中，针对 **Android 端侧 (On-Device)** 场景，结论是唯一且明确的：
*   **vLLM & SGLang**：专为服务端 GPU 集群设计（依赖 CUDA/Triton 等），Python 生态重，**绝对无法**在 Android 端侧运行。
*   **Ollama**：基于 Go 和 llama.cpp 封装，适合桌面端一键部署。若强行移植 Android，需要以后台 Daemon 进程运行，存在严重的进程保活、通信开销和体积冗余问题，**不推荐**。
*   **llama.cpp (The Winner)**：纯 C/C++ 编写，无外部依赖，天然支持跨平台编译。通过 NDK (JNI) 可直接嵌入 Android App 的进程空间中，内存零拷贝，支持 ARM NEON 和 Vulkan 后端加速。**性价比与可行性最高，是端侧部署的行业标准。**

### 1.2 量化精度 (Quantization) 策略
MiniCPM-V 4.6 参数量为 1.3B，全精度 (FP16) 约占用 2.6GB 内存。为了在端侧兼顾速度、内存与效果：
*   **推荐默认量化：`Q4_K_M` (约 800MB)**。在绝大多数中低端手机（4GB+ RAM）上均可流畅运行，且视觉与文本翻译能力衰减极小。
*   **高质量选项：`Q8_0` (约 1.4GB)**。针对旗舰机型（8GB+ RAM），基本达到无损画质。
*   **自定义选项**：不在代码中硬编码量化版本，而是通过“本地模型导入”功能，让用户自行选择下载的 `.gguf` 和视觉投影文件 (`mmproj.gguf`)，将精度选择权完全交还给用户。

### 1.3 体验升级与功能扩展 (Beyond Manga Translation)
既然引入了强大的端侧多模态模型，只做“漫画翻译”大材小用。可以扩展以下功能：
1.  **通用视觉问答 (General Vision QA)**：在悬浮球菜单中增加“分析屏幕/图片”选项，不强制要求输出 JSON，而是让模型直接描述图片内容、提取网页文本或解释当前屏幕的 UI。
2.  **流式反馈 (Streaming Output)**：长文本翻译不再等待整页完成，而是采用打字机效果，提升用户心理预期。

---

## 2. 实施 TODO 清单 (Phase 1 - 5)

### Phase 1: 基础设施与项目管理
- [x] 检查并关联用户的 Fork 远程仓库 (`https://github.com/222222222l/manga-translator-android`)。
- [x] 创建功能分支 `feature/minicpm-v-integration`。
- [x] 清理无用依赖：移除原有的臃肿 OCR 库和模型 (将随管线重构同步进行)。
- [x] 配置 GitHub Actions 云端构建 Debug APK（安装 Android SDK/NDK/CMake，并提前切换到 Node.js 24 运行时）。
- [x] 基于 GitHub Actions / Android 官方环境信息调整 CI：升级到 Node 24 兼容的 Actions 版本，回退到稳定 `compileSdk/targetSdk 35`，并提高 Gradle 堆内存。
- [x] 继续收敛 CI 环境噪音：将 `setup-gradle` 升级到 Node 24 兼容版本，替换 `android-actions/setup-android@v3` 为 `amyu/setup-android@v5`，并开启更详细的 Gradle 日志。
- [x] 根据 GitHub Actions 的 Kotlin 编译日志修复 `TranslationPipeline` 半截重构问题：补齐旧依赖注入参数、恢复 `pageRegionDetector/settingsStore` 等字段、修复 `processImage()` JNI 声明与局部 lambda 编译错误。
- [x] 将 `TranslationPipeline` 重构为 VLM-only：主翻译、缓存元数据、空白结果与 VL 直译统一走本地 MiniCPM 多模态模型，不再依赖 OCR/远程 LLM 主流程。
- [x] 将 `FolderTranslationCoordinator` 的全文翻译入口改为复用本地 VLM 标准管线，绕过旧的“OCR 预处理 -> 术语抽取 -> 二次翻译”两阶段流程。
- [x] 修复 `minicpm_v_jni.cpp` 与当前 `llama.cpp` API 漂移：切换到 `flash_attn_type`、`llama_memory_clear()` 与 `llama_token_to_piece()`。
- [x] 根据最新 CI 原生日志修复 `mtmd` 链接失败：放弃修改 `llama.cpp` 子模块内部脚本，改为在父项目 [CMakeLists.txt](file:///e:/翻译/manga-translator-android/app/src/main/cpp/CMakeLists.txt) 中手工定义最小 `mtmd` 静态库，并关闭不必要的 `LLAMA_BUILD_COMMON/OPENSSL` 依赖。
- [x] 根据最新 CI 原生日志修复 `ggml-cpu/llamafile/sgemm.cpp` 在 `armeabi-v7a` 上的 FP16 intrinsic 编译失败：显式关闭 `GGML_LLAMAFILE`，并将 Android ABI 收缩为 `arm64-v8a`，避免为端侧 VLM 构建无实际价值的 32 位包体。
- [x] 根据本地 Windows 构建日志绕过 Android Gradle Plugin 的非 ASCII 路径拦截：在 `gradle.properties` 中启用 `android.overridePathCheck=true`，避免因工程目录位于 `E:\翻译\...` 而在插件应用阶段提前终止。
- [x] 补齐本地 Android SDK 定位：已在 Android Studio 中安装 `API 35 / Build-Tools 35 / Platform-Tools / NDK / CMake`，本地 Gradle 构建已能自动补齐缺失组件并成功通过 `:app:compileDebugKotlin`。

### Phase 2: C++ 引擎层接入 (llama.cpp)
- [x] 在 `app/src/main/cpp` 中引入 `llama.cpp` 源码（包含 `llava` 多模态扩展支持）。
- [x] 配置 `CMakeLists.txt`，启用 Android NDK 编译，开启 NEON 优化，视情况开启 Vulkan 支持。
- [x] 编写 JNI 接口 `minicpm_jni.cpp`，暴露 `init_model`, `process_image`, `generate_text`, `clear_kv_cache` 等方法。

### Phase 3: Android 数据与设置层
- [x] 增加 `VlmModelManager`：管理内部存储中的 `.gguf` 文件。
- [x] 改造 `SettingsFragment`：
  - 新增 `MiniCPM-V 端侧模型` 设置大类。
  - 提供“导入语言模型 (LLM)”和“导入视觉映射模型 (mmproj)”的按钮。
  - 提供 CPU 线程数 (Threads) 自定义选项。
- [x] 将本地模型目录统一收敛为应用专用 `/Minicpm-model`：设置页展示默认下载目录，并在 `VlmModelManager` 中新增 MiniCPM-V 4.6 的 `F16 / Q4_0 / Q6_K / Q8_0` 精度映射与 mmproj 固定下载链接。
- [x] 为模型设置页补齐自动下载闭环：支持在设置页选择当前 LLM 精度、自动下载所选精度模型与 mmproj，并保留“从自定义路径导入 LLM/mmproj”入口。

### Phase 4: 核心翻译管线重构 (Translation Pipeline)
- [x] 编写 `LocalVlmClient.kt`，封装 JNI 调用，替代原有的 OkHttp 远程调用逻辑。
- [x] 重写 `TranslationPipeline.kt`：
  - 构造系统 Prompt：强制模型以 JSON 格式输出 `[{"box": [x,y,w,h], "text": "译文"}]`。
  - 传入图片和 Prompt，获取 VLM 结果。
  - 编写高鲁棒性的 JSON 提取器（当前已支持数组截取、坐标归一化与失败回退；后续继续增强异常输出清洗）。
- [x] 坐标对齐与渲染：将 VLM 相对坐标转换为原始图像像素坐标，送入 `BubbleRenderer`。
- [x] 将阅读页“空白气泡补译”从旧 `OCR + 远程 LLM` 链路切换为本地 MiniCPM 单气泡裁图翻译。
- [x] 将悬浮球编辑态“确认后补译空白气泡”从旧 `OCR + 远程 LLM` 链路切换为本地 MiniCPM 单气泡裁图翻译。
- [x] 将设置页中的 MiniCPM 线程数输入接入真实配置，推理初始化时按用户设置生效。
- [x] 将悬浮球主检测从 `YOLO + OCR + 远程翻译` 改为整屏截图直接走本地 MiniCPM，多模态模型一次性返回气泡框与译文。
- [x] 收敛设置页的旧云端入口：隐藏主设置中的 OpenAI/OCR 配置项，并将悬浮翻译设置面板降级为仅保留语言、校对模式、自动关闭和手势等仍然有效的本地模式配置。

### Phase 5: UX 体验增强
- [ ] 增加流式解析回调：JNI 边生成 token 边解析，气泡逐个渲染。
- [x] 新增顶部“通用任务”入口：增加标准 chatbot 风格的图文问答页，支持图片上传并复用本地 MiniCPM 推理链路返回自由文本回答。
- [x] 重构首页快捷入口：移除原仓库教程链接，将首屏入口改为 LLM/mmproj 模型导入、模型设置与悬浮窗翻译，并把漫画目录/压缩包导入下沉到项目区卡片顶部。
- [x] 将顶层应用壳收缩为“漫画库 / 通用任务 / 模型中心”三页：不再把高风险的“阅读”作为顶部 tab 暴露，避免用户直接进入旧阅读宿主页链路。
- [x] 将阅读流程改为独立 `ReadingActivity`：从漫画库打开项目或图片时直接进入单独阅读页，减少顶层多 Fragment 状态切换带来的运行时崩溃面。

### Phase 6: 运行稳定性收尾
- [x] 继续清理仍指向旧 OCR / 远程 API 的悬浮球主检测与阅读页补偿入口，避免出现“界面可点但逻辑仍落到旧链路”的运行时闪退。
- [x] 修复悬浮球空白气泡编辑确认流程中的 Kotlin 编译错误：去掉对已删除的旧重试/旧弹窗接口调用，并统一 `FloatingEmptyBubbleCoordinator` 与 `TranslationPipeline` 的可见性。
- [x] 为“未导入本地模型”“模型初始化失败”“模型输出为空”补齐统一前置提示：悬浮窗入口和通用任务入口现在会在缺模型时直接跳转/提示到设置页，不再表现为静默失败。
- [x] 用 `ReadingHostFragment` / `SettingsHubFragment` 替换高风险直接入口：顶部“阅读/设置”先进入轻量宿主页，避免在无阅读会话或旧设置树过重时直接触发闪退。
- [x] 为顶部“阅读/设置”宿主页再加一层保守保护：阅读宿主页改为异步挂载 `ReadingFragment` 并在异常时回退到占位态，设置页的状态渲染与线程数保存改为 `runCatching` 包裹，降低真机切换 tab 时的直接崩溃概率。
- [x] 修复模型切换后仍复用旧句柄的问题：`TranslationPipeline` 现在会在模型路径或线程数变化后释放旧 VLM 并重新初始化，保证精度切换与自动下载后的实际生效。
- [x] 将漫画库内会把用户带回旧链路的高风险入口降级：隐藏文件夹导出、批量翻译、重翻译和旧翻译设置卡片，只保留漫画导入、打开阅读和模型/图像任务主入口。
- [x] 为标准阅读模式补齐“首次打开图片自动触发本地多模态翻译并写回缓存”的闭环；当前阅读页即使没有旧缓存，也会尝试直接生成并叠加译文。
- [ ] 基于真机日志继续修复剩余运行时崩溃，优先处理悬浮窗整页检测后的边界交互、阅读页编辑态和不同 ROM 下的录屏/悬浮权限链路。

---
*文档生成于：2026-05-16*
