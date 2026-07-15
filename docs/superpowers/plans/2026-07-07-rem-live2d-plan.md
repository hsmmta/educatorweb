# Rem Live2D 数字人形象接入 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 AI 语音助手小窗内的静态 emoji 小猫替换为可动的 Rem Live2D 形象，支持待机/说话/点击动作与表情联动，失败回退 emoji。

**Architecture:** 复用项目已装的 `pixi-live2d-display` + `pixi.js` 与现有 `Live2DCharacter.vue` 组件。先拷贝 rem（Cubism 2 格式）模型资源到 `frontend/public/live2d/rem/`，再给 `Live2DCharacter.vue` 增加 modelPath prop + talk 动作/伪口型/分区点击 API，最后在 `VoiceAssistant.vue` 中嵌入该组件并接线状态，保留 emoji 兜底。

**Tech Stack:** Vue 3 (`<script setup>`), pixi-live2d-display@0.5.0-beta, pixi.js@7, Cubism 2 runtime (`/live2d/live2d.core.js`)

**测试说明:** 本项目前端无自动化测试框架（package.json 无 test 脚本、无 vitest/jest）。因此本计划采用**手动验证**替代自动化测试：每个任务给出明确的浏览器/命令行验证步骤与预期结果。前端开发服务器：`cd frontend && npm run dev`（默认 http://localhost:5173，需登录后任意功能页右下角可见语音助手）。

---

## File Structure

| 文件 | 操作 | 职责 |
|---|---|---|
| `frontend/public/live2d/rem/**` | 新增 | rem 模型静态资源（moc/贴图/动作/物理/pose + model.json） |
| `frontend/src/components/Live2DCharacter.vue` | 修改 | 通用 Live2D 渲染组件：模型路径参数化 + 说话动画/伪口型/分区点击 API |
| `frontend/src/components/VoiceAssistant.vue` | 修改 | 语音助手：嵌入 Live2DCharacter，状态联动，emoji 兜底 |

---

## Task 1: 拷贝 rem 模型资源到 public

**Files:**
- Create: `frontend/public/live2d/rem/` （从 `dev-docs/l2d-models/models/rem/` 拷贝）

- [ ] **Step 1: 拷贝模型资源（排除无用文件）**

在仓库根目录 `E:/educatorweb/educatorweb` 下执行（Git Bash）：

```bash
mkdir -p frontend/public/live2d/rem
cp "dev-docs/l2d-models/models/rem/model.json"          frontend/public/live2d/rem/
cp "dev-docs/l2d-models/models/rem/remu.moc"            frontend/public/live2d/rem/
cp "dev-docs/l2d-models/models/rem/remu.physics.json"   frontend/public/live2d/rem/
cp "dev-docs/l2d-models/models/rem/remu.pose.json"      frontend/public/live2d/rem/
cp -r "dev-docs/l2d-models/models/rem/remu2048"         frontend/public/live2d/rem/
cp -r "dev-docs/l2d-models/models/rem/motions"          frontend/public/live2d/rem/
```

（不拷贝 `voice/`、`ReadMe.txt`、`rem.png`、`rem.pil.png`）

- [ ] **Step 2: 验证文件结构**

Run:
```bash
find frontend/public/live2d/rem -type f | sort
```
Expected（应包含）：
```
frontend/public/live2d/rem/model.json
frontend/public/live2d/rem/motions/Live2D_remu01.mtn
... (共 35 个 .mtn，含 Live2D_remu_idle.mtn)
frontend/public/live2d/rem/remu.moc
frontend/public/live2d/rem/remu.physics.json
frontend/public/live2d/rem/remu.pose.json
frontend/public/live2d/rem/remu2048/texture_00.png
```

- [ ] **Step 3: 验证 model.json 内容完整**

Run:
```bash
grep -E '"model"|"textures"|"motions"' frontend/public/live2d/rem/model.json
```
Expected: 能看到 `"model": "remu.moc"`、`"textures"`、`"motions"` 字段（说明 JSON 未损坏）。

- [ ] **Step 4: Commit**

```bash
git add frontend/public/live2d/rem
git commit -m "feat(live2d): add rem model assets to public"
```

---

## Task 2: `Live2DCharacter.vue` — 模型路径参数化

**Files:**
- Modify: `frontend/src/components/Live2DCharacter.vue`

- [ ] **Step 1: 新增 modelPath prop**

在 `<script setup>` 顶部（`import` 之后、`const canvasContainer = ref(null)` 之前）加入 props 定义：

```js
const props = defineProps({
  modelPath: { type: String, default: '/live2d/rem/model.json' }
})
```

- [ ] **Step 2: 用 prop 替换写死的模型路径**

将 `init()` 中这一行：

```js
    model = await Live2DModel.from('/live2d/hijiki/hijiki.model.json')
```

改为：

