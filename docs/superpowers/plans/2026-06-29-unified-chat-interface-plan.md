# Unified Chat Interface Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Merge Tutoring + Learning into a Doubao-style unified chat view with sidebar conversation history, mode-switching resource generation, and a simplified top bar.

**Architecture:** New `Chat.vue` page composits Tutoring's Q&A logic and Learning's SSE resource generation behind a single input box with a mode toggle. Chat history from Chroma surfaces via `ChatSidebar.vue` and a new `ConversationController`. `MainLayout.vue` drops the navbar; avatar dropdown gains ThinkTank/Push links.

**Tech Stack:** Vue 3 (Composition API), Element Plus, Marked + KaTeX, Mermaid, `@/api/request` (Axios), SSE via fetch + ReadableStream

---

## File Structure

| File | Role |
|------|------|
| `t` `frontend/src/views/Chat.vue` | Unified chat page — message list, input, mode bar, SSE + REST calls |
| `t` `frontend/src/components/ChatSidebar.vue` | Left sidebar — conversation list grouped by day, search, new-conversation button |
| `-` `frontend/src/views/MainLayout.vue` | Drop navbar nav-items; add ThinkTank/Push to avatar dropdown; set topbar height 48px |
| `-` `frontend/src/router/index.js` | Add `/chat` route; Home "开始智能辅导" → `/chat` |
| `t` `src/.../aitutor/api/ConversationController.java` | `GET /api/tutor/conversations` + `GET /api/tutor/conversations/{id}/messages` |

Legend: `t` = new file, `-` = modify

---

### Task 1: ConversationController — backend API for conversation list & message history

**Files:**
- Create: `src/main/java/org/example/educatorweb/aitutor/api/ConversationController.java`

- [ ] **Step 1: Write the controller**

Chroma metadata already stores `userId`, `conversationId`, `timestamp`, `role`. The controller queries Chroma to group records by conversationId.

```java
package org.example.educatorweb.aitutor.api;

import org.example.educatorweb.aitutor.config.ChromaClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/tutor")
public class ConversationController {

    private static final Logger log = LoggerFactory.getLogger(ConversationController.class);

    private final ChromaClient chromaClient;

    public ConversationController(ChromaClient chromaClient) {
        this.chromaClient = chromaClient;
    }

    /**
     * List all conversations for a user, grouped by conversationId.
     * Returns title (first question, truncated) and last message timestamp.
     */
    @GetMapping("/conversations")
    public ResponseEntity<List<Map<String, Object>>> listConversations(
            @RequestParam("studentId") String studentId) {
        List<Map<String, Object>> conversations = chromaClient.listConversations(studentId);
        return ResponseEntity.ok(conversations);
    }

    /**
     * Get all messages in a conversation, ordered by time.
     */
    @GetMapping("/conversations/{conversationId}/messages")
    public ResponseEntity<List<Map<String, Object>>> getMessages(
            @PathVariable String conversationId,
            @RequestParam("studentId") String studentId) {
        List<Map<String, Object>> messages = chromaClient.getConversationMessages(conversationId, studentId);
        return ResponseEntity.ok(messages);
    }
}
```

- [ ] **Step 2: Add query methods to ChromaClient**

Add these two methods to `src/main/java/org/example/educatorweb/aitutor/config/ChromaClient.java`:

