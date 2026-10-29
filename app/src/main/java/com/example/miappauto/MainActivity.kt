package com.example.miappauto

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "Abre Android Auto para usar la app"
            textSize = 18f
            setPadding(48, 96, 48, 48)
        })
    }
}
