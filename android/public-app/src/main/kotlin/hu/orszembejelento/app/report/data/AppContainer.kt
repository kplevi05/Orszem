package hu.orszembejelento.app.report.data

import android.content.Context
import hu.orszembejelento.app.location.LocationAssist
import hu.orszembejelento.app.report.data.crypto.KeystoreCryptoBox
import hu.orszembejelento.app.report.data.local.ReportHistoryDatabase
import hu.orszembejelento.app.report.data.network.NetworkModule

/**
 * Plain manual construction - one Room database, one Retrofit client, a handful of small
 * repositories. A dependency-injection framework would be more machinery than a
 * three-screen app needs (mirrors the Service app's own `NetworkModule` convention).
 */
class AppContainer(context: Context) {

    private val database = ReportHistoryDatabase.build(context)
    private val api = NetworkModule.createApi()

    val reportRepository = ReportRepository(
        dao = database.reportHistoryDao(),
        cryptoBox = KeystoreCryptoBox(),
        api = api,
    )
    val catalogRepository = CatalogRepository(api)
    val referenceRepository = ReferenceRepository(api)
    val locationAssist = LocationAssist(context)
}
