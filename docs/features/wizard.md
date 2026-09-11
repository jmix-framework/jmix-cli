# Wizard

The wizard collects project name, repository, Jmix version, template, package,
project ID, theme, locales, add-ons, location, and a setup checklist (Git
repository and Agent Toolkit, both checked by default). Flags and
template metadata can resolve or hide steps. Back navigation returns to the
previous prompted step and preserves answers that remain valid.

## Interaction

- A title, stage count, and progress bar track five phases: General,
  Localization, Add-ons, Location and setup, and Finishing up. The indicator follows back
  navigation and skips questions resolved by flags or template defaults.
  It tracks wizard phases, not download percentages or elapsed time.
- Downloads and other slow steps (version list, template jar, add-on catalog,
  generation, Agent Toolkit, JDK) run behind a one-line `StatusReporter`
  indicator: a spinner, a label naming the host or phase, and a bar with byte
  counts when the size is known. Narrow terminals omit the bar and shorten the
  label to keep actual progress visible. Interactive terminals redraw the line in place
  and remove it when the step ends; piped output prints one plain line per phase.
- Raw terminals use one alternate screen with a welcome logo, prior choices,
  the current prompt, and keyboard hints. Layout adapts to terminal size.
- Short terminals reduce progress to a title or hide it to preserve the prompt,
  focused entry, and keyboard hints. Line-input consoles print `Step 2/5` once
  per phase; non-interactive generation has no progress header.
- Lists use arrows to move, Space to toggle, Enter to confirm, Esc to go back,
  and `q` to quit. Text prompts use Ctrl+Q to quit; plain `q` is ordinary input.
  Quit shortcuts also accept the Russian-layout `й/Й` equivalents, including
  Ctrl+Й when the terminal reports Ctrl. Plain `й` remains text in prompts and search.
- [Add-on selection](add-ons.md) supports `/` search. Enter leaves search editing;
  another Enter confirms the selection. Ctrl+U clears the query, Esc leaves
  search editing before navigating back, and Ctrl+Q quits while editing.
- Without raw terminal support, use numbered lists and line input. Searchable
  lists accept `/query`, comma-separated toggle numbers, Enter to confirm,
  `<` to go back, and `q` to quit. Preserve locked and hidden selections.
- Locale choices default to English, list Russian second, and allow custom codes.
  Matching translation add-ons are suggested in the next step and remain optional.
- The setup checklist offers Git initialization and the Agent Toolkit together.
  `--no-git` and `--no-agents-toolkit` remove an entry; when both are decided the
  step is skipped. Without raw mode each entry becomes a yes/no question;
  completed answers are preserved when navigating back.
- Location choices include a project subdirectory, the current directory,
  `~/IdeaProjects/<name>`, and a custom path with completion.

## Code

- [NewCommand.kt](../../src/main/kotlin/io/jmix/cli/NewCommand.kt) — step state and transitions.
- [Prompts.kt](../../src/main/kotlin/io/jmix/cli/wizard/Prompts.kt) — rendering, input, and fallbacks.
- [StatusReporter.kt](../../src/main/kotlin/io/jmix/cli/wizard/StatusReporter.kt) — activity indicator for slow steps.
- [wizard](../../src/main/kotlin/io/jmix/cli/wizard) — banner, validation, and path completion.

## Verification and demo

Run `./gradlew test --tests 'io.jmix.cli.wizard.*'` and exercise the real CLI in
both terminal modes. Cover back/forward navigation, English/Russian quit shortcuts,
text and search containing `q` and `й`, selection retention, template-included add-ons
omitted, and narrow/resized terminals.

The README GIF uses real CLI checkpoints captured by [demo.tape](../demo.tape).
[render-demo.sh](../render-demo.sh) keeps the CLI's own progress bar, aligns the
add-on panel, and blends transitions and the loop boundary. This excludes transient
terminal redraws while retaining the actual choices and generation result.
Show the filled project info between the welcome and add-on selection so the
demo represents the wizard's setup steps. Keep pauses long enough to read;
keep search and keyboard hints in place.

Capture and rendering commands are in the tape header. Rendering requires
FFmpeg. Validate with
`vhs validate docs/demo.tape` and `bash -n docs/render-demo.sh`, then inspect
the animation at the README's displayed width.
