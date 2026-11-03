package io.jmix.cli.trial

import io.jmix.cli.account.AccountRegion
import io.jmix.cli.account.StoreClient
import io.jmix.cli.env.ProjectLauncher
import io.jmix.cli.command.projectNextSteps
import io.jmix.cli.command.projectRunCommand
import java.nio.file.Path
import java.time.LocalDate

/** Where to download the trial project and when the trial ends. [url] is a capability: never print or log it. */
internal data class TrialDownload(val url: String, val endDate: LocalDate?) {

    /** Hides the download link, so a value that reaches a message or a log does not leak it. */
    override fun toString(): String = "TrialDownload(url=<hidden>, endDate=$endDate)"
}

/** The account has no trial to download; the message says why and where to go instead. */
internal class TrialException(message: String) : Exception(message)

/** What the store says about an ended trial, word for word; [contactsUrl] is the region's contacts page. */
internal fun trialExpiredMessage(contactsUrl: String): String =
    "Your Enterprise+BPM trial has expired. If you didn't have enough time to explore Jmix features, " +
        "please contact us: $contactsUrl"

/**
 * The account's trial to download: the active one that ends last or, when there is none and the store allows it, a
 * newly started one. [confirmStart] is asked right before the start only, because starting uses the trial up.
 */
internal fun findOrStartTrial(
    store: StoreClient,
    today: LocalDate,
    region: AccountRegion,
    confirmStart: () -> Boolean,
): TrialDownload {
    val trials = trialsOf(store)
    latestActive(trials, today)?.let { return it }
    if (!store.trialAvailable()) {
        throw TrialException(
            if (trials.isNotEmpty()) {
                trialExpiredMessage(region.contactsUrl)
            } else {
                "No Enterprise+BPM trial is available for this account. " +
                    "Check your subscriptions at ${region.accountPageUrl}."
            },
        )
    }
    if (!confirmStart()) throw TrialException("The trial was not started.")
    val started = store.startTrial()
    // Read back as the web does after starting: the subscriptions are where the store lists the trial.
    latestActive(trialsOf(store), today)?.let { return it }
    // Only a fallback: what the start call answers is unverified (spec Q3).
    val link = started.link ?: throw TrialException(
        "The trial was started, but its download link is not ready yet. " +
            "Run 'jmix trial' again in a minute or download it from ${region.accountPageUrl}.",
    )
    return TrialDownload(link, started.expiryDate)
}

/**
 * What `jmix trial` prints once the [projects] are unpacked: where they are, when the trial ends and how to start
 * the first demo. Paths are relative to [currentDir] unless they lie outside it, as `jmix new` prints them.
 */
internal fun trialSummary(projects: List<Path>, endDate: LocalDate?, currentDir: Path): List<String> = buildList {
    projects.forEach { add("✓ Enterprise+BPM trial project unpacked to $it") }
    add(
        if (endDate != null) {
            "Trial ends on $endDate. When it ends, the demo applications stop starting."
        } else {
            "Trial end date is unknown. When the trial ends, the demo applications stop starting."
        },
    )
    // The archive's time bomb is set when the store builds it; the same directory is refused as "already exists".
    add("If a demo stops starting earlier, download a fresh copy into another directory: jmix trial --path <directory>")
    projects.firstOrNull()?.let { project ->
        val apps = TrialArchive.runnableApps(project)
        val app = apps.first()
        val runDir = if (apps.size > 1) project.resolve("demo/MODULE") else app
        addAll(projectNextSteps(project, currentDir, "bootRun", runDir))
        if (apps.size > 1) {
            add("  Example (${app.fileName}): ${projectRunCommand(project, app, "bootRun")}")
        }
        add("🧩 Application: http://localhost:${ProjectLauncher.serverPort(app)}")
    }
    add("📖 Guide: https://docs.jmix.io/jmix/account-management.html#enterprise-trial")
}

/** The account's trials: the subscriptions that come with a download link. */
private fun trialsOf(store: StoreClient): List<TrialDownload> =
    store.subscriptions().mapNotNull { subscription ->
        subscription.trialDownloadUrl?.let { TrialDownload(it, subscription.endDate) }
    }

/** The trial still running on [today] that ends last; no end date counts as last, and the end date itself is over. */
private fun latestActive(trials: List<TrialDownload>, today: LocalDate): TrialDownload? =
    trials.filter { it.endDate == null || today.isBefore(it.endDate) }.maxByOrNull { it.endDate ?: LocalDate.MAX }
