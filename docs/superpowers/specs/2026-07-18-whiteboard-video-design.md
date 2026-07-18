# 白板讲解视频生成 — 设计文档

> 基于 whiteboard 工具（zkbys/whiteboard）改造视频生成链路，从"静默幻灯片 MP4"升级为"手绘白板手绘 + TTS 配音 + 镜头运镜"讲解视频。

**目标：** 输入知识点主题 → DeepSeek 生成画板规划+旁白脚本 → Seedream 生成手绘白板图 → whiteboard D+E 渲染含配音/运镜的讲解视频。

**架构：** Java 层负责内容创作（LLM 脚本 + 画图），whiteboard 渲染层负责后期制作（TTS 配音 + bbox 校准 + 运镜 + FFmpeg 合成），中间通过 Python 桥接脚本串联。

**技术栈：** Spring Boot 3.4 (Java) + DeepSeek (LLM) + Seedream (图片) + whiteboard D/E (Python 3.10+ / Node 20+ / ffmpeg / edge-tts)

---

## 1. 整体数据流

```
DeepSeek ① 画板规划          DeepSeek ② 旁白脚本          Seedream 画图
boards.json                  script.json                  images/*.png
     │                            │                            │
     └────────────────────────────┼────────────────────────────┘
                                  │
                   whiteboard-bridge.py（桥接脚本）
                                  │
                     ┌────────────┼────────────┐
                     ▼            ▼            ▼
              board_spec.json  asset_manifest  voiceover_segments.json
                     │            │            │
                     └────────────┼────────────┘
                                  ▼
                     auto_calibrate.py（bbox 校准）
                                  ▼
                     generate_board_package.py（D: 运镜方案）
                                  ▼
                     render_multi_board_project.mjs（E: TTS + 运镜 + FFmpeg）
                                  ▼
                            preview.mp4
```

## 2. Java 层输出合约

### 2.1 画板规划 `boards.json`

DeepSeek 第一个 LLM 调用，输入知识点主题，输出每块白板上有什么元素、怎么排布。

```json
{
  "topic": "梯度下降算法",
  "style": "hand-drawn-whiteboard",
  "boards": [
    {
      "id": "board-1",
      "title": "梯度下降：从山顶到山谷",
      "subtitle": "一步步找到最低点",
      "sections": [
        {
          "id": "concept",
          "title": "核心思想",
          "items": ["损失函数 = 山", "梯度 = 坡度方向", "学习率 = 步长"],
          "annotations": ["circle", "underline"]
        },
        {
          "id": "formula",
          "title": "数学表达",
          "items": ["θ = θ - α ∇J(θ)", "α: 学习率", "∇J: 梯度"],
          "annotations": ["box"]
        }
      ]
    }
  ]
}
```

### 2.2 旁白脚本 `script.json`

DeepSeek 第二个 LLM 调用，输入画板规划和知识点，输出分段旁白 + 时间分配 + 标注动作。

```json
{
  "topic": "梯度下降算法",
  "targetDurationSec": 45,
  "segments": [
    {
      "id": "intro",
      "start": 0,
      "speechEnd": 6.0,
      "end": 6.2,
      "caption": "梯度下降的核心思想很简单：想象你站在山顶，一步步走向山谷。",
      "boardId": "board-1",
      "target": "concept",
      "actions": [
        {
          "type": "circle",
          "element": "concept",
          "spokenAnchor": "一步步走向山谷",
          "duration": 0.8
        }
      ]
    },
    {
      "id": "formula",
      "start": 6.2,
      "speechEnd": 14.0,
      "end": 14.3,
      "caption": "用数学表达就是：参数θ每次减去学习率乘以梯度的方向。",
      "boardId": "board-1",
      "target": "formula",
      "actions": [
        {
          "type": "underline",
          "element": "formula-item-1",
          "spokenAnchor": "参数θ每次减去",
          "duration": 0.9
        }
      ]
    }
  ]
}
```

字段说明：
- `start/speechEnd/end`：时间由 LLM 根据旁白长度估算（文字数 / 每秒约4字）。在 E 阶段会被实测 TTS 音频覆盖。
- `spokenAnchor`：旁白中的关键词，用于精确同步标注出现时机。
- `boardId`：指向 boards.json 中的 board id。
- `target` / `element`：指向 board_spec 中的 section id 或 item id。

### 2.3 手绘白板图 `images/<board-id>.png`

Seedream 生成，每板一张。提示词由画板规划的 sections 信息构造，强制手绘教学风：

```
[section描述]. hand-drawn whiteboard educational illustration, 
hand-drawn style, sketch lines, organic handwriting, 
chalkboard/dark background or clean paper background, 
Chinese text labels clearly readable, 
professional infographic layout, no photo-realistic elements.
```

## 3. 桥接脚本 `whiteboard-bridge.py`

接收包含 `boards.json` + `script.json` + `images/` 的临时目录，转换并串联 whiteboard D+E。

### 3.1 输入

```bash
python3 whiteboard-bridge.py --input <tmp-dir> --whiteboard-root <whiteboard安装目录>
```

### 3.2 执行步骤

1. 读取 `boards.json` → 为每个 board 生成 whiteboard 格式的 `board_spec.json`（位于 `infographic/board_specs/board-<N>.board_spec.json`）
2. 读取 `script.json` → 生成 `script/voiceover_segments.json`
3. 生成 `board_asset_manifest.json`（指向 `images/board-<N>.png`）
4. 生成 `infographic/infographic_plan.json`
5. 调用 `auto_calibrate.py --project-dir <tmp-dir>` 找出图片中元素的 bbox 坐标
6. 调用 `generate_board_package.py`（D 阶段：生成运镜方案 + board_manifest + motion_plan）
7. 调用 `render_multi_board_project.mjs`（E 阶段：edge-tts 配音 → 音频 + 时间轴 → 运镜合成 → FFmpeg 输出 mp4）
8. 校验 `video/preview.mp4` 存在，输出路径给 Java

