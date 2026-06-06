# 多模型 Provider 架构 — 设计规格说明书

> **项目**: 个性化多模态学习资源生成与管理系统  
> **模块**: 多模型 Provider 路由 (multi-resource-generate v2)  
> **日期**: 2026-06-05  
> **依赖**: [v1 设计规格](./2026-06-04-multi-resource-generate-design.md)

---

## 1. 动机

当前 v1 架构中，所有 6 个 Generator 共享单一 `ChatClient` bean（指向 DeepSeek）。这导致两个问题：

1. **PPT 生成内容空白** — DeepSeek 是纯文本模型，无法生成丰富的幻灯片内容
2. **无法按任务类型选择模型** — 文本任务和视觉任务被绑定到同一个模型

本设计引入多 Provider 架构，实现：
- 文本类任务（文档/导图/习题/代码/HTML）→ DeepSeek
- 视觉/多媒体任务（PPT、未来视频）→ 独立配置的视觉 Provider
- 配置驱动，开哪个用哪个，不改代码

---

## 2. 核心抽象

### 2.1 ModelProvider 接口

所有 LLM Provider 实现此接口，每个只需实现 `chat` 方法：

```java
public interface ModelProvider {
    String chat(String prompt);
    String providerName();
    default boolean isEnabled() { return true; }
}
```

### 2.2 Provider 实现清单

| Provider | 实现类 | 用途 | 覆盖的 API |
|---|---|---|---|
| DeepSeek | `DeepSeekProvider` | 所有文本任务 | OpenAI-compatible |
| OpenAI | `OpenAiCompatibleProvider` | 视觉任务 | OpenAI-compatible |
| OpenRouter | `OpenAiCompatibleProvider` | 视觉任务 | OpenAI-compatible |
| 硅基流动 | `OpenAiCompatibleProvider` | 视觉任务 | OpenAI-compatible |
| 讯飞 | `XunfeiProvider` | 文本/视觉 | 讯飞 Spark API |
| Seedance | `VisualProvider` | 视频/动画 | Seedance API |
| HuggingFace | `VisualProvider` | 图片生成 | HuggingFace Inference |

> **注**: OpenAI / OpenRouter / 硅基流动共用 `OpenAiCompatibleProvider`，仅 base-url 和 api-key 不同。

### 2.3 ModelRegistry — 路由中心

```java
@Component
public class ModelRegistry {
    // 由 ModelRoutingProperties 决定 textProvider 和 visualProvider
    private final ModelProvider textProvider;
    private final ModelProvider visualProvider;

    public ModelProvider resolve(ResourceType type) {
        return switch (type) {
            case VIDEO -> visualProvider.isEnabled() ? visualProvider : textProvider;
            default    -> textProvider;
        };
    }
}
```

---

## 3. YAML 配置设计

### 3.1 model-routing 配置块

```yaml
model-routing:
  text:
    provider: deepseek
    model: deepseek-chat
    temperature: 0.7

  visual:
    provider: siliconflow        # 暂 disabled，调研完开
    model: stable-diffusion-xl

  providers:
    deepseek:
      enabled: true
      base-url: https://api.deepseek.com
      api-key: ${DEEPSEEK_API_KEY}

    siliconflow:
      enabled: false
      base-url: https://api.siliconflow.cn/v1
      api-key: ${SILICONFLOW_API_KEY}

    openai:
      enabled: false
      base-url: https://api.openai.com
      api-key: ${OPENAI_API_KEY}

    openrouter:
      enabled: false
      base-url: https://openrouter.ai/api
      api-key: ${OPENROUTER_API_KEY}

    xunfei:
      enabled: false
      base-url: https://spark-api-open.xf-yun.com
      api-key: ${XUNFEI_API_KEY}
      api-secret: ${XUNFEI_API_SECRET}
      app-id: ${XUNFEI_APP_ID}

    seedance:
      enabled: false
      base-url: https://api.seedance.io
      api-key: ${SEEDANCE_API_KEY}

    huggingface:
      enabled: false
      base-url: https://api-inference.huggingface.co
      api-key: ${HUGGINGFACE_API_KEY}
```

### 3.2 开关语义

