package com.legion.orion.publish

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.legion.orion.data.publish.YouTubeAuth
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch

/**
 * Receives the YouTube OAuth consent redirect (com.legion.orion:/oauth2redirect?code=…),
 * exchanges the code for tokens, then closes. Re-opens MainActivity via the back stack.
 */
class OAuthRedirectActivity : ComponentActivity() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun auth(): YouTubeAuth
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val code = intent?.data?.getQueryParameter("code")
        val error = intent?.data?.getQueryParameter("error")
        if (code.isNullOrBlank()) {
            Toast.makeText(this, "YouTube sign-in failed: ${error ?: "no code"}", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val auth = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java).auth()
        lifecycleScope.launch {
            val ok = auth.exchangeCode(code).isSuccess
            Toast.makeText(
                this@OAuthRedirectActivity,
                if (ok) "YouTube connected" else "YouTube sign-in failed",
                Toast.LENGTH_LONG,
            ).show()
            finish()
        }
    }
}