```js
    model = await Live2DModel.from(props.modelPath)
```

- [ ] **Step 3: 手动验证渲染**

临时在任意页面挂载该组件（或直接进入 Task 4 后一起验证）。最小验证：`cd frontend && npm run dev`，若已被 VoiceAssistant 引用则展开面板；否则可在 `Home.vue` 临时加 `<Live2DCharacter style="width:320px;height:320px" />` 查看。
Expected: 画布中出现 rem 形象（半身），控制台无 `Live2D init failed` 报错。验证后移除临时挂载。

- [ ] **Step 4: Commit**

```bash
git add frontend/src/components/Live2DCharacter.vue
git commit -m "feat(live2d): parameterize model path, default to rem"
```

---

## Task 3: `Live2DCharacter.vue` — 说话动画（talk 动作 + 伪口型）

**Files:**
- Modify: `frontend/src/components/Live2DCharacter.vue`

- [ ] **Step 1: 新增伪口型的模块级变量**

在 `let ticker = null` 一行下方，追加：

```js
let lipSyncRAF = null
let lipSyncStart = 0
let idlePaused = false
```

- [ ] **Step 2: idle 调度支持暂停**

将 `scheduleIdle()` 函数体开头改为在暂停时跳过一轮但保持循环：

找到：
```js
function scheduleIdle() {
  if (idleTimer) clearTimeout(idleTimer)
  idleTimer = setTimeout(() => {
    if (!model) return
```
改为：
```js
function scheduleIdle() {
  if (idleTimer) clearTimeout(idleTimer)
  idleTimer = setTimeout(() => {
    if (!model) return
    if (idlePaused) { scheduleIdle(); return }
```

- [ ] **Step 3: 新增 playTalkMotion / startLipSync / stopLipSync**

在 `playRandomMotion()` 函数之后、`// ============ CLEANUP ============` 之前插入：

```js
/** 说话：从 talk 动作组随机播放一个动作 */
function playTalkMotion() {
  if (!model) return
  try {
    const defs = model.internalModel.motionManager.definitions
    const talk = defs && defs.talk
    if (talk && talk.length > 0) {
      model.motion('talk', Math.floor(Math.random() * talk.length))
    }
  } catch (e) { /* ignore */ }
}

/** 说话：启动伪口型（正弦+随机抖动写入 PARAM_MOUTH_OPEN_Y） */
function startLipSync() {
  if (!model) return
  idlePaused = true
  lipSyncStart = performance.now()
  const loop = () => {
    if (!model) return
    const t = (performance.now() - lipSyncStart) / 1000
    // 基础正弦开合 + 小幅随机抖动，范围约 0~0.9
    const base = 0.45 + 0.45 * Math.abs(Math.sin(t * 8))
    const jitter = (Math.random() - 0.5) * 0.1
    const v = Math.max(0, Math.min(1, base + jitter))
    try { model.internalModel.coreModel.setParameterValueById('PARAM_MOUTH_OPEN_Y', v) } catch (e) { /* ignore */ }
    lipSyncRAF = requestAnimationFrame(loop)
  }
  lipSyncRAF = requestAnimationFrame(loop)
}

/** 说话结束：停止伪口型并闭嘴，恢复 idle */
function stopLipSync() {
  if (lipSyncRAF) { cancelAnimationFrame(lipSyncRAF); lipSyncRAF = null }
  idlePaused = false
  if (!model) return
  try { model.internalModel.coreModel.setParameterValueById('PARAM_MOUTH_OPEN_Y', 0) } catch (e) { /* ignore */ }
}
```

- [ ] **Step 4: cleanup 中取消口型 rAF**

在 `cleanup()` 函数开头（`if (idleTimer) clearTimeout(idleTimer)` 之后）追加：

```js
  if (lipSyncRAF) { cancelAnimationFrame(lipSyncRAF); lipSyncRAF = null }
```

- [ ] **Step 5: defineExpose 增补**

将现有：
```js
defineExpose({
  isReady,
  currentExpression,
  setExpression,
  setMouthOpen,
  playRandomMotion,
})
```
改为：
```js
defineExpose({
  isReady,
  currentExpression,
  setExpression,
  setMouthOpen,
  playRandomMotion,
  playTalkMotion,
  startLipSync,
  stopLipSync,
})
```

- [ ] **Step 6: Commit**

```bash
git add frontend/src/components/Live2DCharacter.vue
git commit -m "feat(live2d): add talk motion + fake lip-sync API"
```

---

## Task 4: `Live2DCharacter.vue` — 分区点击触发动作

**Files:**
- Modify: `frontend/src/components/Live2DCharacter.vue`

- [ ] **Step 1: 新增命中矩形常量（rem hit_areas_custom）**

在 props 定义之后、`const canvasContainer` 附近，加入 rem 的命中区域（来自 rem `model.json`）：

