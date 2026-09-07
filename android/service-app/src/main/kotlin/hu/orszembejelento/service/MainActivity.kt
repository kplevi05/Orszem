package hu.orszembejelento.service

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import hu.orszembejelento.service.ui.OrszemServiceTheme
import hu.orszembejelento.service.ui.ServiceShell

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            OrszemServiceTheme {
                ServiceShell()
            }
        }
    }
}
