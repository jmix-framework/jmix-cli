# Test suite audit — 2026-11-02

Baseline: `8a0889c`, macOS ARM64, JDK 25, JaCoCo 0.8.16. Production code was
unchanged. The audit combined source and assertion review, JUnit XML timings,
per-test execution data, and eight controlled faults in an isolated temporary copy.

## Result

The network-enabled suite decreased from **403 to 388 test invocations**; both
runs passed with no skipped tests. Local runs decreased from 402 to 387 entries,
including four disabled integration-test entries. JUnit counts a disabled
parameterized method once; enabling the two Toolkit versions accounts for the
one-entry difference between local and network-enabled counts.

Coverage is identical before and after, including the union of individual
production bytecode probes, not just rounded percentages:

| Scope | Covered lines | Covered branches |
|---|---:|---:|
| Local suite | 3,083 / 4,071 (75.7%) | 2,107 / 3,216 (65.5%) |
| With `JMIX_CLI_IT=true` | 3,200 / 4,071 (78.6%) | 2,142 / 3,216 (66.6%) |

Instruction, method, class, and complexity counters also remained identical.
Coverage measures execution, not the correctness of assertions; it was used to
check the changes, not to select tests for deletion automatically.

## Changes

| Test class | Invocations before → after | What changed |
|---|---:|---|
| `StoreClientTest` | 27 → 24 | Removed weaker copies of refresh/retry checks; retained bearer headers, persisted refresh tokens and retry counts in the stronger scenarios. Moved the empty HTTP 200 generate response into the existing empty-response matrix. |
| `TokenClientTest` | 16 → 14 | Kept provider-secret checks in the existing rejected-code scenario. Removed a check primarily testing the fake server's unmatched-route behavior; real request methods, form encoding, and HTTP failures remain checked. |
| `ProjectGeneratorTest` | 12 → 4 | Check complete rendered output in one generation, including paths, globals, locales, exclusions, wrapper bytes and permissions. Check phases in the rendering and Git-staging scenarios. Four opaque Gradle log fragments now share one failed-wrapper run; they never represented four real authentication exchanges. |
| `SelfUpdaterTest` | 34 → 32 | One successful installation checks the new launcher, command target, previous version, completeness marker, phase sequence and byte progress. |

This removes repeated server setup, six repeated successful project generations,
three failed-wrapper processes and two release extractions. Separate failure
scenarios and independently meaningful assertions remain separate.

The four Gradle error fragments are all still asserted. Neither production
generation nor the updater branches on their text. The installer tests still
exercise the actual packaged command independently.

## Effectiveness check

Both the original and reduced suites rejected all eight controlled faults:

1. Reusing a rejected access token instead of refreshing it.
2. Refreshing on every access instead of caching the token.
3. Ignoring Store `errorCode` fields in successful HTTP responses.
4. Including a malformed Store response body in the error message.
5. Truncating fractional or overflowing Store date numbers.
6. Initializing/staging Git before add-on resolution succeeds.
7. Emitting template metadata into the generated project.
8. Omitting the installed-release completeness marker.

Clean controls passed before both fault runs. Faults were applied only in a
temporary repository copy, and each required a JUnit assertion failure, not a
compilation failure. This is a focused regression check, **not** a full mutation
score or proof that every possible defect is detected.

## Coverage findings

| Area | Line coverage, network-enabled run | Assessment |
|---|---:|---|
| Account | 383 / 394 (97.2%) | Strong local HTTP and persistence checks. Redirect rejection, secret redaction, malformed input, session rotation and conflict choices were retained. |
| Wizard | 928 / 957 (97.0%) | Raw, carriage-return and numbered consoles, resize, search, EOF/back/quit exercise different paths. Similar-looking UI tests are usually useful. |
| Add-ons | 136 / 137 (99.3%) | Compatibility, UI-stack filtering, commercial entries and translations have distinct contracts. |
| Generator | 412 / 446 (92.4%) | Output and failure boundaries remain checked with fewer repeated generations. |
| `NewCommand.kt` | 157 / 576 (27.3%) | Main priority: durable end-to-end creation scenarios for wizard state, options, add-on installation and premium credentials. Helper tests and backing services do not cover their complete orchestration. CI smoke generation checks the basic application path, not this matrix. |
| `ProjectInteraction.kt` | 67 / 124 (54.0%) | Add deterministic process-stub tests for selected module working directory, development `JAVA_HOME`, IDE root and launch failures. Current persistent tests mainly cover selection and command construction. |

`Main.kt`, `UpdateCommand.kt` and Windows console setup show zero coverage in
the instrumented parent JVM. This must **not** be interpreted as no testing:
`tests/test-install.sh` and `tests/test-install.ps1` run the packaged CLI, check
account command registration and `jdk.httpserver`, and exercise update success
and readable failure. Those child processes are outside this JaCoCo session.
Windows-specific behavior also cannot be measured by a macOS run.

Further reductions should not remove timeout, archive traversal/corruption,
cross-version, cross-platform or terminal-mode cases merely because their probes
overlap. Arguments and expected results can distinguish defects on the same code
path. A monolithic test replacing unrelated scenarios would weaken diagnosis.

## Cost and next priorities

With coverage disabled, three measured runs after one warm-up per variant gave
a median of **3.583 → 2.198 seconds** of JUnit execution for the four changed
classes; Gradle wall time was **4.939 → 3.504 seconds**. Both variants used the
same isolated checkout and dependency caches. These numbers describe the affected
classes on this machine, not the entire suite or CI performance.

The baseline network run spent about 87 seconds downloading/installing a real
JDK, 11 seconds testing JDK discovery, and 6 seconds installing both Toolkit
branches. Download time varies; reducing small local tests does not solve that
cost. The unresponsive-launcher test deliberately checks the real ten-second
timeout. Both Toolkit versions and OS archive formats are meaningful coverage.

Prioritize full `new` workflow tests and launch-process assertions over further
count reduction. If CI latency becomes a problem, expose the expensive network
checks as an explicit scheduled/job tier while preserving platform coverage;
do not silently drop them from required validation. For ongoing coverage,
collect parent and packaged-child results on each OS before setting thresholds.

## Evidence and limits

- `./gradlew build` and the network-enabled build passed.
- Both local and network-enabled JaCoCo comparisons lost zero production probes.
- All eight faults were detected before and after the reduction.
- The shell installer tests passed against the packaged CLI.
- Audit XML, HTML, per-test probes, mutation logs and measurement scripts are in
  local `build/test-audit/`; `clean` removes these generated artifacts. The audit
  used a local Gradle init script and did not add a permanent agent or dependency
  to ordinary builds.
- This audit ran on macOS. It did not repeat real account SSO or commercial Maven
  access, or execute the PowerShell installer. Existing CI/manual evidence is
  distinct from this run.

JaCoCo tool/version compatibility and Kotlin filtering were checked against its
[official change history](https://www.jacoco.org/jacoco/trunk/doc/changes.html).
