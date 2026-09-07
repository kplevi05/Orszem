package hu.orszembejelento.backend.maintenance

import hu.orszembejelento.backend.identity.application.CreateSuperAdminUseCase
import hu.orszembejelento.backend.identity.application.ProvisionedCredential
import hu.orszembejelento.backend.identity.application.ResetSuperAdminPasswordUseCase
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

            else -> {
                console("ERROR: unknown maintenance action '${properties.action}'")
                console("Supported: create-super-admin, reset-super-admin-password")
                exitWith(2)
                return
            }
        }

        exitWith(0)
    }

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
    /** `create-super-admin` or `reset-super-admin-password`. Absent on a normal service start. */
    val action: String? = null,
    /** Target service ID, required by `reset-super-admin-password`. */
    val serviceId: String? = null,
)
