package hu.orszem.serviceapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import hu.orszem.core.designsystem.OrszemServiceTheme
import hu.orszem.serviceapp.navigation.ServiceNavHost

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            OrszemServiceTheme {
                ServiceNavHost()
            }
        }
    }
}
