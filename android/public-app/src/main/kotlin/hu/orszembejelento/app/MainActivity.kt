package hu.orszembejelento.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import hu.orszembejelento.app.ui.OrszemPublicTheme
import hu.orszembejelento.app.ui.PublicShell

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            OrszemPublicTheme {
                PublicShell()
            }
        }
    }
}
