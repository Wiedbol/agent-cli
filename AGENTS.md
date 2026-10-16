# Repository Guidelines

## Project Structure & Module Organization

`src/main/java/com/agent/` holds the CLI (`Main`, `CodingAgentCli`), the Lab01 local responder (`Agent`), and console encoding (`ConsoleStreams`). Sub-packages:

- `roles/` — the six collaborating roles, one file each: `UserRole`, `LlmRole`, `RegistrarRole`, `GuardRole`, `ExecutorRole`, `ControllerRole`.
- `llm/` — `DeepSeekClient` plus protocol records (`Message`, `ToolCall`, `LLMResponse`, `LLMClient`, `ApiKeyLoader`).
- `session/` — chat persistence: `SessionStore`, `SessionRecorder`, `SessionPaths`, `Redactor`, `SessionExporter`.
- `tool/` — the unified `Tool` contract and the five tools (`read_file`, `write_file`, `list_files`, `search_code`, `run_command`).

Tests mirror this layout under `src/test/java/com/agent/`. Reports, `HANDOFF.md`, `docs/`, and `.agent/sessions/` are intentionally git-ignored.

## Build, Test, and Development Commands

```powershell
mvn clean test                                                  # 208 offline tests; must be green before committing
mvn -B -DskipTests package                                      # fat jar -> target/agent-cli-0.1.0.jar
java '-Dagent.encoding=UTF-8' -jar target/agent-cli-0.1.0.jar   # REPL; auto-resumes the last session
java -jar target/agent-cli-0.1.0.jar --local                    # Lab01 echo mode, no API key needed
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\verify-deepseek.ps1   # live AT01–AT07 checks
```

PowerShell splits an unquoted `-D…` argument — always quote it.

## Coding Style & Naming Conventions

Java 17 (`--release 17`), UTF-8, 4-space indentation; no formatter or linter is configured. Types are `UpperCamelCase`, methods `lowerCamelCase`, one role or tool per file. Keep Javadoc in Chinese and explain *why*. New tools must declare `name()`, `description()`, `parameters()`, `risk()`, and `pathArguments()` — the guard trusts those declarations. Route console I/O through `ConsoleStreams` and emit `/`-separated paths.

## Testing Guidelines

JUnit 5 with `@TempDir` fixtures; classes end in `Test`, methods read `shouldDoSomething()`. Every test must run offline: use `FakeLLMClient` for agent loops and `MockWebServer` for HTTP. Never let a test hit the real DeepSeek API or write outside its temp directory.

## Commit & Pull Request Guidelines

Follow the history's prefixes — `lab03:`, `v4:`, `feat:`, `docs:` — with `-` bullets describing motivation and verification. Only commit with `mvn clean test` green, and stage explicit paths (`git add src README.md`) rather than `git add -A`. Pull requests should state motivation, list verification commands, and attach terminal screenshots for CLI-visible changes.

## Security & Configuration Tips

`DEEPSEEK_API_KEY` lives in `.env`, which is ignored — never commit it. `--yes` skips guard confirmations — use it only for trusted tasks. The guard blocks path escapes, writes to `.env`/`.git`, and destructive commands; sensitive matches are redacted on disk only, so memory keeps the original text. PowerShell scripts must be saved as UTF-8 **with BOM**.
