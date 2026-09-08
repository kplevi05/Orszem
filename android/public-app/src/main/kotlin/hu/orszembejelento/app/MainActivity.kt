package hu.orszembejelento.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import hu.orszembejelento.app.report.data.AppContainer
import hu.orszembejelento.app.ui.OrszemPublicTheme
import hu.orszembejelento.app.ui.nav.PublicNavGraph

class MainActivity : ComponentActivity() {

    private lateinit var container: AppContainer

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        container = AppContainer(applicationContext)
        setContent {
            OrszemPublicTheme {
                PublicNavGraph(container)
            }
        }
    }
}
