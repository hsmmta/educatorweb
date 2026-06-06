# 视频生成模块 — 设计规格说明书

> **项目**: 个性化多模态学习资源生成与管理系统  
> **模块**: 视频生成 (仿 ViMax 架构)  
> **日期**: 2026-06-05  
> **依赖**: [v1.1 多模型 Provider 设计](./2026-06-05-multi-model-provider-design.md)

---

## 1. 动机

当前 `VideoGenerator` 实际生成的是 `.pptx` 文件（名为 VIDEO 实为 PPT）。本设计：

1. 将 VIDEO 改名为 PPT，创建真正的 `PptGenerator`
2. 新建真正的 `VideoGenerator`，仿 ViMax 四阶段流水线，输出 `.mp4`

**参考项目**: [ViMax (HKUDS)](https://github.com/HKUDS/ViMax) — MIT 协议

---

## 2. ResourceType 变更

| 之前 | 之后 | 说明 |
|---|---|---|
| `VIDEO` | `PPT` | 改名，保留全部已有 PPT 生成功能 |
| — | `VIDEO` (新增) | 真正的视频生成 |

---

## 3. 架构：ViMax 流水线映射

ViMax 的 5 个 Agent 映射到我们的系统：

| ViMax | 我们的实现 | 状态 |
|---|---|---|
| Director (总调度) | `GraphOrchestrator` | 已有 |
| Screenwriter (编剧) | `DesignAgent` → VideoScript 大纲 | 已有 |
| Storyboard Artist (分镜) | DeepSeek → `VideoScript` 详案 | 新建 |
| Producer (制片) | `VideoProvider.generateVideo()` | 新建 |
| Quality Control (质检) | `ReviewAgent` | 已有 |

### 流水线

```
Phase 1: DesignAgent → VideoScript 大纲（主题、风格、场景数）
    ↓
Phase 2: DeepSeek → 逐帧 VideoScene（visualPrompt + narration + cameraAngle）
    ↓
Phase 3: VideoProvider.generateVideo(visualPrompt, duration) → [.mp4 片段]（fanOut 并行）
    ↓
Phase 4: FFmpeg concat → 转场 → .mp4
    ↓
输出: .mp4 + VideoScript（narration 给同学 TTS）
```

---

## 4. 数据模型

### VideoScript

```java
public record VideoScript(
    String title,
    List<VideoScene> scenes,
    String style,              // "Cartoon" | "Realistic" | "Whiteboard"
    int totalDurationSeconds
) {}

public record VideoScene(
    int index,
    String description,        // 场景描述
    String narration,          // 旁白文本（给同学 TTS）
    String visualPrompt,       // 视频生成 prompt
    String cameraAngle,        // "wide" | "close-up" | "dolly"
    int durationSeconds,
    String transition          // "fade" | "cut" | "dissolve"
) {}
```

### 与 PPT 的 LectureScript 区别

| | LectureScript (PPT) | VideoScript (视频) |
|---|---|---|
| 适用 | PPTX 生成 | MP4 视频生成 |
| 粒度 | 页级别 | 场景/分镜级别 |
| 视觉 | visualPrompt（预留） | visualPrompt + cameraAngle |
| 时间 | durationSeconds（估计） | durationSeconds（约束视频长度） |
| 转场 | 无 | transition |

---

## 5. VideoProvider 接口

```java
public interface VideoProvider {
    byte[] generateVideo(String visualPrompt, int durationSeconds);
    byte[] generateImage(String prompt);  // 降级用
    String providerName();
    default boolean isEnabled() { return true; }
}
```

### 实现 + 降级链

| 优先级 | 实现 | 说明 |
|---|---|---|
| 🥇 | `SeedanceVideoProvider` | 调用 ByteDance Seedance API |
| 🥈 | `VeoVideoProvider` | 调用 Google Veo API |
| 🥉 | `CogVideoXProvider` | 本地或云端 CogVideoX |
| ⚠️ | `StaticImageFallbackProvider` | 视觉模型生成静态图 → FFmpeg 转 5 秒静态视频 |
| ❌ | `PureTextFallbackProvider` | 纯色背景 + 文字 → 最低配视频 |

---

## 6. YAML 配置

```yaml
model-routing:
  # ...
  video:
    provider: seedance           # seedance | veo | cogvideox
    model: seedance-v1

  providers:
    seedance:
      enabled: false
      base-url: https://api.seedance.io
      api-key: ${SEEDANCE_API_KEY}
    veo:
      enabled: false
      base-url: https://videogeneration.googleapis.com
      api-key: ${VEO_API_KEY}
    cogvideox:
      enabled: false
      base-url: http://localhost:8000
      api-key: ""
```

---

## 7. 文件改动清单

| 操作 | 文件 | 说明 |
|---|---|---|
| 🆕 | `VideoProvider.java` | 视频生成接口 |
| 🆕 | `SeedanceVideoProvider.java` | Seedance 实现 |
| 🆕 | `StaticImageFallbackProvider.java` | 降级实现 |
| 🆕 | `VideoScript.java` | 分镜脚本模型 |
| 🆕 | `VideoGenerator.java` | 全新四阶段视频生成器 |
| 🆕 | `VideoAssembler.java` | FFmpeg 合成 |
| 📝 改名 | `VideoGenerator.java` → `PptGenerator.java` | 改名为 PPT 生成器 |
| 📝 | `ResourceType.java` | VIDEO→PPT，新增 VIDEO |
| 📝 | `ModelRegistry.java` | 新 VideoProvider 路由 |
| 📝 | `application.yml` | video-providers 配置块 |
| 📝 | `test.html` | PPT / VIDEO 两个选项 |

---

## 8. 不改动

- `LectureScript` / `SlideScript` 数据模型 — PPT 继续用
- `PptBuilder` / `FileStorageService` — PPT 继续用
- 所有现有的 5 个文本 Generator — 不动
- `DesignAgent` / `RequireAgent` / `ReviewAgent` — 不动
- `GraphOrchestrator` / `ModelRegistry` — 扩展，不破坏

---

## 9. 自审清单

- [x] PPT 功能全部保留，只改名
- [x] ViMax 四阶段完整映射
- [x] VideoProvider 降级链 5 层，最低配保证始终能出视频
- [x] narraction 字段留给同学 TTS
- [x] 不与已有 Agent 冲突
- [x] 配置驱动 — .env + YAML 切换