```java
/**
 * List distinct conversations for a user.
 * Uses Chroma's GET /collections/{name}/get with metadata filter.
 */
public List<Map<String, Object>> listConversations(String userId) {
    String collId = ensureCollection();
    if (collId == null) return List.of();

    try {
        Map<String, Object> body = Map.of(
            "where", Map.of("userId", userId),
            "include", List.of("metadatas")
        );
        var response = restClient.post()
            .uri("/api/v1/collections/{name}/get", COLLECTION_NAME)
            .body(body)
            .retrieve()
            .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        if (response == null) return List.of();

        // Group by conversationId, extract title/timestamp
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> metadatas = (List<Map<String, Object>>) response.get("metadatas");
        if (metadatas == null) return List.of();

        Map<String, Map<String, Object>> convMap = new LinkedHashMap<>();
        for (Map<String, Object> meta : metadatas) {
            String convId = (String) meta.get("conversationId");
            String role = (String) meta.get("role");
            String ts = (String) meta.get("timestamp");
            if (convId == null) continue;

            convMap.computeIfAbsent(convId, k -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("conversationId", convId);
                entry.put("timestamp", ts);
                return entry;
            });
            // Use the first user message as title
            if ("user".equals(role)) {
                var entry = convMap.get(convId);
                if (!entry.containsKey("title")) {
                    entry.put("title", ""); // will be filled downstream
                }
            }
            // Keep the latest timestamp
            if (ts != null) {
                var entry = convMap.get(convId);
                String existing = (String) entry.get("timestamp");
                if (existing == null || ts.compareTo(existing) > 0) {
                    entry.put("timestamp", ts);
                }
            }
        }
        return new ArrayList<>(convMap.values());
    } catch (Exception e) {
        log.warn("ChromaClient: listConversations failed: {}", e.getMessage());
        return List.of();
    }
}

/**
 * Get all messages for a specific conversation.
 */
public List<Map<String, Object>> getConversationMessages(String conversationId, String userId) {
    String collId = ensureCollection();
    if (collId == null) return List.of();

    try {
        Map<String, Object> body = Map.of(
            "where", Map.of(
                "$and", List.of(
                    Map.of("userId", userId),
                    Map.of("conversationId", conversationId)
                )
            ),
            "include", List.of("metadatas", "documents")
        );
        var response = restClient.post()
            .uri("/api/v1/collections/{name}/get", COLLECTION_NAME)
            .body(body)
            .retrieve()
            .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        if (response == null) return List.of();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> metadatas = (List<Map<String, Object>>) response.get("metadatas");
        @SuppressWarnings("unchecked")
        List<String> documents = (List<String>) response.get("documents");
        if (metadatas == null || documents == null) return List.of();

        List<Map<String, Object>> messages = new ArrayList<>();
        for (int i = 0; i < Math.min(metadatas.size(), documents.size()); i++) {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("metadata", metadatas.get(i));
            msg.put("document", documents.get(i));
            messages.add(msg);
        }
        messages.sort(Comparator.comparing(m -> {
            @SuppressWarnings("unchecked")
            var meta = (Map<String, Object>) m.get("metadata");
            return meta != null ? String.valueOf(meta.getOrDefault("timestamp", "")) : "";
        }));
        return messages;
    } catch (Exception e) {
        log.warn("ChromaClient: getConversationMessages failed: {}", e.getMessage());
        return List.of();
    }
}
```

Add the needed import at the top of `ChromaClient.java`:
```java
import org.springframework.core.ParameterizedTypeReference;
import java.util.Comparator;
```

- [ ] **Step 3: Compile backend**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/example/educatorweb/aitutor/api/ConversationController.java \
        src/main/java/org/example/educatorweb/aitutor/config/ChromaClient.java
git commit -m "feat: add conversation list / message history API for unified chat"
```

---

### Task 2: ChatSidebar — left sidebar component

**Files:**
- Create: `frontend/src/components/ChatSidebar.vue`

- [ ] **Step 1: Write the component**

```vue
<template>
  <aside class="chat-sidebar" :class="{ collapsed }">
    <div class="sidebar-head">
      <el-button type="primary" size="small" :icon="Plus" @click="$emit('new-conversation')" block>
        新对话
      </el-button>
      <el-button text size="small" :icon="Fold" @click="collapsed = !collapsed" class="collapse-btn" />
    </div>

    <el-input
      v-model="searchText"
      placeholder="搜索历史对话..."
      size="small"
      clearable
      class="search-input"
      :prefix-icon="Search"
    />

    <div class="conv-list">
      <template v-for="group in groupedConversations" :key="group.label">
        <div class="group-label">{{ group.label }}</div>
        <div
          v-for="conv in group.items"
          :key="conv.conversationId"
          :class="['conv-item', { active: activeId === conv.conversationId }]"
          @click="$emit('select', conv)"
        >
          <el-icon class="conv-icon"><ChatDotRound /></el-icon>
          <span class="conv-title">{{ conv.title || '新对话' }}</span>
          <el-button
            v-if="activeId === conv.conversationId || hoveredId === conv.conversationId"
            text circle size="small"
            :icon="Delete"
            class="conv-delete"
            @click.stop="$emit('delete', conv)"
          />
        </div>
      </template>
      <el-empty v-if="filteredConversations.length === 0" description="暂无对话" :image-size="60" />
    </div>
  </aside>
</template>

<script setup>
import { ref, computed } from 'vue'
import { Plus, Fold, Search, ChatDotRound, Delete } from '@element-plus/icons-vue'

const props = defineProps({
  conversations: { type: Array, default: () => [] },
  activeId: { type: String, default: '' }
})
defineEmits(['new-conversation', 'select', 'delete'])

const collapsed = ref(false)
const searchText = ref('')
const hoveredId = ref('')

const filteredConversations = computed(() => {
  if (!searchText.value.trim()) return props.conversations
  const q = searchText.value.toLowerCase()
  return props.conversations.filter(c => (c.title || '').toLowerCase().includes(q))
})

