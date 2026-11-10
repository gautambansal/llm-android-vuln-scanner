package com.astra.scanner.scan;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a model's text reply into Findings. Tolerant of the usual model quirks:
 * ```json fences, prose before/after the JSON, a bare array instead of {"findings": [...]}.
 */
public final class FindingParser {

    public static final ObjectMapper JSON = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private FindingParser() {}

    public static List<Finding> parse(String text) throws ParseException {
        String json = extractJson(text);
        try {
            JsonNode root = JSON.readTree(json);
            JsonNode list = root.isArray() ? root : root.path("findings");
            if (!list.isArray()) throw new ParseException("no 'findings' array in reply");
            List<Finding> out = new ArrayList<>();
            for (JsonNode n : list) out.add(JSON.treeToValue(n, Finding.class));
            return out;
        } catch (ParseException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseException("invalid JSON: " + e.getMessage());
        }
    }

    /** Take the outermost {...} or [...] block from the reply. */
    static String extractJson(String text) throws ParseException {
        if (text == null || text.isBlank()) throw new ParseException("empty reply");
        int obj = text.indexOf('{'), arr = text.indexOf('[');
        int start = obj < 0 ? arr : (arr < 0 ? obj : Math.min(obj, arr));
        if (start < 0) throw new ParseException("no JSON in reply");
        char close = text.charAt(start) == '{' ? '}' : ']';
        int end = text.lastIndexOf(close);
        if (end <= start) throw new ParseException("unterminated JSON in reply");
        return text.substring(start, end + 1);
    }

    public static class ParseException extends Exception {
        public ParseException(String msg) { super(msg); }
    }
}
