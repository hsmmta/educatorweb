<template>
  <div ref="canvasContainer" class="l2d-canvas"></div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import * as PIXI from 'pixi.js'
// 维护中的 fork（兼容 PIXI7，修复了 beta 版 Cubism2 首帧裁剪蒙版竞态）。
// rem 是 Cubism2 模型，用 /cubism2 子入口：更小、只依赖已加载的 window.Live2D 运行时。
import { Live2DModel } from 'pixi-live2d-display-lipsyncpatch/cubism2'

const props = defineProps({
  modelPath: { type: String, default: '/live2d/rem/model.json' }
})

// rem model.json 的 hit_areas_custom（归一化坐标）
const HIT_HEAD = { x: [-0.3, 1.12], y: [0.26, 0.56] }
const HIT_BODY = { x: [-0.41, 0.58], y: [-1.12, 0.46] }

// 固定内部渲染尺寸：canvas 永远按这个尺寸创建、永不 resize，
// 使 Cubism2 裁剪蒙版 framebuffer 只按正确尺寸分配一次（不受容器尺寸/时机影响）。
// 显示层用 CSS object-fit:contain 自适应容器。
const RENDER_W = 380
const RENDER_H = 280

const canvasContainer = ref(null)

// Exposed reactive state
const isReady = ref(false)
const currentExpression = ref('neutral')

let app = null
let model = null
let idleTimer = null
let lipSyncRAF = null
let lipSyncStart = 0
let idlePaused = false
let readyGate = null

// ============ INIT ============
async function init() {
  const container = canvasContainer.value
  if (!container) return

  try {
    // 1. Register PIXI ticker so the model auto-updates (motion/physics/blink)
    Live2DModel.registerTicker(PIXI.Ticker)

    // 用固定内部尺寸创建 canvas（不读容器尺寸）。根因：容器挂载后 clientWidth 长时间为 0，
    // 之前 canvas 被按兜底/错误尺寸创建、随后 resize 到真实尺寸，导致 Cubism2 裁剪蒙版
    // framebuffer 错位、模型被裁掉一半（只剩手/没皮肤）。固定尺寸 + 永不 resize 可根治。
    app = new PIXI.Application({
      width: RENDER_W,
      height: RENDER_H,
      backgroundAlpha: 0,
      antialias: true,
      resolution: Math.min(window.devicePixelRatio, 2),
      autoDensity: false,       // 由 CSS(width/height/object-fit) 控制显示，backing store 固定
    })
    container.appendChild(app.view)
    // canvas 是 JS 动态插入的，scoped CSS 不作用于它 → 用内联样式让它填满容器并按比例缩放，
    // 否则关掉 autoDensity 后 canvas 会按 backing 原始尺寸(570×420)显示、溢出小窗。
    Object.assign(app.view.style, {
      width: '100%',
      height: '100%',
      objectFit: 'contain',
      display: 'block',
    })

    // 4. Load Live2D model — then wait until it is truly ready（贴图绑定、尺寸就绪）
    model = await Live2DModel.from(props.modelPath)
    app.stage.addChild(model)
    layoutModel()
    startIdleMotions()

    // 不再监听容器 resize：canvas 尺寸固定，显示自适应交给 CSS，避免 resize 破坏裁剪蒙版。

    app.view.style.cursor = 'pointer'
    app.view.addEventListener('pointerdown', handleCanvasTap)

    // 就绪门控：等所有贴图真正 GPU 有效后才对外宣布 ready（不是数帧，是查条件）
    await waitForModelStable()
    layoutModel()              // 尺寸此时已可靠，重算一次保证精确
    isReady.value = true
  } catch (err) {
    console.warn('Live2D init failed:', err)
  }
}

/** 取出模型加载的贴图数组（fork 库把它挂在 model.textures） */
function getModelTextures() {
  if (!model) return []
  if (Array.isArray(model.textures)) return model.textures
  const im = model.internalModel
  if (im && Array.isArray(im.textures)) return im.textures
  return []
}

/** 等待模型稳定：尺寸有效 + 所有贴图 baseTexture.valid（条件轮询，非数帧） */
async function waitForModelStable() {
  if (!model || !app) return
  const im = model.internalModel
  let tries = 0
  // 最多约 1s（60 rAF）：等尺寸就绪且所有贴图 GPU 有效
  while (tries < 60) {
    const mw = (im && im.originalWidth) || 0
    const texs = getModelTextures()
    const allValid = texs.length > 0 && texs.every(t => t && t.baseTexture && t.baseTexture.valid)
    if (mw > 0 && allValid) break
    await new Promise(r => { readyGate = requestAnimationFrame(r) })
    tries++
  }
  if (tries >= 60) console.warn('[L2D] waitForModelStable timed out — textures may be incomplete')
  // 贴图有效后，再给 Cubism 跑完一个 update→draw 周期
  await new Promise(r => { readyGate = requestAnimationFrame(r) })
  await new Promise(r => { readyGate = requestAnimationFrame(r) })
  readyGate = null
}

