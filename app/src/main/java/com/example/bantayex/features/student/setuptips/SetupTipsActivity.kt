package com.example.bantayex.features.student.setuptips

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.bantayex.databinding.ActivitySetupTipsBinding
import com.example.bantayex.features.student.calibration.CalibrationActivity

/**
 * No ViewModel here on purpose, same reasoning as WelcomeActivity and
 * CalibrationActivity: this screen is static guidance plus two navigation actions.
 * There is no Firestore call and no state to hold — a ViewModel would only be an
 * empty pass-through wrapper around three intent extras.
 */
class SetupTipsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupTipsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupTipsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val examId = intent.getStringExtra("examId") ?: ""
        val examCode = intent.getStringExtra("examCode") ?: ""
        val studentName = intent.getStringExtra("studentName") ?: ""

        binding.btnBack.setOnClickListener { finish() }

        binding.btnContinue.setOnClickListener {
            val intent = Intent(this, CalibrationActivity::class.java)
            intent.putExtra("examId", examId)
            intent.putExtra("examCode", examCode)
            intent.putExtra("studentName", studentName)
            startActivity(intent)
            finish()
        }
    }
}
