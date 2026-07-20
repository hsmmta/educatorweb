<template>
  <div ref="containerRef" class="canvas-container"></div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import * as THREE from 'three'

const containerRef = ref(null)

let scene, camera, renderer, mainGroup
let raycaster, mouse, clock
let animationId

// Interaction state
let isDragging = false
let prevMouse = { x: 0, y: 0 }
let targetRotY = 0
let targetRotX = 0.2
let currentRotY = 0
let currentRotX = 0.2
let hoveredGroup = null
const models = []

const AUTO_ROTATE_SPEED = 0.004

function createPencil() {
  const group = new THREE.Group()

  const bodyGeom = new THREE.CylinderGeometry(0.09, 0.09, 2.2, 6)
  const bodyMat = new THREE.MeshStandardMaterial({ color: 0xf7c948, roughness: 0.35, metalness: 0.05 })
  const body = new THREE.Mesh(bodyGeom, bodyMat)
  group.add(body)

  const stripeColors = [0xe8453c, 0x3b82f6, 0x22c55e]
  stripeColors.forEach((color, i) => {
    const y = -0.5 + i * 0.35
    const stripeGeom = new THREE.TorusGeometry(0.093, 0.012, 8, 6)
    const stripeMat = new THREE.MeshStandardMaterial({ color, roughness: 0.3 })
    const stripe = new THREE.Mesh(stripeGeom, stripeMat)
    stripe.rotation.x = Math.PI / 2
    stripe.position.y = y
    group.add(stripe)
  })

  const woodGeom = new THREE.ConeGeometry(0.09, 0.3, 6)
  const woodMat = new THREE.MeshStandardMaterial({ color: 0xe8c98b, roughness: 0.55 })
  const wood = new THREE.Mesh(woodGeom, woodMat)
  wood.rotation.x = Math.PI
  wood.position.y = -1.25
  group.add(wood)

  const tipGeom = new THREE.ConeGeometry(0.03, 0.1, 6)
  const tipMat = new THREE.MeshStandardMaterial({ color: 0x2a2a2a, roughness: 0.4 })
  const tip = new THREE.Mesh(tipGeom, tipMat)
  tip.rotation.x = Math.PI
  tip.position.y = -1.35
  group.add(tip)

  const ferruleGeom = new THREE.CylinderGeometry(0.094, 0.094, 0.1, 12)
  const ferruleMat = new THREE.MeshStandardMaterial({ color: 0xd4d4d4, roughness: 0.2, metalness: 0.95 })
  const ferrule = new THREE.Mesh(ferruleGeom, ferruleMat)
  ferrule.position.y = 1.14
  group.add(ferrule)

//橡皮
  const eraserGeom = new THREE.CylinderGeometry(0.088, 0.088, 0.16, 8)
  const eraserMat = new THREE.MeshStandardMaterial({ color: 0xf4a0a8, roughness: 0.5 })
  const eraser = new THREE.Mesh(eraserGeom, eraserMat)
  eraser.position.y = 1.27
  group.add(eraser)

  return group
}

