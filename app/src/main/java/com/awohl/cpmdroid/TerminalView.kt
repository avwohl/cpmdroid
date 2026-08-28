// Backs rows 1, 2, 3, 9 and 13 of z80cpmw/FEATURE_PARITY.md - a change here dates that column.
package com.awohl.cpmdroid

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val MIN_ROWS = 24
        // Length of the terminal bell, and of the audio focus it takes.
        private const val BELL_MS = 100
        private const val MIN_COLS = 80
    }

    // Dynamic terminal dimensions based on screen size
    private var rows = MIN_ROWS
    private var cols = MIN_COLS

    // How many columns actually fit on screen (may be less than cols with larger fonts)
    private var visibleCols = MIN_COLS

    private var screenBuffer = Array(rows) { CharArray(cols) { ' ' } }
    private var colorBuffer = Array(rows) { IntArray(cols) { Color.GREEN } }

    private var cursorRow = 0
    private var cursorCol = 0
    private var cursorVisible = true

    private var charWidth = 0f
    private var charHeight = 0f

    // --- Scrollback ---
    // Lines that have scrolled off the top of the live screen (oldest first).
    private val historyChars = ArrayDeque<CharArray>()
    private val historyColors = ArrayDeque<IntArray>()
    // Max history lines kept (0 disables scrollback). Matches the other ports'
    // default, and is now a Settings entry rather than a constant - so it can
    // shrink while lines are already being held, which the setter has to
    // honour immediately: leaving them until the next scroll would show a user
    // who just chose "Off" a screen they can still drag back through.
    var scrollbackLines: Int = 1000
        set(value) {
            val bounded = value.coerceAtLeast(0)
            field = bounded
            trimHistory()
        }

    private fun trimHistory() {
        while (historyChars.size > scrollbackLines) {
            historyChars.removeFirst()
            historyColors.removeFirst()
        }
        // The user may have been looking further back than what is left.
        if (userScrollUp > historyChars.size) {
            userScrollUp = historyChars.size
        }
        invalidate()
    }
    // How many lines the user has dragged up from the live bottom (0 = at the live prompt).
    private var userScrollUp = 0
    // The full (keyboard-hidden) view height, so the soft keyboard shrinking the view
    // scrolls (see onDraw) instead of shrinking the font. Reset on rotation.
    private var fullHeight = 0
    // Touch tracking: drag-to-scroll vs. tap-to-show-keyboard.
    private var touchDownY = 0f
    private var touchDownScrollUp = 0
    private var isDragging = false

    private val textPaint = Paint().apply {
        color = Color.GREEN
        typeface = Typeface.MONOSPACE
        isAntiAlias = true
        textSize = 32f
    }

    // Font scale factor - 14 = 100% (default), 8 = smaller, 24 = larger
    // This scales the calculated optimal font size
    private val defaultFontScale = 14f
    var fontScaleSetting: Float = defaultFontScale
        set(value) {
            field = value
            if (width > 0 && height > 0) {
                calculateFontSize()
                invalidate()
            }
        }

    // For compatibility - maps to fontScaleSetting
    var customFontSize: Float
        get() = fontScaleSetting
        set(value) { fontScaleSetting = value }

    // Whether to wrap long lines (true) or truncate them (false)
    var wrapLines: Boolean = false
        set(value) {
            field = value
            android.util.Log.i("TerminalView", "wrapLines set to: $value")
        }

    private val bgPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }

    private val cursorPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.FILL
    }

    // VT100 escape sequence parsing state
    private var escapeState = 0
    private val escapeParams = StringBuilder()
    private var currentFgColor = Color.GREEN

    // Bell sound generator
    private var toneGenerator: ToneGenerator? = null
    var soundEnabled: Boolean = false

    // CGA color palette
    private val cgaColors = intArrayOf(
        Color.BLACK,           // 0 - Black
        Color.rgb(0, 0, 170),  // 1 - Blue
        Color.rgb(0, 170, 0),  // 2 - Green
        Color.rgb(0, 170, 170),// 3 - Cyan
        Color.rgb(170, 0, 0),  // 4 - Red
        Color.rgb(170, 0, 170),// 5 - Magenta
        Color.rgb(170, 85, 0), // 6 - Brown
        Color.rgb(170, 170, 170), // 7 - Light Gray
        Color.rgb(85, 85, 85), // 8 - Dark Gray
        Color.rgb(85, 85, 255),// 9 - Light Blue
        Color.rgb(85, 255, 85),// 10 - Light Green
        Color.rgb(85, 255, 255),// 11 - Light Cyan
        Color.rgb(255, 85, 85),// 12 - Light Red
        Color.rgb(255, 85, 255),// 13 - Light Magenta
        Color.rgb(255, 255, 85),// 14 - Yellow
        Color.WHITE            // 15 - White
    )

    // Input handling
    private var inputListener: ((Int) -> Unit)? = null

    fun setInputListener(listener: (Int) -> Unit) {
        inputListener = listener
    }

    /**
     * Play the bell (0x07 BEL), off by default and behind a setting.
     *
     * The tone declares itself as a sonification and takes transient audio
     * focus for the length of the beep. Without either, a CP/M program that
     * rings the bell - and some ring it per keystroke - stopped whatever the
     * user was listening to outright, because a bare STREAM_SYSTEM
     * ToneGenerator tells the system nothing about what kind of sound this is
     * and nothing asks for or gives back focus. AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
     * asks the music to duck for a moment instead, which is what a terminal
     * bell should cost.
     *
     * This is the only sound the app can make: emu_dsky_beep() is an empty
     * stub in emu_io_android.cpp, so the HBIOS SND and DSKY paths are silent
     * on Android.
     */
    private fun playBell() {
        if (!soundEnabled) return
        try {
            if (toneGenerator == null) {
                toneGenerator = ToneGenerator(AudioManager.STREAM_SYSTEM, 50) // 50% volume
            }
            val gotFocus = requestBellAudioFocus()
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, BELL_MS)
            // Give it straight back. The tone is asynchronous, so wait out its
            // own length first - holding focus any longer would duck the
            // user's audio for as long as the app stayed open. Repeated bells
            // just re-post; the last one wins and the focus is still released.
            if (gotFocus) {
                removeCallbacks(abandonBellFocus)
                postDelayed(abandonBellFocus, BELL_MS.toLong() + 50)
            }
        } catch (e: Exception) {
            // Ignore audio errors - some devices may not support this
        }
    }

    // The bell asks for nothing back, so one no-op listener is enough. It has
    // to be the same object at request and abandon time on the pre-O API, or
    // the abandon does not match.
    private val bellFocusListener = AudioManager.OnAudioFocusChangeListener { }
    private val abandonBellFocus = Runnable { abandonBellAudioFocus() }
    private var bellFocusRequest: AudioFocusRequest? = null

    /** True if focus was granted and must be given back. */
    private fun requestBellAudioFocus(): Boolean {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return false
        val result = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val request = bellFocusRequest
                ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener(bellFocusListener)
                    .build()
                    .also { bellFocusRequest = it }
            manager.requestAudioFocus(request)
        } else {
            // API 24-25. The typed request does not exist there, and the
            // stream is the only thing that can carry the intent.
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(
                bellFocusListener,
                AudioManager.STREAM_SYSTEM,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonBellAudioFocus() {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            bellFocusRequest?.let { manager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(bellFocusListener)
        }
    }

    /** Copy entire screen content to clipboard */
    fun copyScreenToClipboard(): Boolean {
        // Scrollback included. Copying only the live rows was the wrong half of
        // what the user can see: the point of scrollback is the output that has
        // already left the screen, and a DIR or a long assembly listing is
        // exactly the thing someone reaches for Copy to keep. With scrollback
        // off there is no history and this is the live screen alone, as before.
        val text = StringBuilder()
        for (line in historyChars) {
            text.append(String(line).trimEnd())
            text.append('\n')
        }
        for (row in 0 until rows) {
            val line = String(screenBuffer[row]).trimEnd()
            text.append(line)
            if (row < rows - 1) text.append("\n")
        }
        // Remove trailing empty lines
        val content = text.toString().trimEnd('\n')
        if (content.isEmpty()) return false

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Terminal Screen", content))
        return true
    }

    /** Paste clipboard content as keyboard input */
    fun pasteFromClipboard(): Boolean {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip ?: return false
        if (clip.itemCount == 0) return false

        val text = clip.getItemAt(0).coerceToText(context)?.toString() ?: return false
        if (text.isEmpty()) return false

        // Send each character as input (converting newlines to CR)
        for (ch in text) {
            when (ch) {
                '\n', '\r' -> sendChar(0x0D)
                else -> if (ch.code in 0..127) sendChar(ch.code)
            }
        }
        return true
    }

    private fun sendChar(ch: Int) {
        // Only send ASCII characters (0-127)
        if (ch in 0..127) {
            inputListener?.invoke(ch)
        }
    }

    /**
     * Send one function key, F1..F12, as the VT220/xterm sequence every
     * sibling port sends: F1-F4 are SS3 (ESC O P..S) and F5 up are CSI ~ with
     * a parameter. The parameter numbers are not contiguous - 16 and 22 are
     * unassigned - which is why this is a table rather than arithmetic.
     */
    private fun sendFunctionKey(n: Int) {
        if (n < 1 || n > 12) return
        if (n <= 4) {
            sendChar(0x1B); sendChar('O'.code); sendChar('P'.code + (n - 1))
            return
        }
        val param = intArrayOf(15, 17, 18, 19, 20, 21, 23, 24)[n - 5]
        sendChar(0x1B); sendChar('['.code)
        for (c in param.toString()) sendChar(c.code)
        sendChar('~'.code)
    }

    /**
     * The control byte for a Ctrl+key press, or -1 if this key has none.
     *
     * The window is '@' (0x00) through '_' (0x1F) after uppercasing, which is
     * the same window the on-screen Ctrl button accepts - so the two input
     * paths now agree. It covers Ctrl+A..Z and also Ctrl+[ (ESC), Ctrl+\
     * (FS), Ctrl+] (GS), Ctrl+^ (RS), Ctrl+_ (US) and Ctrl+@ (NUL), all of
     * which CP/M programs use and none of which the old keycode-only test
     * could produce.
     *
     * It asks the layout what the key would have typed rather than hard-coding
     * keycodes, because the key carrying '[' is not KEYCODE_LEFT_BRACKET on
     * every layout. The keycode path is kept only as the fallback for a key
     * the layout maps to nothing.
     */
    private fun controlByteFor(keyCode: Int, event: KeyEvent): Int {
        val ctrlBits = KeyEvent.META_CTRL_ON or
                KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_CTRL_RIGHT_ON
        var ch = event.getUnicodeChar(event.metaState and ctrlBits.inv())

        if (ch == 0) {
            if (keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
                return keyCode - KeyEvent.KEYCODE_A + 1
            }
            if (keyCode == KeyEvent.KEYCODE_SPACE) return 0
            return -1
        }

        if (ch in 'a'.code..'z'.code) ch -= 32
        return when {
            // Ctrl+Space is NUL, as it is on every other port - a real byte a
            // CP/M program can be waiting for. Space is outside the '@'..'_'
            // window, so it needs saying separately.
            ch == ' '.code -> 0
            ch in '@'.code..'_'.code -> ch - '@'.code
            else -> -1
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchDownY = event.y
                touchDownScrollUp = userScrollUp
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = event.y - touchDownY
                if (!isDragging && kotlin.math.abs(dy) > charHeight / 2f) isDragging = true
                if (isDragging && charHeight > 0f) {
                    // Drag DOWN reveals older history (scroll up); drag UP returns toward live.
                    val lines = (dy / charHeight).toInt()
                    userScrollUp = (touchDownScrollUp + lines).coerceIn(0, historyChars.size)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    // Tap (not a drag): focus and show the keyboard.
                    requestFocus()
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
        return true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // Use TYPE_NULL to prevent IME from intercepting hardware keyboard events
        outAttrs.inputType = InputType.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE
        return TerminalInputConnection(this, true)
    }

    private inner class TerminalInputConnection(
        targetView: View,
        fullEditor: Boolean
    ) : BaseInputConnection(targetView, fullEditor) {

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            text?.forEach { ch ->
                sendChar(ch.code and 0x7F)
            }
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            // Send backspace for delete
            if (beforeLength > 0) {
                repeat(beforeLength) { sendChar(0x08) }
            }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) {
                return handleKeyDown(event.keyCode, event)
            }
            return super.sendKeyEvent(event)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return handleKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)
    }

    private fun handleKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val ctrl = event.isCtrlPressed
        val unicodeChar = event.unicodeChar

        when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> {
                sendChar(0x0D) // CR
                return true
            }
            KeyEvent.KEYCODE_DEL -> {
                sendChar(0x08) // Backspace
                return true
            }
            KeyEvent.KEYCODE_TAB -> {
                sendChar(0x09) // Tab
                return true
            }
            KeyEvent.KEYCODE_ESCAPE -> {
                sendChar(0x1B) // ESC
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                sendChar(0x1B); sendChar('['.code); sendChar('A'.code)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                sendChar(0x1B); sendChar('['.code); sendChar('B'.code)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                sendChar(0x1B); sendChar('['.code); sendChar('C'.code)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                sendChar(0x1B); sendChar('['.code); sendChar('D'.code)
                return true
            }
            // F1-F12. unicodeChar is 0 for these and there is no keycode
            // fallback below that covers them, so before this they fell all
            // the way through to `else -> -1` and were dropped: a hardware
            // keyboard's function keys did nothing at all in CP/M.
            //
            // The bytes are the VT220/xterm set every sibling port sends -
            // F1-F4 as SS3 (ESC O P..S), F5 and up as CSI ~ with a number -
            // so a WordStar-family editor keyed for one front end behaves the
            // same here. Nothing on Android competes for these, so no setting
            // gates them.
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> {
                sendFunctionKey(keyCode - KeyEvent.KEYCODE_F1 + 1)
                return true
            }
            else -> {
                // Ctrl + a key that has a control byte. The window is '@'
                // (0x00) through '_' (0x1F), not just the letters: Ctrl+[ is
                // ESC, Ctrl+\ is FS, Ctrl+] is GS, Ctrl+@ and Ctrl+Space are
                // NUL, and CP/M programs use all of them. The old test was
                // KEYCODE_A..KEYCODE_Z only, so a hardware keyboard could not
                // produce any of those five while the on-screen Ctrl button
                // could - the software path was the better one, which is
                // backwards.
                if (ctrl) {
                    val ctrlChar = controlByteFor(keyCode, event)
                    if (ctrlChar >= 0) {
                        sendChar(ctrlChar)
                        return true
                    }
                }

                // Handle printable characters from hardware keyboard
                if (!event.isCtrlPressed && !event.isAltPressed) {
                    // Try unicodeChar first
                    if (unicodeChar != 0) {
                        sendChar(unicodeChar and 0x7F)
                        return true
                    }
                    // Fallback: map keycodes directly for letters and digits
                    val ch = when (keyCode) {
                        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> {
                            val base = if (event.isShiftPressed) 'A'.code else 'a'.code
                            base + (keyCode - KeyEvent.KEYCODE_A)
                        }
                        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                            '0'.code + (keyCode - KeyEvent.KEYCODE_0)
                        }
                        KeyEvent.KEYCODE_SPACE -> ' '.code
                        KeyEvent.KEYCODE_COMMA -> ','.code
                        KeyEvent.KEYCODE_PERIOD -> '.'.code
                        KeyEvent.KEYCODE_SLASH -> '/'.code
                        KeyEvent.KEYCODE_MINUS -> '-'.code
                        KeyEvent.KEYCODE_EQUALS -> '='.code
                        KeyEvent.KEYCODE_SEMICOLON -> ';'.code
                        KeyEvent.KEYCODE_APOSTROPHE -> '\''.code
                        KeyEvent.KEYCODE_LEFT_BRACKET -> '['.code
                        KeyEvent.KEYCODE_RIGHT_BRACKET -> ']'.code
                        KeyEvent.KEYCODE_BACKSLASH -> '\\'.code
                        KeyEvent.KEYCODE_GRAVE -> '`'.code
                        else -> -1
                    }
                    if (ch >= 0) {
                        sendChar(ch)
                        return true
                    }
                }
            }
        }
        return false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Track the full (keyboard-hidden) height. A width change means a rotation/reflow
        // (reset); otherwise keep the tallest height seen so hiding the keyboard restores it
        // and showing the keyboard (shorter h) does not shrink the font.
        fullHeight = if (w != oldw) h else maxOf(fullHeight, h)
        android.util.Log.i("TerminalView", "onSizeChanged: w=$w, h=$h, fullHeight=$fullHeight")
        calculateFontSize()
    }

    override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
        super.setPadding(left, top, right, bottom)
        android.util.Log.i("TerminalView", "setPadding: bottom=$bottom")
        if (width > 0 && height > 0) {
            calculateFontSize()
            invalidate()
        }
    }

    /** Force recalculation of terminal dimensions after layout changes */
    fun recalculateSize() {
        post {
            calculateFontSize()
            invalidate()
        }
    }

    private fun calculateFontSize() {
        if (width == 0 || height == 0) return

        // Account for padding when calculating available space
        val availableWidth = width - paddingLeft - paddingRight
        val availableHeight = height - paddingTop - paddingBottom
        if (availableWidth <= 0 || availableHeight <= 0) return

        // Start with preferred font size (11sp)
        var baseFontSize = 11f * resources.displayMetrics.scaledDensity
        textPaint.textSize = baseFontSize
        charWidth = textPaint.measureText("M")

        // Reduce font size if needed to fit at least MIN_COLS columns
        val maxCharWidth = availableWidth.toFloat() / MIN_COLS
        if (charWidth > maxCharWidth) {
            // Scale down font to fit MIN_COLS
            baseFontSize *= (maxCharWidth / charWidth)
        }

        // Apply user's font scale (14 = 100%, 8 = ~57%, 24 = ~171%)
        val scaleFactor = fontScaleSetting / defaultFontScale
        var finalFontSize = baseFontSize * scaleFactor
        textPaint.textSize = finalFontSize
        charWidth = textPaint.measureText("M")
        charHeight = textPaint.fontMetrics.descent - textPaint.fontMetrics.ascent

        // Also shrink the font so the whole fixed-height live screen (MIN_ROWS) fits the
        // FULL (keyboard-hidden) height. The keyboard case scrolls (see onDraw) rather than
        // shrinking, so size against fullHeight, not the possibly-shrunk current height.
        val fitHeight = (if (fullHeight > 0) fullHeight else height) - paddingTop - paddingBottom
        if (charHeight > 0f && charHeight * MIN_ROWS > fitHeight) {
            finalFontSize *= fitHeight / (charHeight * MIN_ROWS)
            textPaint.textSize = finalFontSize
            charWidth = textPaint.measureText("M")
            charHeight = textPaint.fontMetrics.descent - textPaint.fontMetrics.ascent
        }

        // Calculate how many columns actually fit on screen at this font size
        visibleCols = maxOf(1, (availableWidth / charWidth).toInt())

        // Fixed live screen: MIN_ROWS tall (standard CP/M screen); extra vertical space
        // shows scrollback history. Columns still fill the width.
        val newCols = maxOf(MIN_COLS, visibleCols)
        val newRows = MIN_ROWS

        // Resize buffers if dimensions changed
        if (newCols != cols || newRows != rows) {
            resizeBuffers(newRows, newCols)
        }

        android.util.Log.i("TerminalView", "calculateFontSize: baseFontSize=$baseFontSize, scaleFactor=$scaleFactor, finalFontSize=$finalFontSize, charWidth=$charWidth, rows=$rows, cols=$cols, visibleCols=$visibleCols")
    }

    private fun resizeBuffers(newRows: Int, newCols: Int) {
        val oldRows = rows
        val oldCols = cols
        val oldScreenBuffer = screenBuffer
        val oldColorBuffer = colorBuffer

        rows = newRows
        cols = newCols

        // Create new buffers
        screenBuffer = Array(rows) { CharArray(cols) { ' ' } }
        colorBuffer = Array(rows) { IntArray(cols) { Color.GREEN } }

        // Copy old content (as much as fits)
        for (r in 0 until minOf(oldRows, rows)) {
            for (c in 0 until minOf(oldCols, cols)) {
                screenBuffer[r][c] = oldScreenBuffer[r][c]
                colorBuffer[r][c] = oldColorBuffer[r][c]
            }
        }

        // Adjust cursor position if needed
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        cursorCol = cursorCol.coerceIn(0, cols - 1)

        android.util.Log.i("TerminalView", "resizeBuffers: $oldRows x $oldCols -> $rows x $cols")
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        if (charHeight <= 0f) return
        val baseline = -textPaint.fontMetrics.ascent
        val offsetX = paddingLeft.toFloat()
        val offsetY = paddingTop.toFloat()
        val availableHeight = height - paddingTop - paddingBottom
        val viewportRows = maxOf(1, (availableHeight / charHeight).toInt())

        if (viewportRows < rows) {
            // Live screen taller than the viewport (soft keyboard up / very short view):
            // scroll within the live screen so the cursor stays visible. Font unchanged.
            val scrollRows = (cursorRow - viewportRows + 1).coerceIn(0, rows - viewportRows)
            for (r in 0 until viewportRows) {
                val liveRow = scrollRows + r
                if (liveRow >= rows) break
                drawRow(canvas, screenBuffer[liveRow], colorBuffer[liveRow], r, offsetX, offsetY, baseline)
            }
            drawCursor(canvas, cursorRow - scrollRows, viewportRows, offsetX, offsetY)
        } else {
            // Live screen fits: anchor it at the bottom, scrollback history fills above.
            // Combined content = [history..., live 0..rows-1]; the user drags up into history.
            val historySize = historyChars.size
            val contentRows = historySize + rows
            val maxScroll = maxOf(0, contentRows - viewportRows)
            val scroll = userScrollUp.coerceIn(0, maxScroll)
            val topLine = contentRows - viewportRows - scroll   // content-line index at viewport row 0
            for (r in 0 until viewportRows) {
                val lineIdx = topLine + r
                if (lineIdx < 0 || lineIdx >= contentRows) continue   // empty area above the history
                if (lineIdx < historySize) {
                    drawRow(canvas, historyChars[lineIdx], historyColors[lineIdx], r, offsetX, offsetY, baseline)
                } else {
                    val liveRow = lineIdx - historySize
                    drawRow(canvas, screenBuffer[liveRow], colorBuffer[liveRow], r, offsetX, offsetY, baseline)
                    if (liveRow == cursorRow) drawCursor(canvas, r, viewportRows, offsetX, offsetY)
                }
            }
        }
    }

    private fun drawRow(canvas: Canvas, chars: CharArray, colors: IntArray, vrow: Int,
                        offsetX: Float, offsetY: Float, baseline: Float) {
        val y = offsetY + vrow * charHeight + baseline
        val n = minOf(chars.size, cols)
        for (col in 0 until n) {
            val ch = chars[col]
            if (ch != ' ') {
                textPaint.color = colors[col]
                canvas.drawText(ch.toString(), offsetX + col * charWidth, y, textPaint)
            }
        }
    }

    private fun drawCursor(canvas: Canvas, vrow: Int, viewportRows: Int,
                           offsetX: Float, offsetY: Float) {
        if (!cursorVisible || vrow < 0 || vrow >= viewportRows || cursorCol >= cols) return
        val x = offsetX + cursorCol * charWidth
        val y = offsetY + vrow * charHeight + charHeight - 4f
        canvas.drawRect(x, y, x + charWidth, y + 3f, cursorPaint)
    }

    private var processOutputCount = 0
    fun processOutput(data: ByteArray) {
        if (processOutputCount++ < 3) {
            android.util.Log.i("TerminalView", "processOutput: ${data.size} bytes, charWidth=$charWidth, charHeight=$charHeight")
        }
        if (data.isNotEmpty()) userScrollUp = 0   // snap back to the live prompt on new output
        for (b in data) {
            processChar(b.toInt() and 0xFF)
        }
        invalidate()
    }

    private fun processChar(ch: Int) {
        when (escapeState) {
            0 -> { // Normal state
                when (ch) {
                    0x1B -> escapeState = 1 // ESC
                    0x0D -> cursorCol = 0   // CR
                    0x0A -> newLine()       // LF
                    0x08 -> if (cursorCol > 0) cursorCol-- // BS
                    0x09 -> { // TAB - next 8-column stop
                        // Dropped entirely before this: the `else` branch only
                        // prints 0x20 and above, so every tab vanished and any
                        // program that lays out columns with them - PIP's
                        // listings, most assemblers' output - ran its columns
                        // together. Same rule as the other ports.
                        cursorCol = ((cursorCol + 8) and 7.inv()).coerceAtMost(cols - 1)
                    }
                    0x07 -> playBell() // BEL - beep
                    else -> {
                        if (ch >= 0x20) {
                            putChar(ch.toChar())
                        }
                    }
                }
            }
            1 -> { // After ESC
                when (ch) {
                    '['.code -> {
                        escapeState = 2
                        escapeParams.clear()
                    }
                    else -> escapeState = 0
                }
            }
            2 -> { // CSI sequence
                when {
                    ch in 0x30..0x3F -> {
                        escapeParams.append(ch.toChar())
                    }
                    ch in 0x40..0x7E -> {
                        processCSI(ch.toChar())
                        escapeState = 0
                    }
                    else -> escapeState = 0
                }
            }
        }
    }

    private fun processCSI(command: Char) {
        val params = escapeParams.toString().split(";").mapNotNull { it.toIntOrNull() }

        when (command) {
            'H', 'f' -> { // Cursor position
                cursorRow = (params.getOrElse(0) { 1 } - 1).coerceIn(0, rows - 1)
                cursorCol = (params.getOrElse(1) { 1 } - 1).coerceIn(0, cols - 1)
            }
            'A' -> cursorRow = (cursorRow - params.getOrElse(0) { 1 }).coerceAtLeast(0)
            'B' -> cursorRow = (cursorRow + params.getOrElse(0) { 1 }).coerceAtMost(rows - 1)
            'C' -> cursorCol = (cursorCol + params.getOrElse(0) { 1 }).coerceAtMost(cols - 1)
            'D' -> cursorCol = (cursorCol - params.getOrElse(0) { 1 }).coerceAtLeast(0)
            'J' -> { // Erase display
                when (params.getOrElse(0) { 0 }) {
                    0 -> clearToEnd()
                    1 -> clearToBeginning()
                    2 -> clearScreen()
                }
            }
            'K' -> { // Erase line
                when (params.getOrElse(0) { 0 }) {
                    0 -> clearLineToEnd()
                    1 -> clearLineToBeginning()
                    2 -> clearLine()
                }
            }
            'm' -> { // SGR - Select Graphic Rendition
                if (params.isEmpty()) {
                    currentFgColor = Color.GREEN
                } else {
                    for (p in params) {
                        when {
                            p == 0 -> currentFgColor = Color.GREEN
                            p in 30..37 -> currentFgColor = cgaColors[p - 30]
                            p in 90..97 -> currentFgColor = cgaColors[p - 90 + 8]
                        }
                    }
                }
            }
        }
    }

    private fun putChar(ch: Char) {
        // When wrap is enabled, wrap at visible screen edge
        // When wrap is disabled, truncate at buffer edge (cols)
        val wrapAt = if (wrapLines) visibleCols else cols

        if (cursorCol >= wrapAt) {
            if (wrapLines) {
                cursorCol = 0
                newLine()
            } else {
                // Truncate - don't advance, just stay at end of line
                return
            }
        }
        screenBuffer[cursorRow][cursorCol] = ch
        colorBuffer[cursorRow][cursorCol] = currentFgColor
        cursorCol++
    }

    private fun newLine() {
        cursorRow++
        if (cursorRow >= rows) {
            scrollUp()
            cursorRow = rows - 1
        }
    }

    private fun scrollUp() {
        // Push the top live line into scrollback history before it scrolls off.
        if (scrollbackLines > 0) {
            historyChars.addLast(screenBuffer[0].copyOf())
            historyColors.addLast(colorBuffer[0].copyOf())
            while (historyChars.size > scrollbackLines) {
                historyChars.removeFirst()
                historyColors.removeFirst()
            }
        } else if (historyChars.isNotEmpty()) {
            // Scrollback was turned off after lines were already kept.
            historyChars.clear()
            historyColors.clear()
            userScrollUp = 0
        }
        for (row in 0 until rows - 1) {
            screenBuffer[row] = screenBuffer[row + 1].copyOf()
            colorBuffer[row] = colorBuffer[row + 1].copyOf()
        }
        screenBuffer[rows - 1] = CharArray(cols) { ' ' }
        colorBuffer[rows - 1] = IntArray(cols) { currentFgColor }
    }

    private fun clearScreen() {
        for (row in 0 until rows) {
            screenBuffer[row].fill(' ')
            colorBuffer[row].fill(currentFgColor)
        }
        cursorRow = 0
        cursorCol = 0
    }

    private fun clearToEnd() {
        clearLineToEnd()
        for (row in cursorRow + 1 until rows) {
            screenBuffer[row].fill(' ')
            colorBuffer[row].fill(currentFgColor)
        }
    }

    private fun clearToBeginning() {
        clearLineToBeginning()
        for (row in 0 until cursorRow) {
            screenBuffer[row].fill(' ')
            colorBuffer[row].fill(currentFgColor)
        }
    }

    private fun clearLine() {
        screenBuffer[cursorRow].fill(' ')
        colorBuffer[cursorRow].fill(currentFgColor)
    }

    private fun clearLineToEnd() {
        for (col in cursorCol until cols) {
            screenBuffer[cursorRow][col] = ' '
            colorBuffer[cursorRow][col] = currentFgColor
        }
    }

    private fun clearLineToBeginning() {
        for (col in 0..cursorCol) {
            screenBuffer[cursorRow][col] = ' '
            colorBuffer[cursorRow][col] = currentFgColor
        }
    }

    fun clear() {
        clearScreen()
        processOutputCount = 0
        invalidate()
    }
}
