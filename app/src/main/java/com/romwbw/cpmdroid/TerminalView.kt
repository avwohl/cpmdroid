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
        private const val ROWS = 25
        private const val COLS = 80
    }

    private val screenBuffer = Array(ROWS) { CharArray(COLS) { ' ' } }
    private val colorBuffer = Array(ROWS) { IntArray(COLS) { Color.GREEN } }

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
        for (row in 0 until ROWS) {
            val line = String(screenBuffer[row]).trimEnd()
            text.append(line)
            if (row < ROWS - 1) text.append("\n")
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

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        // Calculate terminal dimensions based on width
        val terminalWidth = widthSize.toFloat()
        val charWidthAtSize = if (customFontSize > 0) {
            val tempPaint = android.graphics.Paint(textPaint)
            tempPaint.textSize = customFontSize * resources.displayMetrics.density
            tempPaint.measureText("M")
        } else {
            terminalWidth / COLS
        }
        val charHeightAtSize = charWidthAtSize * 1.8f  // Approximate aspect ratio

        // Calculate height needed for terminal content
        val terminalHeight = (ROWS * charHeightAtSize).toInt()

        val measuredWidth = widthSize
        val measuredHeight = when (heightMode) {
            MeasureSpec.EXACTLY -> heightSize
            MeasureSpec.AT_MOST -> minOf(terminalHeight, heightSize)
            else -> terminalHeight
        }

        android.util.Log.i("TerminalView", "onMeasure: width=$measuredWidth, height=$measuredHeight, terminalHeight=$terminalHeight")
        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    // Scaling and offset for filling the view
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    private fun calculateFontSize() {
        // If custom font size is set, use it
        if (customFontSize > 0) {
            android.util.Log.i("TerminalView", "calculateFontSize: using custom size $customFontSize")
            applyCustomFontSize()
            calculateScaling()
            return
        }

        // Find font size that fits width (80 columns)
        val availableWidth = width.toFloat()
        val maxWidth = availableWidth / COLS

        var testSize = 8f
        while (testSize < 100f) {
            textPaint.textSize = testSize
            val testWidth = textPaint.measureText("M")
            if (testWidth > maxWidth) {
                break
            }
            testSize += 1f
        }

        textPaint.textSize = testSize - 1f
        charWidth = textPaint.measureText("M")
        charHeight = textPaint.fontMetrics.descent - textPaint.fontMetrics.ascent

        calculateScaling()
    }

    private fun calculateScaling() {
        // Calculate terminal size at current font
        val terminalWidth = COLS * charWidth
        val terminalHeight = ROWS * charHeight

        // Calculate scale to fill view (uniform scaling)
        val scaleX = width.toFloat() / terminalWidth
        val scaleY = height.toFloat() / terminalHeight
        scale = minOf(scaleX, scaleY)

        // Align terminal to top (with horizontal centering)
        // This ensures text starts at top and any unused space is at bottom
        val scaledWidth = terminalWidth * scale
        offsetX = (width - scaledWidth) / 2f + 4f  // Center horizontally with small left padding
        offsetY = 0f  // Align to top

        android.util.Log.i("TerminalView", "calculateScaling: scale=$scale, offsetX=$offsetX, offsetY=$offsetY")
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val metrics = textPaint.fontMetrics
        val baseline = -metrics.ascent

        // Apply scaling and offset
        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)

        // Draw characters
        for (row in 0 until ROWS) {
            for (col in 0 until COLS) {
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
        if (cursorVisible && cursorRow < ROWS && cursorCol < COLS) {
            val cursorX = cursorCol * charWidth
            val cursorY = cursorRow * charHeight + charHeight - 4f
            canvas.drawRect(cursorX, cursorY, cursorX + charWidth, cursorY + 3f, cursorPaint)
        }

        canvas.restore()
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
                cursorRow = (params.getOrElse(0) { 1 } - 1).coerceIn(0, ROWS - 1)
                cursorCol = (params.getOrElse(1) { 1 } - 1).coerceIn(0, COLS - 1)
            }
            'A' -> cursorRow = (cursorRow - params.getOrElse(0) { 1 }).coerceAtLeast(0)
            'B' -> cursorRow = (cursorRow + params.getOrElse(0) { 1 }).coerceAtMost(ROWS - 1)
            'C' -> cursorCol = (cursorCol + params.getOrElse(0) { 1 }).coerceAtMost(COLS - 1)
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
        if (cursorCol >= COLS) {
            cursorCol = 0
            newLine()
        }
        screenBuffer[cursorRow][cursorCol] = ch
        colorBuffer[cursorRow][cursorCol] = currentFgColor
        cursorCol++
    }

    private fun newLine() {
        cursorRow++
        if (cursorRow >= ROWS) {
            scrollUp()
            cursorRow = ROWS - 1
        }
    }

    private fun scrollUp() {
        for (row in 0 until ROWS - 1) {
            screenBuffer[row] = screenBuffer[row + 1].copyOf()
            colorBuffer[row] = colorBuffer[row + 1].copyOf()
        }
        screenBuffer[ROWS - 1] = CharArray(COLS) { ' ' }
        colorBuffer[ROWS - 1] = IntArray(COLS) { currentFgColor }
    }

    private fun clearScreen() {
        for (row in 0 until ROWS) {
            screenBuffer[row].fill(' ')
            colorBuffer[row].fill(currentFgColor)
        }
        cursorRow = 0
        cursorCol = 0
    }

    private fun clearToEnd() {
        clearLineToEnd()
        for (row in cursorRow + 1 until ROWS) {
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
        for (col in cursorCol until COLS) {
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
