# Account and trial

`jmix login` signs in to a Jmix account in the browser, `jmix logout` forgets the
account on this computer, and `jmix trial` downloads the Enterprise+BPM trial project
of the signed-in account: demo applications that use the commercial add-ons without a
license key.

```shell
jmix login [--region global|ru]
jmix logout
jmix trial [--path <dir>] [--start-trial] [--non-interactive]
```

Every command also accepts `--no-update`. For commercial add-ons, `jmix new` can
use the signed-in account's license to configure premium repository access
([Add-ons](add-ons.md#commercial-add-ons)). The trial project is a separate download.

## Sign-in

`jmix login` follows Jmix Studio: OAuth 2.0 authorization code with PKCE (S256) and a
loopback redirect.

- The client is the public `site-console` client of the `jmix-cuba` realm, with scope
  `openid`. It has no client secret, and none may be added.
- The CLI listens on the loopback interface, on a port the system picks, with the JDK's
  `com.sun.net.httpserver`. The redirect URI is `http://localhost:<port>`, without a path.
- It prints the sign-in address and tries to open it in the default browser. A browser
  that does not start is a warning; the printed address works in any browser on the
  same computer.
- It waits up to 10 minutes. Ctrl+C cancels.
- The first request that carries a `code` or an `error` ends the wait, and a `state` that
  does not match this sign-in fails it. Other requests get 404. After the callback the
  browser lands on the provider's "Login Successful" page, or on the same page with
  `?error=true`.
- The CLI trades the code for tokens with the PKCE verifier, saves the session, and
  prints `Signed in to your Jmix account as <name> (<email>).` Name and email come from
  the claims of the access token; with one of them the line names only that one, and with
  neither it reads `Signed in to your Jmix account.` The token is not verified: it came
  from the token endpoint and only greets you.
- A failure ends with `Sign-in failed: <reason>.`, for example a timeout, a denied login
  or an unreachable service, with no stack trace. Provider error descriptions are not
  echoed: they can contain authorization codes or other private data.
- `jmix login` does not check for a terminal. Signing in is its purpose, so it also runs
  from scripts, agents and IDE consoles, and always prints the address and tries the browser.

### Regions

| Region | `--region` | Sign-in service       | Store                         | Account page                    |
|--------|------------|-----------------------|-------------------------------|---------------------------------|
| Global | `global`   | `https://sso.jmix.io` | `https://store-admin.jmix.io` | `https://store.jmix.io/account` |
| Russia | `ru`       | `https://sso.jmix.ru` | `https://store-admin.jmix.ru` | `https://store.jmix.ru/account` |

- Tokens belong to their sign-in host, so the session remembers its region and hosts.
  Signing in again, also in another region, replaces it.
- The store API root is `<store>/app/rest/v3`. The CLI calls the endpoints that
  `store.jmix.io/account` uses; they are not a documented API.
- `JMIX_CLI_SSO_URL` and `JMIX_CLI_STORE_URL` replace the hosts of the region at sign-in,
  for staging and tests. The session keeps the URLs it was created with, and later
  commands use them.

## Session file

- `~/.jmix/account/session.json` holds the region, the two service URLs and the refresh
  token, plus the name/email for the menu's account block. Access tokens stay in
  memory and never reach the disk. Profile details come from the access token at
  sign-in and are updated on refresh; if a later token omits them, the saved details stay.
- The main menu reads this file without contacting SSO or Store. It displays the
  cached account and region, or `Not signed in`; this is local session status, not
  a server validation. Existing files without profile details remain valid and show
  `Signed in` and the region until a sign-in or refresh supplies the details.
- Account details sit in a two- or three-row block at the top right of the menu,
  beside the logo when space permits. Sign-in and
  sign-out chosen from the menu return there and refresh the displayed account
  and the `login`/`logout` choice; direct commands still finish after the action.
- On macOS and Linux the directory is `rwx------` and the file `rw-------`. Windows has no
  POSIX permissions, so the file keeps the access rights it inherits from the user profile.
- Saving also makes an existing account directory owner-only on macOS and Linux.
- Saves are atomic: the CLI writes an owner-only temporary file next to the session and
  moves it over the old one. A failed save keeps the previous session and leaves no copy
  of the token.
- A command that calls the store trades the refresh token for an access token once, reuses
  it for the rest of the process, and after a 401 refreshes and retries once. The provider
  rotates refresh tokens, so every refresh saves the new one.
- Token and store API requests refuse HTTP redirects, so credentials cannot be forwarded
  to another endpoint. Archive downloads still follow redirects without credentials.
- When the provider ends the session (`invalid_grant`), the CLI deletes the file and says
  `Your Jmix account session has expired. Run 'jmix login' to sign in again.`
- A missing, damaged or unknown-region file reads as signed out.
- `jmix logout` deletes the file and prints `Signed out of your Jmix account.`, or
  `You are not signed in.` when there was none. It makes no server call, so the refresh
  token stays valid on the server until it expires.

## Trial flow

`--non-interactive` disables all prompts and automatic opening/running. Without it,
the location and follow-up steps also support numbered/line-input consoles. EOF chooses
the default location and skips follow-up actions. Automatic sign-in and trial-start
consent require an interactive input terminal; scripts must sign in beforehand and
use `--start-trial` when activation is needed.

1. **Directory.** Without `--path`, an interactive run uses the same location picker as
   `jmix new`: current directory, `./jmix-trial`, `~/IdeaProjects/jmix-trial`, or a custom
   path with completion. Esc (`<` in a text console) returns to the main menu before
   any account request. `--path` skips the picker; non-interactive runs default to the
   current directory. The selected parent directory is created before anything else,
   so a bad path fails before a trial can be started. A file at that path is refused.
   The directory stays, empty, when a later step fails before the download.
2. **Session.** The saved session is used. With none, an interactive run signs in first
   as `jmix login` does, in the global region, and prints the same `Signed in ...` line.
   A browser that still has a Jmix session signs in without showing a page, so that line
   names the account before any trial question. For a Russian account run
   `jmix login --region ru` beforehand. A non-interactive run fails with
   `Not signed in to a Jmix account. Run 'jmix login' first.`
3. **Find the trial.** The account's trials are its subscriptions that carry a download
   link. The active trial that ends last wins. A trial is active while today is before its
   end date, so the end date itself counts as over, as on the website. A trial without an
   end date counts as active. An active trial, including one started on the website, is
   downloaded as it is: no question, and no start call.
4. **No active trial.** The CLI asks the store whether the account can start one.
   - The store says no, and the account had a trial before:
     `Your Enterprise+BPM trial has expired. If you didn't have enough time to explore Jmix features, please contact us: <contacts page>`
     The contacts page is `https://www.jmix.io/contacts/`, or `https://www.jmix.ru/contacts/`
     in the ru region.
   - The store says no, and the account never had one:
     `No Enterprise+BPM trial is available for this account. Check your subscriptions at <account page>.`
   - The store says yes: after consent the CLI starts the trial, reads the subscriptions
     again as the website does, and uses the new trial. When the list does not show it,
     the link and date from the start call are the fallback. With no link either:
     `The trial was started, but its download link is not ready yet. Run 'jmix trial' again in a minute or download it from <account page>.`
5. **Download, unpack and report**, as described below.
6. **Open and run.** Interactive runs reuse the `jmix new` follow-up checklist: open
   the whole trial project in the detected IDE and/or run an application. Choosing
   run opens a demo-module picker with each module's URL; one module is selected
   automatically when it is the only one. Back from this picker returns to the checklist.
   The selected module runs its own Gradle wrapper with a compatible JDK, offering the
   same JDK installation and browser opening as project creation. The file manager is
   the fallback when IDE opening is declined or fails. Non-interactive runs offer no
   open/run actions. An archive with multiple trial projects first asks which to open.

**Consent.** Starting a trial changes the account, so it takes an explicit yes. An
interactive run asks `Start the 28-day Enterprise+BPM trial for your Jmix account now?`
with **No** as the default, and a refusal ends with `The trial was not started.`
`--start-trial` answers yes without asking. A non-interactive run without it fails with
`Starting the Enterprise+BPM trial needs confirmation. Re-run with --start-trial.` The
question comes right before the start and never appears when a trial is active.

## Download and unpack

The archive is `jmix-commercial-addons-demo-<version>.zip` with one top folder of the
same name. It holds:

- a Gradle composite build: `settings.gradle` includes every `demo/<name>` build, and the
  root has no `bootRun`;
- ten demo applications at the time of writing, each a standalone build under
  `demo/<name>` with its own `gradlew`; the primary next step uses the first by name,
  `bpm`, as the example for the general module run command;
- `trial_repository`, a Maven-layout folder with the commercial add-ons, rebuilt with a
  time limit.

Each demo resolves its add-ons from `../../trial_repository`, so the demos need no premium
repository credentials, and the layout must stay as shipped. That is why nothing is renamed,
moved or edited, and why `jmix trial` runs no `git init`: Git would stage the trial JARs,
and the archive has no `.gitignore`.

- The link comes from the store and works as a capability: whoever holds it can download.
  The CLI fetches it with no `Authorization` header, requires `https` (plain `http` only
  for loopback hosts, which the tests use), and never prints it, not even in an error.
- The answer must be HTTP 200, at most 1 GiB, and start with the zip signature. Anything
  else ends with a short message that points to the account page.
- The zip goes to a temporary file in the system temp directory, so an interrupted or
  failed download leaves nothing in `--path`.
- Unpacking uses a hidden `.jmix-trial-*` staging folder inside `--path` and the zip-slip
  guard of `JdkInstaller.unpackZip`. It requires a ZIP central directory and limits the
  total extracted file content to 4 GiB, counting actual bytes across all entries.
  A truncated or oversized archive fails before any project is moved into place.
  The projects are the top folders that hold a
  `settings.gradle(.kts)` and a `trial_repository`; other entries are ignored, and an
  archive without a project is an error.
- An existing `<path>/<folder>`, for example from an earlier run, is refused before anything
  moves: `<path>/<folder> already exists. Remove it or choose another directory with --path`.
- The archive stores no Unix permissions, so every `gradlew` is made executable. The folders
  then move into place and the staging folder goes away.

The report reads `bomVersion` from the root or `demo/*` build files (a `.trial` suffix is
dropped; only the shape is checked, because trials run on the newest Jmix) and runs the same
JDK check as `jmix new`, which reports and never fails the command. Then it prints the
location, the end date, a general module run command with one real example and its URL,
and the guide link:

```
✓ Enterprise+BPM trial project unpacked to /home/user/jmix-commercial-addons-demo-3.1.0
Trial ends on 2026-11-24. When it ends, the demo applications stop starting.
If a demo stops starting earlier, download a fresh copy into another directory: jmix trial --path <directory>

Next steps:
  1. cd jmix-commercial-addons-demo-3.1.0
  2. ./demo/MODULE/gradlew -p demo/MODULE bootRun
  Example (bpm): ./demo/bpm/gradlew -p demo/bpm bootRun
Application: http://localhost:8101
Guide: https://docs.jmix.io/jmix/account-management.html#enterprise-trial
```

The `cd` and wrapper invocation are separate lines because Windows PowerShell 5.1 rejects
`&&`. Replace `MODULE` with a demo module name. Each command runs from the trial root
and uses the module's wrapper with `-p` to
select its standalone Gradle build. Windows instructions use `gradlew.bat`; paths are
quoted for the current platform, including spaces and apostrophes.

## Expiry

- The CLI prints the subscription's end date, the same date the website's download gate uses.
  Without one it says the end date is unknown.
- The add-ons in `trial_repository` stop the application at startup once a fixed date has
  passed. The store sets that date when it builds the archive, not when you start the trial,
  so it can fall before or after the subscription's end date.
- After the trial ends, `jmix trial` reports it as expired and points to the contacts page,
  unless the store lets the account start another trial.
- When a demo stops starting before the printed end date, download a fresh copy into another
  directory with `jmix trial --path <directory>`. The old folder is refused, so keep it
  elsewhere or delete it. A fresh copy helps only if the store has built a newer archive.

## Limitations

- Sign-in needs a browser on the same computer, because the redirect goes to a loopback
  port. There is no device flow yet, so SSH sessions and headless machines cannot sign in.
- There is no OS proxy support yet: only JVM proxy properties are honored. Adding
  `-Djava.net.useSystemProxies=true` to the jpackage `javaOptions` would change every command.
- There is no `cn` region: it has no store front end, and its sign-in service names another issuer.
- `jmix logout` revokes nothing on the server.
- The store endpoints are the website's own. The CLI treats their answers as untrusted:
  checked casts only, several date shapes, and no response body or server-provided error
  text in any message. Failed calls report their HTTP status.

## Behavior to preserve

- Never print or log access tokens, refresh tokens, the authorization code, the PKCE
  verifier or the trial download link. The sign-in address may be printed. `Session`,
  `Tokens`, `Subscription`, `StartedTrial` and `TrialDownload` hide their secrets in `toString`.
- Send `Authorization: Bearer` only to the store API. Download the archive without it.
- Read JSON through checked casts (`as?`, `JsonObject.string`). Gson's exception messages
  quote the whole document, which can hold tokens or the download link, so no response body
  or Gson message goes into an error. Never echo `error`, `error_description` or `message`
  fields either. Access tokens must have the bearer-token character format before use in
  a header; malformed values otherwise leak through JDK exception messages.
- Expected failures end in one concise `CliktError`. Network failures name the service
  (`the Jmix sign-in service`, `the Jmix Store`).
- Starting a trial needs `--start-trial` or an explicit interactive yes. The default is No.
  Every browser sign-in, also the one inside `jmix trial`, ends with the `Signed in ...` line,
  so the account is named before that question.
- Send `User-Agent: jmix-cli/<version or dev>` through `Http.request`.
- Keep `jdk.httpserver` in `releaseRuntimeModules`. The release image is built with `jlink`,
  and without the module `jmix login` fails only in the installed CLI.

## Code

- [LoginCommand.kt](../../src/main/kotlin/io/jmix/cli/command/LoginCommand.kt),
  [LogoutCommand.kt](../../src/main/kotlin/io/jmix/cli/command/LogoutCommand.kt) and
  [TrialCommand.kt](../../src/main/kotlin/io/jmix/cli/command/TrialCommand.kt) — the commands;
  [Main.kt](../../src/main/kotlin/io/jmix/cli/Main.kt) registers them.
- [AccountEndpoints.kt](../../src/main/kotlin/io/jmix/cli/account/AccountEndpoints.kt) — regions, derived URLs, host overrides.
- [Pkce.kt](../../src/main/kotlin/io/jmix/cli/account/Pkce.kt) — verifier, challenge, state.
- [LoopbackCallback.kt](../../src/main/kotlin/io/jmix/cli/account/LoopbackCallback.kt) — one-shot redirect receiver.
- [TokenClient.kt](../../src/main/kotlin/io/jmix/cli/account/TokenClient.kt) — authorization URL, code exchange, refresh.
- [BrowserSignIn.kt](../../src/main/kotlin/io/jmix/cli/account/BrowserSignIn.kt) — the sign-in flow and `Identity`.
- [SessionStore.kt](../../src/main/kotlin/io/jmix/cli/account/SessionStore.kt) — the session file.
- [AccessTokens.kt](../../src/main/kotlin/io/jmix/cli/account/AccessTokens.kt) — refresh once per process, save rotation.
- [StoreClient.kt](../../src/main/kotlin/io/jmix/cli/account/StoreClient.kt) — subscriptions, availability, start, store dates.
- [TrialFinder.kt](../../src/main/kotlin/io/jmix/cli/trial/TrialFinder.kt) — find or start the trial, summary lines.
- [TrialArchive.kt](../../src/main/kotlin/io/jmix/cli/trial/TrialArchive.kt) — download, unpack, version, demo list.
- [Http.kt](../../src/main/kotlin/io/jmix/cli/util/Http.kt) and
  [Json.kt](../../src/main/kotlin/io/jmix/cli/util/Json.kt) — shared HTTP client and checked JSON reads.

## Verification

Run the account and trial tests, which talk to the in-process
[FakeServer](../../src/test/kotlin/io/jmix/cli/FakeServer.kt) and need no network or account:

```shell
./gradlew test --tests 'io.jmix.cli.account.*' --tests 'io.jmix.cli.trial.*' \
    --tests 'io.jmix.cli.command.LoginCommandTest' --tests 'io.jmix.cli.command.LogoutCommandTest' \
    --tests 'io.jmix.cli.command.TrialCommandTest'
```

For the release image, `./gradlew releaseBundle` and `tests/test-install.sh` (on Windows,
`tests/test-install.ps1`) check that `login --help` and `trial --help` run and that the
runtime lists `jdk.httpserver`.

Sign in and download on a test account by hand: `jmix login`, then
`jmix trial --path <scratch directory>`, then `./gradlew bootRun` in `demo/bpm`. Repeat the
sign-in in the default browsers of macOS (Safari, Chrome), Windows (Edge) and Linux
(Firefox): each must reach `http://localhost:<port>` while the CLI listens on the loopback
interface. To keep your own session, run with a scratch user home
(`JAVA_TOOL_OPTIONS=-Duser.home=<scratch directory>`). Do not paste the session file, tokens
or the download link into issues or logs. Starting a trial changes the account, so keep
`--start-trial` out of routine checks.
