package com.example.bantayex

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.bantayex.features.welcome.WelcomeActivity

/**
 * Pure launcher redirect — no UI of its own, no ViewModel needed. This didn't exist at
 * all after the original Compose MainActivity was deleted in Step 1; this replaces it.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, WelcomeActivity::class.java))
        finish()
    }
}