package org.example.educatorweb.knowledgegraph.build;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.educatorweb.knowledgegraph.build.builder.KgNeo4jWriter;
import org.example.educatorweb.resourcegen.infrastructure.ModelProvider;
import org.neo4j.driver.Session;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

/**
 * Imports knowledge points from the MOOCCube dataset (Tsinghua University).
 *
 * <p>MOOCCube provides:
 * <ul>
 *   <li>entities/concept.json — 114K+ concepts with id, name, en, explanation</li>
 *   <li>entities/course.json — 706 courses with id, name, about</li>
 *   <li>relations/course-concept.json — tab-separated course_id → concept_id</li>
 *   <li>relations/prerequisite-dependency.json — tab-separated A → B (A depends on B)</li>
 *   <li>relations/concept-field.json — concept → field hierarchy</li>
 *   <li>relations/parent-son.json — parent-child concept relationships</li>
 * </ul>
 */
@Component
public class MOOCCubeImporter {

    private static final Logger log = LoggerFactory.getLogger(MOOCCubeImporter.class);

    // Sub-field → our 5-category mapping
    private static final Map<String, String> FIELD_TO_CATEGORY = Map.ofEntries(
        Map.entry("机器学习", "算法"),
        Map.entry("模式识别", "算法"),
        Map.entry("神经网络", "算法"),
        Map.entry("深度学习", "算法"),
        Map.entry("自动推理", "数学基础"),
        Map.entry("人工智能逻辑", "数学基础"),
        Map.entry("知识工程", "应用"),
        Map.entry("计算语言学", "应用"),
        Map.entry("多智能体系统", "应用"),
        Map.entry("机器翻译", "应用"),
        Map.entry("语音识别", "应用"),
        Map.entry("计算机视觉", "应用"),
        Map.entry("自然语言处理", "应用"),
        Map.entry("数据挖掘", "应用"),
        Map.entry("智能机器人", "应用")
    );

    private static final Pattern FIELD_PATTERN = Pattern.compile("学科：(.+?)(?:\\s|$)");
    private static final Pattern DEF_PATTERN = Pattern.compile("定义：(.+?)(?:\\s(?:见载|又称|英文)|$)");