### 3.3 whiteboard 格式对照

| 我们的输出 | whiteboard D 期望 | 转换逻辑 |
|---|---|---|
| `boards.json` | `infographic/board_specs/*.board_spec.json` | section → items 展开为 element 列表 |
| `script.json` | `script/voiceover_segments.json` | 几乎 1:1，补充 boardId |
| `images/board-1.png` | `board_asset_manifest.json` | 包装为 `{kind: "file", uri: ...}` |

## 4. Java 侧改动

### 4.1 新建文件

**`BoardPlan.java`** — 画板规划 record
```java
public record BoardPlan(
    String topic,
    String style,
    List<Board> boards
) {}

public record Board(
    String id,
    String title,
    String subtitle,
    List<BoardSection> sections
) {}

public record BoardSection(
    String id,
    String title,
    List<String> items,
    List<String> annotations  // ["circle", "underline", "box", ...]
) {}
```

**`NarrationScript.java`** — 旁白脚本 record
```java
public record NarrationScript(
    String topic,
    int targetDurationSec,
    List<ScriptSegment> segments
) {}

public record ScriptSegment(
    String id,
    double start,
    double speechEnd,
    double end,
    String caption,
    String boardId,
    String target,
    List<ScriptAction> actions
) {}

public record ScriptAction(
    String type,
    String element,
    String spokenAnchor,
    double duration
) {}
```

**`WhiteboardPipelineRunner.java`** — 封装 ProcessBuilder
```java
@Component
public class WhiteboardPipelineRunner {
    // 配置项（从 ResourceGenConfig 注入）
    private final String pythonPath;      // python3
    private final String whiteboardRoot;  // whiteboard 安装目录
    private final String bridgeScript;    // scripts/whiteboard-bridge.py 绝对路径

    /**
     * @param workDir  包含 boards.json / script.json / images/ 的目录
     * @return preview.mp4 的 byte[]
     */
    public byte[] run(Path workDir) throws Exception {
        // ProcessBuilder 调 python3 bridge.py --input workDir
        // 等待进程结束，校验退出码
        // 读取 workDir/video/preview.mp4
    }
}
```

### 4.2 修改文件

**`VideoGenerator.java`** — 重写 `doGenerate()`
```
原流程：DeepSeek → VideoScript → Seedream 画图 → FFmpeg images→mp4
新流程：DeepSeek → BoardPlan → Seedream 画图 → DeepSeek → NarrationScript
          → 写临时目录 → WhiteboardPipelineRunner.run() → 读 mp4 → 存储
```

**`VideoAssembler.java`** — 保留但不作为主路径，whiteboard E 阶段自行处理 FFmpeg 合成。

**`ResourceGenConfig.java`** — 新增配置项：
```yaml
resourcegen:
  whiteboard:
    python-path: python3
    whiteboard-root: /path/to/whiteboard
    bridge-script: scripts/whiteboard-bridge.py
```

## 5. 环境依赖

服务器需额外安装：

| 依赖 | 安装方式 | 用途 |
|------|---------|------|
| Python 3.10+ | 已有 | whiteboard 脚本运行 |
| Node.js 20+ | 已有 | E 阶段 HyperFrames 渲染 |
| ffmpeg + ffprobe | 已有（VideoAssembler 已在用） | 视频合成 |
| edge-tts | `pip install edge-tts` | 免费中文 TTS 配音 |
| hyperframes | `npm install -g hyperframes@0.6.99` | 可编辑视频工程 |
| whiteboard 安装 | `python3 scripts/install.py --target claude` | D+E 渲染模块 |

## 6. 降级策略

当 whiteboard 流水线执行失败时：
- 捕获异常，回退到原 VideoGenerator 的"Seedream 图片 → FFmpeg 幻灯片"逻辑（VideoAssembler 保留）
- whiteboard 依赖通过 `doctor.py --json` 在首次调用时检查，失败则直接走降级

## 7. 错误处理

| 阶段 | 失败点 | 处理 |
|------|--------|------|
| LLM 画板规划 | DeepSeek 返回格式异常 | 重试 1 次，失败走降级 |
| LLM 旁白脚本 | DeepSeek 返回格式异常 | 重试 1 次，失败走降级 |
| Seedream 画图 | API 失败 / 图片异常 | 单板失败用纯色占位图，全部失败走降级 |
| 桥接脚本 | 格式转换 / 文件路径错误 | 日志记录，走降级 |
| auto_calibrate | bbox 识别失败 | 使用估算坐标（section 均匀分布），继续流水线 |
| D 阶段 | board_package 生成失败 | 日志记录，走降级 |
| E 阶段 | TTS / FFmpeg 失败 | 日志记录，走降级 |
| 整体 | preview.mp4 不存在 | 走降级 |

## 8. 测试策略

- **单元测试**：BoardPlan / NarrationScript JSON 序列化/反序列化
- **集成测试**：WhiteboardPipelineRunner 对 mock 桥接脚本的调用
- **端到端测试**：提交一个简单知识点（如"什么是机器学习"），验证完整流水线产出 preview.mp4 且时长在预期范围
- **降级测试**：模拟桥接脚本失败，验证回退到旧幻灯片逻辑
