package app.benzpro

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import app.benzpro.ui.BenzProScreen
import app.benzpro.ui.theme.BenzProTheme

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: BenzProViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        viewModel = ViewModelProvider(this)[BenzProViewModel::class.java]
        enableEdgeToEdge()
        setContent {
            BenzProTheme {
                BenzProScreen(viewModel)
            }
        }
    }

    override fun onDestroy() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }
}
