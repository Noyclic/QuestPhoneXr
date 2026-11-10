package com.noyclic.questphonexr.runtime

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import java.security.MessageDigest
import java.util.Locale

/**
 * User-approved, package-scoped access for one app: read-only controller state
 * plus write-only runtime lifecycle telemetry.
 *
 * The providers keep signature permissions as their defaults. Explicit URI
 * grants are limited to controller_state (read) and runtime_event (write).
 * Consent is bound to the target app's signing certificate, not just its
 * package name, so a different app cannot inherit an old approval.
 */
data class EndXrInputClient internal constructor(
    val packageName: String,
    val label: String,
    internal val signingCertificateSha256: String
)

class EndXrInputAccessManager(context: Context) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun installedLaunchableClient(packageName: String): EndXrInputClient? {
        if (!EndXrInputGrantCodec.isValidPackageName(packageName)) return null
        val resolveInfo = queryLauncherActivities(packageName).firstOrNull() ?: return null
        val activity = resolveInfo.activityInfo ?: return null
        if (activity.packageName != packageName ||
            (activity.applicationInfo.flags and
                android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        ) return null
        val fingerprint = signingCertificateFingerprint(packageName) ?: return null
        val label = runCatching { resolveInfo.loadLabel(packageManager).toString() }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
            ?: packageName
        return EndXrInputClient(packageName, label, fingerprint)
    }

