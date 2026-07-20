# 答题评估提示 + 错题集 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add quiz assessment loading indicator + persistent wrong answer collection with review/redo support

**Architecture:** Two independent features. Feature A is frontend-only (loading toast in Chat.vue). Feature B adds a new MySQL table + JPA entity, 3 REST endpoints, and a new UI section in Profile.vue. Redo is purely client-side (no proficiency impact).

**Tech Stack:** Vue 3 + Element Plus (frontend), Spring Boot 3.4 + JPA + MySQL (backend)

---

## File Map

| Action | File | Responsibility |
|--------|------|----------------|
| Create | `src/.../learninglog/model/WrongAnswer.java` | JPA Entity |
| Create | `src/.../learninglog/repository/WrongAnswerRepository.java` | JPA Repo |
| Modify | `src/.../profile/controller/QuizSubmitController.java` | +3 REST endpoints |
| Modify | `frontend/src/views/Chat.vue` | assess loading bar + wrong answer submit |
| Modify | `frontend/src/views/Profile.vue` | 错题集 UI section |

---

### Task 1: WrongAnswer JPA Entity + Repository

**Files:**
- Create: `src/main/java/org/example/educatorweb/learninglog/model/WrongAnswer.java`
- Create: `src/main/java/org/example/educatorweb/learninglog/repository/WrongAnswerRepository.java`

- [ ] **Step 1: Create the entity**

```java
package org.example.educatorweb.learninglog.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "wrong_answer_book")
public class WrongAnswer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "student_id", length = 64, nullable = false)
    private String studentId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String question;

    @Column(columnDefinition = "JSON", nullable = false)
    private String options;

    @Column(name = "user_answer", length = 10, nullable = false)
    private String userAnswer;

    @Column(name = "correct_answer", length = 10, nullable = false)
    private String correctAnswer;

    @Column(name = "knowledge_point", length = 256)
    private String knowledgePoint;

    @Column(name = "quiz_title", length = 256)
    private String quizTitle;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public WrongAnswer() {}

    public WrongAnswer(String studentId, String question, String options,
                       String userAnswer, String correctAnswer,
                       String knowledgePoint, String quizTitle) {
        this.studentId = studentId;
        this.question = question;
        this.options = options;
        this.userAnswer = userAnswer;
        this.correctAnswer = correctAnswer;
        this.knowledgePoint = knowledgePoint;
        this.quizTitle = quizTitle;
    }

    @PrePersist
    void prePersist() {
        if (this.createdAt == null) this.createdAt = LocalDateTime.now();
    }

    // getters
    public Long getId() { return id; }
    public String getStudentId() { return studentId; }
    public String getQuestion() { return question; }
    public String getOptions() { return options; }
    public String getUserAnswer() { return userAnswer; }
    public String getCorrectAnswer() { return correctAnswer; }
    public String getKnowledgePoint() { return knowledgePoint; }
    public String getQuizTitle() { return quizTitle; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    // setters
    public void setId(Long id) { this.id = id; }
    public void setStudentId(String studentId) { this.studentId = studentId; }
    public void setQuestion(String question) { this.question = question; }
    public void setOptions(String options) { this.options = options; }
    public void setUserAnswer(String userAnswer) { this.userAnswer = userAnswer; }
    public void setCorrectAnswer(String correctAnswer) { this.correctAnswer = correctAnswer; }
    public void setKnowledgePoint(String knowledgePoint) { this.knowledgePoint = knowledgePoint; }
    public void setQuizTitle(String quizTitle) { this.quizTitle = quizTitle; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
```

- [ ] **Step 2: Create the repository**

```java
package org.example.educatorweb.learninglog.repository;

import org.example.educatorweb.learninglog.model.WrongAnswer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WrongAnswerRepository extends JpaRepository<WrongAnswer, Long> {

    List<WrongAnswer> findByStudentIdOrderByCreatedAtDesc(String studentId);

    void deleteByStudentId(String studentId);
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/example/educatorweb/learninglog/model/WrongAnswer.java src/main/java/org/example/educatorweb/learninglog/repository/WrongAnswerRepository.java
git commit -m "feat: add WrongAnswer entity and repository for wrong answer collection"
```

---

### Task 2: QuizSubmitController — Add wrong answer endpoints

**Files:**
- Modify: `src/main/java/org/example/educatorweb/profile/controller/QuizSubmitController.java`

- [ ] **Step 1: Inject WrongAnswerRepository**

Add to imports:
```java
import org.example.educatorweb.learninglog.model.WrongAnswer;
import org.example.educatorweb.learninglog.repository.WrongAnswerRepository;
```

Add field:
```java
private final WrongAnswerRepository wrongAnswerRepo;
```