```js
// rem model.json 的 hit_areas_custom（归一化坐标）
const HIT_HEAD = { x: [-0.3, 1.12], y: [0.26, 0.56] }
const HIT_BODY = { x: [-0.41, 0.58], y: [-1.12, 0.46] }
```

- [ ] **Step 2: 新增分区点击处理函数**

在 `playTalkMotion()` 之前插入一个把画布像素坐标换算为模型归一化坐标并判定命中的函数：

```js
/** 画布点击 → 命中 head / body → 播对应动作组 */
function handleCanvasTap(ev) {
  if (!model || !app) return
  const rect = app.view.getBoundingClientRect()
  const px = ev.clientX - rect.left
  const py = ev.clientY - rect.top
  // 换算为以模型锚点为原点的归一化坐标（右为 +x，上为 +y）
  const nx = (px - model.x) / (model.width || 1) * 2
  const ny = -(py - model.y) / (model.height || 1) * 2
  const inArea = (a) => nx >= a.x[0] && nx <= a.x[1] && ny >= a.y[0] && ny <= a.y[1]

  let group = null
  if (inArea(HIT_HEAD)) group = 'flick_head'
  else if (inArea(HIT_BODY)) group = 'tap_body'

  try {
    const defs = model.internalModel.motionManager.definitions
    if (group && defs && defs[group] && defs[group].length > 0) {
      model.motion(group, Math.floor(Math.random() * defs[group].length))
    } else {
      playRandomMotion()  // 未命中特定区域 → 随机非 idle 动作
    }
  } catch (e) { /* ignore */ }
}
```

- [ ] **Step 3: init 成功后绑定点击监听**

在 `init()` 中 `isReady.value = true` 之前插入：

```js
    app.view.style.cursor = 'pointer'
    app.view.addEventListener('pointerdown', handleCanvasTap)
```

- [ ] **Step 4: cleanup 中解绑监听**

在 `cleanup()` 中，`if (app) {` 之前插入：

```js
  if (app && app.view) app.view.removeEventListener('pointerdown', handleCanvasTap)
```

- [ ] **Step 5: 手动验证**

`cd frontend && npm run dev` → 展开语音助手（Task 5 完成后）或临时挂载 → 点击 rem 头部与身体。
Expected: 点头部播 flick_head 类动作，点身体播 tap_body 类动作，其它区域播随机动作；控制台无报错。

- [ ] **Step 6: Commit**

```bash
git add frontend/src/components/Live2DCharacter.vue
git commit -m "feat(live2d): add hit-area tap-to-motion"
```

---

## Task 5: `VoiceAssistant.vue` — 嵌入 Live2DCharacter + emoji 兜底

**Files:**
- Modify: `frontend/src/components/VoiceAssistant.vue`

- [ ] **Step 1: 导入组件与就绪状态引用**

在 `<script setup>` 的 import 区（`import { useRouter } from 'vue-router'` 下方）加入：

```js
import Live2DCharacter from './Live2DCharacter.vue'
```

在 `const convoRef = ref(null)` 附近加入：

```js
const l2dRef = ref(null)
const l2dReady = ref(false)
```

- [ ] **Step 2: 轮询子组件 isReady 设置 l2dReady**

在 `function open()` 中，`expanded.value = true` 之后加入一次性轮询（子组件挂载后异步加载模型）：

```js
  // 面板展开后，等待 Live2D 模型就绪；失败则保持 emoji 兜底
  let tries = 0
  const poll = setInterval(() => {
    tries++
    if (l2dRef.value?.isReady) { l2dReady.value = true; clearInterval(poll) }
    else if (tries > 40) { clearInterval(poll) }  // ~8s 超时后放弃，保留 emoji
  }, 200)
```

- [ ] **Step 3: 模板中嵌入 Live2D，emoji 作为兜底**

将模板中 `va-canvas-wrap` 区块：

```html
      <div class="va-canvas-wrap">
        <div class="va-avatar">
          <span class="va-avatar-emoji">{{ avatarEmoji }}</span>
          <span v-if="isSpeaking" class="va-speaking-dot"></span>
        </div>
        <div class="va-expression-label">{{ expressionLabel }}</div>
      </div>
```

替换为：

```html
      <div class="va-canvas-wrap">
        <Live2DCharacter v-show="l2dReady" ref="l2dRef" class="va-l2d" />
        <div v-show="!l2dReady" class="va-avatar">
          <span class="va-avatar-emoji">{{ avatarEmoji }}</span>
          <span v-if="isSpeaking" class="va-speaking-dot"></span>
        </div>
        <div class="va-expression-label">{{ expressionLabel }}</div>
      </div>
```

- [ ] **Step 4: 新增 .va-l2d 样式**

在 `<style scoped>` 的 `.va-canvas-wrap { ... }` 规则之后加入：

