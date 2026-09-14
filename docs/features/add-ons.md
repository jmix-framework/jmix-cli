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
  purple `[$]` badge before the name and a license-required note; the wizard
  summary uses the same prefix. When commercial entries are available, the
  picker heading includes the `[$] paid add-on` legend, also in plain consoles.
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
block under `// Selected Jmix add-ons`. Test security prerequisites use the same
block; installation does not append additional `dependencies` blocks. Selected
marketplace dependencies omit explicit versions and use the project's Jmix BOM,
matching Studio. This also covers registered community add-ons; see
[Jmix BOM registration](https://docs.jmix.io/jmix/publish-add-on.html#update-bom).
It configures Liquibase includes and, for add-on templates, module dependencies
and test security. Configuration completes before Git staging.

Resolution supplies the actual module classes, dependency order, changelog paths
and transitive metadata needed for this configuration. It does not compile or
start the application. The Studio catalog contains compatibility and dependency
coordinates, but not enough installation metadata to replace this step. Gradle's
cache is reused, so later builds can reuse the downloaded artifacts.

## Commercial add-ons

Configure repository access before generating a project with commercial add-ons.
Use the existing Jmix credential names in your Gradle user home
(`~/.gradle/gradle.properties`, or under `GRADLE_USER_HOME` if set):

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
Gradle's normal project property resolution applies. The CLI does not prompt for, store,
or insert credential values into the generated project or its reproduction command.
See [Gradle project properties](https://docs.gradle.org/current/userguide/build_environment.html#sec:project_properties).

A commercial selection adds the premium repository through the same Studio
template binding as the selected public repository. The global public repository
uses `https://global.repo.jmix.io/repository/premium`; the `nexus.jmix.io` public
mirror uses `https://nexus.jmix.io/repository/premium`. A custom repository is
preserved and receives the global premium repository alongside it. An existing
known premium repository is reused. Free-only selections add no premium repository.
Both Java and Kotlin application and add-on templates are supported.

The generated `build.gradle` reads `rootProject['premiumRepoUser']` and
`rootProject['premiumRepoPass']`. A nearby comment explains credential setup and
links to [Jmix Account and Subscription](https://docs.jmix.io/jmix/studio/subscription.html).
Access requires a license covering the selected add-ons; the CLI does not check
subscriptions or activate licenses.

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
- [NewCommand.kt](../../src/main/kotlin/io/jmix/cli/NewCommand.kt) and
  [Prompts.kt](../../src/main/kotlin/io/jmix/cli/wizard/Prompts.kt) — selection state and UI.

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