function createTriangleRuler() {
  const group = new THREE.Group()

  const longLeg = 1.6
  const shortLeg = longLeg / Math.sqrt(3)

  const shape = new THREE.Shape()
  shape.moveTo(0, 0)
  shape.lineTo(longLeg, 0)
  shape.lineTo(0, shortLeg)
  shape.closePath()

  // Inner cutout
  const holePath = new THREE.Path()
  const inset = 0.15
  const innerLong = longLeg - inset * 2.5
  const innerShort = innerLong / Math.sqrt(3)
  holePath.moveTo(inset, inset)
  holePath.lineTo(longLeg - inset * 2, inset)
  holePath.lineTo(inset, shortLeg - inset * 1.8)
  holePath.closePath()
  shape.holes.push(holePath)

  const extrudeSettings = {
    steps: 1,
    depth: 0.05,
    bevelEnabled: true,
    bevelThickness: 0.012,
    bevelSize: 0.012,
    bevelSegments: 2
  }

  const geom = new THREE.ExtrudeGeometry(shape, extrudeSettings)
  const mat = new THREE.MeshStandardMaterial({
    color: 0x5dd9d3,
    roughness: 0.12,
    metalness: 0.05,
    transparent: true,
    opacity: 0.78
  })

  const mesh = new THREE.Mesh(geom, mat)
  geom.computeBoundingBox()
  const center = new THREE.Vector3()
  geom.boundingBox.getCenter(center)
  mesh.position.set(-center.x, -center.y, -center.z)
  group.add(mesh)

  const tickMat = new THREE.MeshStandardMaterial({ color: 0xffffff, roughness: 0.3, emissive: 0x333333, emissiveIntensity: 0.2 })
  const halfDepth = (geom.boundingBox.max.z - geom.boundingBox.min.z) / 2
  const topZ = halfDepth + 0.002
  for (let i = 0.25; i < longLeg - 0.3; i += 0.25) {
    const isLong = Math.abs(i * 10 % 5) < 0.01
    const tickHeight = isLong ? 0.07 : 0.04
    const tickGeom = new THREE.BoxGeometry(0.012, isLong ? 0.07 : 0.04, 0.003)
    const tick = new THREE.Mesh(tickGeom, tickMat)
    tick.position.set(i, tickHeight / 2, topZ)
    mesh.add(tick)
  }
  return group
}

function createLaptop() {
  const group = new THREE.Group()

  //底座
  const baseGeom = new THREE.BoxGeometry(1.5, 0.04, 0.95)
  const baseMat = new THREE.MeshStandardMaterial({ color: 0xc8c8c8, roughness: 0.25, metalness: 0.85 })
  const base = new THREE.Mesh(baseGeom, baseMat)
  group.add(base)

  //键盘凹陷区域
  const kbWellGeom = new THREE.BoxGeometry(1.15, 0.004, 0.52)
  const kbWellMat = new THREE.MeshStandardMaterial({ color: 0x1e1e1e, roughness: 0.45 })
  const kbWell = new THREE.Mesh(kbWellGeom, kbWellMat)
  kbWell.position.set(0, 0.023, -0.06)
  group.add(kbWell)

  //键盘按键
  const keyMat = new THREE.MeshStandardMaterial({ color: 0x333333, roughness: 0.4 })
  for (let row = 0; row < 4; row++) {
    for (let col = 0; col < 10; col++) {
      const keyGeom = new THREE.BoxGeometry(0.09, 0.003, 0.08)
      const key = new THREE.Mesh(keyGeom, keyMat)
      key.position.set(-0.5 + col * 0.105, 0.026, -0.18 + row * 0.1)
      group.add(key)
    }
  }

  //触摸板
  const trackpadGeom = new THREE.BoxGeometry(0.4, 0.002, 0.22)
  const trackpadMat = new THREE.MeshStandardMaterial({ color: 0x999999, roughness: 0.15, metalness: 0.6 })
  const trackpad = new THREE.Mesh(trackpadGeom, trackpadMat)
  trackpad.position.set(0, 0.022, 0.28)
  group.add(trackpad)

  //屏幕铰链组
  const screenGroup = new THREE.Group()
  screenGroup.position.set(0, 0.02, 0.46)
  screenGroup.rotation.x = -0.5
  group.add(screenGroup)

  //屏幕后盖
  const lidGeom = new THREE.BoxGeometry(1.5, 0.95, 0.03)
  const lidMat = new THREE.MeshStandardMaterial({ color: 0xbebebe, roughness: 0.25, metalness: 0.85 })
  const lid = new THREE.Mesh(lidGeom, lidMat)
  lid.position.y = 0.48
  screenGroup.add(lid)

  // 创建Canvas并绘制文字
  const canvas = document.createElement('canvas')
  canvas.width = 512
  canvas.height = 256
  const ctx = canvas.getContext('2d')

  const gradient = ctx.createLinearGradient(0, 0, 0, canvas.height)
  gradient.addColorStop(0, '#1e2a4a')
  gradient.addColorStop(1, '#0f172a')
  ctx.fillStyle = gradient
  ctx.fillRect(0, 0, canvas.width, canvas.height)

  ctx.fillStyle = 'rgba(255, 255, 255, 0.75)'
  ctx.font = 'bold 56px "PingFang SC", "Microsoft YaHei", "Noto Sans SC", sans-serif'
  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  ctx.shadowColor = 'rgba(255, 255, 255, 0.4)'
  ctx.shadowBlur = 12
  ctx.fillText('智引未来', canvas.width / 2, canvas.height / 2)

  ctx.shadowBlur = 0
  ctx.strokeStyle = '#4f9cf5'
  ctx.lineWidth = 3
  ctx.beginPath()
  ctx.moveTo(canvas.width / 2 - 100, canvas.height / 2 + 50)
  ctx.lineTo(canvas.width / 2 + 100, canvas.height / 2 + 50)
  ctx.stroke()

  const texture = new THREE.CanvasTexture(canvas)
  texture.needsUpdate = true

  const screenGeom = new THREE.PlaneGeometry(1.3, 0.78)
  const screenMat = new THREE.MeshBasicMaterial({ map: texture })
  const screenMesh = new THREE.Mesh(screenGeom, screenMat)
  screenMesh.position.set(0, 0.48, 0.016)
  screenGroup.add(screenMesh)

  //摄像头
  const camGeom = new THREE.SphereGeometry(0.015, 8, 8)
  const camMat = new THREE.MeshStandardMaterial({ color: 0x111111 })
  const cam = new THREE.Mesh(camGeom, camMat)
  cam.position.set(0, 0.96, 0.015)
  screenGroup.add(cam)

  return group
}

