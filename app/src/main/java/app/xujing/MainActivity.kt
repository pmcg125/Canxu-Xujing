package app.xujing

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import app.xujing.ui.XujingApp
import kotlinx.coroutines.launch

class MainActivity:ComponentActivity() {
    private lateinit var controller:AppController
    private var importKind="auto"
    private val importFile=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { controller.importUri(it,importKind) } }
    private val exportFile=registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> uri?.let { controller.writeExport(it) } }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        importKind=savedInstanceState?.getString("importKind") ?: "auto"
        controller=ViewModelProvider(this)[AppController::class.java]
        setContent {
            val state by controller.state.collectAsStateWithLifecycle()
            val runtime by controller.runtime.collectAsStateWithLifecycle()
            XujingApp(state,runtime,controller)
        }
        lifecycleScope.launch { controller.effects.collect { effect -> when(effect) {
            is UiEffect.Import -> { importKind=effect.kind; importFile.launch(if(effect.kind.startsWith("background:")) arrayOf("image/png","image/jpeg","image/webp") else arrayOf("*/*")) }
            is UiEffect.Export -> exportFile.launch(effect.name)
            is UiEffect.Browse -> runCatching { startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(effect.url))) }
        } } }
    }
    override fun onSaveInstanceState(outState:Bundle) { outState.putString("importKind",importKind); super.onSaveInstanceState(outState) }
}
