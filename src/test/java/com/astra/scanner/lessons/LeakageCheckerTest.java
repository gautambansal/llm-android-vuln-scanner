package com.astra.scanner.lessons;

import com.astra.scanner.ingest.SourceFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeakageCheckerTest {

    private final LeakageChecker checker = new LeakageChecker("com.shopapp", List.of(
            new SourceFile("sources/com/shopapp/Util.java", SourceFile.Kind.CODE,
                    "class Util { String u = \"admin_user\"; String a = \"com.shopapp.action.OPEN\"; String s = \"android.intent.extra.STREAM\"; }"),
            new SourceFile("sources/com/shopapp/WebViewActivity.java", SourceFile.Kind.CODE, "class WebViewActivity {}"),
            new SourceFile("sources/com/shopapp/Prefs.java", SourceFile.Kind.CODE, "class Prefs {}"),
            new SourceFile("resources/res/values/strings.xml", SourceFile.Kind.RESOURCE,
                    "<string name=\"pool\">\"us-east-1:7e94-42af\"</string>")));

    private static Lesson lesson(String text) {
        return new Lesson("t", text, "w", "other");
    }

    private boolean leaks(String text) {
        return !checker.check(List.of(lesson(text))).isEmpty();
    }

    @Test void generalRuleWithAndroidApisIsClean() {
        assertTrue(!leaks("Exported activities that pass a Parcelable Intent extra to startActivity enable intent redirection."));
        assertTrue(!leaks("Data printed with android.util.Log is readable via adb logcat."));   // real false positive we hit
        assertTrue(!leaks("Credentials stored in SharedPreferences are unencrypted."));       // "Prefs" must not fire inside
        assertTrue(!leaks("Check android.intent.extra.STREAM URIs before copying files."));   // platform constant
    }

    @Test void appSpecificNamesAreBlocked() {
        assertTrue(leaks("WebViewActivity loads any URL."));                // app class
        assertTrue(leaks("The Util class holds the password."));            // app class, standalone
        assertTrue(leaks("Anything mentioning shopapp is specific."));      // package segment
        assertTrue(leaks("Send com.shopapp.action.OPEN to trigger it."));   // app action string
        assertTrue(leaks("The password admin_user is hardcoded."));         // app literal
        assertTrue(leaks("The pool us-east-1:7e94-42af is exposed."));      // resource literal
    }

    @Test void lineReferencesAreBlocked() {
        assertTrue(leaks("Flag line 91 when you see this."));
        assertTrue(leaks("See Foo.java:91."));
    }

    @Test void reportsWhichTermsLeaked() {
        var leak = checker.check(List.of(lesson("WebViewActivity and Prefs are bad"))).get(0);
        assertEquals(List.of("Prefs", "WebViewActivity"), leak.terms());
    }
}