function createBook() {
  const group = new THREE.Group()

  const coverGeom = new THREE.BoxGeometry(1.0, 0.1, 1.35)
  const coverMat = new THREE.MeshStandardMaterial({ color: 0x667eea, roughness: 0.25, metalness: 0.03 })
  const cover = new THREE.Mesh(coverGeom, coverMat)
  group.add(cover)

  const pagesGeom = new THREE.BoxGeometry(0.9, 0.07, 1.42)
  const pagesMat = new THREE.MeshStandardMaterial({ color: 0xfafaf5, roughness: 0.55 })
  const pages = new THREE.Mesh(pagesGeom, pagesMat)
  pages.position.y = 0.02
  group.add(pages)

  const topCoverGeom = new THREE.BoxGeometry(1.0, 0.04, 1.35)
  const topCoverMat = new THREE.MeshStandardMaterial({ color: 0x7c8ef0, roughness: 0.25, metalness: 0.03 })
  const topCover = new THREE.Mesh(topCoverGeom, topCoverMat)
  topCover.position.y = 0.055
  group.add(topCover)

  const spineGeom = new THREE.BoxGeometry(0.05, 0.1, 1.35)
  const spineMat = new THREE.MeshStandardMaterial({ color: 0x5568d6, roughness: 0.3 })
  const spine = new THREE.Mesh(spineGeom, spineMat)
  spine.position.x = -0.5
  group.add(spine)

  const lineGeom = new THREE.BoxGeometry(0.88, 0.001, 0.005)
  const lineMat = new THREE.MeshStandardMaterial({ color: 0xcccccc })
  for (let i = 0; i < 8; i++) {
    const line = new THREE.Mesh(lineGeom, lineMat)
    line.position.set(0, -0.025 + i * 0.008, 0.71)
    group.add(line)
  }

  return group
}

