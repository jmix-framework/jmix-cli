# Add-ons

The wizard offers compatible free and commercial add-ons from the same catalog used by Studio.
Scripts select catalog IDs with `--addons quartz,german-translation`.
Without `--addons`, non-interactive generation adds nothing beyond the template
and does not fetch the catalog or resolve add-on dependencies.

## Catalog and selection

- `AddonRepository.DEFAULT_CATALOG_URL` identifies Studio's public API. It is
  independent of the selected Maven template repository. Cache entries under
  `~/.jmix/addons/` are isolated by API URL, refreshed after 24 hours, and used
  offline only when valid.
- Compatibility uses the Jmix version and the actual template's dependencies,
  including its UI stack. Templates without a supported module skip the picker.
  Entries without supported runtime metadata, including paid resources such as
  the Figma UI Kit, are excluded.
- Groups are **Features**, **UI**, **Integrations**, **Security**, **System**,
  **Other**, and **Translations**, in that order; empty groups are omitted.
  Studio catalog tags determine one group per add-on. For overlapping tags,
  precedence is Security, Integration, System, UI, then Features; unknown or
  missing tags use Other. Translation category entries always use Translations.
  Within a group, higher catalog weight comes first, matching Studio's featured
  ordering; name and ID break ties.
- Add-ons supplied by the template are omitted from the picker. Names appear
  without ID suffixes, with descriptions below. Commercial entries show a bold
  purple `[$]` badge after the name and a license-required note; the wizard
  summary uses the same format. When commercial entries are available, the
  picker shows `[$] - commercial add-on` on a separate line below its heading,
  with secondary gray text and the purple badge, also in plain consoles.
  Very short terminals hide the legend to keep the focused entry and controls visible.
  Search matches words across
  IDs, names, descriptions, groups, tags, and vendors;
  filtering does not discard selections.
  The heading shows the visible range when scrolling and the total selected
  count, including choices hidden by search. The Search row contains only the
  query or its placeholder. Its label is gray to distinguish it from cyan group
  headings; editing controls appear in the keyboard hints.
- Suggest translations from compatible free catalog artifacts. Normalize locale case
  and `_`/`-`, prefer an exact locale, then its language. Revisited steps preserve
  manual selections and opt-outs; changed languages replace only automatic choices.
- Commercial add-ons are selected explicitly in the picker or with `--addons`.
  Unknown, unsupported, or incompatible explicit IDs fail before project files
  are written. If the catalog is unavailable interactively, offer retry or
  continuation without additional add-ons.

## Installation

