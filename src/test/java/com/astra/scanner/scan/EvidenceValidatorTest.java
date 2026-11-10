package com.astra.scanner.scan;

import com.astra.scanner.ingest.SourceFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.astra.scanner.scan.Finding.Verification.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Cases taken from real model output. A buggy validator silently deletes real findings and lowers recall. */
class EvidenceValidatorTest {

    private static final SourceFile XML = new SourceFile("resources/res/xml/provider_paths.xml", SourceFile.Kind.RESOURCE, """
            <?xml version="1.0" encoding="utf-8"?>
            <paths xmlns:android="http://schemas.android.com/apk/res/android">
                <root-path
                    name="root"
                    path="/"/>
            </paths>""");

    private static final SourceFile JAVA = new SourceFile("sources/com/app/LoginActivity.java", SourceFile.Kind.CODE, """
            package com.app;
            class LoginActivity {
                void load() {
                    Context packageContext = createPackageContext(packageName, 3);
                    Object value = packageContext.getClassLoader().loadClass("x.Main").getMethod("get").invoke(null, this);
                    Intrinsics.checkExpressionValueIsNotNull(value, "packageContext.classLoad…      .invoke(null, this)");
                    Log.d("login", "user=" + user + " pass=" + password);
                }
            }""");

    private final EvidenceValidator v = new EvidenceValidator(List.of(XML, JAVA), 5, 12);

    private static Finding finding(String file, int line, String evidence) {
        return new Finding("t", "other", "high", file, line, line, evidence, "e", null, null, null);
    }

    @Test void shortXmlLinesStillVerify() {
        var f = finding("resources/res/xml/provider_paths.xml", 3, "    <root-path\n        name=\"root\"\n        path=\"/\"/>");
        assertEquals(VERIFIED, v.validate(f).verification());
    }

    @Test void xmlQuotedOnOneLineMatchesAcrossLines() {
        var f = finding("resources/res/xml/provider_paths.xml", 3, "<root-path name=\"root\" path=\"/\"/>");
        assertEquals(VERIFIED, v.validate(f).verification());
    }

    @Test void jadxEllipsisInsideLineVerifies() {
        var f = finding("sources/com/app/LoginActivity.java", 4, """
                Context packageContext = createPackageContext(packageName, 3);
                Intrinsics.checkExpressionValueIsNotNull(value, "packageContext.classLoad…      .invoke(null, this)");""");
        assertEquals(VERIFIED, v.validate(f).verification());
    }

    @Test void wrongLineIsCorrected() {
        var r = v.validate(finding("sources/com/app/LoginActivity.java", 90, "Log.d(\"login\", \"user=\" + user + \" pass=\" + password);"));
        assertEquals(LINE_CORRECTED, r.verification());
        assertEquals(7, r.startLine());
    }

    @Test void shortFileNameAndWrongFileAreResolved() {
        assertEquals(VERIFIED, v.validate(finding("LoginActivity.java", 7, "Log.d(\"login\", \"user=\" + user")).verification());
        var r = v.validate(finding("resources/res/xml/provider_paths.xml", 7, "Log.d(\"login\", \"user=\" + user"));
        assertEquals(FILE_CORRECTED, r.verification());
        assertEquals("sources/com/app/LoginActivity.java", r.file());
    }

    @Test void inventedCodeIsRejected() {
        assertEquals(NOT_FOUND, v.validate(finding("LoginActivity.java", 5, "webView.loadUrl(getIntent().getStringExtra(\"url\"));")).verification());
    }

    @Test void trivialQuoteIsRejected() {
        assertEquals(NOT_FOUND, v.validate(finding("LoginActivity.java", 8, "}")).verification());
    }

    @Test void paraphraseIsRejected() {
        assertEquals(NOT_FOUND, v.validate(finding("LoginActivity.java", 7, "logs the user's password to logcat")).verification());
    }
}