function createProtractor() {
  const group = new THREE.Group()

  const outerR = 0.6
  const innerR = 0.42

  const shape = new THREE.Shape()
  shape.moveTo(-outerR, 0)
  shape.absarc(0, 0, outerR, Math.PI, 0, true)
  shape.lineTo(innerR, 0)
  shape.absarc(0, 0, innerR, 0, Math.PI, false)
  shape.closePath()

  const extrudeSettings = {
    steps: 1,
    depth: 0.04,
    bevelEnabled: true,
    bevelThickness: 0.008,
    bevelSize: 0.008,
    bevelSegments: 2
  }

  const geom = new THREE.ExtrudeGeometry(shape, extrudeSettings)
  const mat = new THREE.MeshStandardMaterial({
    color: 0xff9f68,
    roughness: 0.15,
    metalness: 0.05,
    transparent: true,
    opacity: 0.75
  })

  const mesh = new THREE.Mesh(geom, mat)
  geom.computeBoundingBox()
  const center = new THREE.Vector3()
  geom.boundingBox.getCenter(center)
  mesh.position.set(-center.x, -center.y, -center.z)
  group.add(mesh)

  const tickGroup = new THREE.Group()
  const halfDepth = (geom.boundingBox.max.z - geom.boundingBox.min.z) / 2
  const topZ = halfDepth + 0.002
  for (let deg = 0; deg <= 180; deg += 10) {
    const rad = THREE.MathUtils.degToRad(deg)
    const isLong = deg % 30 === 0
    const r1 = isLong ? innerR : innerR + 0.03
    const r2 = outerR
    const startX = Math.cos(rad) * r1
    const startY = Math.sin(rad) * r1
    const endX = Math.cos(rad) * r2
    const endY = Math.sin(rad) * r2

    const points = [
      new THREE.Vector3(startX, startY, topZ),
      new THREE.Vector3(endX, endY, topZ)
    ]
    const tickGeom = new THREE.BufferGeometry().setFromPoints(points)
    const tickLine = new THREE.Line(tickGeom, new THREE.LineBasicMaterial({
      color: isLong ? 0x444444 : 0x888888,
      transparent: true,
      opacity: 0.6
    }))
    tickGroup.add(tickLine)
  }
  mesh.add(tickGroup)

  const chPoints = [
    new THREE.Vector3(-0.1, 0, topZ),
    new THREE.Vector3(0.1, 0, topZ),
    new THREE.Vector3(0, -0.1, topZ),
    new THREE.Vector3(0, 0.1, topZ)
  ]
  const chGeom1 = new THREE.BufferGeometry().setFromPoints([chPoints[0], chPoints[1]])
  const chGeom2 = new THREE.BufferGeometry().setFromPoints([chPoints[2], chPoints[3]])
  const chMat = new THREE.LineBasicMaterial({ color: 0x666666, transparent: true, opacity: 0.5 })
  mesh.add(new THREE.Line(chGeom1, chMat))
  mesh.add(new THREE.Line(chGeom2, chMat))

  return group
}

function createParticles() {
  const count = 70
  const positions = new Float32Array(count * 3)
  const sizes = new Float32Array(count)
  const radius = 5.0

  for (let i = 0; i < count; i++) {
    const theta = Math.random() * Math.PI * 2
    const phi = Math.acos(2 * Math.random() - 1)
    const r = radius * (0.35 + Math.random() * 0.65)
    positions[i * 3] = r * Math.sin(phi) * Math.cos(theta)
    positions[i * 3 + 1] = r * Math.sin(phi) * Math.sin(theta)
    positions[i * 3 + 2] = r * Math.cos(phi)
    sizes[i] = Math.random() * 0.025 + 0.01
  }

  const geom = new THREE.BufferGeometry()
  geom.setAttribute('position', new THREE.BufferAttribute(positions, 3))
  geom.setAttribute('size', new THREE.BufferAttribute(sizes, 1))

  const mat = new THREE.PointsMaterial({
    color: 0x667eea,
    size: 0.02,
    transparent: true,
    opacity: 0.45,
    blending: THREE.AdditiveBlending,
    depthWrite: false
  })

  return new THREE.Points(geom, mat)
}


