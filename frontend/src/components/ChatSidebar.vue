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