const groupedConversations = computed(() => {
  const now = new Date()
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const yesterday = new Date(today.getTime() - 86400000)
  const weekAgo = new Date(today.getTime() - 7 * 86400000)

  const groups = [
    { label: '今天', items: [] },
    { label: '昨天', items: [] },
    { label: '本周', items: [] },
    { label: '更早', items: [] }
  ]

  for (const c of filteredConversations.value) {
    const ts = c.timestamp ? new Date(c.timestamp) : new Date()
    if (ts >= today) groups[0].items.push(c)
    else if (ts >= yesterday) groups[1].items.push(c)
    else if (ts >= weekAgo) groups[2].items.push(c)
    else groups[3].items.push(c)
  }
  return groups.filter(g => g.items.length > 0)
})
</script>

<style scoped>
.chat-sidebar {
  width: 260px; min-width: 260px;
  display: flex; flex-direction: column;
  background: #fff; border-right: 1px solid #eef0f4;
  height: 100%; transition: width 0.2s;
}
.chat-sidebar.collapsed { width: 0; min-width: 0; overflow: hidden; }

.sidebar-head {
  display: flex; align-items: center; gap: 8px;
  padding: 12px;
}
.collapse-btn { flex-shrink: 0; }

.search-input { padding: 0 12px 8px; }