function initScene() {
  const container = containerRef.value
  if (!container) return

  const width = container.clientWidth || 1
  const height = container.clientHeight || 1

  scene = new THREE.Scene()
  scene.background = new THREE.Color(0xeef2ff)
  scene.fog = new THREE.Fog(0xeef2ff, 8, 22)

  camera = new THREE.PerspectiveCamera(48, width / height, 0.1, 50)
  camera.position.set(0, 0.2, 7.5)
  camera.lookAt(0, 0, 0)

  renderer = new THREE.WebGLRenderer({ antialias: true, alpha: false })
  renderer.setSize(width, height)
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
  renderer.shadowMap.enabled = true
  renderer.shadowMap.type = THREE.PCFSoftShadowMap
  renderer.toneMapping = THREE.ACESFilmicToneMapping
  renderer.toneMappingExposure = 1.15
  container.appendChild(renderer.domElement)

  raycaster = new THREE.Raycaster()
  raycaster.far = 12
  mouse = new THREE.Vector2()
  clock = new THREE.Clock()

  mainGroup = new THREE.Group()
  scene.add(mainGroup)

  setupLights()
}

function setupLights() {
  const ambient = new THREE.AmbientLight(0xffffff, 0.55)
  scene.add(ambient)

  const keyLight = new THREE.DirectionalLight(0xffffff, 0.75)
  keyLight.position.set(5, 7, 5)
  keyLight.castShadow = true
  keyLight.shadow.mapSize.width = 1024
  keyLight.shadow.mapSize.height = 1024
  keyLight.shadow.camera.near = 0.3
  keyLight.shadow.camera.far = 25
  keyLight.shadow.camera.left = -7
  keyLight.shadow.camera.right = 7
  keyLight.shadow.camera.top = 7
  keyLight.shadow.camera.bottom = -7
  keyLight.shadow.bias = -0.00015
  keyLight.shadow.normalBias = 0.01
  scene.add(keyLight)

  const purpleLight = new THREE.PointLight(0x667eea, 0.5, 10)
  purpleLight.position.set(-3, 2.5, 3)
  scene.add(purpleLight)

  const tealLight = new THREE.PointLight(0x4ecdc4, 0.35, 8)
  tealLight.position.set(3, -1, 2)
  scene.add(tealLight)

  const warmLight = new THREE.PointLight(0xffb088, 0.3, 6)
  warmLight.position.set(0, 2, -3)
  scene.add(warmLight)

  const fillLight = new THREE.DirectionalLight(0x8899cc, 0.25)
  fillLight.position.set(-2, 1, -2)
  scene.add(fillLight)
}

// ==================== SCENE ASSEMBLY ====================

