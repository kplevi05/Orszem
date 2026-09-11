package hu.orszembejelento.service.reports.data

import android.content.Context
import androidx.core.content.edit

/**
 * The signed-in user's "active work view" — a **local UI preference only** (brief §13-14).
 *
 * It holds one service-area id (or nothing = "all areas in my scope"). It is applied purely
 * as the `areaId` query parameter on the report queues, so it can only ever *narrow* what the
 * user is looking at within their real server-side scope. It is never sent as proof of
 * authority and never changes what the backend authorises.
 *
 * Keyed by service id, so a different user signing in on the same device never inherits the
 * previous user's view.
 */
interface ActiveWorkAreaStore {
    fun get(serviceId: String): String?
    fun set(serviceId: String, areaId: String?)
}

class SharedPrefsActiveWorkAreaStore(context: Context) : ActiveWorkAreaStore {

    private val prefs = context.getSharedPreferences("active_work_area", Context.MODE_PRIVATE)

    override fun get(serviceId: String): String? =
        prefs.getString(key(serviceId), null)?.takeIf { it.isNotBlank() }

    override fun set(serviceId: String, areaId: String?) {
        prefs.edit {
            if (areaId.isNullOrBlank()) remove(key(serviceId)) else putString(key(serviceId), areaId)
        }
    }

    private fun key(serviceId: String) = "area::$serviceId"
}
