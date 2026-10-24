package com.tankistador.game

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.EditText

class MainActivity : Activity(), Host {
    private lateinit var view: GameView
    private lateinit var sound: Sound
    private lateinit var music: Music

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sound = Sound(this)
        music = Music(this)
        val prefs = getSharedPreferences("tankistador", Context.MODE_PRIVATE)
        val kv = object : KeyValue {
            override fun get(key: String): String? = prefs.getString(key, null)
            override fun put(key: String, value: String?) {
                prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
            }
        }
        val game = Game(this, kv, AndroidSound(sound), music)
        view = GameView(this, game)
        setContentView(view)
        game.start()
    }

    // The app does not opt into predictive back, so the system still delivers this callback.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        view.game.back()
    }

    override fun onResume() {
        super.onResume()
        hideBars()
        view.resume()
        music.resume()
    }

    override fun onPause() {
        view.pause()
        music.pause()
        super.onPause()
    }

    override fun onDestroy() {
        sound.release()
        music.release()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    @Suppress("DEPRECATION")
    private fun hideBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    override fun askText(title: String, initial: String, onDone: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            setSelection(initial.length)
            setSingleLine()
            filters = arrayOf(InputFilter.LengthFilter(14))
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) view.post { onDone(t) }
            }
            .setNegativeButton("Отмена", null)
            .setOnDismissListener { hideBars() }
            .show()
    }

    override fun quit() = finish()
}