function createModels() {
  // Pencil — far upper left
  const pencil = createPencil()
  pencil.position.set(-2.2, 1.3, 0.8)
  pencil.rotation.z = 0.4
  pencil.rotation.x = -0.12
  pencil.traverse(child => {
    if (child.isMesh) {
      child.castShadow = true
      child.receiveShadow = true
    }
  })
  mainGroup.add(pencil)
  models.push({ group: pencil, baseY: 1.3, floatSpeed: 1.1, floatAmp: 0.18, phase: 0 })

  // Triangle ruler — far upper right
  const ruler = createTriangleRuler()
  ruler.position.set(2.0, 1.1, -0.6)
  ruler.rotation.x = -0.25
  ruler.rotation.y = -0.2
  ruler.rotation.z = 0.3
  ruler.traverse(child => {
    if (child.isMesh) {
      child.castShadow = true
      child.receiveShadow = true
    }
  })
  mainGroup.add(ruler)
  models.push({ group: ruler, baseY: 1.1, floatSpeed: 0.85, floatAmp: 0.14, phase: 1.8 })

  const laptop = createLaptop()
  laptop.position.set(0.1, -0.4, 0.4)
  laptop.rotation.y = -0.08
  laptop.traverse(child => {
    if (child.isMesh) {
      child.castShadow = true
      child.receiveShadow = true
    }
  })
  mainGroup.add(laptop)
  models.push({ group: laptop, baseY: -0.4, floatSpeed: 0.7, floatAmp: 0.1, phase: 3.2 })

  const book = createBook()
  book.position.set(-1.8, -1.3, -0.7)
  book.rotation.y = 0.35
  book.rotation.x = 0.15
  book.traverse(child => {
    if (child.isMesh) {
      child.castShadow = true
      child.receiveShadow = true
    }
  })
  mainGroup.add(book)
  models.push({ group: book, baseY: -1.3, floatSpeed: 0.95, floatAmp: 0.1, phase: 2.4 })

  const protractor = createProtractor()
  protractor.position.set(1.9, -1.0, -0.3)
  protractor.rotation.x = -0.4
  protractor.rotation.y = -0.3
  protractor.rotation.z = -0.1
  protractor.traverse(child => {
    if (child.isMesh) {
      child.castShadow = true
      child.receiveShadow = true
    }
  })
  mainGroup.add(protractor)
  models.push({ group: protractor, baseY: -1.0, floatSpeed: 1.05, floatAmp: 0.12, phase: 4.5 })

  const particles = createParticles()
  mainGroup.add(particles)
}


function getEventPos(event) {
  if (event.touches && event.touches.length > 0) {
    return { x: event.touches[0].clientX, y: event.touches[0].clientY }
  }
  if (event.changedTouches && event.changedTouches.length > 0) {
    return { x: event.changedTouches[0].clientX, y: event.changedTouches[0].clientY }
  }
  return { x: event.clientX, y: event.clientY }
}

function onPointerDown(event) {
  event.preventDefault()
  isDragging = true
  const pos = getEventPos(event)
  prevMouse.x = pos.x
  prevMouse.y = pos.y
  if (containerRef.value) {
    containerRef.value.style.cursor = 'grabbing'
  }
}

function onPointerMove(event) {
  const pos = getEventPos(event)

  const container = containerRef.value
  if (container) {
    const rect = container.getBoundingClientRect()
    mouse.x = ((pos.x - rect.left) / rect.width) * 2 - 1
    mouse.y = -((pos.y - rect.top) / rect.height) * 2 + 1
  }

  if (isDragging) {
    const deltaX = pos.x - prevMouse.x
    const deltaY = pos.y - prevMouse.y
    targetRotY += deltaX * 0.007
    targetRotX += deltaY * 0.007
    targetRotX = Math.max(-Math.PI / 3.5, Math.min(Math.PI / 3.5, targetRotX))
    prevMouse.x = pos.x
    prevMouse.y = pos.y
  }
}

function onPointerUp() {
  isDragging = false
  if (containerRef.value && !hoveredGroup) {
    containerRef.value.style.cursor = 'grab'
  }
}

function onWheel(event) {
  event.preventDefault()
  camera.position.z += event.deltaY * 0.004
  camera.position.z = Math.max(4, Math.min(13, camera.position.z))
}

function onResize() {
  const container = containerRef.value
  if (!container || !renderer) return
  const width = container.clientWidth
  const height = container.clientHeight
  if (width === 0 || height === 0) return
  camera.aspect = width / height
  camera.updateProjectionMatrix()
  renderer.setSize(width, height)
}


