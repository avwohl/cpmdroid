package com.romwbw.cpmdroid

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.IOException
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val FRAME_DELAY_MS = 16L // ~60fps
    }

    private lateinit var terminalView: TerminalView
    private lateinit var inputEditText: EditText
    private lateinit var sendButton: Button
    private lateinit var enterButton: Button

    private val emulator = EmulatorEngine()
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var romLoaded = false
    private var running = false

    private val runLoop = object : Runnable {
        override fun run() {
            if (running && romLoaded) {
                executor.execute {
                    if (emulator.runBatch()) {
                        mainHandler.postDelayed(this@runLoop, FRAME_DELAY_MS)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        terminalView = findViewById(R.id.terminalView)
        inputEditText = findViewById(R.id.inputEditText)
        sendButton = findViewById(R.id.sendButton)
        enterButton = findViewById(R.id.enterButton)

        setupEmulator()
        setupInput()
        loadDefaultRom()
    }

    private fun setupEmulator() {
        emulator.init()
        emulator.setOutputListener { data ->
            mainHandler.post {
                terminalView.processOutput(data)
            }
        }
    }

    private fun setupInput() {
        sendButton.setOnClickListener {
            val text = inputEditText.text.toString()
            if (text.isNotEmpty()) {
                emulator.queueInputString(text)
                inputEditText.text.clear()
            }
        }

        enterButton.setOnClickListener {
            emulator.queueInput(0x0D) // CR
        }

        inputEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                val text = inputEditText.text.toString()
                emulator.queueInputString(text)
                emulator.queueInput(0x0D) // CR
                inputEditText.text.clear()
                true
            } else {
                false
            }
        }
    }

    private fun loadDefaultRom() {
        executor.execute {
            try {
                // Try to load ROM from assets
                val romData = assets.open("romwbw.rom").use { it.readBytes() }
                Log.i(TAG, "ROM loaded from assets: ${romData.size} bytes")

                if (emulator.loadRom(romData)) {
                    // Try to load disk image if available
                    try {
                        val diskData = assets.open("disk0.img").use { it.readBytes() }
                        emulator.loadDisk(0, diskData)
                        Log.i(TAG, "Disk 0 loaded: ${diskData.size} bytes")
                    } catch (e: IOException) {
                        Log.i(TAG, "No disk image found in assets")
                    }

                    emulator.completeInit()
                    romLoaded = true

                    mainHandler.post {
                        Toast.makeText(this, "ROM loaded, starting emulator", Toast.LENGTH_SHORT).show()
                        startEmulation()
                    }
                } else {
                    mainHandler.post {
                        Toast.makeText(this, "Failed to load ROM", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "No ROM file found in assets", e)
                mainHandler.post {
                    Toast.makeText(this, "Place romwbw.rom in assets folder", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startEmulation() {
        if (!running && romLoaded) {
            running = true
            emulator.start()
            mainHandler.post(runLoop)
            Log.i(TAG, "Emulation started")
        }
    }

    private fun stopEmulation() {
        running = false
        emulator.stop()
        mainHandler.removeCallbacks(runLoop)
        Log.i(TAG, "Emulation stopped")
    }

    override fun onResume() {
        super.onResume()
        if (romLoaded) {
            startEmulation()
        }
    }

    override fun onPause() {
        super.onPause()
        stopEmulation()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopEmulation()
        executor.shutdown()
        emulator.destroy()
    }
}