- `enabled: true` → Provider 注册到 ModelRegistry，可用
- `enabled: false` → Provider 不注册（或注册但 `isEnabled() == false`），不可用
- `visual.provider` 指向的 Provider 如果 `enabled: false` → VideoGenerator 自动降级到 V1

---

## 4. PPT 两阶段生成

### 4.1 数据模型：LectureScript

```java
public record LectureScript(
    String title,
    List<SlideScript> slides,
    String teacherName,
    int estimatedDurationSeconds
) {}

public record SlideScript(
    int index,
    String title,
    List<String> bulletPoints,    // 显示在 PPT 上
    String narration,              // 教师讲稿（给同学的 TTS 模块朗读）
    String visualPrompt,           // 传给视觉 Provider 的生成 prompt
    int durationSeconds            // 这页讲多久
) {}
```

### 4.2 生成流程

```
阶段1（DeepSeek）:
  输入: GenerationState (blueprint, profile, kgContext, ragContext)
  输出: LectureScript JSON
  降级: LLM 失败 → 3 页基础讲稿

阶段2（Visual Provider）:
  输入: LectureScript
  输出: PPTX byte[]
  降级: Visual Provider disabled 或调用失败 → Apache POI 基础 PPTX (V1 行为)
```

### 4.3 降级策略

| 场景 | 行为 |
|---|---|
| visual.provider 的 enabled=false | 跳过阶段2，直接用 Apache POI 出 PPTX |
| visual Provider 调用报错 | 日志 warn，降级到 Apache POI，不中断 |
| visual Provider 返回空/无效 | 降级到 Apache POI |
| DeepSeek 阶段1 失败 | 生成 3 页基础讲稿作为 fallback |

---

## 5. 文件改动清单

| 文件 | 操作 | 说明 |
|---|---|---|
| `resourcegen/infrastructure/ModelProvider.java` | 新增 | 核心接口 |
| `resourcegen/infrastructure/DeepSeekProvider.java` | 新增 | 复用现有 ChatClient |
| `resourcegen/infrastructure/OpenAiCompatibleProvider.java` | 新增 | 覆盖 3 个供应商 |
| `resourcegen/infrastructure/XunfeiProvider.java` | 新增 | 讯飞适配（比赛要求） |
| `resourcegen/config/ModelRegistry.java` | 新增 | 路由中心 |
| `resourcegen/config/ModelRoutingProperties.java` | 新增 | @ConfigurationProperties |
| `resourcegen/model/LectureScript.java` | 新增 | 讲稿数据模型 |
| `agents/generators/AbstractGenerator.java` | 修改 | ChatClient → ModelRegistry |
| `agents/generators/VideoGenerator.java` | 修改 | 两阶段重构 |
| `config/ResourceGenConfig.java` | 修改 | 移除 ChatClient bean，改用 Provider bean |
| `config/DotenvConfig.java` | 修改 | KEYS 数组加 5 个 |
| `resources/application.yml` | 修改 | 添加 model-routing 块 |
| `.env.example` | 修改 | 添加 5 个新 Key 占位符 |

---

## 6. 与同学模块的接口

| 产出 | 格式 | 消费者 |
|---|---|---|
| `LectureScript` (含 `narration` 讲稿) | JSON 或 Java 对象 | 同学的 TTS 模块 |
| PPTX 文件 | `.pptx` 二进制 | 前端播放器 |
| `estimatedDurationSeconds` + 逐页 `durationSeconds` | int | 时间轴同步 |

**注意**：本项目不做语音合成，只出讲稿文本。TTS 由同学模块负责。

---

## 7. 自审清单

- [x] 无 TBD / TODO 占位符
- [x] ModelProvider 接口极简（1 个方法），降低实现成本
- [x] 配置驱动 — 用户不改代码即可切换 Provider
- [x] 降级策略完整 — 任何失败不影响流水线
- [x] 外部接口预留 — LectureScript 讲稿字段供 TTS 模块消费
- [x] 复用现有基础设施 — DeepSeek 走现有 OpenAiApi，OpenAI 兼容供应商共用一个实现
- [x] 范围聚焦 — 只涉及资源生成模块，不涉及其他子系统的修改
