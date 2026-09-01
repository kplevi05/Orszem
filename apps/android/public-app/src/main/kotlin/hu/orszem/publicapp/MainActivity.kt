package hu.orszem.publicapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import hu.orszem.core.designsystem.OrszemPublicTheme
import hu.orszem.publicapp.navigation.PublicNavGraph
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            OrszemPublicTheme {
                PublicNavGraph()
            }
        }
    }
}