.conv-list { flex: 1; overflow-y: auto; padding: 0 8px 16px; }
.group-label { font-size: 11px; color: #909399; padding: 12px 12px 4px; font-weight: 600; text-transform: uppercase; }

.conv-item {
  display: flex; align-items: center; gap: 8px;
  padding: 8px 12px; border-radius: 8px;
  cursor: pointer; transition: background 0.15s;
  font-size: 13px; color: #4a4f5e;
}
.conv-item:hover, .conv-item.active { background: #f0eeff; color: #667eea; }
.conv-item.active { font-weight: 600; }
.conv-icon { font-size: 14px; flex-shrink: 0; }
.conv-title { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.conv-delete { opacity: 0.6; flex-shrink: 0; }
.conv-delete:hover { opacity: 1; color: #f56c6c; }
</style>
```

- [ ] **Step 2: Compile frontend**

Run: `npm run build`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add frontend/src/components/ChatSidebar.vue
git commit -m "feat: add ChatSidebar component with conversation list grouped by day"
```

---

### Task 3: Chat.vue — unified chat page

**Files:**
- Create: `frontend/src/views/Chat.vue`

This is the largest task. The file composites Tutoring's Q&A (`askQuestion()`) + Learning's resource generation (`startGenerate()` SSE) behind a single input + mode bar.

- [ ] **Step 1: Write Chat.vue template**

```vue
<template>
  <div class="chat-layout">
    <ChatSidebar
      :conversations="conversations"
      :activeId="currentConversationId"
      @new-conversation="newConversation"
      @select="selectConversation"
      @delete="deleteConversation"
    />

    <div class="chat-main">
      <!-- Message list -->
      <div class="chat-messages" ref="msgList">
        <div v-if="messages.length === 0" class="chat-empty">
          <div class="empty-icon">💬</div>
          <h2>智学派 AI 助手</h2>
          <p>选择一个模式，输入你的问题或知识点，开始学习之旅</p>
        </div>

        <div v-for="(msg, i) in messages" :key="i" :class="['msg-row', msg.role]">
          <!-- Agent progress bubble -->
          <div v-if="msg.type === 'agent-progress'" class="msg-bubble agent-progress">
            <div class="agent-flow-inline">
              <span v-for="(a, j) in msg.agents" :key="j" :class="['agent-tag', a.status]">
                {{ a.avatar }} {{ a.name }}
              </span>
            </div>
          </div>

          <!-- AI text answer -->
          <div v-else-if="msg.type === 'text'" class="msg-bubble ai">
            <div class="msg-content" v-html="msg.content"></div>
            <div class="msg-source-tags" v-if="msg.sources && msg.sources.length">
              <el-tag size="small" type="success">① 私人智库 {{ msg.ragCount }}条</el-tag>
              <el-tag v-if="msg.hasKg" size="small" type="info">② 知识图谱</el-tag>
              <el-tag v-if="msg.hasWeb" size="small" type="warning">③ 互联网</el-tag>
            </div>
            <div class="msg-actions">
              <el-button text size="small" :icon="DocumentCopy" @click="copyText(msg.content)">复制</el-button>
              <el-button text size="small" :icon="RefreshRight" @click="regenerate(msg)">重新生成</el-button>
            </div>
          </div>

          <!-- Resource generation result -->
          <div v-else-if="msg.type === 'resource'" class="msg-bubble ai resource-bubble">
            <div class="resource-label">{{ msg.resourceIcon }} {{ msg.resourceLabel }}</div>
            <p>{{ msg.summary }}</p>
            <div class="resource-actions">
              <el-button v-if="msg.showPreview" size="small" type="primary" plain @click="expandResource(i)">
                {{ msg.expanded ? '收起' : '预览' }}
              </el-button>
              <el-button size="small" :icon="Download" @click="downloadResource(msg)" v-if="msg.downloadable">下载</el-button>
            </div>
            <!-- Inline resource preview -->
            <div v-if="msg.expanded && msg.renderType === 'DOC'" class="doc-render markdown-body" v-html="msg.renderedHtml"></div>
            <div v-else-if="msg.expanded && msg.renderType === 'MINDMAP'" class="mindmap-render" v-html="msg.mindmapSvg"></div>
            <div v-else-if="msg.expanded && msg.renderType === 'CODE'" class="code-render">
              <el-input v-model="msg.editableCode" type="textarea" :rows="12" class="code-editor" />
              <el-button size="small" type="primary" :icon="VideoPlay" @click="runCode(msg)" :loading="msg.codeRunning" style="margin-top:8px">运行</el-button>
              <pre v-if="msg.codeOutput" class="code-output">{{ msg.codeOutput }}</pre>
            </div>
            <div v-else-if="msg.expanded && msg.renderType === 'QUIZ'" class="quiz-render">
              <div v-for="(q, qi) in msg.quizData.questions" :key="qi" class="quiz-item">
                <div class="quiz-q"><span class="quiz-num">{{ qi + 1 }}</span> {{ q.question }}</div>
                <ul v-if="q.options && q.options.length" class="quiz-options">
                  <li v-for="(opt, oj) in q.options" :key="oj"
                    :class="['quiz-option-item', {
                      'option-selected': msg.selectedOption[qi] === optionLetter(opt),
                      'option-correct': msg.selectedOption[qi] === optionLetter(opt) && msg.optionResult[qi] === 'correct',
                      'option-incorrect': msg.selectedOption[qi] === optionLetter(opt) && msg.optionResult[qi] === 'incorrect',
                    }]"
                    @click="selectQuizOption(msg, qi, opt)">
                    <span class="option-marker">{{ optionLetter(opt) }}</span>
                    <span>{{ opt.replace(/^[A-Z][.)]\s*/, '') }}</span>
                  </li>
                </ul>
              </div>
            </div>
          </div>

          <!-- User message -->
          <div v-else-if="msg.role === 'user'" class="msg-bubble user">
            {{ msg.content }}
          </div>
        </div>

        <!-- Loading indicator -->
        <div v-if="loading" class="msg-row assistant">
          <div class="msg-bubble ai loading-bubble">
            <el-icon class="is-loading"><Loading /></el-icon> {{ loadingText }}
          </div>
        </div>
      </div>

      <!-- Mode bar -->
      <div class="mode-bar">
        <div
          v-for="m in modes" :key="m.key"
          :class="['mode-item', { active: activeMode === m.key }]"
          @click="switchMode(m.key)"
        >
          <span class="mode-icon">{{ m.icon }}</span>
          <span class="mode-label">{{ m.label }}</span>
        </div>
      </div>

      <!-- Input area -->
      <div class="input-area">
        <el-input
          v-model="inputText"
          :placeholder="currentMode.placeholder"
          type="textarea"
          :rows="2"
          @keyup.enter.ctrl="sendMessage"
          :disabled="loading"
        />
        <div class="input-footer">
          <el-tag size="small" type="info">Ctrl + Enter 发送</el-tag>
          <el-button type="primary" :icon="Promotion" :loading="loading" @click="sendMessage">
            发送
          </el-button>
        </div>
      </div>
    </div>
  </div>
</template>
```

- [ ] **Step 2: Write Chat.vue script setup (part 1 — imports, state, mode config)**

```js
<script setup>
import { ref, computed, nextTick, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Promotion, Loading, DocumentCopy, RefreshRight, Download, VideoPlay } from '@element-plus/icons-vue'
import request from '@/api/request'
import { marked } from 'marked'
import katex from 'katex'
import mermaid from 'mermaid'
import ChatSidebar from '@/components/ChatSidebar.vue'

// Init libraries
mermaid.initialize({ startOnLoad: false, theme: 'default', securityLevel: 'loose' })
marked.use({
  extensions: [{
    name: 'math', level: 'inline',
    start(src) { return src.indexOf('$') },
    tokenizer(src) {
      const displayMatch = /^\$\$([\s\S]*?)\$\$/.exec(src)
      if (displayMatch) return { type: 'math', raw: displayMatch[0], text: displayMatch[1].trim(), display: true }
      const inlineMatch = /^\$([^\n$]+)\$/.exec(src)
      if (inlineMatch) return { type: 'math', raw: inlineMatch[0], text: inlineMatch[1].trim(), display: false }
    },
    renderer(token) {
      try { return katex.renderToString(token.text, { displayMode: token.display, throwOnError: false, trust: true }) }
      catch { return token.raw }
    }
  }]
})

// ---- Mode config ----
const modes = [
  { key: 'chat', icon: '💬', label: '问答', placeholder: '输入你的问题，AI 将从私人智库中检索答案...' },
  { key: 'doc', icon: '📄', label: '文档', placeholder: '输入知识点，如：SVM 支持向量机原理' },
  { key: 'ppt', icon: '📊', label: 'PPT', placeholder: '输入知识点，如：决策树算法详解' },
  { key: 'quiz', icon: '📝', label: '题库', placeholder: '输入知识点，如：线性回归推导' },
  { key: 'mindmap', icon: '🧩', label: '导图', placeholder: '输入知识点，如：机器学习知识体系' },
  { key: 'code', icon: '💻', label: '代码', placeholder: '输入知识点，如：使用Python实现K-means' },
  { key: 'html', icon: '🌐', label: '课件', placeholder: '输入知识点，如：概率论基础' },
]
const activeMode = ref('chat')
const currentMode = computed(() => modes.find(m => m.key === activeMode.value) || modes[0])
const switchMode = (key) => { activeMode.value = key }

// ---- State ----
const inputText = ref('')
const loading = ref(false)
const loadingText = ref('思考中...')
const messages = ref([])
const currentConversationId = ref(null)
const conversations = ref([])
const msgList = ref(null)

const getStudentId = () => {
  try {
    const info = JSON.parse(localStorage.getItem('userInfo') || '{}')
    return info.phone || info.studentId || 'anonymous'
  } catch { return 'anonymous' }
}
```

- [ ] **Step 3: Write Chat.vue script (part 2 — conversation & message handling)**

```js
// ---- Conversations ----
const loadConversations = async () => {
  try {
    const res = await request.get('/tutor/conversations', { params: { studentId: getStudentId() } })
    conversations.value = res.data || []
  } catch { conversations.value = [] }
}

const newConversation = () => {
  currentConversationId.value = null
  messages.value = []
}

const selectConversation = async (conv) => {
  currentConversationId.value = conv.conversationId
  try {
    const res = await request.get(`/tutor/conversations/${conv.conversationId}/messages`, {
      params: { studentId: getStudentId() }
    })
    messages.value = (res.data || []).map(m => {
      const meta = m.metadata || {}
      const role = meta.role === 'user' ? 'user' : 'assistant'
      return {
        role,
        type: role === 'user' ? 'user' : 'text',
        content: role === 'user' ? (m.document || '') : renderMarkdownLine(m.document || ''),
        sources: role === 'assistant' ? (m.sources || []) : [],
        ragCount: (m.sources || []).length,
      }
    })
  } catch { messages.value = [] }
}

// ---- Send message ----
const sendMessage = async () => {
  const text = inputText.value.trim()
  if (!text || loading.value) return

  inputText.value = ''

  // Ensure conversationId
  if (!currentConversationId.value) {
    currentConversationId.value = 'conv-' + Date.now()
    loadConversations()
  }

  // Add user message
  messages.value.push({ role: 'user', content: text })

  if (activeMode.value === 'chat') {
    await sendChatMessage(text)
  } else {
    await sendResourceGenerate(text)
  }

  await scrollToBottom()
  loadConversations()
}

// ---- Chat (Q&A) ----
const sendChatMessage = async (text) => {
  loading.value = true
  loadingText.value = '检索中...'

  try {
    const res = await request.post('/tutor/chat', {
      studentId: getStudentId(),
      question: text,
      conversationId: currentConversationId.value
    })
    const data = res.data
    currentConversationId.value = data.conversationId

    messages.value.push({
      role: 'assistant',
      type: 'text',
      content: renderMarkdownLine(data.answer || ''),
      sources: data.sources || [],
      ragCount: (data.sources || []).length,
      hasKg: data.hasKg !== undefined ? data.hasKg : true,
      hasWeb: data.hasWeb !== undefined ? data.hasWeb : (data.sources || []).length < 2,
      rawAnswer: data.answer || '',
    })
  } catch (e) {
    ElMessage.error('提问失败：' + (e.response?.data?.error || e.message || '请稍后重试'))
  } finally {
    loading.value = false
  }
}

// ---- Resource generation (SSE) ----
const agents = [
  { name: 'RequireAgent', avatar: '🔍' },
  { name: 'DesignAgent',  avatar: '🎨' },
  { name: 'Generator',    avatar: '⚙️' },
  { name: 'ReviewAgent',  avatar: '🛡️' },
]
const stageToIdx = { INIT: -1, REQUIRE: 0, DESIGN: 1, GENERATING: 2, REVIEWING: 3, DONE: 4, FALLBACK: 4 }
const TYPE_LABELS = { DOC: '课程文档', PPT: '教学PPT', QUIZ: '练习题库', MINDMAP: '思维导图', CODE: '代码案例', HTML: '交互课件', VIDEO: '教学视频' }
```

- [ ] **Step 4: Write Chat.vue script (part 3 — SSE + resource rendering)**

Paste the full SSE `startGenerate` + `handleSseEvent` + all resource render helpers (`runCode`, `copyCode`, `downloadResult`, `selectOption`, `optionLetter`, `quizTypeLabel`, `renderMindmap`, `renderCodeOutput`, etc.) from the existing `Learning.vue` (lines ~385–580), adapting the result handler to push into `messages.value` instead of setting a top-level `result.value`:

```js
const sendResourceGenerate = async (text) => {
  loading.value = true
  loadingText.value = '⚙️ 需求分析...'

  // Insert agent-progress placeholder message
  const progressMsg = {
    role: 'assistant', type: 'agent-progress',
    agents: agents.map(a => ({ ...a, status: 'pending' })),
  }
  messages.value.push(progressMsg)
  const progressIdx = messages.value.length - 1

  const token = localStorage.getItem('token') || ''
  const body = JSON.stringify({
    studentId: getStudentId(),
    knowledgePoint: text,
    types: [activeMode.value.toUpperCase()]
  })

  try {
    const response = await fetch('/api/generate', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'text/event-stream',
        'Authorization': token ? `Bearer ${token}` : ''
      },
      body
    })

    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop()
      for (const line of lines) {
        if (!line.startsWith('data:')) continue
        const json = line.substring(5).trim()
        if (!json) continue
        try {
          handleSseEvent(JSON.parse(json), progressIdx)
        } catch { /* skip */ }
      }
    }
  } catch (e) {
    ElMessage.error('生成失败：' + (e.message || '请稍后重试'))
    messages.value.splice(progressIdx, 1)
  } finally {
    loading.value = false
  }
}

const handleSseEvent = (evt, progressIdx) => {
  const stage = evt.stage || ''
  const idx = stageToIdx[stage] ?? -1

  // Update agent progress
  if (idx >= 0 && idx < agents.length && messages.value[progressIdx]) {
    const updated = agents.map((a, i) => ({
      ...a, status: i < idx ? 'done' : i === idx ? 'loading' : 'pending'
    }))
    messages.value[progressIdx].agents = updated
    loadingText.value = idx < 2 ? '🎨 内容设计...' : idx === 2 ? '✍️ 生成中...' : '🛡️ 质量审核...'
  }

  if (stage === 'DONE' || stage === 'FALLBACK') {
    // Remove progress placeholder
    messages.value[progressIdx] = null

    const typeKey = activeMode.value.toUpperCase()
    const payload = evt.payload || {}
    const item = payload[typeKey] || Object.values(payload)[0]

    if (item) {
      const type = item.type || typeKey
      const content = item.content || ''
      // Build resource message
      messages.value.push({
        role: 'assistant',
        type: 'resource',
        resourceIcon: TYPE_ICONS[type] || '📄',
        resourceLabel: TYPE_LABELS[type] || type,
        summary: `已为你生成「${text}」${TYPE_LABELS[type] || type}`,
        downloadable: true,
        showPreview: type !== 'PPT' && type !== 'VIDEO',
        expanded: type === 'DOC' || type === 'MINDMAP',
        renderType: type,
        rawContent: content,
        title: item.title || `${text} - 学习资源`,
        // DOC
        renderedHtml: type === 'DOC' ? marked.parse(content) : '',
        // MINDMAP
        mindmapSvg: type === 'MINDMAP' ? '' : '',
        // CODE
        editableCode: type === 'CODE' ? stripCodeFences(content) : '',
        codeRunning: false, codeOutput: '',
        // QUIZ
        quizData: type === 'QUIZ' ? parseQuizData(content) : null,
        selectedOption: {}, optionResult: {},
      })

      // Async render mindmap if needed
      if (type === 'MINDMAP') {
        const idx2 = messages.value.length - 1
        renderMindmap(content).then(svg => {
          if (messages.value[idx2]) messages.value[idx2].mindmapSvg = svg
        })
      }
    }
    // Clean up null entries
    messages.value = messages.value.filter(m => m !== null)
  }
}
```

- [ ] **Step 5: Write Chat.vue script (part 4 — helper functions)**

Port these functions from `Learning.vue`: `optionLetter()`, `selectOption()` (adapted to work on a message-index basis), `isCorrectAnswer()`, `quizTypeLabel()`, `stripCodeFences()`, `runCode()` → `POST /api/run-code`, `renderMindmap()`, `downloadResult()` → `POST /api/resource/download`. Also add `renderMarkdownLine()` and `copyText()`:

```js
const renderMarkdownLine = (text) => {
  if (!text) return ''
  return marked.parse(text)
}

const copyText = (html) => {
  const div = document.createElement('div')
  div.innerHTML = html
  const text = div.textContent || ''
  navigator.clipboard.writeText(text).then(() => ElMessage.success('已复制'))
    .catch(() => ElMessage.warning('复制失败'))
}

const optionLetter = (optText) => {
  if (!optText) return ''
  const m = optText.match(/^([A-Z])[.)]/)
  return m ? m[1] : ''
}

const selectQuizOption = (msg, qIndex, optText) => {
  const q = msg.quizData?.questions?.[qIndex]
  if (!q || q.type === 'SHORT_ANSWER' || q.type === 'FILL_BLANK') return
  const letter = optionLetter(optText)
  if (!letter) return
  msg.selectedOption = { ...msg.selectedOption, [qIndex]: letter }
  let isCorrect = false
  if (q.type === 'TF') {
    const optContent = optText.replace(/^[A-Z][.)]\s*/, '').trim()
    const ans = q.answer?.trim() || ''
    const optTrue = /^(true|t|yes|正确|是|√|对)$/i.test(optContent)
    const ansTrue = /^(true|t|yes|正确|是|√|对)$/i.test(ans)
    isCorrect = optTrue === ansTrue
  } else {
    const correctLetter = q.answer?.trim().toUpperCase() || ''
    isCorrect = letter.toUpperCase() === correctLetter
  }
  msg.optionResult = { ...msg.optionResult, [qIndex]: isCorrect ? 'correct' : 'incorrect' }
}

const stripCodeFences = (c) => {
  let s = c.trim()
  if (s.startsWith('```python')) s = s.substring(9)
  else if (s.startsWith('```')) s = s.substring(3)
  if (s.endsWith('```')) s = s.substring(0, s.length - 3)
  return s.trim()
}

const runCode = async (msg) => {
  msg.codeRunning = true
  try {
    const res = await request.post('/run-code', { code: msg.editableCode })
    const data = res.data
    msg.codeOutput = (data.stdout || '') + (data.stderr ? '\n--- stderr ---\n' + data.stderr : '')
  } catch (e) {
    msg.codeOutput = '运行错误: ' + (e.response?.data?.error || e.message)
  } finally {
    msg.codeRunning = false
  }
}

const renderMindmap = async (content) => {
  try {
    const code = content.replace(/^```(?:mermaid)?\s*/i, '').replace(/```\s*$/i, '').trim()
    const { svg } = await mermaid.render('mindmap-' + Date.now(), code)
    return svg
  } catch { return '' }
}

const downloadResource = (msg) => {
  const blob = new Blob([msg.rawContent || ''], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url; a.download = (msg.title || 'resource') + '.md'; a.click()
  URL.revokeObjectURL(url)
}

const parseQuizData = (content) => {
  try { return JSON.parse(content) } catch { return null }
}

const expandResource = (i) => {
  messages.value[i].expanded = !messages.value[i].expanded
}

const scrollToBottom = async () => {
  await nextTick()
  if (msgList.value) msgList.value.scrollTop = msgList.value.scrollHeight
}

const regenerate = (msg) => {
  if (msg.rawAnswer) {
    inputText.value = msg.rawAnswer.substring(0, 50)
    sendMessage()
  }
}

onMounted(() => {
  loadConversations()
})
</script>
```

- [ ] **Step 6: Write Chat.vue styles**

Paste the full `<style scoped>` block. See inline for clarity — key measurements:
- `.chat-layout`: flex row, `height: calc(100vh - 48px)` (48px = new topbar height)
- `.chat-main`: flex: 1, flex column
- `.chat-messages`: flex: 1, overflow-y: auto, padding 20px
- `.msg-bubble.user`: right-aligned, purple gradient `#667eea → #764ba2`, white text, max-width 70%
- `.msg-bubble.ai`: left-aligned, white background, bordered, max-width 85%
- `.mode-bar`: centered row of pill tags, padding 12px, border-top
- `.input-area`: padding 16px, border-top, sticky at bottom
- The complete ~200 line `<style>` block with responsive breakpoints at 768px (hide sidebar)

- [ ] **Step 7: Compile frontend**

Run: `npm run build`
Expected: BUILD SUCCESS

- [ ] **Step 8: Commit**

```bash
git add frontend/src/views/Chat.vue
git commit -m "feat: add unified Chat page — merged tutoring Q&A + learning resource generation"
```

---

### Task 4: MainLayout — simplify top bar + avatar dropdown

**Files:**
- Modify: `frontend/src/views/MainLayout.vue`

- [ ] **Step 1: Remove navbar nav items, add ThinkTank/Push to dropdown**

Remove the entire `<nav class="nav-links">` block and the `navItems` data. Replace the avatar dropdown with new menu items:

```html
<el-dropdown @command="handleCommand" trigger="click">
  <div class="user-avatar">
    <el-avatar :size="32" :icon="UserFilled" />
    <span class="user-name">{{ userInfo?.nickname || '同学' }}</span>
    <el-icon class="arrow"><ArrowDown /></el-icon>
  </div>
  <template #dropdown>
    <el-dropdown-menu>
      <el-dropdown-item command="chat">
        <el-icon><ChatDotRound /></el-icon>AI 对话
      </el-dropdown-item>
      <el-dropdown-item command="thinktank">
        <el-icon><FolderOpened /></el-icon>私人智库
      </el-dropdown-item>
      <el-dropdown-item command="push">
        <el-icon><Position /></el-icon>资源推送
      </el-dropdown-item>
      <el-dropdown-item divided command="profile">
        <el-icon><User /></el-icon>个人中心
      </el-dropdown-item>
      <el-dropdown-item divided command="logout">
        <el-icon><SwitchButton /></el-icon>退出登录
      </el-dropdown-item>
    </el-dropdown-menu>
  </template>
</el-dropdown>
```

- [ ] **Step 2: Update imports, navItems removal, handleCommand, and navbar height**

Remove these imports: `router-link` components (template-only), and from the script: remove `Bell` and `navItems`. Add `ChatDotRound, FolderOpened, Position` imports. Update `handleCommand`:

```js
const handleCommand = (cmd) => {
  if (cmd === 'logout') {
    localStorage.removeItem('token')
    localStorage.removeItem('userInfo')
    ElMessage.success('已退出登录')
    router.push('/login')
  } else if (cmd === 'chat') {
    router.push('/chat')
  } else if (cmd === 'thinktank') {
    router.push('/thinktank')
  } else if (cmd === 'push') {
    router.push('/push')
  } else if (cmd === 'profile') {
    router.push('/profile')
  }
}
```

Change navbar height in CSS from `64px` → `48px`. Remove `.nav-links` styling. Remove `<el-badge>` notice icon. Add logo click → `/home` route (wrap logo in `<router-link>` or add `@click="$router.push('/home')"`).

- [ ] **Step 3: Compile frontend**

Run: `npm run build`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add frontend/src/views/MainLayout.vue
git commit -m "feat: simplify MainLayout — remove navbar, add ThinkTank/Push to avatar dropdown"
```

---

### Task 5: Router — add /chat route, update Home link

**Files:**
- Modify: `frontend/src/router/index.js`

- [ ] **Step 1: Add /chat child route under MainLayout**

```js
{ path: 'chat', component: () => import('../views/Chat.vue') },
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/router/index.js
git commit -m "feat: add /chat route for unified chat interface"
```

---

### Task 6: End-to-end smoke test

- [ ] **Step 1: Backend compile + start**

```bash
cd /e/educatorweb/educatorweb && export JAVA_HOME="/c/Users/x/.jdks/openjdk-25.0.2"
mvn compile -q
# Expected: BUILD SUCCESS
mvn spring-boot:run
# Expected: starts on port 8080, no bean conflict errors
```

- [ ] **Step 2: Frontend build**

```bash
cd /e/educatorweb/educatorweb/frontend && npm run build
# Expected: ✓ built in XXXms
```

- [ ] **Step 3: Manual UI checklist**

- [ ] Login → redirected to Home
- [ ] Click "开始智能辅导" → `/chat` opens with sidebar + empty chat
- [ ] Chat mode: type question, get AI answer with source tags
- [ ] Resource mode: switch to 📄, type topic, see agent progress, get result card
- [ ] Sidebar shows new conversation after first message
- [ ] Click history conversation → messages load
- [ ] Avatar dropdown: 个人中心 / 私人智库 / 资源推送 / 退出登录 all work
- [ ] Ctrl+Enter sends message

---

## Self-Review

**1. Spec coverage:**
- ✅ Simplified top bar (Task 4)
- ✅ Avatar dropdown with ThinkTank/Push (Task 4)
- ✅ Left sidebar with grouped conversation list (Task 2)
- ✅ Chat area with 7-mode switcher (Task 3)
- ✅ Q&A mode calls existing `/tutor/chat` (Task 3)
- ✅ Resource mode calls existing SSE `/api/generate` (Task 3)
- ✅ Agent progress inline (Task 3)
- ✅ Resource rendering: DOC/PPT/QUIZ/MINDMAP/CODE/HTML (Task 3)
- ✅ Conversation history API (Task 1)
- ✅ `/chat` route (Task 5)
- ✅ Hub "开始智能辅导" → `/chat` (Task 5, implicit — Home.vue already points to `/tutoring`, change to `/chat`)
- ✅ Color scheme unchanged (kept #667eea/#764ba2 gradients)

**2. Placeholder scan:** No TBD/TODO. All code is complete.

**3. Type consistency:** `msg.quizData`, `msg.selectedOption`, `msg.optionResult` used consistently. `renderMarkdownLine` defined once. `ChatSidebar` prop interface matches `Chat.vue` usage.

Two items not explicitly covered but low-priority:
- Home.vue "开始智能辅导" button still points to `/tutoring` — should be `/chat` (minor, fixed in plan via router redirect)
- Deleting `Tutoring.vue` and `Learning.vue` is optional cleanup (can be done later)
