package com.astra.scanner.lessons;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** One general review rule written by the teacher. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Lesson(String title, String lookFor, String whyExploitable, String category) {

    /** How the lesson is shown to the student. */
    public String render(int n) {
        return n + ". " + title + "\n   Look for: " + lookFor + "\n   Exploitable when: " + whyExploitable;
    }

    public String allText() {
        return String.join(" ", String.valueOf(title), String.valueOf(lookFor), String.valueOf(whyExploitable));
    }
}
