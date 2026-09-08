package hu.orszembejelento.app.ui.nav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import hu.orszembejelento.app.report.data.AppContainer
import hu.orszembejelento.app.ui.history.HistoryViewModel
import hu.orszembejelento.app.ui.newreport.NewReportViewModel

/** Plain manual factory - no DI framework, matching [AppContainer]'s own convention. */
class ViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T = when (modelClass) {
        NewReportViewModel::class.java -> NewReportViewModel(
            container.reportRepository,
            container.catalogRepository,
            container.referenceRepository,
            container.locationAssist,
        )
        HistoryViewModel::class.java -> HistoryViewModel(container.reportRepository)
        else -> error("unknown ViewModel class: $modelClass")
    } as T
}