```css
.va-l2d {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
}
```

- [ ] **Step 5: 手动验证渲染与兜底**

`cd frontend && npm run dev` → 登录 → 右下角点开语音助手。
Expected:
- 正常：面板内显示 rem 形象并循环 idle 动作。
- 兜底：将 `frontend/public/live2d/rem/model.json` 临时改名后刷新，面板应回退显示 emoji（🐱）且不白屏；验证后改回。

- [ ] **Step 6: Commit**

```bash
git add frontend/src/components/VoiceAssistant.vue
git commit -m "feat(voice): embed rem Live2D with emoji fallback"
```

---

## Task 6: `VoiceAssistant.vue` — 说话/监听/表情状态联动

**Files:**
- Modify: `frontend/src/components/VoiceAssistant.vue`

- [ ] **Step 1: speak() 起止联动 talk 动作 + 伪口型**

在 `speak(text)` 内，找到 `utterance.onstart` 回调：

```js
  utterance.onstart = () => {
    isSpeaking = true
    setExpression('speaking')
  }
```
改为：
```js
  utterance.onstart = () => {
    isSpeaking = true
    setExpression('speaking')
    l2dRef.value?.playTalkMotion?.()
    l2dRef.value?.startLipSync?.()
  }
```

找到 `utterance.onend` 与 `utterance.onerror` 回调（两处都有 `isSpeaking = false; setExpression('neutral')`），各自在其后追加停止口型：

```js
  utterance.onend = () => {
    isSpeaking = false
    setExpression('neutral')
    l2dRef.value?.stopLipSync?.()
  }

  utterance.onerror = () => {
    isSpeaking = false
    setExpression('neutral')
    l2dRef.value?.stopLipSync?.()
  }
```

- [ ] **Step 2: 把本地 setExpression 桥接到 Live2D**

当前 `setExpression(expr)` 只更新文字标签。改为同时驱动 Live2D（保留文字标签逻辑）：

找到：
```js
function setExpression(expr) {
  expressionLabel.value = EXPR_LABELS[expr] || ''
}
```
改为：
```js
function setExpression(expr) {
  expressionLabel.value = EXPR_LABELS[expr] || ''
  l2dRef.value?.setExpression?.(expr)
}
```

> 说明：`setExpression` 已在多处（监听 surprised、开心 happy、思考 thinking、说话 speaking、neutral）被调用，桥接后这些状态会自动同步到 Live2D，无需改各调用点。

- [ ] **Step 3: close() 时停止口型**

在 `function close()` 内，`if (synth) synth.cancel()` 之后追加：

```js
  l2dRef.value?.stopLipSync?.()
```

- [ ] **Step 4: 手动验证联动**

`cd frontend && npm run dev` → 打开语音助手：
Expected:
- 发送文字/语音得到回复并朗读时：rem 播 talk 动作、嘴巴开合；朗读结束嘴巴闭合、恢复 idle。
- 点麦克风开始监听：表情变化（surprised，视 rem 参数支持）。
- 说"你好"/"谢谢"：happy 表情；无法识别意图：thinking 表情。
- 关闭面板：口型停止、无残留动画。

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/VoiceAssistant.vue
git commit -m "feat(voice): wire speaking/listening/expression to Live2D"
```

---

## Self-Review

**1. Spec coverage：**
- 4.1 资源放置 → Task 1 ✅
- 4.2(1) modelPath 参数化 → Task 2 ✅
- 4.2(2) 缩放定位 → 复用现有逻辑（Task 2 Step 3 验证时按需微调，spec 允许）✅
- 4.2(3) 说话动画 API → Task 3 ✅
- 4.2(4) 分区点击 → Task 4 ✅
- 4.2(5) 表情参数 → 复用现有 setExpression（Task 6 桥接）✅
- 4.2(6) defineExpose 增补 → Task 3 Step 5 ✅
- 4.3 VoiceAssistant 接线（渲染+兜底/状态联动/emoji 保留）→ Task 5、Task 6 ✅
- 5 风险（表情参数缺失静默、懒加载、失败回退）→ 可选链 + v-show 兜底 + open() 懒挂载已覆盖 ✅

**2. Placeholder scan：** 无 TBD/TODO；每个改动步骤均给出完整代码与替换锚点。✅

**3. Type consistency：** 暴露方法名 `playTalkMotion / startLipSync / stopLipSync / setExpression` 在 Task 3 定义、Task 6 调用一致；`l2dRef / l2dReady` 在 Task 5 定义、Task 5/6 使用一致；参数名 `PARAM_MOUTH_OPEN_Y` 与现有组件一致。✅

**说明：** 缩放/定位（spec 4.2(2)）依赖实际渲染效果微调，属正常调参而非独立任务，已并入 Task 2 的验证步骤，符合 spec "按实际渲染结果调参" 的表述。