    fun installedLaunchableClients(): List<EndXrInputClient> =
        queryLauncherActivities()
            .asSequence()
            .mapNotNull { resolveInfo ->
                val activity = resolveInfo.activityInfo ?: return@mapNotNull null
                val packageName = activity.packageName?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                if (packageName == appContext.packageName) return@mapNotNull null
                if ((activity.applicationInfo.flags and
                        android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                ) return@mapNotNull null

                val fingerprint = signingCertificateFingerprint(packageName)
                    ?: return@mapNotNull null
                val label = runCatching { resolveInfo.loadLabel(packageManager).toString() }
                    .getOrNull()
                    ?.takeIf(String::isNotBlank)
                    ?: packageName
                EndXrInputClient(packageName, label, fingerprint)
            }
            .distinctBy { it.packageName }
            .sortedWith(
                compareBy<EndXrInputClient, String>(String.CASE_INSENSITIVE_ORDER) { it.label }
                    .thenBy { it.packageName }
            )
            .toList()

    fun isAuthorized(client: EndXrInputClient): Boolean =
        readRecords().any { EndXrInputGrantCodec.matches(it, client) }

    fun isRuntimeTelemetryAuthorized(client: EndXrInputClient): Boolean =
        readRecords().any {
            EndXrInputGrantCodec.matches(it, client) && it.runtimeTelemetryGranted
        }

    /**
     * Provider hot-path check. Controller input is queried around 50 times per
     * second by the game's background worker, so avoid doing PackageManager and
     * signing-certificate lookups for every IPC snapshot.
     */
    internal fun isPackageAuthorized(
        packageName: String,
        callingUid: Int,
        requireRuntimeTelemetry: Boolean
    ): Boolean {
        if (!EndXrInputGrantCodec.isValidPackageName(packageName)) return false
        val cacheKey = "$packageName|$callingUid|" +
            if (requireRuntimeTelemetry) "telemetry" else "input"
        val now = SystemClock.elapsedRealtime()
        val cached = CALLER_ACCESS_CACHE[cacheKey]
        if (cached != null) {
            val maxAgeMs = if (cached.allowed) 5_000L else 1_000L
            if (now - cached.checkedAtElapsedMs in 0L..maxAgeMs) return cached.allowed
        }

        val packageUid = runCatching {
            packageManager.getApplicationInfo(packageName, 0).uid
        }.getOrNull()
        val client = if (packageUid == callingUid) {
            installedLaunchableClient(packageName)
        } else {
            null
        }
        val allowed = client != null && (
            if (requireRuntimeTelemetry) {
                isRuntimeTelemetryAuthorized(client)
            } else {
                isAuthorized(client)
            }
        )
        CALLER_ACCESS_CACHE[cacheKey] = CachedCallerAuthorization(allowed, now)
        return allowed
    }

    /**
     * Re-applies approved temporary URI grants after the app is opened.
     * Stale approvals are removed when the package disappears or its signer changes.
     */
    fun reconcileGrants(): List<EndXrInputClient> {
        val installed = installedLaunchableClients().associateBy { it.packageName }
        return synchronized(GRANT_STATE_LOCK) {
            val retained = mutableListOf<EndXrInputGrantRecord>()
            val active = mutableListOf<EndXrInputClient>()

            readRecords().forEach { record ->
                val client = installed[record.packageName]
                if (client == null || !EndXrInputGrantCodec.matches(record, client)) {
                    revokeQuietly(record.packageName)
                    return@forEach
                }
                val granted = runCatching {
                    appContext.grantUriPermission(
                        record.packageName,
                        INPUT_URI,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    if (record.runtimeTelemetryGranted) {
                        appContext.grantUriPermission(
                            record.packageName,
                            RUNTIME_EVENT_URI,
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    } else {
                        appContext.revokeUriPermission(
                            record.packageName,
                            RUNTIME_EVENT_URI,
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    }
                }.isSuccess
                if (granted) {
                    retained += record
                    active += client
                } else {
                    revokeQuietly(record.packageName)
                }
            }

            writeRecords(retained)
            active
        }
    }

    /** Call only after the UI has shown the exact package and the user confirmed. */
    fun authorize(packageName: String): EndXrInputClient {
        require(EndXrInputGrantCodec.isValidPackageName(packageName)) {
            "Некорректное имя пакета."
        }
        val client = installedLaunchableClients().firstOrNull { it.packageName == packageName }
            ?: throw IllegalStateException(
                "Приложение не найдено среди установленных пользовательских приложений с ярлыком запуска."
            )

        synchronized(GRANT_STATE_LOCK) {
            try {
                appContext.grantUriPermission(
                    client.packageName,
                    INPUT_URI,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                appContext.grantUriPermission(
                    client.packageName,
                    RUNTIME_EVENT_URI,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                writeRecords(
                    readRecords().filterNot { it.packageName == client.packageName } +
                        EndXrInputGrantRecord(
                            client.packageName,
                            client.signingCertificateSha256,
                            runtimeTelemetryGranted = true
                        )
                )
            } catch (error: Throwable) {
                runCatching {
                    appContext.revokeUriPermission(
                        client.packageName,
                        INPUT_URI,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    appContext.revokeUriPermission(
                        client.packageName,
                        RUNTIME_EVENT_URI,
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }
                throw error
            }
        }
        return client
    }

    /** Revokes the selected package's controller-read and telemetry-write grants. */
    fun revoke(packageName: String): Boolean {
        if (!EndXrInputGrantCodec.isValidPackageName(packageName)) return false
        synchronized(GRANT_STATE_LOCK) {
            appContext.revokeUriPermission(
                packageName,
                INPUT_URI,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            appContext.revokeUriPermission(
                packageName,
                RUNTIME_EVENT_URI,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            writeRecords(readRecords().filterNot { it.packageName == packageName })
        }
        return true
    }

    private fun readRecords(): List<EndXrInputGrantRecord> =
        EndXrInputGrantCodec.decode(preferences.getStringSet(KEY_GRANTS, null))

    private fun writeRecords(records: Collection<EndXrInputGrantRecord>) {
        preferences.edit()
            .putStringSet(KEY_GRANTS, EndXrInputGrantCodec.encode(records))
            .apply()
        invalidateAuthorizationCache()
    }

    private fun revokeQuietly(packageName: String) {
        runCatching {
            appContext.revokeUriPermission(
                packageName,
                INPUT_URI,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            appContext.revokeUriPermission(
                packageName,
                RUNTIME_EVENT_URI,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    private fun queryLauncherActivities(packageName: String? = null): List<ResolveInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).apply {
            if (!packageName.isNullOrBlank()) setPackage(packageName)
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
    }

    private fun signingCertificateFingerprint(packageName: String): String? {
        val packageInfo = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(
                        PackageManager.GET_SIGNING_CERTIFICATES.toLong()
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
            }
        }.getOrNull() ?: return null

        val signers = packageInfo.signingInfo?.apkContentsSigners?.toList().orEmpty()
        if (signers.isEmpty()) return null
        val signerFingerprints = signers.map { signer ->
            MessageDigest.getInstance("SHA-256").digest(signer.toByteArray()).toHex()
        }.sorted()
        return MessageDigest.getInstance("SHA-256")
            .digest(signerFingerprints.joinToString("|").toByteArray(Charsets.US_ASCII))
            .toHex()
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }

    companion object {
        private data class CachedCallerAuthorization(
            val allowed: Boolean,
            val checkedAtElapsedMs: Long
        )

        private val GRANT_STATE_LOCK = Any()
        private val CALLER_ACCESS_CACHE = ConcurrentHashMap<String, CachedCallerAuthorization>()

        internal fun invalidateAuthorizationCache() {
            CALLER_ACCESS_CACHE.clear()
        }
        private const val PREFERENCES_NAME = "endxr_input_access"
        private const val KEY_GRANTS = "approved_package_signers"
        private val INPUT_URI: Uri = Uri.parse(EndXrInputContentProvider.CONTENT_URI)
        private val RUNTIME_EVENT_URI: Uri =
            Uri.parse(EndXrRuntimeEventContentProvider.CONTENT_URI)
    }
}

internal data class EndXrInputGrantRecord(
    val packageName: String,
    val signingCertificateSha256: String,
    val runtimeTelemetryGranted: Boolean = false
)

internal object EndXrInputGrantCodec {
    private val packageNamePattern =
        Regex("""[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+""")
    private val fingerprintPattern = Regex("[0-9a-f]{64}")

    fun isValidPackageName(value: String): Boolean = packageNamePattern.matches(value)
    fun isValidFingerprint(value: String): Boolean = fingerprintPattern.matches(value)

    /** An approval belongs to one exact package identity and signing certificate. */
    fun matches(record: EndXrInputGrantRecord, client: EndXrInputClient): Boolean =
        record.packageName == client.packageName &&
            record.signingCertificateSha256 == client.signingCertificateSha256

    fun encode(records: Collection<EndXrInputGrantRecord>): Set<String> =
        records.asSequence()
            .filter {
                isValidPackageName(it.packageName) &&
                    isValidFingerprint(it.signingCertificateSha256)
            }
            .distinctBy { it.packageName }
            .map {
                it.packageName + "|" + it.signingCertificateSha256 + "|" +
                    if (it.runtimeTelemetryGranted) "1" else "0"
            }
            .toSet()

    fun decode(values: Set<String>?): List<EndXrInputGrantRecord> =
        values.orEmpty().mapNotNull { value ->
            val parts = value.split('|')
            if (parts.size !in 2..3) return@mapNotNull null
            val packageName = parts[0]
            val fingerprint = parts[1]
            if (!isValidPackageName(packageName) || !isValidFingerprint(fingerprint)) {
                return@mapNotNull null
            }
            // Two-field records are grants created before runtime telemetry
            // existed. Preserve input access, but never silently add write access.
            val telemetryGranted = when (parts.getOrNull(2)) {
                null, "0" -> false
                "1" -> true
                else -> return@mapNotNull null
            }
            EndXrInputGrantRecord(packageName, fingerprint, telemetryGranted)
        }.distinctBy { it.packageName }
}