Update constructor to include the new parameter:
```java
public QuizSubmitController(ProficiencyService proficiencyService,
                             ProfileUpdateTrigger profileUpdateTrigger,
                             KnowledgeGraphService kgService,
                             ProfileService profileService,
                             OpenAiChatModel chatModel,
                             WrongAnswerRepository wrongAnswerRepo) {
    this.proficiencyService = proficiencyService;
    this.profileUpdateTrigger = profileUpdateTrigger;
    this.kgService = kgService;
    this.profileService = profileService;
    this.chatModel = chatModel;
    this.wrongAnswerRepo = wrongAnswerRepo;
}
```

- [ ] **Step 2: Add POST /wrong-answer endpoint**

Add after the existing `updateErrorPatterns` method:
```java
/**
 * Save a wrong answer to the wrong answer collection.
 * POST /api/quiz/wrong-answer
 */
@PostMapping("/wrong-answer")
public ResponseResult<Map<String, Object>> saveWrongAnswer(@RequestBody WrongAnswerRequest request) {
    try {
        String optionsJson = "[]";
        if (request.options() != null) {
            try {
                optionsJson = objectMapper.writeValueAsString(request.options());
            } catch (Exception e) {
                log.warn("QuizSubmit: failed to serialize options: {}", e.getMessage());
            }
        }
        WrongAnswer wa = new WrongAnswer(
            request.studentId(), request.question(), optionsJson,
            request.userAnswer(), request.correctAnswer(),
            request.knowledgePoint(), request.quizTitle()
        );
        wrongAnswerRepo.save(wa);
        log.info("QuizSubmit: wrong answer saved for student={}, id={}", request.studentId(), wa.getId());
        return ResponseResult.success(Map.of("id", wa.getId()));
    } catch (Exception e) {
        log.error("QuizSubmit: failed to save wrong answer: {}", e.getMessage());
        return ResponseResult.error("保存错题失败");
    }
}

/**
 * Get all wrong answers for a student, newest first.
 * GET /api/quiz/wrong-answers/{studentId}
 */
@GetMapping("/wrong-answers/{studentId}")
public ResponseResult<List<Map<String, Object>>> getWrongAnswers(@PathVariable String studentId) {
    List<WrongAnswer> list = wrongAnswerRepo.findByStudentIdOrderByCreatedAtDesc(studentId);
    List<Map<String, Object>> result = list.stream().map(wa -> {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", wa.getId());
        m.put("question", wa.getQuestion());
        m.put("options", parseJsonArray(wa.getOptions()));
        m.put("userAnswer", wa.getUserAnswer());
        m.put("correctAnswer", wa.getCorrectAnswer());
        m.put("knowledgePoint", wa.getKnowledgePoint());
        m.put("quizTitle", wa.getQuizTitle());
        m.put("createdAt", wa.getCreatedAt() != null ? wa.getCreatedAt().toString() : null);
        return m;
    }).toList();
    return ResponseResult.success(result);
}

/** DELETE /api/quiz/wrong-answers/{studentId} */
@DeleteMapping("/wrong-answers/{studentId}")
public ResponseResult<String> clearWrongAnswers(@PathVariable String studentId) {
    wrongAnswerRepo.deleteByStudentId(studentId);
    return ResponseResult.success("cleared");
}

private List<String> parseJsonArray(String json) {
    if (json == null || json.isBlank()) return List.of();
    try {
        @SuppressWarnings("unchecked")
        List<String> arr = objectMapper.readValue(json, List.class);
        return arr;
    } catch (Exception e) { return List.of(); }
}

/** Request body for saving wrong answer. */
public record WrongAnswerRequest(
    String studentId, String question, List<String> options,
    String userAnswer, String correctAnswer,
    String knowledgePoint, String quizTitle
) {}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/example/educatorweb/profile/controller/QuizSubmitController.java
git commit -m "feat: add wrong answer save/list/clear endpoints to QuizSubmitController"
```

---

### Task 3: Chat.vue — Assessment loading indicator + wrong answer submission

**Files:**
- Modify: `frontend/src/views/Chat.vue`

- [ ] **Step 1: Add assessing ref**

In the `<script setup>` section, add after the `loading` ref (around line 322):
```javascript
const assessing = ref(false)  // true while quiz answer is being evaluated
```

- [ ] **Step 2: Update submitQuizAnswer to set assessing + save wrong answers**

Replace the existing `submitQuizAnswer` function:
```javascript
const submitQuizAnswer = async (msg, qIndex, q, correct) => {
  assessing.value = true
  try {
    const primaryConcept = lockedTopicName.value || msg.title || ''
    await request.post('/quiz/submit', {
      studentId: getStudentId(),
      knowledgePoint: primaryConcept,
      results: [{
        questionIndex: qIndex,
        correct: correct,
        relatedConcept: q.relatedConcept || primaryConcept
      }]
    })
    // Save wrong answer to collection
    if (!correct) {
      try {
        const userLetter = msg.selectedOption[qIndex] || ''
        await request.post('/quiz/wrong-answer', {
          studentId: getStudentId(),
          question: q.question,
          options: q.options || [],
          userAnswer: userLetter,
          correctAnswer: q.answer || '',
          knowledgePoint: primaryConcept,
          quizTitle: msg.title || ''
        })
      } catch (e) {
        console.warn('Wrong answer save failed:', e.message)
      }
    }
  } catch (e) {
    console.warn('Quiz submit failed:', e.message)
  } finally {
    assessing.value = false
  }
}
```

