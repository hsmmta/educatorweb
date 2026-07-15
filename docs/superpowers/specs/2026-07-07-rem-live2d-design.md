# Rem Live2D 数字人形象接入 设计文档

> **日期:** 2026-07-07
> **主题:** 为 AI 语音助手小窗接入 Rem 的 Live2D 形象
> **实现方式:** 复用项目现有 `pixi-live2d-display` + `pixi.js` + `Live2DCharacter.vue`（非 l2d 工具）

## 1. 目标

将所有功能页面右下角 AI 语音助手（`VoiceAssistant.vue`）小窗内的静态 emoji 小猫（🐱），替换为可动的 Rem Live2D 形象，支持待机动作、说话动画（talk 动作组 + 伪口型）、分区点击触发动作，并与语音助手的说话/监听/表情状态联动。Live2D 加载失败时回退到现有 emoji，保证不白屏。

## 2. 背景与现状

- **rem 模型**：位于 `dev-docs/l2d-models/models/rem/`，为 **Cubism 2.x** 格式（`remu.moc` + `model.json` + `.mtn` 动作 + `remu.physics.json` + `remu.pose.json` + `remu2048/` 贴图）。`model.json` 结构与项目已跑通的 hijiki 完全一致。
- **rem 动作组**：`idle`、`flick_head`（10 个）、`tap_body`（15 个）、`talk`（25 个）、`sleepy`、`rest`。
- **rem 布局元数据**：`layout.center_y = -0.05`、`layout.width = 2.0`；`hit_areas_custom` 提供 head/body 命中矩形。
- **现有渲染组件**：`frontend/src/components/Live2DCharacter.vue` 已用 `pixi-live2d-display`（Cubism 2 core）渲染 hijiki，已暴露 `setExpression / setMouthOpen / playRandomMotion / isReady`，含 idle 动作循环、resize、cleanup。
- **现有运行时**：`frontend/public/live2d/` 已有 `live2d.core.js`（Cubism 2 运行时）、`Live2DFramework.js`，以及 hijiki 模型目录。
- **现有依赖**：`pixi-live2d-display@0.5.0-beta`、`pixi.js@7.4.3` 已安装，无需新增依赖。
- **缺口**：`VoiceAssistant.vue` 当前显示静态 emoji，未接入 `Live2DCharacter.vue`（注释称"Cubism2 runtime 修复后启用"）；rem 模型文件尚未拷入 `frontend/public/`。

## 3. 实现方式说明（为何不用 l2d）

`dev-docs/l2d-models` 仓库提供的是**通用 Cubism 2 模型资源**；其 README 用 `l2d`（hacxy/l2d）做在线预览，只是该仓库自身的演示器，不限制使用方式。本方案复用项目已有的 `pixi-live2d-display` 底层渲染库，理由：(1) 已安装且有可用组件，零新依赖、改动最小；(2) 需要程序化细控口型、动作、命中测试、表情联动，pixi-live2d-display 直接支持，而 l2d 是打包好的浮动挂件、不易细控且与现有小窗集成方式不符。

## 4. 详细设计

### 4.1 资源放置

将 rem 模型目录拷贝到前端静态资源目录：

- 源：`dev-docs/l2d-models/models/rem/`
- 目标：`frontend/public/live2d/rem/`
- 拷贝内容：`model.json`、`remu.moc`、`remu.physics.json`、`remu.pose.json`、`remu2048/`（贴图）、`motions/`（全部 `.mtn`）
- **不拷贝**：`voice/`（音频用不上）、`ReadMe.txt`、`rem.png`/`rem.pil.png`（预览图）
- 运行时访问路径：`/live2d/rem/model.json`

### 4.2 `Live2DCharacter.vue` 改造（在现有组件上小改）

**(1) 模型路径参数化**
- 新增 prop：`modelPath`（String，默认 `'/live2d/rem/model.json'`）。
- 将 `init()` 中写死的 `Live2DModel.from('/live2d/hijiki/hijiki.model.json')` 改为 `Live2DModel.from(props.modelPath)`。

**(2) 缩放/定位适配**
- 保持现有基于容器尺寸的居中逻辑；`anchor` 与 `y` 偏移按 rem `layout.center_y = -0.05` 微调，`scale` 起始系数按需微调使半身像在 280px 小窗内完整可见（实现时按实际渲染结果调参）。

**(3) 说话动画 API（新增）**
- `playTalkMotion()`：从 `talk` 动作组随机 `model.motion('talk', idx)`。
- `startLipSync()`：启动 `requestAnimationFrame` 循环，按 `0.5 + 0.5*sin(t)` 叠加小幅随机抖动写入 `PARAM_MOUTH_OPEN_Y`，模拟说话口型。
- `stopLipSync()`：取消 rAF 并将 `PARAM_MOUTH_OPEN_Y` 归零。
- 说话期间暂停 idle 随机动作调度，结束后恢复。