// ============ LAYOUT (fit model to fixed render surface) ============
function layoutModel() {
  if (!app || !model) return
  const w = RENDER_W
  const h = RENDER_H
  const im = model.internalModel
  const mw = (im && im.originalWidth) || 0
  const mh = (im && im.originalHeight) || 0
  // 尺寸未就绪（如竞态时 originalWidth 仍为 0）→ 跳过；waitForModelStable 后会再算一次
  if (mw <= 0 || mh <= 0) return
  const scale = Math.min(w / mw, h / mh) * 0.92
  model.scale.set(scale)
  model.anchor.set(0.5, 0.5)
  model.x = w / 2
  model.y = h / 2
}

// ============ IDLE MOTIONS ============
function startIdleMotions() {
  scheduleIdle()
}

function scheduleIdle() {
  if (idleTimer) clearTimeout(idleTimer)
  idleTimer = setTimeout(() => {
    if (!model) return
    if (idlePaused) { scheduleIdle(); return }
    try {
      // Random idle motion
      const motions = model.internalModel.motionManager.definitions
      if (motions && motions.length > 0) {
        // Pick a random motion group
        const groupNames = Object.keys(motions)
        const group = groupNames[Math.floor(Math.random() * groupNames.length)]
        const groupMotions = motions[group]
        if (groupMotions && groupMotions.length > 0) {
          const idx = Math.floor(Math.random() * groupMotions.length)
          model.motion(group, idx)
        }
      }
    } catch (e) { /* ignore */ }
    scheduleIdle()
  }, 4000 + Math.random() * 5000)
}

// ============ PUBLIC API (exposed to parent) ============

/** Set expression: 'neutral' | 'happy' | 'surprised' | 'thinking' */
function setExpression(expr) {
  if (!model) return
  currentExpression.value = expr

  try {
    const cm = model.internalModel.coreModel

    // Map expressions to Live2D parameter values
    // These parameter IDs are common across Cubism2 models
    const paramMouth = 'PARAM_MOUTH_OPEN_Y'
    const paramEye = 'PARAM_EYE_OPEN'

    switch (expr) {
      case 'happy':
        cm.setParameterValueById('PARAM_EYE_SMILE', 0.6)
        cm.setParameterValueById('PARAM_BROW_L_Y', 0.3)
        cm.setParameterValueById('PARAM_BROW_R_Y', 0.3)
        break
      case 'surprised':
        cm.setParameterValueById(paramEye, 1.3)
        cm.setParameterValueById(paramMouth, 0.4)
        cm.setParameterValueById('PARAM_BROW_L_Y', 0.8)
        cm.setParameterValueById('PARAM_BROW_R_Y', 0.8)
        break
      case 'thinking':
        cm.setParameterValueById('PARAM_BROW_L_Y', 0.5)
        cm.setParameterValueById('PARAM_BROW_R_Y', 0.1)
        cm.setParameterValueById('PARAM_EYE_OPEN', 0.7)
        break
      case 'neutral':
      default:
        cm.setParameterValueById('PARAM_EYE_SMILE', 0)
        cm.setParameterValueById(paramEye, 1.0)
        cm.setParameterValueById(paramMouth, 0)
        cm.setParameterValueById('PARAM_BROW_L_Y', 0)
        cm.setParameterValueById('PARAM_BROW_R_Y', 0)
        break
    }
  } catch (e) { /* ignore */ }
}

/** Control mouth openness for lip-sync (0 = closed, 1 = fully open) */
function setMouthOpen(value) {
  if (!model) return
  try {
    model.internalModel.coreModel.setParameterValueById('PARAM_MOUTH_OPEN_Y', value)
  } catch (e) { /* ignore */ }
}

/** Play a random non-idle motion */
function playRandomMotion() {
  if (!model) return
  try {
    const motions = model.internalModel.motionManager.definitions
    if (!motions) return
    // Avoid idle group
    const groups = Object.keys(motions).filter(g => g !== 'idle')
    if (groups.length === 0) return
    const group = groups[Math.floor(Math.random() * groups.length)]
    const groupMotions = motions[group]
    if (groupMotions && groupMotions.length > 0) {
      const idx = Math.floor(Math.random() * groupMotions.length)
      model.motion(group, idx)
    }
  } catch (e) { /* ignore */ }
}

/** 画布点击 → 命中 head / body → 播对应动作组 */
function handleCanvasTap(ev) {
  if (!model || !app) return
  const rect = app.view.getBoundingClientRect()
  const px = ev.clientX - rect.left
  const py = ev.clientY - rect.top
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
      playRandomMotion()
    }
  } catch (e) { /* ignore */ }
}

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

// ============ CLEANUP ============
function cleanup() {
  if (readyGate) { cancelAnimationFrame(readyGate); readyGate = null }
  if (idleTimer) clearTimeout(idleTimer)
  if (lipSyncRAF) { cancelAnimationFrame(lipSyncRAF); lipSyncRAF = null }
  if (model) {
    model.destroy()
    model = null
  }
  if (app && app.view) app.view.removeEventListener('pointerdown', handleCanvasTap)
  if (app) {
    app.destroy(true, { children: true, texture: true })
    app = null
  }
  isReady.value = false
}

onMounted(() => {
  init()
})

onUnmounted(() => {
  cleanup()
})

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
</script>

<style scoped>
.l2d-canvas {
  width: 100%;
  height: 100%;
  overflow: hidden;   /* 双保险：即便 canvas 尺寸异常也不溢出遮挡关闭/输入 */
}
/* 注：canvas 由 PIXI 动态插入，scoped CSS 不生效，实际显示样式在 init() 里内联设置 */
</style>