- [ ] **Step 3: Add assessBar UI in template**

Add after the quiz options `<ul>` block, before the closing of the resource section (around line 195):
```html
<!-- Assessment loading bar -->
<transition name="assess-fade">
  <div v-if="assessing" class="assess-bar">
    <span class="assess-spin">🧠</span> 正在评估你的答题表现...
  </div>
</transition>
```

- [ ] **Step 4: Add CSS for assess-bar**

Add at end of `<style scoped>`:
```css
.assess-bar {
  display: flex; align-items: center; gap: 8px; justify-content: center;
  padding: 10px 16px; margin-top: 10px; border-radius: 12px;
  background: linear-gradient(135deg, rgba(102,126,234,0.08), rgba(118,75,162,0.05));
  font-size: 13px; color: #667eea; font-weight: 500;
}
.assess-spin {
  display: inline-block; font-size: 18px;
  animation: assess-pulse 1.2s ease-in-out infinite;
}
@keyframes assess-pulse {
  0%, 100% { transform: scale(1); opacity: 0.6; }
  50% { transform: scale(1.15); opacity: 1; }
}
.assess-fade-enter-active { transition: opacity 0.3s; }
.assess-fade-leave-active { transition: opacity 0.5s; }
.assess-fade-enter-from, .assess-fade-leave-to { opacity: 0; }
```

- [ ] **Step 5: Commit**

```bash
git add frontend/src/views/Chat.vue
git commit -m "feat: add quiz assessment loading indicator and wrong answer submission"
```

---

### Task 4: Profile.vue — 错题集 UI section

**Files:**
- Modify: `frontend/src/views/Profile.vue`

- [ ] **Step 1: Add wrongAnswers ref + load function**

In `<script setup>`, add after the existing `weakPoints` ref (around line 285):
```javascript
const wrongAnswers = ref([])
const wrongShowAnswer = ref({})   // per-card: { [id]: true/false }
const wrongRedone = ref({})       // per-card: { [id]: true/false }

const loadWrongAnswers = async () => {
  try {
    const res = await request.get('/quiz/wrong-answers/' + getStudentId())
    wrongAnswers.value = (res.data?.data || []).slice(0, 50)
  } catch { wrongAnswers.value = [] }
}

const toggleWrongAnswer = (id) => {
  wrongShowAnswer.value = { ...wrongShowAnswer.value, [id]: !wrongShowAnswer.value[id] }
}

const redoWrongAnswer = (item, optLetter) => {
  correctRedoAnswer.value = { ...correctRedoAnswer.value, [item.id + '|' + optLetter]: optLetter }
  const isCorrect = optLetter.toUpperCase() === (item.correctAnswer || '').trim().toUpperCase()
  redoResult.value = { ...redoResult.value, [item.id]: isCorrect ? 'correct' : 'incorrect' }
  if (isCorrect) {
    wrongRedone.value = { ...wrongRedone.value, [item.id]: true }
  }
}

const correctRedoAnswer = ref({})
const redoResult = ref({})
```

Add `loadWrongAnswers()` call in `loadReport()` after `loadSavedPathData()` (around line 464):
```javascript
loadSavedPathData()
loadWrongAnswers()
```

- [ ] **Step 2: Add 错题集 template**

