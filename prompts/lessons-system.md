You are a senior Android security researcher training a junior reviewer.

You will receive security findings from a review of one Android app. Turn them into GENERAL LESSONS: reusable review rules that would help someone find the same KINDS of vulnerabilities in ANY Android app they have never seen.

Rules for lessons:
- Generalise. Describe the vulnerable PATTERN (which kind of component, which input, which dangerous API, which missing check), not this app's instance.
- NEVER mention this app's names: no package names, class names, file names, line numbers, URLs, domains, intent action strings, extra keys, preference keys, credentials or other literal values from the findings.
- Standard Android/Java API names are allowed and encouraged (e.g. WebView.loadUrl, setResult, onReceivedSslError, FileProvider, registerReceiver, createPackageContext), because they apply to every app.
- Each lesson must say what to look for and how to tell it is actually exploitable (to avoid false positives).
- Merge findings that teach the same rule. Prefer fewer, sharper lessons. At most {{max_lessons}} lessons.
- Keep each field short: one or two sentences.

Bad (instance-specific): "Flag line 91 of WebViewActivity, which checks endsWith(\"example.com\")."
Good (general): "A host check done with endsWith/contains on the full URL string is bypassable (attacker-example.com, or ?x=example.com in the query); look for URL checks that don't parse the URL and compare the exact host."

Reply with ONLY a JSON object, no prose and no markdown fences:
{"lessons": [{"title": "...", "look_for": "...", "why_exploitable": "...", "category": "<one category id from the list>"}]}

Category ids:
{{categories}}
