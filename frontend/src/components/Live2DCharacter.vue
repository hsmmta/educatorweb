<template>
  <div ref="canvasContainer" class="l2d-canvas"></div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import * as PIXI from 'pixi.js'
import { Live2DModel } from 'pixi-live2d-display'
import { Cubism2ModelSettings } from 'pixi-live2d-display/cubism2'

const canvasContainer = ref(null)

// Exposed reactive state
const isReady = ref(false)
const currentExpression = ref('neutral')

let app = null
let model = null
let resizeObserver = null
let idleTimer = null
let ticker = null

// ============ SCRIPT LOADER ============
function loadScript(src) {
  return new Promise((resolve, reject) => {
    const existing = document.querySelector(`script[src="${src}"]`)
    if (existing) return resolve()
    const s = document.createElement('script')
    s.src = src
    s.onload = resolve
    s.onerror = () => reject(new Error(`Failed to load ${src}`))
    document.head.appendChild(s)
  })
}

// ============ INIT ============
async function init() {
  const container = canvasContainer.value
  if (!container) return

  try {
    // 1. Load Cubism 2 runtime (must be loaded before pixi-live2d-display uses it)
    await loadScript('/live2d/live2d.core.js')

    // 2. Register Cubism 2 model format
    Live2DModel.register(Cubism2ModelSettings)

    // 3. Create PixiJS application
    const w = container.clientWidth || 320
    const h = container.clientHeight || 280

    app = new PIXI.Application({
      width: w,
      height: h,
      backgroundAlpha: 0,
      antialias: true,
      resolution: Math.min(window.devicePixelRatio, 2),
      autoDensity: true,
    })
    container.appendChild(app.view)

    // 4. Load Live2D model
    model = await Live2DModel.from('/live2d/hijiki/hijiki.model.json')

    // 5. Position and scale
    model.anchor.set(0.5, 0.35)
    model.x = w / 2
    model.y = h / 2 + h * 0.08
    model.scale.set(Math.min(w, h) / 600)

    app.stage.addChild(model)

    // 6. Start idle motion loop
    startIdleMotions()

    // 7. Handle resize
    resizeObserver = new ResizeObserver(() => {
      if (!app || !container) return
      const nw = container.clientWidth
      const nh = container.clientHeight
      app.renderer.resize(nw, nh)
      if (model) {
        model.x = nw / 2
        model.y = nh / 2 + nh * 0.08
        model.scale.set(Math.min(nw, nh) / 600)
      }
    })
    resizeObserver.observe(container)

    isReady.value = true
  } catch (err) {
    console.warn('Live2D init failed:', err)
  }
}

// ============ IDLE MOTIONS ============
function startIdleMotions() {
  scheduleIdle()
}

function scheduleIdle() {
  if (idleTimer) clearTimeout(idleTimer)
  idleTimer = setTimeout(() => {
    if (!model) return
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

// ============ CLEANUP ============
function cleanup() {
  if (idleTimer) clearTimeout(idleTimer)
  if (resizeObserver) resizeObserver.disconnect()
  if (model) {
    model.destroy()
    model = null
  }
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
})
</script>

<style scoped>
.l2d-canvas {
  width: 100%;
  height: 100%;
}
.l2d-canvas canvas {
  display: block;
}
</style>
