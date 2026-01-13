package com.romwbw.cpmdroid

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.ToneGenerator
import android.media.AudioManager
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
        private const val MIN_COLS = 80
    }

    // Dynamic terminal dimensions based on screen size
    private var rows = MIN_ROWS
    private var cols = MIN_COLS

    private var screenBuffer = Array(rows) { CharArray(cols) { ' ' } }
    private var colorBuffer = Array(rows) { IntArray(cols) { Color.GREEN } }

    private var cursorRow = 0
    private var cursorCol = 0
    private var cursorVisible = true

    private var charWidth = 0f
    private var charHeight = 0f

    private val textPaint = Paint().apply {
        color = Color.GREEN
        typeface = Typeface.MONOSPACE
        isAntiAlias = true
        textSize = 32f
    }

    // Custom font size (0 = auto-calculate)
    var customFontSize: Float = 0f
        set(value) {
            field = value
            if (value > 0 && width > 0) {
                applyCustomFontSize()
            }
            invalidate()
        }

    private fun applyCustomFontSize() {
        // Convert point size to pixels (roughly)
        val pixelSize = customFontSize * resources.displayMetrics.density
        textPaint.textSize = pixelSize
        charWidth = textPaint.measureText("M")
        charHeight = textPaint.fontMetrics.descent - textPaint.fontMetrics.ascent
        android.util.Log.i("TerminalView", "applyCustomFontSize: pixelSize=$pixelSize, charWidth=$charWidth, charHeight=$charHeight")
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

    /** Play bell sound (0x07 BEL character) */
    private fun playBell() {
        try {
            if (toneGenerator == null) {
                toneGenerator = ToneGenerator(AudioManager.STREAM_SYSTEM, 50) // 50% volume
            }
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 100) // 100ms beep
        } catch (e: Exception) {
            // Ignore audio errors - some devices may not support this
        }
    }

    /** Copy entire screen content to clipboard */
    fun copyScreenToClipboard(): Boolean {
        val text = StringBuilder()
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            // Request focus and show keyboard
            requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
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
            else -> {
                // Handle Ctrl+letter combinations
                if (ctrl && keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
                    val ctrlChar = keyCode - KeyEvent.KEYCODE_A + 1 // Ctrl+A=1, Ctrl+B=2, etc.
                    sendChar(ctrlChar)
                    return true
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
        android.util.Log.i("TerminalView", "onSizeChanged: w=$w, h=$h, customFontSize=$customFontSize")
        calculateFontSize()
    }

    private fun calculateFontSize() {
        // Calculate font size to fit at least MIN_COLS (80) columns
        if (customFontSize > 0) {
            applyCustomFontSize()
            // Check if custom size fits 80 columns, if not, scale down
            val colsAtCustomSize = (width / charWidth).toInt()
            if (colsAtCustomSize < MIN_COLS) {
                // Scale down to fit 80 columns
                val maxCharWidth = width.toFloat() / MIN_COLS
                var testSize = customFontSize * resources.displayMetrics.density
                while (testSize > 8f) {
                    textPaint.textSize = testSize
                    if (textPaint.measureText("M") <= maxCharWidth) break
                    testSize -= 1f
                }
                charWidth = textPaint.measureText("M")
                charHeight = textPaint.fontMetrics.descent - textPaint.fontMetrics.ascent
            }
        } else {
            // Auto-calculate font size to fit MIN_COLS columns
            val maxCharWidth = width.toFloat() / MIN_COLS
            var testSize = 24f * resources.displayMetrics.density  // Start larger
            while (testSize > 8f) {
                textPaint.textSize = testSize
                if (textPaint.measureText("M") <= maxCharWidth) break
                testSize -= 1f
            }
            charWidth = textPaint.measureText("M")
            charHeight = textPaint.fontMetrics.descent - textPaint.fontMetrics.ascent
        }

        // Calculate how many rows and columns fit on screen (at least MIN values)
        val newCols = maxOf(MIN_COLS, (width / charWidth).toInt())
        val newRows = maxOf(MIN_ROWS, (height / charHeight).toInt())

        // Resize buffers if dimensions changed
        if (newCols != cols || newRows != rows) {
            resizeBuffers(newRows, newCols)
        }

        android.util.Log.i("TerminalView", "calculateFontSize: rows=$rows, cols=$cols, charWidth=$charWidth, charHeight=$charHeight")
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

        val metrics = textPaint.fontMetrics
        val baseline = -metrics.ascent

        // Draw characters (no scaling - dynamic rows/cols fill the screen)
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val ch = screenBuffer[row][col]
                if (ch != ' ') {
                    textPaint.color = colorBuffer[row][col]
                    canvas.drawText(
                        ch.toString(),
                        col * charWidth,
                        row * charHeight + baseline,
                        textPaint
                    )
                }
            }
        }

        // Draw cursor
        if (cursorVisible && cursorRow < rows && cursorCol < cols) {
            val cursorX = cursorCol * charWidth
            val cursorY = cursorRow * charHeight + charHeight - 4f
            canvas.drawRect(cursorX, cursorY, cursorX + charWidth, cursorY + 3f, cursorPaint)
        }
    }

    private var processOutputCount = 0
    fun processOutput(data: ByteArray) {
        if (processOutputCount++ < 3) {
            android.util.Log.i("TerminalView", "processOutput: ${data.size} bytes, charWidth=$charWidth, charHeight=$charHeight")
        }
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
        if (cursorCol >= cols) {
            cursorCol = 0
            newLine()
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
        invalidate()
    }
}