function animate() {
  animationId = requestAnimationFrame(animate)

  const time = clock.getElapsedTime()

  // Smooth rotation lerp
  currentRotY += (targetRotY - currentRotY) * 0.06
  currentRotX += (targetRotX - currentRotX) * 0.06

  // Auto-rotate when idle
  if (!isDragging) {
    targetRotY += AUTO_ROTATE_SPEED
  }

  mainGroup.rotation.y = currentRotY
  mainGroup.rotation.x = currentRotX

  models.forEach(m => {
    const offset = Math.sin(time * m.floatSpeed + m.phase) * m.floatAmp
    m.group.position.y = m.baseY + offset
  })

  // Subtle particle rotation
  const pts = mainGroup.children.find(c => c.isPoints)
  if (pts) {
    pts.rotation.y += 0.0004
    pts.rotation.x += 0.00025
  }

  raycaster.setFromCamera(mouse, camera)
  const allMeshes = []
  mainGroup.children.forEach(child => {
    if (child.isGroup) {
      child.traverse(c => { if (c.isMesh) allMeshes.push(c) })
    }
  })
  const intersects = raycaster.intersectObjects(allMeshes, false)

  let newHovered = null
  if (intersects.length > 0) {
    let obj = intersects[0].object
    while (obj && obj.parent !== mainGroup) {
      obj = obj.parent
    }
    if (obj && obj.parent === mainGroup && obj.isGroup) {
      newHovered = obj
    }
  }

  if (hoveredGroup !== newHovered) {
    if (hoveredGroup) {
      hoveredGroup.scale.lerp(new THREE.Vector3(1, 1, 1), 0.3)
    }
    if (newHovered) {
      newHovered.scale.lerp(new THREE.Vector3(1.12, 1.12, 1.12), 0.3)
    }
    hoveredGroup = newHovered
    if (containerRef.value && !isDragging) {
      containerRef.value.style.cursor = newHovered ? 'pointer' : 'grab'
    }
  }

  renderer.render(scene, camera)
}


function cleanup() {
  if (animationId) {
    cancelAnimationFrame(animationId)
  }
  scene.traverse(child => {
    if (child.geometry) child.geometry.dispose()
    if (child.material) {
      if (Array.isArray(child.material)) {
        child.material.forEach(m => {
          if (m.map) m.map.dispose()
          m.dispose()
        })
      } else {
        if (child.material.map) child.material.map.dispose()
        child.material.dispose()
      }
    }
  })
  if (renderer) {
    renderer.dispose()
    const canvas = renderer.domElement
    if (canvas.parentElement) {
      canvas.parentElement.removeChild(canvas)
    }
  }
}


onMounted(() => {
  initScene()
  createModels()
  animate()

  requestAnimationFrame(() => {
    requestAnimationFrame(onResize)
  })

  const container = containerRef.value
  if (container) {
    container.addEventListener('mousedown', onPointerDown)
    container.addEventListener('mousemove', onPointerMove)
    container.addEventListener('mouseup', onPointerUp)
    container.addEventListener('mouseleave', onPointerUp)
    container.addEventListener('wheel', onWheel, { passive: false })
    container.addEventListener('touchstart', onPointerDown, { passive: false })
    container.addEventListener('touchmove', onPointerMove, { passive: false })
    container.addEventListener('touchend', onPointerUp)
  }
  window.addEventListener('resize', onResize)
})

onUnmounted(() => {
  const container = containerRef.value
  if (container) {
    container.removeEventListener('mousedown', onPointerDown)
    container.removeEventListener('mousemove', onPointerMove)
    container.removeEventListener('mouseup', onPointerUp)
    container.removeEventListener('mouseleave', onPointerUp)
    container.removeEventListener('wheel', onWheel)
    container.removeEventListener('touchstart', onPointerDown)
    container.removeEventListener('touchmove', onPointerMove)
    container.removeEventListener('touchend', onPointerUp)
  }
  window.removeEventListener('resize', onResize)
  cleanup()
})
</script>

<style scoped>
.canvas-container {
  width: 100%;
  height: 100%;
  min-height: 100vh;
  cursor: grab;
  user-select: none;
  -webkit-user-select: none;
}
.canvas-container canvas {
  display: block;
}
</style>