Add after the tips-panel closing, before the learning path summary (find the `<!-- 学习路径摘要 -->` section around line 217):
```html
<!-- 错题集 -->
<section class="section" v-if="wrongAnswers.length">
  <h3>📝 错题集 ({{ wrongAnswers.length }} 道)</h3>
  <div class="wrong-list">
    <div v-for="item in wrongAnswers" :key="item.id" class="wrong-card">
      <div class="wrong-q">
        <span class="wrong-num">#{{ item.id }}</span>
        <span class="wrong-text">{{ item.question }}</span>
      </div>
      <div class="wrong-opts" v-if="item.options && item.options.length">
        <span
          v-for="(opt, oi) in item.options" :key="oi"
          :class="[
            'wrong-opt',
            { 'opt-chosen': item.userAnswer === optionLetter(opt) && wrongShowAnswer[item.id] !== false },
            { 'opt-right': wrongShowAnswer[item.id] !== false && isCorrectOpt(item, opt) },
            { 'redo-selected': correctRedoAnswer[item.id + '|' + optionLetter(opt)] },
            { 'redo-correct': redoResult[item.id] === 'correct' && correctRedoAnswer[item.id + '|' + optionLetter(opt)] },
            { 'redo-wrong': redoResult[item.id] === 'incorrect' && correctRedoAnswer[item.id + '|' + optionLetter(opt)] }
          ]"
          @click="wrongShowAnswered[item.id] === false && redoWrongAnswer(item, optionLetter(opt))"
        >
          {{ opt }}
        </span>
      </div>
      <div class="wrong-meta">
        <span>来源: {{ item.quizTitle || item.knowledgePoint || '未知' }}</span>
        <span v-if="wrongRedone[item.id]" class="wrong-redone-tag">✓ 已重做</span>
      </div>
      <div class="wrong-actions">
        <el-button size="small" text @click="toggleWrongAnswer(item.id)">
          {{ wrongShowAnswer[item.id] !== false ? '🙈 不看答案重做' : '👁 显示答案' }}
        </el-button>
      </div>
    </div>
  </div>
</section>
```

- [ ] **Step 3: Add helper functions for options**

In `<script setup>`, add helper functions:
```javascript
const optionLetter = (optText) => {
  if (!optText) return ''
  const m = optText.match(/^([A-Z])[.)]/)
  return m ? m[1] : ''
}

const isCorrectOpt = (item, opt) => {
  return optionLetter(opt).toUpperCase() === (item.correctAnswer || '').trim().toUpperCase()
}
```

Note: `optionLetter` may already exist in the file — if so, reuse; if not, add it.

- [ ] **Step 4: Fix redo toggle variable name**

The template uses `wrongShowAnswered` but the ref is `wrongShowAnswer`. Fix the template `@click` handler and cleanup references to use consistent naming. The ref defined in Step 1 is `wrongShowAnswer`, use `wrongShowAnswer[item.id]` consistently in the template.

- [ ] **Step 5: Add CSS for wrong answer cards**

Add at end of `<style scoped>`:
```css
/* ---- wrong answers ---- */
.wrong-list { display: flex; flex-direction: column; gap: 12px; }
.wrong-card {
  background: #fff; border-radius: 14px; padding: 18px 20px;
  box-shadow: 0 1px 4px rgba(0,0,0,0.04); border: 1px solid #f0f2f5;
  transition: all 0.15s;
}
.wrong-card:hover { border-color: #d0d5dd; box-shadow: 0 2px 8px rgba(0,0,0,0.06); }
.wrong-q { display: flex; gap: 10px; margin-bottom: 10px; align-items: flex-start; }
.wrong-num { font-size: 11px; color: #c0c4cc; font-weight: 700; flex-shrink: 0; padding-top: 2px; }
.wrong-text { font-size: 14px; font-weight: 500; color: #1a1a2e; line-height: 1.5; }

.wrong-opts { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 10px; }
.wrong-opt {
  padding: 5px 12px; border-radius: 8px; font-size: 12px;
  background: #f8f9fe; border: 1px solid #eef0f4; color: #4a4f5e;
  transition: all 0.12s;
}
.wrong-opt.opt-chosen { background: #fef0f0; border-color: #f56c6c; color: #dc2626; }
.wrong-opt.opt-right { background: #dcfce7; border-color: #22c55e; color: #15803d; }
.wrong-opt.redo-selected { border-color: #667eea; background: #eef0ff; }
.wrong-opt.redo-correct { background: #dcfce7; border-color: #22c55e; color: #15803d; }
.wrong-opt.redo-wrong { background: #fef0f0; border-color: #f56c6c; color: #dc2626; }

.wrong-meta { display: flex; gap: 12px; font-size: 11px; color: #909399; margin-bottom: 6px; }
.wrong-redone-tag { color: #22c55e; font-weight: 600; }
.wrong-actions { display: flex; justify-content: flex-end; }

.wrong-opt { cursor: default; }
.wrong-opt.redo-selected { cursor: default; }
```

- [ ] **Step 6: Commit**

```bash
git add frontend/src/views/Profile.vue
git commit -m "feat: add wrong answer collection UI to Profile page"
```

---

## Self-Review

1. **Spec coverage**: 
   - Feature A (loading indicator): Task 3 covers ✅
   - Feature B (wrong answer collection): Tasks 1, 2, 3, 4 cover ✅
   - API endpoints: Task 2 covers POST, GET, DELETE ✅
   - Redo without proficiency impact: Task 4 redo is client-side only ✅
   - Cards persist: no deletion logic ✅

2. **Placeholder scan**: No TBDs, TODOs, or vague instructions. All code is complete. ✅

3. **Type consistency**: `optionLetter()` referenced in Task 4 — may conflict if Chat.vue already has it exported. Task 4 note handles this. API response shape matches frontend expectations. ✅
