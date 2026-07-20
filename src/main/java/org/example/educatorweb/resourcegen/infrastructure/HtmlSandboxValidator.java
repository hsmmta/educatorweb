package org.example.educatorweb.resourcegen.infrastructure;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Static utility for HTML sandbox validation and CSP injection.
 * Ensures generated HTML pages are self-contained and free of obvious XSS vectors.
 */
public final class HtmlSandboxValidator {

    private HtmlSandboxValidator() { }

    public record ValidationResult(boolean isValid, List<String> issues) {

        public static ValidationResult ok() {
            return new ValidationResult(true, List.of());
        }

        public static ValidationResult withIssues(List<String> issues) {
            return new ValidationResult(false, List.copyOf(issues));
        }
    }

    // Detect external resource / script patterns

    private static final Pattern EXTERNAL_SCRIPT = Pattern.compile(
        "<script\\s[^>]*\\bsrc\\s*=\\s*[\"']https?://", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXTERNAL_LINK = Pattern.compile(
        "<link\\s[^>]*\\bhref\\s*=\\s*[\"']https?://", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXTERNAL_IMG = Pattern.compile(
        "<img\\s[^>]*\\bsrc\\s*=\\s*[\"']https?://", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXTERNAL_IFRAME = Pattern.compile(
        "<iframe\\s[^>]*\\bsrc\\s*=\\s*[\"']https?://", Pattern.CASE_INSENSITIVE);

    // Inline event handler patterns (onXxx= attribute)

    private static final Pattern INLINE_EVENT_HANDLER = Pattern.compile(
        "\\bon\\w+\\s*=", Pattern.CASE_INSENSITIVE);

    // XSS-like patterns

    private static final Pattern JAVASCRIPT_URI = Pattern.compile(
        "javascript\\s*:", Pattern.CASE_INSENSITIVE);
    private static final Pattern EVAL_CALL = Pattern.compile(
        "\\beval\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern INNER_HTML = Pattern.compile(
        "\\.innerHTML\\s*=", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOCUMENT_WRITE = Pattern.compile(
        "document\\.write\\s*\\(", Pattern.CASE_INSENSITIVE);

    /**
     * Validate an HTML string for sandbox compliance.
     * Checks for external resources, inline event handlers, and XSS patterns.
     *
     * @param html the HTML content to validate
     * @return ValidationResult indicating whether the HTML is valid and any issues found
     */
    public static ValidationResult validate(String html) {
        List<String> issues = new ArrayList<>();

        if (html == null || html.isBlank()) {
            return ValidationResult.withIssues(List.of("HTML content is empty or null"));
        }

        if (EXTERNAL_SCRIPT.matcher(html).find()) {
            issues.add("External <script> with src detected — all scripts must be inline");
        }
        if (EXTERNAL_LINK.matcher(html).find()) {
            issues.add("External <link> with href detected — all styles must be inline");
        }
        if (EXTERNAL_IMG.matcher(html).find()) {
            issues.add("External <img> with src detected — use data URIs or inline SVGs");
        }
        if (EXTERNAL_IFRAME.matcher(html).find()) {
            issues.add("External <iframe> with src detected — iframes with external sources are not allowed");
        }
        if (INLINE_EVENT_HANDLER.matcher(html).find()) {
            issues.add("Inline event handler (onXxx=) detected — use addEventListener instead");
        }
        if (JAVASCRIPT_URI.matcher(html).find()) {
            issues.add("javascript: URI detected — this is a potential XSS vector");
        }
        if (EVAL_CALL.matcher(html).find()) {
            issues.add("eval() call detected — eval is a security risk and not allowed");
        }
        if (INNER_HTML.matcher(html).find()) {
            issues.add(".innerHTML assignment detected — prefer textContent or safe DOM methods");
        }
        if (DOCUMENT_WRITE.matcher(html).find()) {
            issues.add("document.write() detected — this is deprecated and unsafe");
        }

        if (issues.isEmpty()) {
            return ValidationResult.ok();
        }
        return ValidationResult.withIssues(issues);
    }

    /**
     * Inject a Content-Security-Policy meta tag into the HTML head.
     * If a <head> tag exists, the meta tag is inserted right after it.
     * If no <head> exists but <html> exists, injects <head> with the meta.
     * Otherwise, wraps the entire content in a basic HTML document.
     *
     * @param html the original HTML content
     * @return HTML with CSP meta tag injected
     */
    public static String injectCSPMeta(String html) {
        String cspMeta = "\n<meta http-equiv=\"Content-Security-Policy\" " +
            "content=\"default-src 'self'; script-src 'self' 'unsafe-inline'; " +
            "style-src 'self' 'unsafe-inline'; img-src 'self' data:; " +
            "frame-src 'none'; object-src 'none'; base-uri 'self';\">";

        if (html.contains("<head>")) {
            return html.replaceFirst("(?i)<head>", "<head>" + cspMeta);
        }

        if (html.contains("<html>")) {
            return html.replaceFirst("(?i)<html>", "<html><head>" + cspMeta + "</head>");
        }

        // Wrap in a proper HTML document
        return "<!DOCTYPE html>\n<html>\n<head>" + cspMeta + "</head>\n<body>\n"
            + html + "\n</body>\n</html>";
    }
}
