package hu.orszembejelento.backend.maintenance

import hu.orszembejelento.backend.identity.application.CreateSuperAdminUseCase
import hu.orszembejelento.backend.identity.application.ProvisionedCredential
import hu.orszembejelento.backend.identity.application.ResetSuperAdminPasswordUseCase
import hu.orszembejelento.backend.reference.application.ReferenceDiffOutcome
import hu.orszembejelento.backend.reference.application.ReferenceImportOutcome
import hu.orszembejelento.backend.reference.application.ReferenceImportUseCase
import hu.orszembejelento.backend.reference.domain.ReferenceDiff
import hu.orszembejelento.backend.reference.domain.ReferenceImportException
import hu.orszembejelento.backend.reference.infrastructure.CanonicalDatasetLoader
import java.nio.file.Path
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.context.WebApplicationContext
import org.springframework.context.ApplicationContext
import org.springframework.stereotype.Component

/**
 * Local maintenance actions, run on the server by someone with shell access.
 *
 * Deliberately **not** HTTP endpoints. Administrator creation and administrator password
 * recovery are the two operations that could hand someone full control of the system, so
 * they are gated on shell access to the machine rather than on any credential that could be
 * guessed, phished or replayed. There is no remote maintenance interface to attack, and no
 * emergency master password.
 *
 * Activated by a property, so the normal service start does nothing:
 * ```
 *   java -jar backend.jar --orszem.maintenance.action=create-super-admin
 * ```
 */
