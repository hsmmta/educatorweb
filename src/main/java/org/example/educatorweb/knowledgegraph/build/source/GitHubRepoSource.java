package org.example.educatorweb.knowledgegraph.build.source;

import org.example.educatorweb.knowledgegraph.build.config.KgBuildProperties.GitHubSource;
import org.example.educatorweb.rag.model.DocumentChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class GitHubRepoSource implements KgSource {

    private static final Logger log = LoggerFactory.getLogger(GitHubRepoSource.class);
    private static final String BASE_DIR = "dev-docs/kg-sources/";

    private final String url;
    private final String name;
    private final String type;

    public GitHubRepoSource(GitHubSource config) {
        this.url = config.getUrl();
        this.name = config.getName();
        this.type = config.getType();
    }

    @Override public String name() { return name; }
    @Override public String type() { return type; }

    @Override
    public List<DocumentChunk> fetch() {
        File repoDir = new File(BASE_DIR + name);
        // If repo already exists locally, skip git and just parse
        if (!repoDir.exists()) {
            repoDir = syncRepo();
            if (repoDir == null || !repoDir.exists()) return List.of();
        }
        return parseRepo(repoDir);
    }

    private File syncRepo() {
        File repoDir = new File(BASE_DIR + name);
        try {
            log.info("GitHubRepoSource: cloning {} → {}", url, repoDir);
            repoDir.getParentFile().mkdirs();
            var pb = new ProcessBuilder("git", "clone", "--depth", "1", url, repoDir.getAbsolutePath());
            var proc = pb.start();
            proc.waitFor(60, TimeUnit.SECONDS);
            if (!repoDir.exists()) {
                log.warn("GitHubRepoSource: clone failed, stderr: {}",
                    new String(proc.getErrorStream().readAllBytes()));
                return null;
            }
            return repoDir;
        } catch (Exception e) {
            log.error("GitHubRepoSource: git operation failed for {}: {}", name, e.getMessage());
            return null;
        }
    }

    private List<DocumentChunk> parseRepo(File repoDir) {
        List<DocumentChunk> chunks = new ArrayList<>();
        File[] entries = repoDir.listFiles(File::isDirectory);
        if (entries == null) return chunks;

        for (File entry : entries) {
            String dirName = entry.getName();
            if (dirName.startsWith(".") || dirName.equals("images") || dirName.equals("docs")
                || dirName.equals("translations") || dirName.equals("pdf")
                || dirName.equals("sketchnotes") || dirName.equals("quiz-app"))
                continue;

            // For awesome-list type: extract GitHub links from README
            if ("awesome-list".equals(type)) {
                File readme = new File(entry, "README.md");
                if (!readme.exists()) readme = new File(repoDir, "README.md");
                if (readme.exists()) {
                    String text = readText(readme);
                    java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("https://github\\.com/([a-zA-Z0-9._-]+/[a-zA-Z0-9._-]+)")
                        .matcher(text);
                    java.util.Set<String> seen = new java.util.HashSet<>();
                    int idx = 0;
                    while (m.find() && idx < 30) {
                        String repo = m.group(1);
                        if (seen.add(repo)) {
                            chunks.add(DocumentChunk.of(
                                name + "/" + repo.replace("/", "_"),
                                name,
                                "awesome-list: " + repo,
                                repo + " — ML resource from awesome list",
                                extractTopic(dirName), 0));
                            idx++;
                        }
                    }
                }
                return chunks;  // awesome-list only has one README at root
            }

            // For code-repo type: parse .py files as algorithm implementations
            if ("code-repo".equals(type)) {
                File[] pyFiles = entry.listFiles((dir, fname) -> fname.endsWith(".py") && !fname.startsWith("__"));
                if (pyFiles != null) {
                    for (File pyFile : pyFiles) {
                        String algoName = pyFile.getName().replace(".py", "").replace("_", " ");
                        chunks.add(DocumentChunk.of(
                            name + "/" + pyFile.getName(),
                            name,
                            dirName + " → " + algoName,
                            algoName + " algorithm implementation (ML-From-Scratch)",
                            extractTopic(dirName), 0));
                    }
                }
                continue;
            }

            File[] lessons = entry.listFiles(File::isDirectory);
            if (lessons != null) {
                for (File lesson : lessons) {
                    File readme = new File(lesson, "README.md");
                    if (readme.exists()) {
                        String text = readText(readme);
                        if (!text.isBlank()) {
                            chunks.add(DocumentChunk.of(
                                name + "/" + lesson.getName(),
                                name,
                                dirName + " → " + lesson.getName(),
                                text.substring(0, Math.min(text.length(), 500)),
                                extractTopic(dirName),
                                0));
                        }
                    }
                }
            }

            File parentReadme = new File(entry, "README.md");
            if (parentReadme.exists()) {
                String text = readText(parentReadme);
                if (!text.isBlank()) {
                    chunks.add(DocumentChunk.of(
                        name + "/" + dirName + "/overview",
                        name, dirName + " overview",
                        text.substring(0, Math.min(text.length(), 500)),
                        extractTopic(dirName), 0));
                }
            }
        }
        log.info("GitHubRepoSource: extracted {} chunks from {}", chunks.size(), name);
        return chunks;
    }

    private String extractTopic(String dirName) {
        return dirName.replaceAll("^\\d+-", "").replace("_", " ").replace("-", " ").toLowerCase();
    }

    private String readText(File file) {
        try { return Files.readString(file.toPath()); }
        catch (IOException e) { log.warn("Cannot read {}: {}", file, e.getMessage()); return ""; }
    }
}
