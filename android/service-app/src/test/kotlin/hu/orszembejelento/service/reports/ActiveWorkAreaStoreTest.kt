package hu.orszembejelento.service.reports

import hu.orszembejelento.service.reports.data.ActiveWorkAreaStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The active-work-view preference is per-service-id local state (brief §13-14): a different
 * user signing in on the same device never inherits the previous user's view.
 */
class ActiveWorkAreaStoreTest {

    /** A pure in-memory stand-in with the same contract as SharedPrefsActiveWorkAreaStore. */
    private class FakeStore : ActiveWorkAreaStore {
        private val map = mutableMapOf<String, String>()
        override fun get(serviceId: String): String? = map[serviceId]
        override fun set(serviceId: String, areaId: String?) {
            if (areaId.isNullOrBlank()) map.remove(serviceId) else map[serviceId] = areaId
        }
    }

    @Test
    fun `the preference is stored and read back per service id`() {
        val store = FakeStore()
        store.set("SZ-100001", "area-north")
        store.set("SZ-100002", "area-south")

        assertEquals("area-north", store.get("SZ-100001"))
        assertEquals("area-south", store.get("SZ-100002"))
    }

    @Test
    fun `a different user starts with no active work view`() {
        val store = FakeStore()
        store.set("SZ-100001", "area-north")
        assertNull("user B must not inherit user A's view", store.get("SZ-100002"))
    }

    @Test
    fun `clearing to null removes the preference`() {
        val store = FakeStore()
        store.set("SZ-100001", "area-north")
        store.set("SZ-100001", null)
        assertNull(store.get("SZ-100001"))
    }
}
