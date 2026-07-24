package com.example.bantayex.features.welcome

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.bantayex.databinding.ActivityWelcomeBinding
import com.example.bantayex.features.auth.login.LoginActivity
import com.example.bantayex.features.student.entercode.EnterCodeActivity

/**
 * No ViewModel here on purpose: this screen has zero state and zero logic, just two
 * clicks that start other Activities. A ViewModel that holds nothing and does nothing
 * would be the kind of unnecessary abstraction we're avoiding. Add one only if this
 * screen ever needs to, e.g., check session state before deciding where to route.
 */
class WelcomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWelcomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.cardProfessor.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }

        binding.cardStudent.setOnClickListener {
            startActivity(Intent(this, EnterCodeActivity::class.java))
        }
    }
}