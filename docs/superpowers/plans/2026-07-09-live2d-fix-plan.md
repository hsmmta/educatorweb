# Live2D PIXI6 降级 + 资源跳转修复

> **目标:** 将 pixi.js 从 7.4.3 降级到 6.5.10,搭配 pixi-live2d-display@0.4.0 稳定版,消除首帧裁剪蒙版竞态(10/10 完整渲染)。同时修复 ResourcePush.vue 的资源"学习"跳转不再指向旧版 /learning 页面。

## 子代理任务(共 4 个)

### Task 1: 降级 PIXI 依赖

**操作:**
- 卸载现有三个包: `pixi-live2d-display-lipsyncpatch`, `pixi-live2d-display`(如果还残留), `pixi.js`
- 安装 `pixi.js@6.5.10` + `pixi-live2d-display@0.4.0`

```bash
cd E:/educatorweb/educatorweb/frontend
npm uninstall pixi-live2d-display-lipsyncpatch pixi-live2d-display pixi.js
npm install pixi.js@6.5.10 pixi-live2d-display@0.4.0
```

**预期:** `package.json` 中 `pixi.js` 变为 `^6.5.10`,`pixi-live2d-display` 变为 `^0.4.0`

### Task 2: 更新 Live2DCharacter.vue 适配 PIXI 6

**差异:**
- PIXI 6 的 `import * as PIXI from 'pixi.js'` 一样可用
- `pixi-live2d-display@0.4.0` 没有 `/cubism2` 子路径,`import { Live2DModel } from 'pixi-live2d-display'` 直接从主入口导入
- 其余 API (`Application`, `Ticker`, `registerTicker`, `Live2DModel.from`, `stage.addChild`, `renderer.resize`, `model.scale/x/y/anchor`, `internalModel`) 全部相同
- `registerTicker` 在 0.4.0 中存在且稳定

**改动:**
```javascript
// 改动前(Live2DCharacter.vue 第 10 行):
import { Live2DModel } from 'pixi-live2d-display-lipsyncpatch/cubism2'

// 改为:
import { Live2DModel } from 'pixi-live2d-display'
```

同时确保 `index.html` 的 Cubism 2 运行时 `live2d.min.js` 仍然在 `<script>` 中加载(PIXI 6 的 0.4.0 版同样依赖 `window.Live2D` 全局)。

### Task 3: 修复 ResourcePush.vue 资源跳转

**当前问题:** `goLearn` 全部跳到 `/learning?topic=xxx`(旧资源生成页),那不是"推送的资源"。

**临时修复(资源消费页尚未设计):**
- DOC 类型:在新标签页打开 `/resource/doc?topic=xxx&title=xxx`(需要新建一个简单的资源阅读页——或从简:直接跳到 `/chat` 并自动发送"帮我生成 {title}"的消息)
- QUIZ 类型:弹窗提示"练习功能即将上线"
- CODE 类型:弹窗提示"代码浏览功能即将上线"
- VIDEO 类型:弹窗提示"视频播放功能即将上线"
- MINDMAP 类型:弹窗提示"思维导图功能即将上线"

**实用方案:** 所有类型暂时统一跳到 `/chat` 页面并预填输入框内容为资源标题,让 AI 辅导来处理。这样至少比跳到无关的旧页面强。

```javascript
function goLearn(res, topic) {
  const t = topic || searchText.value
  // 临时:跳转到 AI 对话,用户可以直接向 AI 提问该资源内容
  window.location.href = '/chat?q=' + encodeURIComponent(t + ' - ' + (res.title || ''))
}
```

### Task 4: 构建验证 + 测试

1. `npm run build` 前端无报错
2. `mvn test` 后端 33/33 通过
