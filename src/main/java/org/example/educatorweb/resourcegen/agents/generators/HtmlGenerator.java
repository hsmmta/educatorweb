package org.example.educatorweb.resourcegen.agents.generators;

import org.example.educatorweb.common.model.ResourceType;
import org.example.educatorweb.knowledgegraph.model.KnowledgeContext;
import org.example.educatorweb.profile.model.StudentProfile;
import org.example.educatorweb.resourcegen.infrastructure.HtmlSandboxValidator;
import org.example.educatorweb.resourcegen.infrastructure.HtmlSandboxValidator.ValidationResult;
import org.example.educatorweb.resourcegen.infrastructure.SandboxTemplate;
import org.example.educatorweb.resourcegen.model.GenerationState;
import org.example.educatorweb.resourcegen.model.ResourceBlueprint;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * Generator for interactive HTML resources.
 * Generates self-contained HTML pages with embedded CSS/JS, validates them
 * against sandbox constraints, and injects Content-Security-Policy.
 */
@Component
public class HtmlGenerator extends AbstractGenerator {

    public HtmlGenerator(ChatClient chatClient) {
        super(chatClient, ResourceType.HTML);
    }

    @Override
    protected String doGenerate(GenerationState state) {
        String prompt = buildPrompt(state);
        log.info("HtmlGenerator: sending prompt to LLM for topic={} (prompt length={})",
            state.knowledgePoint(), prompt.length());

        String response = chatClient.prompt().user(prompt).call().content();
        log.info("HtmlGenerator: received LLM response (length={})",
            response != null ? response.length() : 0);

        if (response == null || response.isBlank()) {
            log.warn("HtmlGenerator: empty response from LLM, using fallback");
            return buildFallbackHtml(state);
        }

        return postProcess(response);
    }

    @Override
    protected String getFormatHint() {
        return "HTML page";
    }

    /**
     * Post-process the LLM output: strip code fences, inject CSP, validate.
     */
    private String postProcess(String raw) {
        // 1. Strip code fences
        String html = stripCodeFences(raw);

        // 2. Inject Content-Security-Policy meta tag
        html = HtmlSandboxValidator.injectCSPMeta(html);

        // 3. Validate
        ValidationResult validation = HtmlSandboxValidator.validate(html);
        log.info("HtmlGenerator: validation result: valid={} issues={}",
            validation.isValid(), validation.issues().size());

        // 4. If validation failed, embed issues as HTML comments at the top
        if (!validation.isValid()) {
            html = embedValidationIssues(html, validation);
        }

        return html;
    }

