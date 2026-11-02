# Wizard

Running `jmix` without a command opens the command menu, in this order:

1. `new` — create a new project.
2. `trial` — download the Enterprise+BPM trial project.
3. `login` — sign in, or `logout` — sign out when a local session is saved.
4. `update` — update the CLI.

Descriptions come from the commands' help text. The menu checks only the saved
session; it makes no account request. An expired session can therefore still show
`logout` until a command detects the expiry and clears it. Direct command calls
and `--help` bypass the menu, and both `login` and `logout` remain available directly.
Arrow keys and Enter work in raw terminals; other consoles accept a number.
Enter selects `new`. `q` (also `й`) quits; EOF before a choice runs nothing.
After `login` or `logout`, the menu reappears with fresh account details and the
corresponding account command. A failed account action shows an error in the menu
and lets you choose again. Other commands run once and exit. Direct `jmix login`
and `jmix logout` remain one-shot commands; `jmix new` starts the project wizard directly.

The Jmix logo and CLI version belong to the main menu. The top-right account block
uses a header with the region, a name/status row, and an email row when both name
and email are available. Its rows share a left edge inside the right-aligned block.
Wide terminals place it beside the logo; narrow terminals stack it above the artwork.
Signed-out, email-only, name-only and older sessions use two rows, without blanks
or duplicated email. Older sessions without
profile details show `Signed in` and the region until the next sign-in or token refresh.
The account name (or email when there is no name) is bold green, email is gray,
and region is cyan. `Not signed in` is bold yellow. With `NO_COLOR`, the same
information stays readable as plain text. Cropping happens before styling, so
narrow consoles never split color escape sequences.
Short terminals omit the logo first, keeping the account block with the commands.
Each account row is clipped independently. The wordmark stays visible and static;
only the colored logo reveals from left to right once on first opening, over 600 ms. The menu accepts
keys during the reveal. It stays static in plain/legacy consoles, CI, `TERM=dumb`,
or when `NO_COLOR` is set, and does not replay after sign-in or sign-out.

The wizard collects project name, repository, Jmix version, template, package,
project ID, theme, locales, add-ons, location, and a setup checklist (Git
repository and Agent Toolkit, both checked by default). Flags and
template metadata can resolve or hide steps. Back navigation returns to the
previous prompted step and preserves answers that remain valid.
Going back from the first editable step returns to the main menu instead of
quitting: Esc in raw terminals, `<` in line-input consoles. This works for both
menu-launched creation and direct `jmix new`. Reopening creation starts a fresh
draft; templates opened during a cancelled run are closed before returning home.

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
- Raw terminals use one alternate screen with prior choices,
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
- Commercial add-on credential conflicts offer keeping the existing settings,
  replacing them, or saving the account credentials separately. Keeping is the
  default, including on EOF; `--non-interactive` keeps the file unchanged without
  prompting. Every successful save explicitly reports the properties file.
- `jmix trial` shares the location picker and post-creation open/run checklist. Its
  location is the parent for the archive's project folder. The run action also asks
  which demo module to start; Back returns to the checklist. `--path` skips location
  selection, and non-interactive trial runs skip both interactive steps.

## Code

- [JmixCli.kt](../../src/main/kotlin/io/jmix/cli/command/JmixCli.kt) — root command and command menu.
- [NewCommand.kt](../../src/main/kotlin/io/jmix/cli/command/NewCommand.kt) — step state and transitions.
- [ProjectInteraction.kt](../../src/main/kotlin/io/jmix/cli/command/ProjectInteraction.kt) — shared location, next steps, opening, and running.
- [Prompts.kt](../../src/main/kotlin/io/jmix/cli/wizard/Prompts.kt) — rendering, input, and fallbacks.
- [StatusReporter.kt](../../src/main/kotlin/io/jmix/cli/wizard/StatusReporter.kt) — activity indicator for slow steps.
- [wizard](../../src/main/kotlin/io/jmix/cli/wizard) — banner, validation, and path completion.

## Verification and demo

Run `./gradlew test --tests 'io.jmix.cli.wizard.*'` and exercise the real CLI in
both terminal modes. Cover back/forward navigation, English/Russian quit shortcuts,
text and search containing `q` and `й`, selection retention, template-included add-ons
omitted, and narrow/resized terminals.

The README GIF uses real CLI checkpoints captured by [demo.tape](../demo.tape).
[render-demo.sh](../render-demo.sh) keeps the CLI's own progress bar and blends
transitions and the loop boundary. This excludes transient
terminal redraws while retaining the actual choices and generation result.
Show the filled project info between the welcome and add-on selection so the
demo represents the wizard's setup steps. Keep pauses long enough to read;
keep search and keyboard hints in place. Show the purple `[$]` badges and the
secondary `[$] - commercial add-on` legend below the picker heading in the catalog checkpoint.

Capture and rendering commands are in the tape header. Rendering requires
FFmpeg. Validate with
`vhs validate docs/demo.tape` and `bash -n docs/render-demo.sh`, then inspect
the animation at the README's displayed width.
