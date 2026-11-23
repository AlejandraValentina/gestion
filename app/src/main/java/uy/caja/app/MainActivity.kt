package uy.caja.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import uy.caja.app.data.CajaDb
import uy.caja.app.ui.CajaViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dao = CajaDb.get(this).dao()
        setContent {
            val vm: CajaViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = CajaViewModel(dao) as T
            })
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF006B5D),
                    secondary = Color(0xFF52645F),
                    background = Color(0xFFF4FBF8)
                )
            ) {
                CajaApp(vm, dao, onExit = { finish() })
            }
        }
    }
}
