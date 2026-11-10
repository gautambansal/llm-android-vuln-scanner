# astra-scanner

A static Android vulnerability scanner that measures how much a cheap open model (the **student**) improves when a strong hosted model (the **teacher**) turns its findings into general lessons.

```
APK ─► ingest ─► teacher scan ─► lessons (1 call + leakage check)
          │                              │
          ├─► student scan (baseline) ───┤
          └─► student scan + lessons  ◄──┘ ─► match vs ground truth (or teacher) ─► recall / precision / lift
```

Built for v0.1: a working end-to-end slice, honest measurement, easy to extend. Not polished.

## Quick start

Requirements: Java 21+, Maven, [jadx](https://github.com/skylot/jadx) on the `PATH` (`brew install jadx`).

```bash
cp .env.example .env              # add the API keys for the models you use
mvn -q package                    # builds target/astra-scanner.jar and runs the unit tests
java -jar target/astra-scanner.jar check-models     # ping every configured model
java -jar target/astra-scanner.jar run --repeats 2  # the whole pipeline on the APK in config.yaml
```

`run` does: teacher scan → lessons → N × (student baseline, student + lessons, interleaved) → report. Every scan, lesson set and report is saved under `work/<apk-name>-<sha256>/`.

## Scanning a new app

1. Set `app.apk_path` in `config.yaml`.
2. Remove `app.ground_truth`, or point it at a ground-truth file for that app (format: `ground_truth/insecureshop.yaml`).
3. `java -jar target/astra-scanner.jar run`

Nothing in the code is specific to InsecureShop. Without ground truth, scans are scored against the teacher's findings ("agreement with the teacher": how much of the teacher's work the student reproduces). That's the distillation target, but not true recall: it inherits the teacher's blind spots.

## Adding a model

Any OpenAI-compatible API (Groq, Gemini, OpenAI, Together, vLLM, Ollama, ...) is a config entry only:

```yaml
models:
  my-model:
    provider: openai_compatible
    base_url: https://api.example.com/v1
    model: some-model-name
    api_key_env: EXAMPLE_API_KEY      # the NAME of the env var, never the key
    max_tokens: 4000
    tokens_per_minute: 8000           # optional client-side rate limits
    requests_per_minute: 30
    max_chunk_tokens: 3000            # optional: how much code this model reads per call
roles:
  student: my-model
```

A vendor with its own API needs one class implementing `LlmClient` plus one line in `LlmClientFactory.PROVIDERS` (see `AnthropicLlmClient`).

## Commands

| Command | What it does |
|---|---|
| `run [--repeats N]` | Everything end to end |
| `check-models` | Sends a tiny prompt to every configured model |
| `ingest` | Decompile, filter, chunk; saves `ingest/attack-surface.txt` and every chunk exactly as the models see it |
| `scan --role teacher\|student [--model m] [--label l] [--lessons file]` | One scan |
| `learn --from <teacher-scan> [--label l]` | Teacher findings → general lessons (one call) → leakage check |
| `evaluate [--scans a,b] [--reference <scan>]` | Scores scans; aggregates repeats per condition; writes `reports/*.md` |

All commands accept `--config <path>` (default `config.yaml`). Config, prompts (`prompts/`) and ground truth are read at runtime: no rebuild needed to change them.

## How it works

**Ingest** (`ingest/`): jadx (cached by APK hash) → manifest parsed into an attack surface (exported components with the *effective* exported value, permissions and their protection levels, security flags) → the app's own code found from the manifest package plus same-organisation component packages (fallback for obfuscated apps: all non-library code) → generated files dropped, Kotlin `@Metadata` blobs trimmed (line numbers preserved) → selected resources (`strings.xml`, `res/xml/*`) → line-numbered chunks. Chunk size is per model.

**Scan** (`scan/`): the same code runs teacher, student and student + lessons. Parallel model calls (virtual threads, bounded), JSON findings (`category, severity, file, start_line, end_line, evidence, explanation`), then:
- **Evidence check**: the verbatim quote must exist in the cited file (lines and paths corrected if they're off; otherwise the finding is dropped and counted as hallucinated).
- **Dedupe**: same file + category + nearby lines.

**Lessons** (`lessons/`): one teacher call turns its verified findings into general rules. A leakage check, built automatically from the app (package, class names, distinctive string literals, line references), drops any lesson that names something app-specific.

**Matching** (`eval/`): deterministic. A finding is compatible with a ground-truth entry if its category is one of the entry's categories, its file is one of the entry's locations, and its lines overlap (± tolerance). Recall uses a maximum one-to-one assignment (Kuhn's algorithm; exact-line matches first), so a finding can't claim two bugs and the score doesn't depend on order. Precision counts findings: a second instance of a found bug is a correct duplicate. Strict precision treats unknown findings as wrong; lenient precision also accepts listed extras. Near-misses (right place, different category) are listed for human review.

**Reliability**: rate limiting and retries are decorators around `LlmClient` (`RateLimitedLlmClient`, `RetryingLlmClient`); retries are visible in the log; a failed chunk is recorded, never silently skipped.

## Tests

`mvn test` runs the unit tests for the evidence validator, the matcher and the leakage checker. Most cases come from real failures seen while building this.

## Limitations

- One app can't show that lessons generalise. Lessons learned and tested on the same app are optimistic; a held-out app is needed.
- InsecureShop is public and probably in the models' training data, so its baselines are high.
- Static only. Native code, React Native/Flutter bundles and obfuscated apps are handled poorly or not at all.
- Token counts are estimated (~4 chars per token).
