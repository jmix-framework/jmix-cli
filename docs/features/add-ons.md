# Add-ons

The wizard offers compatible free add-ons from the same catalog used by Studio.
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
  Commercial add-ons and entries without supported runtime metadata are excluded.
- Groups are **Features**, **UI**, **Integrations**, **Security**, **System**,
  **Other**, and **Translations**, in that order; empty groups are omitted.
  Studio catalog tags determine one group per add-on. For overlapping tags,
  precedence is Security, Integration, System, UI, then Features; unknown or
  missing tags use Other. Translation category entries always use Translations.
  Within a group, higher catalog weight comes first, matching Studio's featured
  ordering; name and ID break ties.
- Add-ons supplied by the template are omitted from the picker. Names appear
  without ID suffixes, with descriptions below. Search matches words across
  IDs, names, descriptions, groups, tags, and vendors;
  filtering does not discard selections.
  The heading shows the visible range when scrolling and the total selected
  count, including choices hidden by search. The Search row contains only the
  query or its placeholder. Its label is gray to distinguish it from cyan group
  headings; editing controls appear in the keyboard hints.
- Suggest translations from compatible catalog artifacts. Normalize locale case
  and `_`/`-`, prefer an exact locale, then its language. Revisited steps preserve
  manual selections and opt-outs; changed languages replace only automatic choices.
- Unknown, commercial, or incompatible explicit IDs fail before project files
  are written. If the catalog is unavailable interactively, offer retry or
  continuation without additional add-ons.

## Installation

Additional add-ons require a compatible development JDK before generation.
The installer adds dependencies without duplicating template entries, resolves
artifacts with the generated Gradle wrapper, and reads module metadata from JARs.
Selected dependencies go at the start of the module's existing `dependencies`
block under `// Selected Jmix add-ons`. Test security prerequisites use the same
block; installation does not append additional `dependencies` blocks.
It configures Liquibase includes and, for add-on templates, module dependencies
and test security. Configuration completes before Git staging.

Commercial repository authentication and credentials are outside the current
feature; see [issue #7](https://github.com/jmix-framework/jmix-cli/issues/7).

## Code and verification

- [AddonCatalog.kt](../../src/main/kotlin/io/jmix/cli/addon/AddonCatalog.kt) — metadata, compatibility, ordering, translations.
- [AddonRepository.kt](../../src/main/kotlin/io/jmix/cli/repo/AddonRepository.kt) — API and validated cache.
- [AddonInstaller.kt](../../src/main/kotlin/io/jmix/cli/generator/AddonInstaller.kt) and
  [addon-metadata.gradle](../../src/main/resources/io/jmix/cli/addon-metadata.gradle) — project integration.
- [NewCommand.kt](../../src/main/kotlin/io/jmix/cli/NewCommand.kt) and
  [Prompts.kt](../../src/main/kotlin/io/jmix/cli/wizard/Prompts.kt) — selection state and UI.

Run catalog, repository, installer, and prompt tests. Exercise both terminal
modes with group headings, search across groups, template-included entries
omitted, translation opt-outs, and changed locales.
For installer changes, generate and build an application with add-ons; inspect
its dependencies and Liquibase includes. Include an add-on template when module
configuration changes, and verify non-interactive use with and without `--addons`.