Additional add-ons require a compatible development JDK before generation.
The installer adds dependencies without duplicating template entries, resolves
artifacts with the generated Gradle wrapper, and reads module metadata from JARs.
Selected dependencies go at the start of the module's existing `dependencies`
block, separated from template dependencies by a blank line. Test security
prerequisites use the same block; installation does not append additional
`dependencies` blocks. Selected marketplace dependencies omit explicit versions
and use the project's Jmix BOM, matching Studio. This also covers registered
community add-ons; see
[Jmix BOM registration](https://docs.jmix.io/jmix/publish-add-on.html#update-bom).
It configures Liquibase includes and, for add-on templates, module dependencies
and test security. Configuration completes before Git staging.

Resolution supplies the actual module classes, dependency order, changelog paths
and transitive metadata needed for this configuration. It does not compile or
start the application. The Studio catalog contains compatibility and dependency
coordinates, but not enough installation metadata to replace this step. Gradle's
cache is reused, so later builds can reuse the downloaded artifacts.

## Commercial add-ons

When commercial add-ons are selected, the CLI checks configured premium credentials
and, when signed in, the account's commercial license: `jmix login`, then generate
the project normally. This also works with
`--non-interactive`; generation never starts a browser sign-in or activates a license.
Without configured credentials or a usable account license, the error explains
how to sign in or configure credentials manually, before project files are written. Free-only selections do
not read the account or Gradle properties.

As in Studio, the license key is split at the dash and the two parts are saved in
the Gradle user home, outside the project. Existing settings and comments are
preserved. Matching property values are reused. When existing credentials differ
from the account license, the interactive CLI offers **Keep existing credentials**
(the default), **Replace existing credentials**, or **Save account credentials
separately**. The last option uses names such as `premiumRepoUser.0`, referenced
by the generated repository block. Reusing those names may require generating
interactively and choosing **Save account credentials separately** again.
Replacement is unavailable when conflicting
credentials come from environment variables. Quitting writes nothing; EOF keeps
existing settings. With `--non-interactive`, conflicts leave the file untouched
and generation continues with the existing settings, even if they are incomplete.
After each successful addition or replacement, the CLI explicitly reports
`Premium repository credentials saved to <file>.` without printing the values.
Files are replaced atomically, with owner-only permissions on POSIX systems.
Neither the license key nor credential values appear in CLI output, the generated
project, or the reproduction command. `jmix logout` removes the account session;
it leaves saved Gradle credentials in place, as they are also used by other projects.

You can instead use the existing Jmix credential names in your Gradle user home
(`~/.gradle/gradle.properties`, or under `GRADLE_USER_HOME` if set). Use an
absolute `GRADLE_USER_HOME` for commercial add-ons so the CLI and generated build
use the same directory.

```properties
premiumRepoUser=<repository-user>
premiumRepoPass=<repository-password>
```

For scripts and CI, Gradle also reads the corresponding environment variables:

```shell
export ORG_GRADLE_PROJECT_premiumRepoUser='<repository-user>'
export ORG_GRADLE_PROJECT_premiumRepoPass='<repository-password>'
jmix new demo --non-interactive --addons business-calendars
```

On PowerShell, set `$env:ORG_GRADLE_PROJECT_premiumRepoUser` and
`$env:ORG_GRADLE_PROJECT_premiumRepoPass` before running the same CLI command.
For the credential sources the CLI checks, a `systemProp.org.gradle.project.*`
entry takes precedence over an environment variable, which takes precedence over
a plain property in `gradle.properties`. A blank value at a higher priority still
overrides the lower-priority value. When the environment supplies the effective
pair, the CLI skips the account lookup and leaves the properties file untouched.
A complete configured pair also remains usable when signed out, when the
account key is missing or malformed, or when the Store is unavailable.
See [Gradle project properties](https://docs.gradle.org/current/userguide/build_environment.html#sec:project_properties).

A commercial selection adds the premium repository through the same Studio
template binding as the selected public repository. The global public repository
uses `https://global.repo.jmix.io/repository/premium`; the `nexus.jmix.io` public
mirror uses `https://nexus.jmix.io/repository/premium`. A custom repository is
preserved and receives the global premium repository alongside it. An existing
known premium repository is reused. Free-only selections add no premium repository.
Both Java and Kotlin application and add-on templates are supported.

The generated `build.gradle` reads the selected property names from `rootProject`.
The defaults are `premiumRepoUser` and `premiumRepoPass`. A nearby comment explains
how to split the license key between the selected properties and links to the
[enterprise subscription](https://docs.jmix.io/jmix/studio/subscription.html#enterprise-subscription).
Access requires a license covering the selected add-ons; Gradle resolution checks
access to their artifacts. To retrieve a missing key, the CLI calls Studio's
`GET <store>/app/rest/v3/studio/license/check` with the account access token, using
the same no-redirect client and refresh retry as trial requests. The key is accepted
only for an active commercial license and is never added to the session file.
[`jmix trial`](account-and-trial.md) downloads the
Enterprise+BPM trial project instead; its demos resolve the trial add-ons from the bundled
`trial_repository` and need no premium credentials.

If Gradle cannot resolve the add-ons, generation fails with the underlying error
and setup instructions. Rendered files remain, but module/Liquibase configuration
is incomplete and Git staging has not run. After correcting credentials or the
reported repository/JDK problem, regenerate in an empty directory, or deliberately
use `--force` to overwrite the generated files. Running Gradle alone does not
complete the CLI's add-on configuration.

## Code and verification

- [AddonCatalog.kt](../../src/main/kotlin/io/jmix/cli/addon/AddonCatalog.kt) — metadata, compatibility, ordering, translations.
- [AddonRepository.kt](../../src/main/kotlin/io/jmix/cli/repo/AddonRepository.kt) — API and validated cache.
- [AddonInstaller.kt](../../src/main/kotlin/io/jmix/cli/generator/AddonInstaller.kt) and
  [addon-metadata.gradle](../../src/main/resources/io/jmix/cli/addon-metadata.gradle) — project integration.
- [NewCommand.kt](../../src/main/kotlin/io/jmix/cli/command/NewCommand.kt) and
  [Prompts.kt](../../src/main/kotlin/io/jmix/cli/wizard/Prompts.kt) — selection state and UI.
- [PremiumRepository.kt](../../src/main/kotlin/io/jmix/cli/account/PremiumRepository.kt) — account license and user-level Gradle credentials.

Run catalog, repository, installer, and prompt tests. Exercise both terminal
modes with group headings, search across groups, template-included entries
omitted, paid labels, free-only translation defaults, translation opt-outs, and changed locales.
For installer changes, generate and build an application with add-ons; inspect
its dependencies and Liquibase includes. Include an add-on template when module
configuration changes, and verify non-interactive use with and without `--addons`.
Check premium mirror selection, generated comments and credential references,
property/environment wiring, and failed resolution before Git staging.
Run authenticated commercial builds separately when repository access is available;
routine tests use fixtures and do not need a commercial license.