    private final ModelProvider llm;
    private final KgNeo4jWriter writer;
    private final ObjectMapper mapper = com.fasterxml.jackson.databind.json.JsonMapper.builder()
        .enable(com.fasterxml.jackson.core.json.JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
        .build();

    public MOOCCubeImporter(@Qualifier("deepSeekProvider") ModelProvider llm, KgNeo4jWriter writer) {
        this.llm = llm;
        this.writer = writer;
    }

    /**
     * Import ML concepts from a MOOCCube dataset directory.
     *
     * @param dirPath path to the MOOCCube root (contains entities/ and relations/)
     */
    // Maps MOOCCube original concept ID → our slug ID, built during import
    private final Map<String, String> moocIdToSlug = new LinkedHashMap<>();

    public ImportResult importFromDir(String dirPath) throws IOException {
        Path base = Path.of(dirPath);
        moocIdToSlug.clear();

        // Step 0: Pre-load prerequisite concept IDs (to include them in filter)
        Set<String> prereqConceptIds = loadPrereqConceptIds(
            base.resolve("relations/prerequisite-dependency.json"));
        log.info("MOOCCube: {} unique concepts in prerequisite data", prereqConceptIds.size());

        // Step 1: Load concepts (filter for CS/AI/ML + prereq concepts)
        List<ConceptRecord> concepts = loadConcepts(base.resolve("entities/concept.json"),
            prereqConceptIds);
        log.info("MOOCCube: loaded {} CS/AI/ML concepts from {} total",
            concepts.size(), countLines(base.resolve("entities/concept.json")));

        // Step 2: Load prerequisite dependencies
        Map<String, List<String>> prereqMap = loadPrerequisites(
            base.resolve("relations/prerequisite-dependency.json"));
        log.info("MOOCCube: loaded {} prerequisite entries", prereqMap.size());

        // Step 3: Load parent-son hierarchy
        Map<String, List<String>> parentMap = loadParentSon(
            base.resolve("relations/parent-son.json"));

        // Step 4: Load course-concept mappings
        Map<String, Set<String>> courseConcepts = loadCourseConcepts(
            base.resolve("relations/course-concept.json"));
        log.info("MOOCCube: loaded course-concept mappings for {} courses", courseConcepts.size());

        // Step 5: Load courses
        Map<String, CourseRecord> courses = loadCourses(
            base.resolve("entities/course.json"), courseConcepts);
        log.info("MOOCCube: {} ML-relevant courses", courses.size());

        // Step 6: Dedupe against existing Neo4j
        Set<String> existingNames = new HashSet<>();
        try (var s = writer.newSession()) {
            var r = s.run("MATCH (kp:KnowledgePoint) RETURN kp.name AS name");
            while (r.hasNext()) existingNames.add(r.next().get("name").asString(""));
        }

        List<ConceptRecord> newConcepts = new ArrayList<>();
        for (ConceptRecord c : concepts) {
            if (!existingNames.contains(c.name)) newConcepts.add(c);
        }
        log.info("MOOCCube: {} new concepts after dedup ({} existing)",
            newConcepts.size(), concepts.size() - newConcepts.size());

        // Step 7: AI-complete missing fields (skip if too many — would take too long)
        if (newConcepts.size() <= 300) {
            aiCompleteFields(newConcepts);
        } else {
            log.info("MOOCCube: skipping AI completion for {} concepts (too many, would take too long)",
                newConcepts.size());
        }

        // Step 8: Write concepts to Neo4j
        int nodeCount = writeConcepts(newConcepts);

        // Step 9: Write prerequisites from MOOCCube data (prereq + parent-son)
        int edgeCount = writeEdges(newConcepts, prereqMap, parentMap);

        // Step 10: Write courses and BELONGS_TO edges
        int courseCount = writeCourses(courses, newConcepts);

        return new ImportResult(nodeCount, edgeCount,
            String.format("MOOCCube: %d concepts, %d edges, %d courses from %s",
                nodeCount, edgeCount, courseCount, dirPath));
    }

    /** Collect all concept IDs referenced in prerequisite-dependency.json */
    private Set<String> loadPrereqConceptIds(Path path) throws IOException {
        Set<String> ids = new LinkedHashSet<>();
        if (!Files.exists(path)) return ids;
        try (BufferedReader r = Files.newBufferedReader(path)) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] parts = line.split("\t");
                if (parts.length >= 2) {
                    ids.add(parts[0].trim());
                    ids.add(parts[1].trim());
                }
            }
        }
        return ids;
    }

    // ─── Loaders ──────────────────────────────────────────────

    private static final Set<String> CS_KEYWORDS = Set.of(
        "计算机", "人工智能", "机器学习", "模式识别", "数据挖掘", "自然语言处理",
        "软件", "编程", "算法", "信息科学", "电子", "通信", "网络", "数据库",
        "操作系统", "编译", "安全", "密码", "分布式", "并行", "嵌入式"
    );

    private static boolean isCSRelated(String conceptId) {
        for (String kw : CS_KEYWORDS) {
            if (conceptId.contains(kw)) return true;
        }
        return false;
    }

    private List<ConceptRecord> loadConcepts(Path path, Set<String> prereqConceptIds)
            throws IOException {
        List<ConceptRecord> result = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(path)) {
            String line;
            int lineNum = 0;
            while ((line = r.readLine()) != null) {
                lineNum++;
                if (line.isBlank()) continue;
                Map<String, Object> obj;
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parsed = mapper.readValue(line, Map.class);
                    obj = parsed;
                } catch (Exception e) {
                    continue; // skip malformed lines (NaN, etc.)
                }
                String id = (String) obj.get("id");
                String name = (String) obj.get("name");
                String explanation = (String) obj.get("explanation");

                if (id == null || name == null) continue;

                // Filter: CS/AI concepts OR concepts with prerequisite data
                if (!isCSRelated(id) && !prereqConceptIds.contains(id)) continue;

                // Parse explanation
                String field = "", description = "";
                if (explanation != null) {
                    Matcher fm = FIELD_PATTERN.matcher(explanation);
                    if (fm.find()) field = fm.group(1).trim();

                    Matcher dm = DEF_PATTERN.matcher(explanation);
                    if (dm.find()) description = dm.group(1).trim();
                }

                // Map to our category
                String category = "概念"; // default
                if (field != null && !field.isBlank()) {
                    for (var entry : FIELD_TO_CATEGORY.entrySet()) {
                        if (field.contains(entry.getKey())) {
                            category = entry.getValue();
                            break;
                        }
                    }
                }

                // Generate clean slug ID
                String slug = name.replaceAll("[^a-z0-9\\u4e00-\\u9fff]", "_")
                    .replaceAll("^_|_$", "").toLowerCase();
                if (slug.length() > 80) slug = slug.substring(0, 80);

                // Store MOOCCube ID → slug mapping for exact edge matching later
                moocIdToSlug.put(id, slug);

                result.add(new ConceptRecord(slug, name, category, 3, description));
            }
        }
        return result;
    }

    private Map<String, List<String>> loadPrerequisites(Path path) throws IOException {
        Map<String, List<String>> map = new LinkedHashMap<>();
        if (!Files.exists(path)) return map;
        try (BufferedReader r = Files.newBufferedReader(path)) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] parts = line.split("\t");
                if (parts.length >= 2) {
                    map.computeIfAbsent(parts[0].trim(), k -> new ArrayList<>())
                        .add(parts[1].trim());
                }
            }
        }
        return map;
    }

    private Map<String, List<String>> loadParentSon(Path path) throws IOException {
        return loadPrerequisites(path); // same tab-separated format
    }

    private Map<String, Set<String>> loadCourseConcepts(Path path) throws IOException {
        Map<String, Set<String>> map = new LinkedHashMap<>();
        if (!Files.exists(path)) return map;
        try (BufferedReader r = Files.newBufferedReader(path)) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] parts = line.split("\t");
                if (parts.length >= 2) {
                    map.computeIfAbsent(parts[0].trim(), k -> new LinkedHashSet<>())
                        .add(parts[1].trim());
                }
            }
        }
        return map;
    }

    private Map<String, CourseRecord> loadCourses(Path path,
                                                   Map<String, Set<String>> courseConcepts)
            throws IOException {
        Map<String, CourseRecord> map = new LinkedHashMap<>();
        if (!Files.exists(path)) return map;

        // Concept ID → name lookup
        Map<String, String> conceptNames = new LinkedHashMap<>();
        try (var s = writer.newSession()) {
            var r = s.run("MATCH (kp:KnowledgePoint) RETURN kp.id AS id, kp.name AS name");
            while (r.hasNext()) {
                var rec = r.next();
                conceptNames.put(rec.get("id").asString(""), rec.get("name").asString(""));
            }
        }

        try (BufferedReader r = Files.newBufferedReader(path)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                Map<String, Object> obj;
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parsed = mapper.readValue(line, Map.class);
                    obj = parsed;
                } catch (Exception e) {
                    continue; // skip malformed lines
                }
                String cid = (String) obj.get("id");
                String cname = (String) obj.get("name");
                if (cid == null || cname == null) continue;

                // Only courses that have ML concepts
                Set<String> conceptIds = courseConcepts.getOrDefault(cid, Set.of());
                if (conceptIds.isEmpty()) continue;

                // Check if any concept is in our imported list
                boolean hasML = false;
                for (String cpid : conceptIds) {
                    if (cpid.contains("计算机科学技术") || cpid.contains("人工智能")
                        || cpid.contains("机器学习") || cpid.contains("数据挖掘")) {
                        hasML = true;
                        break;
                    }
                }
                if (!hasML) continue;

                map.put(cid, new CourseRecord(cid, cname, conceptIds));
            }
        }
        return map;
    }

    // ─── AI completion ────────────────────────────────────────

    private void aiCompleteFields(List<ConceptRecord> records) {
        List<ConceptRecord> needsCompletion = records.stream()
            .filter(r -> r.category.equals("概念") || r.description.isBlank())
            .collect(Collectors.toList());

        if (needsCompletion.isEmpty()) return;

        int batchSize = 10;
        for (int i = 0; i < needsCompletion.size(); i += batchSize) {
            int end = Math.min(i + batchSize, needsCompletion.size());
            aiCompleteBatch(needsCompletion.subList(i, end));
        }
    }

    private void aiCompleteBatch(List<ConceptRecord> batch) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < batch.size(); i++) {
            var r = batch.get(i);
            sb.append(String.format(
                "{\"i\":%d,\"name\":\"%s\",\"category\":\"%s\",\"description\":\"%s\"}\n",
                i, r.name, r.category, r.description));
        }

        String prompt = """
            Complete these ML knowledge points. For each:
            - category must be one of: 数学基础, 概念, 算法, 应用, 工具
            - description: one Chinese sentence explaining the concept
            - Keep existing values if already correct.

            Records:
            %s

            Output ONLY a JSON array, no markdown:
            [{"i":0,"category":"算法","description":"..."}, ...]
            """.formatted(sb);

        try {
            String response = llm.chat(prompt);
            if (response == null || response.isBlank()) return;
            response = response.replaceAll("```json\\s*", "").replaceAll("```\\s*$", "").trim();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> completed = mapper.readValue(response, List.class);
            for (var c : completed) {
                int idx = ((Number) c.get("i")).intValue();
                if (idx >= 0 && idx < batch.size()) {
                    var orig = batch.get(idx);
                    if (c.get("category") != null && !orig.category.equals("概念"))
                        orig.category = c.get("category").toString();
                    if (c.get("description") != null && orig.description.isBlank())
                        orig.description = c.get("description").toString();
                }
            }
        } catch (Exception e) {
            log.warn("MOOCCube: AI batch failed: {}", e.getMessage());
        }
    }

    // ─── Neo4j writes ─────────────────────────────────────────

    private int writeConcepts(List<ConceptRecord> records) {
        int count = 0;
        try (Session s = writer.newSession()) {
            for (var r : records) {
                s.run("""
                    MERGE (n:KnowledgePoint {id: $id})
                    SET n.name = $name, n.category = $cat,
                        n.difficulty = $diff, n.description = $desc
                    """,
                    Map.of("id", r.id, "name", r.name, "cat", r.category,
                        "diff", (long) r.difficulty, "desc", r.description));
                count++;
            }
        }
        log.info("MOOCCube: wrote {} concepts", count);
        return count;
    }

    private int writeEdges(List<ConceptRecord> records,
                            Map<String, List<String>> prereqMap,
                            Map<String, List<String>> parentMap) {
        int count = 0;
        // Build slug set for fast lookup
        Set<String> slugSet = new LinkedHashSet<>();
        Map<String, String> slugToName = new LinkedHashMap<>();
        for (var r : records) {
            slugSet.add(r.id);
            slugToName.put(r.id, r.name);
        }

        try (Session s = writer.newSession()) {

            // ── Step 1: REQUIRES from prerequisite-dependency.json ──
            // prereqMap: prereqConceptId → [list of concepts it depends on]
            // Meaning: A → B means "A depends on B" (B is prerequisite of A)
            for (var entry : prereqMap.entrySet()) {
                String moocA = entry.getKey();              // the dependent concept
                String slugA = moocIdToSlug.get(moocA);    // our slug
                if (slugA == null || !slugSet.contains(slugA)) continue;

                for (String moocB : entry.getValue()) {    // the prerequisite
                    String slugB = moocIdToSlug.get(moocB);
                    if (slugB == null || !slugSet.contains(slugB)) continue;
                    if (slugA.equals(slugB)) continue;

                    // A REQUIRES B (B is prerequisite of A)
                    s.run("MATCH (a:KnowledgePoint {id:$a}), (b:KnowledgePoint {id:$b}) " +
                          "MERGE (a)-[:REQUIRES]->(b)",
                        Map.of("a", slugA, "b", slugB));
                    count++;
                }
            }

            // ── Step 2: REQUIRES from parent-son.json ──
            // parent-son: parentId → [child concepts]
            // Meaning: parent is broader, child is narrower
            // For knowledge: child REQUIRES parent (need broad concept before specific)
            for (var entry : parentMap.entrySet()) {
                String moocParent = entry.getKey();
                String slugParent = moocIdToSlug.get(moocParent);
                if (slugParent == null || !slugSet.contains(slugParent)) continue;

                for (String moocChild : entry.getValue()) {
                    String slugChild = moocIdToSlug.get(moocChild);
                    if (slugChild == null || !slugSet.contains(slugChild)) continue;
                    if (slugParent.equals(slugChild)) continue;

                    // Child REQUIRES Parent
                    s.run("MATCH (a:KnowledgePoint {id:$child}), (b:KnowledgePoint {id:$parent}) " +
                          "MERGE (a)-[:REQUIRES]->(b)",
                        Map.of("child", slugChild, "parent", slugParent));
                    count++;
                }
            }
        }
        log.info("MOOCCube: wrote {} REQUIRES edges (prereq + parent-son)", count);
        return count;
    }

    private int writeCourses(Map<String, CourseRecord> courses,
                              List<ConceptRecord> concepts) {
        int count = 0;
        // Build name→id index
        Map<String, String> nameToId = new LinkedHashMap<>();
        for (var c : concepts) nameToId.put(c.name, c.id);

        try (Session s = writer.newSession()) {
            for (var entry : courses.entrySet()) {
                CourseRecord cr = entry.getValue();
                String courseSlug = "mooccube_" + cr.id.replaceAll("[^a-zA-Z0-9_\\u4e00-\\u9fff]", "_")
                    .replaceAll("__+", "_").replaceAll("^_|_$", "");
                if (courseSlug.length() > 200) courseSlug = courseSlug.substring(0, 200);

                // Create course node
                s.run("""
                    MERGE (c:Course {id: $id})
                    SET c.name = $name, c.institution = 'MOOCCube',
                        c.type = '理论', c.rating = 4.0,
                        c.description = 'MOOCCube课程'
                    """, Map.of("id", courseSlug, "name", cr.name));

                // Link concepts to this course
                for (String cpid : cr.conceptIds) {
                    for (var c : concepts) {
                        if (cpid.contains(c.name) || c.name.contains(cpid)) {
                            s.run("MATCH (kp:KnowledgePoint {id:$kpid}), (co:Course {id:$cid}) " +
                                  "MERGE (kp)-[:BELONGS_TO]->(co) " +
                                  "MERGE (co)-[:CONTAINS]->(kp)",
                                Map.of("kpid", c.id, "cid", courseSlug));
                        }
                    }
                }
                count++;
            }
        }
        log.info("MOOCCube: wrote {} courses with concept links", count);
        return count;
    }

    // ─── Helpers ──────────────────────────────────────────────

    private long countLines(Path path) throws IOException {
        if (!Files.exists(path)) return 0;
        try (var lines = Files.lines(path)) {
            return lines.count();
        }
    }

    // ─── Internal records ─────────────────────────────────────

    static class ConceptRecord {
        String id, name, category, description;
        int difficulty;
        ConceptRecord(String id, String name, String category, int difficulty, String description) {
            this.id = id; this.name = name; this.category = category;
            this.difficulty = difficulty; this.description = description;
        }
    }

    record CourseRecord(String id, String name, Set<String> conceptIds) {}

    public record ImportResult(int nodeCount, int edgeCount, String message) {}
}
