package com.legion.orion

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.legion.orion.core.design.OrionTheme
import com.legion.orion.ui.OrionApp
import dagger.hilt.android.AndroidEntryPoint

// FragmentActivity (not plain ComponentActivity) so it can host androidx Fragments — needed for
// the embedded Godot panel (GodotEmbedPanel), which places Godot's own RemoteGodotFragment inside
// a Compose screen via a FragmentContainerView + supportFragmentManager transaction.
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Forced-dark cosmic theme: transparent bars with light icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            OrionTheme {
                OrionApp()
            }
        }
    }
}
