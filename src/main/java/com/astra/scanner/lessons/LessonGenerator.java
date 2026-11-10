package com.astra.scanner.lessons;

import com.astra.scanner.config.AppConfig;
import com.astra.scanner.llm.LlmClient;
import com.astra.scanner.llm.LlmException;
import com.astra.scanner.llm.LlmResponse;
import com.astra.scanner.scan.Finding;
import com.astra.scanner.scan.FindingParser;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Teacher findings -> general lessons, in ONE model call (as the assignment asks).
 * Leaky lessons are dropped (not silently rewritten) and reported, so the result stays auditable.
 */
public final class LessonGenerator {

    public record Result(List<Lesson> kept, List<Lesson> rejected, List<LeakageChecker.Leak> leaks,
                         long inputTokens, long outputTokens) {
        /** The text pasted into the student's prompt. */
        public String renderForStudent() {
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < kept.size(); i++) lines.add(kept.get(i).render(i + 1));
            return String.join("\n", lines);
        }
    }

    private final AppConfig config;
    private final String systemTemplate;
    private final String userTemplate;

    public LessonGenerator(AppConfig config) throws IOException {
        this.config = config;
        this.systemTemplate = Files.readString(config.scan().promptsDir().resolve("lessons-system.md"));
        this.userTemplate = Files.readString(config.scan().promptsDir().resolve("lessons-user.md"));
    }

    public Result generate(List<Finding> teacherFindings, LlmClient teacher, LeakageChecker checker)
            throws LlmException, FindingParser.ParseException {
        String categories = config.scan().categories().keySet().stream().collect(Collectors.joining(", "));
        String system = systemTemplate
                .replace("{{max_lessons}}", String.valueOf(config.lessons().maxLessons()))
                .replace("{{categories}}", categories);
        String user = userTemplate.replace("{{findings}}", describe(teacherFindings));

        LlmResponse r = teacher.complete(system, user);
        List<Lesson> all = parse(r.text());

        List<LeakageChecker.Leak> leaks = checker.check(all);
        var leakyIdx = leaks.stream().map(LeakageChecker.Leak::lessonIndex).collect(Collectors.toSet());
        List<Lesson> kept = new ArrayList<>(), rejected = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) (leakyIdx.contains(i) ? rejected : kept).add(all.get(i));
        return new Result(kept, rejected, leaks, r.inputTokens(), r.outputTokens());
    }

    /** What the teacher sees about its own findings: kind, why, and the code pattern. */
    private static String describe(List<Finding> findings) {
        var sb = new StringBuilder();
        int n = 1;
        for (Finding f : findings) {
            sb.append(n++).append(". [").append(f.category()).append(", ").append(f.severity()).append("] ")
              .append(f.title()).append("\n   Why: ").append(f.explanation())
              .append("\n   Code: ").append(f.evidence() == null ? "" : f.evidence().strip().replace("\n", " ")).append("\n");
        }
        return sb.toString();
    }

    private static List<Lesson> parse(String text) throws FindingParser.ParseException {
        try {
            JsonNode root = FindingParser.JSON.readTree(extract(text));
            JsonNode list = root.isArray() ? root : root.path("lessons");
            List<Lesson> out = new ArrayList<>();
            for (JsonNode n : list) out.add(FindingParser.JSON.treeToValue(n, Lesson.class));
            if (out.isEmpty()) throw new FindingParser.ParseException("no lessons in reply");
            return out;
        } catch (FindingParser.ParseException e) {
            throw e;
        } catch (Exception e) {
            throw new FindingParser.ParseException("invalid lessons JSON: " + e.getMessage());
        }
    }

    private static String extract(String text) throws FindingParser.ParseException {
        int s = text.indexOf('{'), e = text.lastIndexOf('}');
        if (s < 0 || e <= s) throw new FindingParser.ParseException("no JSON object in reply");
        return text.substring(s, e + 1);
    }
}
