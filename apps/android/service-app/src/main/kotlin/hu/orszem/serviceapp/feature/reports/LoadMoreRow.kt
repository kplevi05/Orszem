package hu.orszem.serviceapp.feature.reports

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Emits [onAppear] when it enters composition (i.e. the list is scrolled to the end). */
@Composable
fun LoadMoreRow(onAppear: () -> Unit) {
    LaunchedEffect(Unit) { onAppear() }
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
