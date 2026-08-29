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

        // The power-on rendition, and the two values every reset goes back to.
        //
        // The foreground stays Color.GREEN rather than becoming cgaColors[7].
        // Both sibling ports reset SGR 0 to CGA 7 light grey; this one is a
        // green-phosphor terminal from the boot banner down, and moving it
        // would repaint the entire app for a parity argument nobody asked for.
        // The divergence is deliberate, and it is written down here so the next
        // cross-port sweep stops filing it. Note the shade: Color.GREEN is
        // 0xFF00FF00, which is neither cgaColors[2] nor cgaColors[10], so
        // ESC[32m then ESC[0m changes the shade of green as well as the colour.
        //
        // The background is TRANSPARENT, and that is a sentinel for "no
        // background", not a colour. drawRow paints nothing for a cell holding
        // it, so a screen that has never seen an SGR sequence looks exactly as
        // it did before backgrounds existed: the one full-view bgPaint rect and
        // nothing else. It cannot be confused with a background a guest asked
        // for either - every cgaColors entry is fully opaque, and SGR 40 is CGA
        // black, 0xFF000000, a different value from this.
        private val DEFAULT_FG = Color.GREEN
        private val DEFAULT_BG = Color.TRANSPARENT

        // CSI parameter bounds - the same numbers as ioscpm's maxCSIParams /
        // maxCSIParamDigits and z80cpmw's MAX_CSI_PARAMS / MAX_CSI_PARAM_DIGITS,
        // so the three parsers agree about what they will swallow.
        //
        // The parameter buffer used to be an unbounded StringBuilder, so an
        // unterminated escape - TYPEing a binary file is enough - grew it
        // without limit. Over either bound the excess is dropped SILENTLY and
        // the final byte is still executed, which is what both siblings do: a
        // sequence aborted mid-flight prints its own tail as glyphs, and that
        // is the worse failure of the two.
        private const val MAX_CSI_PARAMS = 16
        private const val MAX_CSI_PARAM_DIGITS = 6
        // Value clamp, matching min(value, 9999) in both siblings. Six digits
        // already fit an Int; this is what keeps a wild row or column count out
        // of the handlers.
        private const val MAX_CSI_PARAM_VALUE = 9999
    }

    // Dynamic terminal dimensions based on screen size
    private var rows = MIN_ROWS
    private var cols = MIN_COLS

    // How many columns actually fit on screen (may be less than cols with larger fonts)
    private var visibleCols = MIN_COLS

    private var screenBuffer = Array(rows) { CharArray(cols) { ' ' } }
    private var colorBuffer = Array(rows) { IntArray(cols) { DEFAULT_FG } }
    // Per-cell background, a third parallel array rather than a foreground and
    // a background packed into one Long. Every site that touches colour here -
    // the six erase helpers, scrollUp, resizeBuffers, putChar, drawRow and the
    // scrollback deques - already works in parallel arrays, and a packed cell
    // would have to unpack at each of them for nothing gained. At the default
    // 1000 scrollback lines this costs about a third of a megabyte, which is
    // the price of a background that can actually be painted.
    private var bgBuffer = Array(rows) { IntArray(cols) { DEFAULT_BG } }

    private var cursorRow = 0
    private var cursorCol = 0
    private var cursorVisible = true

    private var charWidth = 0f
    private var charHeight = 0f

    // --- Scrollback ---
    // Lines that have scrolled off the top of the live screen (oldest first).
    private val historyChars = ArrayDeque<CharArray>()
    private val historyColors = ArrayDeque<IntArray>()
    // Backgrounds for those same lines. It has to be pushed, trimmed and
    // cleared in lockstep with the other two everywhere, or a scrolled-off line
    // draws with another line's background - which is why every mutation of the
    // three deques below happens in one place each.
    private val historyBg = ArrayDeque<IntArray>()
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
            historyBg.removeFirst()
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

    // The per-cell background brush, kept apart from bgPaint because bgPaint is
    // the page fill and must stay Color.BLACK: drawRow reassigns this one's
    // colour for every painted cell, and borrowing bgPaint for that would leave
    // the next full-view rect drawn in whatever the last cell happened to be.
    // Held as a field for the same reason as the other three - onDraw runs per
    // frame and must not allocate.
    private val cellBgPaint = Paint().apply {
        style = Paint.Style.FILL
    }

    private val cursorPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.FILL
    }

    // VT100 escape sequence parsing state
    private var escapeState = 0
    // Parameters are accumulated one field at a time, the way both siblings do
    // it, rather than collected as text and split on ';' at the end. The split
    // form used mapNotNull, which REMOVES a field it cannot parse instead of
    // defaulting it: "ESC[;5H" became [5] and moved the cursor to row 5 instead
    // of column 5, and any over-long field did the same to everything after it.
    // Closing each field where it ends is what keeps a parameter in the
    // position the guest put it in.
    private val escapeParams = mutableListOf<Int>()
    private val escapeCurrentParam = StringBuilder()
    private var currentFgColor = DEFAULT_FG
    private var currentBgColor = DEFAULT_BG

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

    /**
     * Translate an ANSI SGR colour index into an index into cgaColors.
     *
     * SGR parameters 30-37 and 90-97 carry ANSI colour indices:
     *     0 black, 1 red, 2 green, 3 yellow, 4 blue, 5 magenta, 6 cyan, 7 white
     * cgaColors above is in CGA attribute order, and must stay that way:
     *     0 black, 1 blue, 2 green, 3 cyan, 4 red, 5 magenta, 6 brown, 7 light grey
     * That ordering is not incidental. It is the order of a real CGA attribute
     * byte, which is the value the emulator itself deals in: a CP/M guest can
     * hand one down through HBIOS VDA, and it arrives at emu_video_set_attr()
     * in emu_io_android.cpp - stored there rather than drawn, because Android
     * renders from the VT100 escapes this parser handles, but ioscpm and
     * z80cpmw do draw from that byte, against palettes in this same order. So
     * the palette is not the thing to reorder. The ANSI index is converted
     * where it is parsed, and nowhere else.
     *
     * The two orders differ only in which bit means red and which means blue,
     * so the conversion is a swap of bit 0 and bit 2, and is its own inverse:
     *     0->0  1->4  2->2  3->6  4->1  5->5  6->3  7->7
     * Pass only a 0-7 colour index. The 0x08 intensity bit is not a colour
     * index and must never go through here; the bright SGR range maps its low
     * three bits and then adds 8.
     */
    private fun ansiToCgaIndex(ansi: Int): Int =
        ((ansi and 1) shl 2) or (ansi and 2) or ((ansi shr 2) and 1)

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
     * Send one key sequence: the ESC, then the rest of it byte for byte.
     *
     * The argument is the sequence WITHOUT its leading ESC, which this adds -
     * so a call reads the way the sibling key tables read. z80cpmw's Keymap.h
     * spells PageUp as backslash-E then "[5~", and ioscpm's KeyMap.swift the
     * same; only the escape marker differs, and the rest is the argument here.
     * Emitting the ESC in one place is what stops a caller forgetting it, and a
     * caller that forgets it sends the printable tail to CP/M as literal text -
     * strictly worse than not sending the key at all.
     */
    private fun sendEscapeSeq(s: String) {
        sendChar(0x1B)
        for (c in s) sendChar(c.code)
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
            sendEscapeSeq("O" + ('P' + (n - 1)))
            return
        }
        val param = intArrayOf(15, 17, 18, 19, 20, 21, 23, 24)[n - 5]
        sendEscapeSeq("[$param~")
    }

    /**
     * Send one arrow key. `finalByte` is the CSI final byte - A/B/C/D for
     * up/down/right/left - so this stays one-to-one with the four entries in
     * z80cpmw's Keymap.h and ioscpm's KeyMap.swift.
     *
     * With Ctrl held it is the xterm modified form, CSI 1 ; 5 <final>, where
     * the 5 is the Ctrl modifier. Before this the four DPAD arms matched on
     * keyCode alone and returned before the `else` arm could ever consult
     * `ctrl`, so Ctrl+Up was byte-for-byte identical to Up - the same bug
     * ioscpm's pressesBegan had, where the nav-key branch tested only for
     * .command and threw the Ctrl modifier away. cpmdroid has no VT52 mode, so
     * unlike ioscpm there is no profile here that must fall back to the bare
     * arrow for want of a parameterised CSI to put the 5 in.
     */
    private fun sendArrow(finalByte: Char, ctrl: Boolean) {
        sendEscapeSeq(if (ctrl) "[1;5$finalByte" else "[$finalByte")
    }

    /**
     * Move the scrollback view, for the four key combinations the app answers
     * itself instead of sending to CP/M.
     *
     * Positive is back into history, negative is forward toward the live
     * prompt, which is the sense userScrollUp already carries for the drag
     * gesture - the bound is the same one onTouchEvent uses.
     */
    private fun scrollHistoryBy(lines: Int) {
        userScrollUp = (userScrollUp + lines).coerceIn(0, historyChars.size)
        invalidate()
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
            KeyEvent.KEYCODE_DPAD_UP    -> { sendArrow('A', ctrl); return true }
            KeyEvent.KEYCODE_DPAD_DOWN  -> { sendArrow('B', ctrl); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { sendArrow('C', ctrl); return true }
            KeyEvent.KEYCODE_DPAD_LEFT  -> { sendArrow('D', ctrl); return true }
            // Home/End/PageUp/PageDown/Insert/Forward-Delete. Like F1-F12
            // before them these produce unicodeChar 0 and match nothing in the
            // printable fallback below, so a hardware keyboard's whole
            // navigation cluster reached CP/M as nothing at all.
            //
            // The bytes are z80cpmw's defaultBindings() table, which ioscpm's
            // VT100/ANSI profile matches except for Delete: z80cpmw sends ^?
            // (0x7F) where ioscpm sends CSI 3 ~. ^? wins here because
            // FEATURE_PARITY item 1 already asserts that cpmdroid sends it, and
            // because it is the byte a CP/M program is most likely to be
            // waiting for. KEYCODE_MOVE_HOME, not KEYCODE_HOME: the latter is
            // the device Home button and is not this key.
            //
            // Four combinations are the app's, not the guest's. They are the
            // four z80cpmw reserves in reservedKeys(), doing the same things in
            // the same words, because cpmdroid has the scrollback they act on
            // (historyChars, capped at scrollbackLines) and until now only a
            // drag gesture could reach it - a hardware keyboard could not scroll
            // back at all. The modifier test is a mask, not an equality, which
            // is reservedFor()'s rule: someone still holding Ctrl from Ctrl+Home
            // gets the scroll they asked for from Shift+PageUp too. A page is
            // rows - 1, the same overlap-by-one-line z80cpmw scrolls.
            KeyEvent.KEYCODE_MOVE_HOME -> {
                if (ctrl) scrollHistoryBy(historyChars.size) else sendEscapeSeq("[H")
                return true
            }
            KeyEvent.KEYCODE_MOVE_END -> {
                if (ctrl) scrollHistoryBy(-historyChars.size) else sendEscapeSeq("[F")
                return true
            }
            KeyEvent.KEYCODE_PAGE_UP -> {
                if (event.isShiftPressed) scrollHistoryBy(rows - 1) else sendEscapeSeq("[5~")
                return true
            }
            KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (event.isShiftPressed) scrollHistoryBy(-(rows - 1)) else sendEscapeSeq("[6~")
                return true
            }
            KeyEvent.KEYCODE_INSERT -> {
                sendEscapeSeq("[2~")
                return true
            }
            KeyEvent.KEYCODE_FORWARD_DEL -> {
                // One byte, so not through sendEscapeSeq - DEL is not an escape
                // sequence. KEYCODE_DEL above is Backspace and stays 0x08.
                sendChar(0x7F)
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
        val oldBgBuffer = bgBuffer

        rows = newRows
        cols = newCols

        // Create new buffers. The cells outside the copied region get the
        // power-on rendition rather than the current one: this is a font-size
        // or rotation change, not an erase, and a guest that set a background
        // never asked for the new space to be painted in it.
        screenBuffer = Array(rows) { CharArray(cols) { ' ' } }
        colorBuffer = Array(rows) { IntArray(cols) { DEFAULT_FG } }
        bgBuffer = Array(rows) { IntArray(cols) { DEFAULT_BG } }

        // Copy old content (as much as fits)
        for (r in 0 until minOf(oldRows, rows)) {
            for (c in 0 until minOf(oldCols, cols)) {
                screenBuffer[r][c] = oldScreenBuffer[r][c]
                colorBuffer[r][c] = oldColorBuffer[r][c]
                bgBuffer[r][c] = oldBgBuffer[r][c]
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
                drawRow(canvas, screenBuffer[liveRow], colorBuffer[liveRow], bgBuffer[liveRow],
                        r, offsetX, offsetY, baseline)
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
                    drawRow(canvas, historyChars[lineIdx], historyColors[lineIdx], historyBg[lineIdx],
                            r, offsetX, offsetY, baseline)
                } else {
                    val liveRow = lineIdx - historySize
                    drawRow(canvas, screenBuffer[liveRow], colorBuffer[liveRow], bgBuffer[liveRow],
                            r, offsetX, offsetY, baseline)
                    if (liveRow == cursorRow) drawCursor(canvas, r, viewportRows, offsetX, offsetY)
                }
            }
        }
    }

    private fun drawRow(canvas: Canvas, chars: CharArray, colors: IntArray, bgs: IntArray,
                        vrow: Int, offsetX: Float, offsetY: Float, baseline: Float) {
        // `top` is the cell rect's top edge; `y` is the text baseline, which is
        // one ascent lower. They are not the same number and the background
        // rect wants the first of them.
        val top = offsetY + vrow * charHeight
        val y = top + baseline
        val n = minOf(chars.size, cols)
        for (col in 0 until n) {
            val x = offsetX + col * charWidth
            // The background comes first, and it is drawn for a blank cell too.
            // The old fast path skipped a cell outright when its character was a
            // space, which is exactly why a background could never appear: an
            // erase leaves nothing BUT spaces, so ESC[44m then ESC[2J - the
            // sequence this whole cross-port thread was about - painted nothing
            // at all. A DEFAULT_BG cell is still skipped, so a screen that has
            // never seen an SGR background costs exactly what it used to.
            if (bgs[col] != DEFAULT_BG) {
                cellBgPaint.color = bgs[col]
                canvas.drawRect(x, top, x + charWidth, top + charHeight, cellBgPaint)
            }
            val ch = chars[col]
            if (ch != ' ') {
                textPaint.color = colors[col]
                canvas.drawText(ch.toString(), x, y, textPaint)
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
                        escapeCurrentParam.clear()
                    }
                    else -> escapeState = 0
                }
            }
            2 -> { // CSI sequence
                when {
                    ch in '0'.code..'9'.code -> {
                        // A leading zero is padding, not a digit, and is
                        // dropped rather than spending the field's budget:
                        // without this, ESC[000000000005H fills all six digits
                        // with zeros and truncates to 0. ioscpm does the same;
                        // z80cpmw does not, and this is the better of the two.
                        // It also makes "0" and "" the same field, which is
                        // what ECMA-48 says they mean - both are "default".
                        if (escapeCurrentParam.isEmpty() && ch == '0'.code) {
                            // padding: nothing to record
                        } else if (escapeCurrentParam.length < MAX_CSI_PARAM_DIGITS) {
                            escapeCurrentParam.append(ch.toChar())
                        }
                    }
                    ch == ';'.code -> {
                        // A separator ends a field even when the field is
                        // empty. That is the whole fix for ESC[;5H: an omitted
                        // parameter means "take the default", it does not mean
                        // "there is no parameter here", and dropping it shifted
                        // every later one left a place.
                        endCsiParam(keepEmpty = true)
                    }
                    ch in 0x30..0x3F -> {
                        // ':' and the private markers '<', '=', '>', '?'.
                        // Consumed and ignored, and NOT treated as a final:
                        // ESC[?25l has to reach processCSI with 'l' as its
                        // final and be dropped there. Ending the sequence at
                        // the '?' would print "25l" on screen, which is the bug
                        // z80cpmw's item 13 records fixing in the other
                        // direction.
                    }
                    ch in 0x40..0x7E -> {
                        // A trailing empty field is not a parameter: ESC[H and
                        // ESC[m must still arrive with an empty list, so that
                        // getOrElse's defaults and the SGR reset keep working.
                        endCsiParam(keepEmpty = false)
                        processCSI(ch.toChar())
                        escapeState = 0
                    }
                    else -> escapeState = 0
                }
            }
        }
    }

    /**
     * Close the CSI parameter field being accumulated.
     *
     * `keepEmpty` says what an empty field means at this point. After a ';' it
     * is a parameter that was written down as "use the default", and it has to
     * take its place in the list or everything after it moves. At the final
     * byte it is not a parameter at all - it is the end of the sequence - so
     * ESC[H still arrives with an empty list and the handlers' own defaults
     * apply, exactly as they did before this parser kept a list.
     *
     * Past MAX_CSI_PARAMS the field is parsed and thrown away rather than
     * aborting the sequence: see the note on the constant.
     */
    private fun endCsiParam(keepEmpty: Boolean) {
        if (escapeCurrentParam.isEmpty() && !keepEmpty) return
        if (escapeParams.size < MAX_CSI_PARAMS) {
            val value = escapeCurrentParam.toString().toIntOrNull() ?: 0
            escapeParams.add(value.coerceAtMost(MAX_CSI_PARAM_VALUE))
        }
        escapeCurrentParam.clear()
    }

    /**
     * A CSI parameter whose ECMA-48 default is 1 - the cursor motions and both
     * halves of CUP.
     *
     * The default is on the VALUE, not on the index, which is the rule both
     * siblings use (std::max(p1, 1) in z80cpmw, max(p1, 1) in ioscpm) and the
     * one this port did not have. Defaulting on a missing index alone meant
     * ESC[0A and ESC[0;0H moved by zero, and it could not survive the parser
     * learning to record an omitted parameter: ESC[;5H now really does have a
     * parameter at index 0, and its value is the "default" marker 0.
     */
    private fun csiParamOrOne(params: List<Int>, index: Int): Int =
        params.getOrElse(index) { 1 }.coerceAtLeast(1)

    private fun processCSI(command: Char) {
        val params = escapeParams

        when (command) {
            'H', 'f' -> { // Cursor position
                cursorRow = (csiParamOrOne(params, 0) - 1).coerceIn(0, rows - 1)
                cursorCol = (csiParamOrOne(params, 1) - 1).coerceIn(0, cols - 1)
            }
            'A' -> cursorRow = (cursorRow - csiParamOrOne(params, 0)).coerceAtLeast(0)
            'B' -> cursorRow = (cursorRow + csiParamOrOne(params, 0)).coerceAtMost(rows - 1)
            'C' -> cursorCol = (cursorCol + csiParamOrOne(params, 0)).coerceAtMost(cols - 1)
            'D' -> cursorCol = (cursorCol - csiParamOrOne(params, 0)).coerceAtLeast(0)
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
                    // ESC[m is ESC[0m.
                    resetRendition()
                } else {
                    var i = 0
                    while (i < params.size) {
                        val p = params[i]
                        // Extended colour: ESC[38;5;<n>m and ESC[38;2;<r>;<g>;<b>m,
                        // and 48 for the background. This is a sixteen-colour
                        // terminal and the sub-parameters carry a 256-colour
                        // index or a truecolour triple, so they are consumed and
                        // discarded rather than approximated onto the palette:
                        // z80cpmw discards them too, and a port that guessed a
                        // nearest CGA entry would put a colour on screen that
                        // neither sibling shows for the same bytes - which is
                        // the divergence this dimension exists to remove.
                        //
                        // Consuming them is not optional. Read as parameters in
                        // their own right they land as colours: the "33" of
                        // ESC[38;5;33m set a brown foreground, and the "0" of
                        // ESC[38;2;0;128;255m reset the whole rendition
                        // mid-sequence.
                        //
                        // The steps are 3 and 5, not z80cpmw's 2 and 4: its
                        // skip lives in a for-loop whose own i++ follows, and
                        // this one continues straight back to the top. The
                        // missing-tail step of 1 is not optional either - a bare
                        // trailing ESC[38m with no bottom increment would spin
                        // here forever, on the UI thread.
                        if (p == 38 || p == 48) {
                            i += if (i + 1 < params.size) {
                                when (params[i + 1]) {
                                    5 -> 3     // 38 ; 5 ; <index>
                                    2 -> 5     // 38 ; 2 ; <r> ; <g> ; <b>
                                    else -> 2  // unknown form: skip it and the 38
                                }
                            } else {
                                1              // trailing bare 38
                            }
                            continue
                        }
                        when {
                            p == 0 -> resetRendition()
                            // 39 and 49 are "back to the default", separately
                            // for each half. Without them a program that said
                            // ESC[31m ... ESC[39m stayed red for the rest of
                            // the session, because nothing but a full reset
                            // could undo a colour.
                            p == 39 -> currentFgColor = DEFAULT_FG
                            p == 49 -> currentBgColor = DEFAULT_BG
                            p in 30..37 -> currentFgColor = cgaColors[ansiToCgaIndex(p - 30)]
                            p in 40..47 -> currentBgColor = cgaColors[ansiToCgaIndex(p - 40)]
                            p in 90..97 -> currentFgColor = cgaColors[ansiToCgaIndex(p - 90) + 8]
                            // The bright backgrounds are NOT folded onto the
                            // normal ones the way z80cpmw folds them. It has to:
                            // its cell is a packed CGA attribute byte whose
                            // background field is three bits wide, with bit 7
                            // being blink, so a bright background could only be
                            // stored by borrowing it. A cell here is a full ARGB
                            // Int - no nibble, no blink bit, nothing to borrow -
                            // and the bright foregrounds already render, so the
                            // background half is symmetric with them. Folding
                            // would make ESC[104m indistinguishable from
                            // ESC[44m for a reason that does not apply here.
                            p in 100..107 -> currentBgColor = cgaColors[ansiToCgaIndex(p - 100) + 8]
                        }
                        i++
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
        bgBuffer[cursorRow][cursorCol] = currentBgColor
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
            historyBg.addLast(bgBuffer[0].copyOf())
            while (historyChars.size > scrollbackLines) {
                historyChars.removeFirst()
                historyColors.removeFirst()
                historyBg.removeFirst()
            }
        } else if (historyChars.isNotEmpty()) {
            // Scrollback was turned off after lines were already kept.
            historyChars.clear()
            historyColors.clear()
            historyBg.clear()
            userScrollUp = 0
        }
        for (row in 0 until rows - 1) {
            screenBuffer[row] = screenBuffer[row + 1].copyOf()
            colorBuffer[row] = colorBuffer[row + 1].copyOf()
            bgBuffer[row] = bgBuffer[row + 1].copyOf()
        }
        // Fresh arrays because cols may have moved since these were allocated,
        // then blanked through the one definition of a blank cell - the new
        // bottom line is an erase like any other, and takes the current
        // background with it.
        screenBuffer[rows - 1] = CharArray(cols)
        colorBuffer[rows - 1] = IntArray(cols)
        bgBuffer[rows - 1] = IntArray(cols)
        blankRow(rows - 1)
    }

    /**
     * The cell every erase leaves behind: a space in the CURRENT rendition, not
     * a default one.
     *
     * This is background-colour-erase - what a real VT does, what xterm does,
     * and what a program that sets a colour and then clears is asking for. It
     * is the rule FEATURE_PARITY.md records as settled on 2026-08-27 in
     * z80cpmw's favour, and ioscpm's blankCell is the same function. Before the
     * background existed the erases here already filled currentFgColor, which
     * read as parity but was not: nothing drew a blank cell, so the colour they
     * wrote was never on screen.
     *
     * Every erase goes through this pair and nothing else fills a cell, so an
     * erased cell and a character written into it afterwards always agree.
     */
    private fun blankCells(row: Int, fromCol: Int, toCol: Int) {
        for (col in fromCol until toCol) {
            screenBuffer[row][col] = ' '
            colorBuffer[row][col] = currentFgColor
            bgBuffer[row][col] = currentBgColor
        }
    }

    private fun blankRow(row: Int) {
        screenBuffer[row].fill(' ')
        colorBuffer[row].fill(currentFgColor)
        bgBuffer[row].fill(currentBgColor)
    }

    private fun clearScreen() {
        for (row in 0 until rows) {
            blankRow(row)
        }
        cursorRow = 0
        cursorCol = 0
    }

    private fun clearToEnd() {
        clearLineToEnd()
        for (row in cursorRow + 1 until rows) {
            blankRow(row)
        }
    }

    private fun clearToBeginning() {
        clearLineToBeginning()
        for (row in 0 until cursorRow) {
            blankRow(row)
        }
    }

    private fun clearLine() {
        blankRow(cursorRow)
    }

    private fun clearLineToEnd() {
        blankCells(cursorRow, cursorCol, cols)
    }

    private fun clearLineToBeginning() {
        blankCells(cursorRow, 0, (cursorCol + 1).coerceAtMost(cols))
    }

    /** Back to the power-on rendition, both halves of it. */
    private fun resetRendition() {
        currentFgColor = DEFAULT_FG
        currentBgColor = DEFAULT_BG
    }

    /**
     * The machine-level clear: host-only, and the guest cannot reach it.
     * MainActivity.bootEmulation is the sole caller; ESC[2J goes to
     * clearScreen(), and no escape sequence this parser handles ends up here.
     *
     * The power-on state goes back FIRST, before anything is painted. An erase
     * now fills with the current background, so clearing first and resetting
     * second would paint the new session's screen in the colour the dying one
     * happened to end on - the ordering bug ioscpm's reset() had and fixed. The
     * parser state goes with it: a guest that died mid-CSI left escapeState at
     * 2, and bootEmulation prints its banner straight after this, so the
     * banner's first bytes were swallowed until some byte in 0x40..0x7E arrived
     * and was executed as a final against the dead session's parameters.
     *
     * userScrollUp goes back to the live screen for the same reason - the
     * banner is posted back from the executor, so between here and that post
     * the view can otherwise sit at a scroll offset into history.
     *
     * The scrollback itself is deliberately kept. Both siblings drop it on a
     * cold boot, but each does so at the CALL SITE (z80cpmw's startEmulator and
     * onEmulatorReset call resetScrollback next to clear(); ioscpm's reset()
     * empties scrollbackLines), which here is MainActivity - and losing the
     * user's history is a product decision, not part of putting the terminal
     * back to power-on.
     *
     * soundEnabled, wrapLines and scrollbackLines are user settings, not
     * terminal state, and stay untouched: bootEmulation re-applies all three
     * from settingsRepo after this returns.
     */
    fun clear() {
        resetRendition()
        escapeState = 0
        escapeParams.clear()
        escapeCurrentParam.clear()
        userScrollUp = 0

        clearScreen()
        processOutputCount = 0
        invalidate()
    }
}