    private String stripCodeFences(String content) {
        String cleaned = content.trim();
        if (cleaned.startsWith("```html")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        return cleaned.trim();
    }

    private String embedValidationIssues(String html, ValidationResult validation) {
        StringBuilder comment = new StringBuilder();
        comment.append("<!--\n");
        comment.append("  Sandbox Validation Warnings:\n");
        for (String issue : validation.issues()) {
            comment.append("  - ").append(issue).append("\n");
        }
        comment.append("  These issues should be fixed for a fully compliant resource.\n");
        comment.append("-->\n");

        // Insert after <head> if present, otherwise at the very beginning
        if (html.contains("<head>")) {
            int headClose = html.indexOf("</head>");
            if (headClose > 0) {
                return html.substring(0, headClose) + "\n" + comment
                    + html.substring(headClose);
            }
            int headOpen = html.indexOf("<head>");
            return html.substring(0, headOpen + 6) + "\n" + comment
                + html.substring(headOpen + 6);
        }

        return comment + html;
    }

    private String buildPrompt(GenerationState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一位资深的交互式教育网页设计师，擅长创建美观、交互性强的教学网页。\n\n");

        // --- Student profile ---
        sb.append("## 学生画像\n");
        StudentProfile profile = state.profile();
        if (profile != null) {
            sb.append("- 知识基础: ").append(profile.knowledgeBase() != null
                ? profile.knowledgeBase().level() : "未评估").append("\n");
            sb.append("- 认知风格: ").append(profile.cognitiveStyle() != null
                ? profile.cognitiveStyle().type() : "未评估").append("\n");
            if (profile.errorPattern() != null && profile.errorPattern().tags() != null
                    && !profile.errorPattern().tags().isEmpty()) {
                sb.append("- 常见错误模式: ").append(profile.errorPattern().tags()).append("\n");
            }
            sb.append("- 学习节奏: ").append(profile.learningPace() != null
                ? profile.learningPace().type() : "未评估").append("\n");
            sb.append("- 内容偏好: ").append(profile.contentPreference() != null
                ? profile.contentPreference().type() : "未评估").append("\n");
            sb.append("- 学习目标: ").append(profile.goalOrientation() != null
                ? profile.goalOrientation().type() : "未评估").append("\n");
        } else {
            sb.append("（无可用画像数据，请使用通用风格和中等难度）\n");
        }

        // --- Knowledge graph context ---
        sb.append("\n## 知识点背景\n");
        KnowledgeContext kg = state.knowledgeContext();
        if (kg != null) {
            if (!kg.prerequisites().isEmpty()) {
                sb.append("- 前置知识: ").append(String.join(", ", kg.prerequisites())).append("\n");
            }
            if (!kg.successors().isEmpty()) {
                sb.append("- 后续知识: ").append(String.join(", ", kg.successors())).append("\n");
            }
            if (!kg.relatedConcepts().isEmpty()) {
                sb.append("- 相关概念: ").append(String.join(", ", kg.relatedConcepts())).append("\n");
            }
            sb.append("- 难度级别: ").append(kg.difficultyLevel()).append(" / 5\n");
        } else {
            sb.append("（无可用图谱数据）\n");
        }

        // --- Blueprint sections ---
        sb.append("\n## 内容大纲\n");
        ResourceBlueprint blueprint = state.blueprint();
        if (blueprint != null && blueprint.sections() != null && !blueprint.sections().isEmpty()) {
            sb.append("请围绕以下大纲设计网页：\n\n");
            for (ResourceBlueprint.BlueprintSection section : blueprint.sections()) {
                sb.append("- ").append(section.heading()).append(": ").append(section.keyPoints()).append("\n");
            }
        } else {
            sb.append("请围绕知识点「").append(state.knowledgePoint()).append("」自行设计网页内容。\n");
        }

        // --- Knowledge point ---
        sb.append("\n## 目标知识点\n");
        sb.append(state.knowledgePoint()).append("\n");

        // --- Sandbox constraints ---
        sb.append("\n");
        sb.append(SandboxTemplate.availableFeatures());
        sb.append("\n");

        // --- Output requirements ---
        sb.append("## 输出要求\n\n");
        sb.append("请生成一个完整的、交互式的、可用于教学的单页面 HTML 文件。要求如下：\n\n");
        sb.append("### 结构要求\n");
        sb.append("- 包含完整的 <html>, <head>, <body> 结构\n");
        sb.append("- 所有 CSS 写在 <style> 标签中\n");
        sb.append("- 所有 JavaScript 写在 <script> 标签中\n");
        sb.append("- 使用现代、美观的配色方案\n\n");

        sb.append("### 内容要求\n");
        sb.append("- 包含该知识点的核心概念讲解\n");
        sb.append("- 包含可视化的示意图或动画（纯 CSS/Canvas 实现）\n");
        sb.append("- 包含交互式的练习或测验（可以是选择题、拖拽、匹配等形式）\n");
        sb.append("- 包含即时反馈机制（正确/错误的视觉提示）\n");
        sb.append("- 响应式设计，在桌面端和移动端都能正常显示\n\n");

        sb.append("### 安全约束（非常重要！）\n");
        sb.append("- **禁止**使用外部资源（无外部 CSS、JS、图片、字体）\n");
        sb.append("- **禁止**使用 inline event handler（onclick/onload 等），必须使用 addEventListener\n");
        sb.append("- **禁止**使用 eval()、document.write()、.innerHTML = \n");
        sb.append("- **禁止**使用 javascript: 伪协议\n");
        sb.append("- 所有图片使用 SVG 或 CSS 绘制，不使用外部图片 URL\n");
        sb.append("- 所有数据都内嵌在脚本中，不发起网络请求\n\n");

        sb.append("### 代码质量\n");
        sb.append("- 代码要有清晰的注释\n");
        sb.append("- 不使用任何第三方库或框架\n");
        sb.append("- 使用语义化 HTML 标签\n\n");

        sb.append("请直接输出完整的 HTML 代码，不要包含代码块标记（```html），不要包含解释性前言或后记。");

        return sb.toString();
    }

    private String buildFallbackHtml(GenerationState state) {
        String topic = state.knowledgePoint();
        String escapedTopic = topic.replace("<", "&lt;").replace(">", "&gt;");

        return String.format("""
            <!--
              Sandbox Validation Warnings:
              - LLM 生成失败，这是备用内容
              These issues should be fixed for a fully compliant resource.
            -->
            <!DOCTYPE html>
            <html>
            <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <meta http-equiv="Content-Security-Policy" content="default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-src 'none'; object-src 'none'; base-uri 'self';">
            <title>%1$s - 交互式学习</title>
            <style>
              * { margin: 0; padding: 0; box-sizing: border-box; }
              body { font-family: system-ui, -apple-system, sans-serif; background: #f0f4f8; color: #334155; min-height: 100vh; display: flex; justify-content: center; align-items: center; padding: 20px; }
              .container { max-width: 700px; width: 100%%; background: #fff; border-radius: 16px; padding: 40px; box-shadow: 0 4px 24px rgba(0,0,0,0.08); }
              h1 { font-size: 28px; color: #1e293b; margin-bottom: 16px; }
              p { font-size: 16px; line-height: 1.6; color: #64748b; margin-bottom: 12px; }
              .note { background: #fef3c7; border-left: 4px solid #f59e0b; padding: 12px 16px; border-radius: 8px; margin-top: 20px; font-size: 14px; color: #92400e; }
            </style>
            </head>
            <body>
            <div class="container">
              <h1>%1$s</h1>
              <p>欢迎学习「%1$s」。本页面是备用内容，因为 LLM 生成失败。</p>
              <p>请稍后重试，或联系管理员获取更完整的教学资源。</p>
              <div class="note">
                <strong>提示：</strong>这是自动生成的备用页面。真实的教学页面将包含交互式的演示和练习。
              </div>
            </div>
            </body>
            </html>""", escapedTopic);
    }
}