@Component
@ConditionalOnProperty(prefix = "orszem.maintenance", name = ["action"])
class MaintenanceCommandRunner(
    private val properties: MaintenanceProperties,
    private val createSuperAdmin: CreateSuperAdminUseCase,
    private val resetSuperAdminPassword: ResetSuperAdminPasswordUseCase,
    private val referenceImport: ReferenceImportUseCase,
    private val applicationContext: ApplicationContext,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        // Refuse to act if a maintenance action was somehow set on a process that is also
        // serving HTTP. A running web server would mean this is a live service instance,
        // and a maintenance action must never be a side effect of an ordinary deployment.
        if (applicationContext is WebApplicationContext) {
            log.error(
                "Refusing to run maintenance action '{}': this process is serving HTTP. " +
                    "Run maintenance with --spring.main.web-application-type=none.",
                properties.action,
            )
            exitWith(2)
            return
        }

        when (properties.action) {
            "create-super-admin" -> printCredential(
                "SUPER_ADMIN created",
                createSuperAdmin.create(),
            )

            "reset-super-admin-password" -> {
                val target = properties.serviceId
                if (target.isNullOrBlank()) {
                    console("ERROR: --orszem.maintenance.service-id is required for reset-super-admin-password")
                    exitWith(2)
                    return
                }
                try {
                    printCredential("SUPER_ADMIN password reset", resetSuperAdminPassword.reset(target))
                } catch (e: ResetSuperAdminPasswordUseCase.NotASuperAdminException) {
                    console("ERROR: ${e.message}")
                    exitWith(3)
                    return
                }
            }

            "reference-validate" -> runReferenceCommand { dir ->
                when (val result = referenceImport.validate(dir)) {
                    is CanonicalDatasetLoader.LoadResult.Valid -> {
                        val m = result.dataset.manifest
                        console("VALID  dataset ${m.datasetVersion}")
                        console(
                            "  verificationStatus=${m.verificationStatus}  " +
                                "coverageStatus=${m.coverageStatus}  reuseStatus=${m.reuseStatus}",
                        )
                        console(
                            "  settlements=${m.settlementCount}  railwayLines=${m.railwayLineCount}  " +
                                "mappings=${m.mappingCount}",
                        )
                        if (m.reuseStatus.name != "CLEARED") {
                            console("  NOTE: reuseStatus is ${m.reuseStatus}; 'reference-import' will refuse this dataset until it is CLEARED.")
                        }
                        true
                    }
                    is CanonicalDatasetLoader.LoadResult.Invalid -> {
                        console("INVALID  ${result.issues.size} issue(s):")
                        result.issues.forEach { console("  - $it") }
                        false
                    }
                }
            }

            "reference-diff" -> runReferenceCommand { dir ->
                when (val outcome = referenceImport.diff(dir)) {
                    is ReferenceDiffOutcome.DatasetInvalid -> {
                        console("INVALID  ${outcome.issues.size} issue(s):")
                        outcome.issues.forEach { console("  - $it") }
                        false
                    }
                    is ReferenceDiffOutcome.Computed -> {
                        printDiff(outcome.diff)
                        true
                    }
                }
            }

            "reference-import" -> runReferenceCommand { dir ->
                try {
                    when (val outcome = referenceImport.import(dir)) {
                        is ReferenceImportOutcome.AlreadyImported -> {
                            console("NO-OP  dataset ${outcome.datasetVersion} was already imported; nothing changed")
                            true
                        }
                        is ReferenceImportOutcome.Applied -> {
                            console("IMPORTED  dataset ${outcome.datasetVersion} (provenance ${outcome.provenanceId})")
                            printDiff(outcome.diff)
                            true
                        }
                    }
                } catch (e: ReferenceImportException) {
                    console("ERROR: ${e.code}")
                    console("  ${e.message}")
                    false
                }
            }

            else -> {
                console("ERROR: unknown maintenance action '${properties.action}'")
                console(
                    "Supported: create-super-admin, reset-super-admin-password, " +
                        "reference-validate, reference-diff, reference-import",
                )
                exitWith(2)
                return
            }
        }

        exitWith(0)
    }

    /**
     * Shared entry point for the three reference-dataset commands: resolves
     * `--orszem.maintenance.dataset-dir`, runs [action], and turns its boolean result (and
     * any [ReferenceImportException]) into the process exit code the shell wrapper checks.
     */
    private fun runReferenceCommand(action: (Path) -> Boolean) {
        val rawDir = properties.datasetDir
        if (rawDir.isNullOrBlank()) {
            console("ERROR: --orszem.maintenance.dataset-dir is required for '${properties.action}'")
            exitWith(2)
            return
        }
        val ok = action(Path.of(rawDir))
        exitWith(if (ok) 0 else 1)
    }

    private fun printDiff(diff: ReferenceDiff) {
        console(
            "  settlements   +${diff.settlementsToInsert.size} new   " +
                "${diff.settlementsToUpdate.size} upserted   " +
                "${diff.settlementsToReactivate.size} reactivated   " +
                "${diff.settlementsToDeactivate.size} deactivated" +
                preservedSuffix(diff.settlementsPreservedDespiteAbsence.size),
        )
        console(
            "  railway lines +${diff.linesToInsert.size} new   " +
                "${diff.linesToUpdate.size} upserted   " +
                "${diff.linesToReactivate.size} reactivated   " +
                "${diff.linesToDeactivate.size} deactivated" +
                preservedSuffix(diff.linesPreservedDespiteAbsence.size),
        )
        console(
            "  relations     +${diff.relationsToAdd.size} add   " +
                "-${diff.relationsToRemove.size} remove" +
                preservedSuffix(diff.relationsPreservedDespiteAbsence.size),
        )
        if (diff.isEmpty) console("  (no changes)")
    }

    /**
     * Absence under PARTIAL coverage is preserved, not removed (ADR 0006) - surfaced here
     * so an operator can see what the importer deliberately left alone, rather than that
     * silently looking identical to "nothing was different".
     */
    private fun preservedSuffix(preservedCount: Int): String =
        if (preservedCount > 0) "   ($preservedCount preserved - absent from a PARTIAL-coverage snapshot)" else ""

    /**
     * Writes straight to the console, never through the logging framework.
     *
     * A temporary credential must not reach a log file, a log aggregator or a journal,
     * where it would outlive its single use and be readable by anyone with log access.
     */
    private fun printCredential(title: String, credential: ProvisionedCredential) {
        console("")
        console("  $title")
        console("  ------------------------------------------")
        console("  Service ID:          ${credential.serviceId.value}")
        console("  Temporary credential: ${credential.temporaryCredential}")
        console("")
        console("  This credential is shown once and is not stored anywhere in plaintext.")
        console("  It must be changed on first sign-in.")
        console("")
    }

    private fun console(line: String) = println(line)

    private fun exitWith(code: Int) {
        // Exit code carries success/failure to the shell wrapper.
        Runtime.getRuntime().halt(code)
    }
}

@ConfigurationProperties(prefix = "orszem.maintenance")
data class MaintenanceProperties(
    /**
     * `create-super-admin`, `reset-super-admin-password`, `reference-validate`,
     * `reference-diff` or `reference-import`. Absent on a normal service start.
     */
    val action: String? = null,
    /** Target service ID, required by `reset-super-admin-password`. */
    val serviceId: String? = null,
    /** Canonical dataset directory, required by the three `reference-*` actions. */
    val datasetDir: String? = null,
)
