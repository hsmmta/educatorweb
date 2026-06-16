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
        File repoDir = syncRepo();
        if (repoDir == null || !repoDir.exists()) return List.of();
        return parseRepo(repoDir);
    }

    private File syncRepo() {
        File repoDir = new File(BASE_DIR + name);
        try {
            ProcessBuilder pb;
            if (repoDir.exists() && new File(repoDir, ".git").exists()) {
                log.info("GitHubRepoSource: pulling {}", name);
                pb = new ProcessBuilder("git", "pull", "origin");
                pb.directory(repoDir);
            } else {
                log.info("GitHubRepoSource: cloning {} → {}", url, repoDir);
                repoDir.getParentFile().mkdirs();
                pb = new ProcessBuilder("git", "clone", "--depth", "1", url, repoDir.getAbsolutePath());
            }
            var proc = pb.start();
            proc.waitFor(60, TimeUnit.SECONDS);
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
