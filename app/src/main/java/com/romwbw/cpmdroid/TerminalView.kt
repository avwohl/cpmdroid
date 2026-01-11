package com.romwbw.cpmdroid

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
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
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN
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
                val unicodeChar = event.unicodeChar
                if (unicodeChar != 0 && !event.isCtrlPressed && !event.isAltPressed) {
                    sendChar(unicodeChar and 0x7F)
                    return true
                }
            }
        }
        return false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        calculateFontSize()
    }

    private fun calculateFontSize() {
        // If custom font size is set, use it
        if (customFontSize > 0) {
            applyCustomFontSize()
            return
        }

        val maxWidth = width.toFloat() / COLS
        val maxHeight = height.toFloat() / ROWS

        // Find font size that fits both constraints
        var testSize = 8f
        while (testSize < 100f) {
            textPaint.textSize = testSize
            val testWidth = textPaint.measureText("M")
            val metrics = textPaint.fontMetrics
            val testHeight = metrics.descent - metrics.ascent

            if (testWidth > maxWidth || testHeight > maxHeight) {
                break
            }
            testSize += 1f
        }

        textPaint.textSize = testSize - 1f
        charWidth = textPaint.measureText("M")
        charHeight = textPaint.fontMetrics.descent - textPaint.fontMetrics.ascent
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val metrics = textPaint.fontMetrics
        val baseline = -metrics.ascent

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
    }

    fun processOutput(data: ByteArray) {
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
                    0x07 -> { } // BEL - ignore
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
