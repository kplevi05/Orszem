package hu.orszembejelento.service.update.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Verification of a downloaded APK beyond its checksum. */
interface ApkVerifier {
    fun verify(apk: File): Boolean
}

/** Hands a verified APK to the system installer; the user always confirms in the system dialog. */
interface ApkInstaller {
    /** Whether this app may currently request package installs ("unknown sources" for this app). */
    fun canRequestInstalls(): Boolean

    /** Opens the system page where the user allows installs from this app. */
    fun openInstallPermissionSettings()

    /** Starts a PackageInstaller session; the outcome arrives on [InstallEvents]. */
    fun install(apk: File)
}

/**
 * Defence in depth on top of the SHA-256 check: the archive must be our own package and,
 * whenever the platform can read the archive's signer, signed by exactly the certificate set
 * the running app is signed with. Android enforces the signer match itself on update, so a
 * platform that cannot read archive signers still cannot be tricked into replacing the app.
 */
class AndroidApkVerifier(private val context: Context) : ApkVerifier {

    override fun verify(apk: File): Boolean {
        val pm = context.packageManager
        val archive = archiveInfo(pm, apk) ?: return false
        if (archive.packageName != context.packageName) return false
        val installed = installedInfo(pm) ?: return false
        val archiveCerts = certDigests(archive)
        val installedCerts = certDigests(installed)
        if (archiveCerts.isEmpty() || installedCerts.isEmpty()) return true
        return archiveCerts == installedCerts
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, apk: File): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        return pm.getPackageArchiveInfo(apk.absolutePath, flags)?.also { info ->
            // Older platforms only fill signatures when the source path is set.
            info.applicationInfo?.sourceDir = apk.absolutePath
            info.applicationInfo?.publicSourceDir = apk.absolutePath
        }
    }

    @Suppress("DEPRECATION")
    private fun installedInfo(pm: PackageManager): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    @Suppress("DEPRECATION")
    private fun certDigests(info: PackageInfo): Set<String> {
        val signatures: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.let { signing ->
                if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
            }
        } else {
            info.signatures
        }
        return signatures.orEmpty().map { sha256(it.toByteArray()) }.toSet()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

class AndroidApkInstaller(private val context: Context) : ApkInstaller {

    override fun canRequestInstalls(): Boolean = context.packageManager.canRequestPackageInstalls()

    override fun openInstallPermissionSettings() {
        val intent = Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            android.net.Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    override fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val statusIntent = Intent(context, UpdateInstallReceiver::class.java)
                .setAction(UpdateInstallReceiver.ACTION_INSTALL_STATUS)
            // MUTABLE is required: the system fills the status extras into this intent.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, sessionId, statusIntent, flags)
            session.commit(pending.intentSender)
        }
    }
}

/** Process-wide channel from [UpdateInstallReceiver] back to the update UI. */
object InstallEvents {
    sealed interface Event {
        data object Aborted : Event
        data object Failed : Event
    }

    private val events = MutableSharedFlow<Event>(extraBufferCapacity = 4)
    val flow: SharedFlow<Event> = events.asSharedFlow()

    internal fun emit(event: Event) {
        events.tryEmit(event)
    }
}

/**
 * Receives PackageInstaller status callbacks. On PENDING_USER_ACTION it opens the system
 * confirmation dialog; on success the platform replaces the running app, so nothing needs
 * reporting; every failure is surfaced so the user can retry.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = confirmationIntent(intent) ?: run {
                    InstallEvents.emit(InstallEvents.Event.Failed)
                    return
                }
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_FAILURE_ABORTED -> InstallEvents.emit(InstallEvents.Event.Aborted)
            else -> InstallEvents.emit(InstallEvents.Event.Failed)
        }
    }

    @Suppress("DEPRECATION")
    private fun confirmationIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    companion object {
        const val ACTION_INSTALL_STATUS = "hu.orszembejelento.service.update.INSTALL_STATUS"
    }
}