**(4) 分区点击触发动作（新增）**
- 在 canvas 上监听 `pointerdown`，将点击坐标换算为模型归一化坐标，依据 rem `hit_areas_custom` 的 head/body 矩形判断命中区域：
  - 命中 head → `model.motion('flick_head', 随机)`
  - 命中 body → `model.motion('tap_body', 随机)`
  - 未命中特定区域 → 播一个随机非 idle 动作（复用 `playRandomMotion`）。

**(5) 表情参数**
- 保留现有 `setExpression`（happy/surprised/thinking/neutral，基于通用 Cubism 2 参数名）。rem 若缺某参数，`setParameterValueById` 静默失败、不崩溃；实现时先探测 rem 实际参数集，对缺失项做降级（跳过该参数）。

**(6) `defineExpose` 增补**
- 追加暴露：`playTalkMotion`、`startLipSync`、`stopLipSync`。

**(7) 加载失败反馈**
- `init()` 失败时保持 `isReady = false`（现已实现），供父组件据此回退 emoji。

### 4.3 `VoiceAssistant.vue` 接线

**(1) 头像区渲染**
- 在 `va-canvas-wrap` 内：
  - 挂载 `<Live2DCharacter ref="l2dRef" />`（默认加载 rem）。
  - 当 Live2D 未就绪（`!l2dReady`）或加载失败时，`v-show` 回退显示现有 emoji 头像块（`va-avatar`）。
  - 通过监听子组件 `isReady`（或轮询/事件）设置本地 `l2dReady`，控制两者显隐。
- 保留 `va-expression-label` 文字标签叠加在角色上方。

**(2) 状态联动**
- `speak(text)` 的 `onstart`：`l2dRef.value?.playTalkMotion()` + `l2dRef.value?.startLipSync()` + `l2dRef.value?.setExpression('speaking')`；`onend`/`onerror`：`stopLipSync()` + `setExpression('neutral')`。
- `toggleListening` 开始监听：`setExpression('surprised')`。
- 问候/感谢等"开心"回复：`setExpression('happy')`；兜底/思考回复：`setExpression('thinking')`。
- 所有调用用可选链，Live2D 未就绪时静默跳过，不影响语音功能。

**(3) emoji 兜底逻辑保留**
- 现有 `avatarEmoji`/`expressionLabel` 计算逻辑保留，作为 Live2D 不可用时的完整降级路径。

### 4.4 数据流

```
用户语音/文字输入
      │
handleIntent() ── 生成回复文本
      │
   speak(text)  ──onstart──▶ l2d.playTalkMotion() + startLipSync() + setExpression('speaking')
      │          ──onend───▶ l2d.stopLipSync() + setExpression('neutral')
      │
canvas pointerdown ──命中判定──▶ l2d.motion('flick_head' | 'tap_body' | random)
      │
idle 循环（组件内）──▶ 无输入时定时播 idle 动作
```

## 5. 边界与风险

| 风险 | 说明 | 处理 |
|---|---|---|
| rem 表情参数缺失 | `setExpression` 用的通用参数名 rem 不一定全有 | setById 静默失败不崩；实现时探测实际参数，缺失项跳过 |
| 过往"未启用" | VoiceAssistant 注释称 Cubism2 runtime 待修复 | 同格式 hijiki 已跑通，判断为未接线而非 runtime 坏；实现第一步先验证 rem 能在小窗渲染 |
| 首次加载体积 | 2048×贴图 + 35 个 mtn，数百 KB | 面板首次展开时才挂载（懒加载），不阻塞页面 |
| WebGL 上下文 | 小窗内 PIXI 画布 | 复用现有 resize/cleanup 逻辑；失败回退 emoji |

## 6. 验证方式

1. 面板展开后，rem 形象在 280px 小窗内完整可见并循环 idle 动作。
2. 语音/文字触发回复时，rem 播 talk 动作且嘴巴开合，回复结束归于待机。
3. 点击头部/身体分别触发 flick_head / tap_body 动作。
4. 监听/开心/思考状态下表情有相应变化（视 rem 参数支持情况）。
5. 断网或删除模型文件时，小窗回退显示 emoji 小猫，语音功能不受影响。

## 7. 涉及文件

| 文件 | 操作 |
|---|---|
| `frontend/public/live2d/rem/**` | **新增**（拷贝 rem 模型资源） |
| `frontend/src/components/Live2DCharacter.vue` | **修改**（modelPath prop、talk/lipsync/分区点击 API、expose 增补） |
| `frontend/src/components/VoiceAssistant.vue` | **修改**（接入 Live2DCharacter、状态联动、emoji 兜底） |

## 8. 不做的事（YAGNI）

- 不接真·音频驱动口型（SpeechSynthesis 无振幅流，成本高）。
- 不改浮动触发按钮（保持 🤖）。
- 不引入 l2d 或其他新依赖。
- 不拷贝 rem 的语音 wav 文件。
- 不做多模型切换 UI（仅接 rem；modelPath 已参数化，未来可扩展）。
