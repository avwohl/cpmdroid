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

        // What DEFAULT_BG actually looks like once it is on screen. drawRow
        // paints no rect for a DEFAULT_BG cell, so the page fill shows through,
        // and the page fill is bgPaint's Color.BLACK. Reverse video is the one
        // place that has to know: swapping a sentinel produces a FOREGROUND of
        // "no background", which is not a colour and would draw nothing at all.
        // Kept next to bgPaint's colour rather than derived from it because a
        // Paint is mutable and this must not be.
        private val PAPER = Color.BLACK

        // Per-cell attribute bits, the same three z80cpmw's TerminalCell::flags
        // carries and with the same values (TCELL_BOLD, TCELL_UNDERLINE,
        // TCELL_BLINK), so the two ports' cell dumps can be compared directly.
        // There is no italic bit: SGR 3 is not among them there either.
        //
        // Reverse is deliberately NOT one of these. It is a property of the
        // rendition being written, not of the cell written with it - resolved
        // into the two colours at the moment a cell is filled, exactly as
        // z80cpmw resolves it with swapAttrNibbles. A cell that recorded
        // "reversed" would have to be un-reversed at paint time against
        // whatever the defaults were by then, and SGR 7 followed by SGR 27
        // would stop being an exact inverse.
        private const val CELL_BOLD = 0x01
        private const val CELL_UNDERLINE = 0x02
        private const val CELL_BLINK = 0x04

        // Bold and underline pick a face; blink does not. The mask is what
        // turns a flags byte into an index into glyphPaints, and it is the
        // same arithmetic as z80cpmw's fontIndexFor().
        private const val CELL_FACE_MASK = CELL_BOLD or CELL_UNDERLINE

        // How long each half of a blink lasts, matching z80cpmw's 500 ms
        // WM_TIMER.
        private const val BLINK_MS = 500L

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

        // Parser states. These were bare 0/1/2 while there were three of them
        // and the escape dispatch was "'[' or discard"; there are six now and
        // two of them are mid-sequence byte collectors, which is more than a
        // literal should be asked to carry.
        private const val ESC_NORMAL = 0
        private const val ESC_SEEN = 1
        private const val ESC_CSI = 2
        private const val ESC_VT52_ROW = 3
        private const val ESC_VT52_COL = 4
        private const val ESC_CONSUME_ONE = 5

        // The two fixed replies, sent back to the guest as though typed.
        // z80cpmw and ioscpm both answer these exact bytes: a VT100 with no
        // options for a Device Attributes request, and the VT52 identity for
        // ESC Z while in VT52 mode.
        private const val DA_RESPONSE = "[?1;0c"
        private const val VT52_ID_RESPONSE = "/Z"
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
    // Per-cell CELL_* bits. A ByteArray rather than a fourth IntArray: three
    // bits are in use and the scrollback holds a thousand lines of them, so the
    // Int form would spend 320 KB to carry 3 bits per cell where 80 KB carries
    // the same thing. It is the one of the four that is not a colour, and the
    // type says so.
    private var attrBuffer = Array(rows) { ByteArray(cols) }

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
    // ...and their attributes, the fourth deque that has to move with the other
    // three. Same rule: every mutation of the four happens in one place each.
    private val historyFlags = ArrayDeque<ByteArray>()
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
            historyFlags.removeFirst()
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

    // The four faces bold and underline select between, indexed by
    // flags and CELL_FACE_MASK - so index 0 IS textPaint and an unattributed
    // screen draws through exactly the object it always did.
    //
    // Four Paints rather than one Paint reconfigured per cell, for the reason
    // z80cpmw keeps four HFONTs: a typeface change invalidates the glyph cache
    // behind it, and doing that per cell on a 24x80 grid would do it up to 1920
    // times a frame. Only the colour is written per cell, which is a field
    // store.
    //
    // The grid metrics stay measured from textPaint alone, as z80cpmw measures
    // from m_fonts[0] alone. A bold monospace face need not have the same
    // advance as its regular twin, and letting it move charWidth would make a
    // line of bold text sit on a different grid from the line above it. It
    // cannot smear either way here: drawRow positions every glyph at
    // col * charWidth individually, so a wider glyph overhangs its cell and
    // nothing downstream of it shifts.
    private val glyphPaints: Array<Paint> = Array(4) { i ->
        if (i == 0) textPaint else Paint().apply {
            typeface = if (i and CELL_BOLD != 0) {
                Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            } else {
                Typeface.MONOSPACE
            }
            isUnderlineText = i and CELL_UNDERLINE != 0
            isAntiAlias = true
            textSize = textPaint.textSize
        }
    }

    // Which half of the blink cycle is showing. True means "draw the glyph",
    // and it starts true so a view that never schedules a tick - the ordinary
    // case, because nothing on screen blinks - shows its text rather than
    // hiding it forever.
    private var textBlinkOn = true

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

    // VT100 escape sequence parsing state. The five states past NORMAL are the
    // same set z80cpmw's EscapeState enum carries; ESC_VT52_ROW/COL are the two
    // bytes of a VT52 direct cursor address and ESC_CONSUME_ONE swallows the
    // single byte after a charset or line-size designator.
    private var escapeState = ESC_NORMAL
    // Parameters are accumulated one field at a time, the way both siblings do
    // it, rather than collected as text and split on ';' at the end. The split
    // form used mapNotNull, which REMOVES a field it cannot parse instead of
    // defaulting it: "ESC[;5H" became [5] and moved the cursor to row 5 instead
    // of column 5, and any over-long field did the same to everything after it.
    // Closing each field where it ends is what keeps a parameter in the
    // position the guest put it in.
    private val escapeParams = mutableListOf<Int>()
    private val escapeCurrentParam = StringBuilder()
    // Whether a private marker ('?', '<', '=', '>') was seen anywhere in the
    // current CSI. Five finals consult it and the rest ignore it, which is what
    // keeps ESC[?5H a cursor move and ESC[>4;2m out of the rendition.
    private var escapePrivate = false
    private var currentFgColor = DEFAULT_FG
    private var currentBgColor = DEFAULT_BG
    // The CELL_* bits the next cell written will carry.
    private var currentFlags = 0
    // SGR 7. Not folded into the two colours - see the note on CELL_BOLD.
    private var reverseVideo = false

    // DECSC/DECRC (ESC 7 / ESC 8) and SCP/RCP (CSI s / CSI u). One slot, shared
    // by both pairs exactly as it is in both sibling ports, so an ESC 7 then a
    // CSI s then an ESC 8 restores the CSI s position with the ESC 7 rendition.
    // A restore before any save homes the cursor, which is what a VT100 does.
    private var savedCursorRow = 0
    private var savedCursorCol = 0
    private var savedFgColor = DEFAULT_FG
    private var savedBgColor = DEFAULT_BG
    private var savedFlags = 0
    private var savedReverse = false

    // DECSTBM, 0-based and INCLUSIVE of both ends. Only the line feed, the
    // reverse index, IL/DL and SU/SD consult it; cursor addressing does not,
    // because there is no origin mode here or in either sibling.
    private var scrollTop = 0
    private var scrollBottom = MIN_ROWS - 1

    // The deferred wrap. A glyph landing in the last column leaves the cursor
    // ON that column with this armed, and the wrap is taken by the NEXT glyph -
    // which is what stops a line that exactly fills the width from scrolling
    // before anything has been written on the line after it.
    private var pendingWrap = false
    // DECAWM (CSI ? 7 h / l). The GUEST's half of the wrap decision; wrapLines
    // is the USER's, and a wrap needs both. With wrapLines off this port
    // truncates at the buffer edge whatever the guest asks for, because the
    // user chose that and a guest does not get to overrule it.
    private var autoWrap = true

    // DECANM. False is ANSI/VT100, the power-on state.
    private var vt52Mode = false
    // The row byte of an ESC Y, held while its column byte is awaited.
    private var vt52CursorRow = 0

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
     * .command and threw the Ctrl modifier away.
     *
     * This port now HAS a VT52 mode, and the arrows deliberately ignore it:
     * they send the ANSI forms whatever mode the SCREEN is in. ioscpm gates its
     * key table on the dialect, because a VT52 has no parameterised CSI to put
     * the Ctrl modifier 5 in and its arrows must fall back to the bare form.
     * The two are separable - what the guest paints with and what the keyboard
     * sends are different directions - and following ioscpm here would mean a
     * guest that sent one ESC A silently changed what the arrow keys transmit.
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

        // The other three faces take the size the plain one settled on. Their
        // metrics are deliberately not consulted - see the note on glyphPaints.
        for (paint in glyphPaints) paint.textSize = finalFontSize

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
        val oldAttrBuffer = attrBuffer

        rows = newRows
        cols = newCols

        // Create new buffers. The cells outside the copied region get the
        // power-on rendition rather than the current one: this is a font-size
        // or rotation change, not an erase, and a guest that set a background
        // never asked for the new space to be painted in it.
        screenBuffer = Array(rows) { CharArray(cols) { ' ' } }
        colorBuffer = Array(rows) { IntArray(cols) { DEFAULT_FG } }
        bgBuffer = Array(rows) { IntArray(cols) { DEFAULT_BG } }
        attrBuffer = Array(rows) { ByteArray(cols) }

        // Copy old content (as much as fits)
        for (r in 0 until minOf(oldRows, rows)) {
            for (c in 0 until minOf(oldCols, cols)) {
                screenBuffer[r][c] = oldScreenBuffer[r][c]
                colorBuffer[r][c] = oldColorBuffer[r][c]
                bgBuffer[r][c] = oldBgBuffer[r][c]
                attrBuffer[r][c] = oldAttrBuffer[r][c]
            }
        }

        // Adjust cursor position if needed
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        cursorCol = cursorCol.coerceIn(0, cols - 1)

        // The saved cursor moves with the live one, and for the same reason:
        // a rotation between an ESC 7 and its ESC 8 would otherwise restore a
        // position from the old grid.
        savedCursorRow = savedCursorRow.coerceIn(0, rows - 1)
        savedCursorCol = savedCursorCol.coerceIn(0, cols)

        // The scrolling region is in rows, and the row count can move under it
        // - a rotation or a font-size change comes through here. A region left
        // pointing past the last row would make lineFeed() compare the cursor
        // against a row that does not exist, so it is re-clamped rather than
        // trusted. A region that no longer makes sense goes back to the whole
        // screen, which is the state a guest that never set one expects.
        scrollBottom = scrollBottom.coerceIn(0, rows - 1)
        scrollTop = scrollTop.coerceIn(0, rows - 1)
        if (scrollTop >= scrollBottom) {
            scrollTop = 0
            scrollBottom = rows - 1
        }

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
                        attrBuffer[liveRow], r, offsetX, offsetY, baseline)
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
                            historyFlags[lineIdx], r, offsetX, offsetY, baseline)
                } else {
                    val liveRow = lineIdx - historySize
                    drawRow(canvas, screenBuffer[liveRow], colorBuffer[liveRow], bgBuffer[liveRow],
                            attrBuffer[liveRow], r, offsetX, offsetY, baseline)
                    if (liveRow == cursorRow) drawCursor(canvas, r, viewportRows, offsetX, offsetY)
                }
            }
        }
    }

    private fun drawRow(canvas: Canvas, chars: CharArray, colors: IntArray, bgs: IntArray,
                        flags: ByteArray, vrow: Int, offsetX: Float, offsetY: Float,
                        baseline: Float) {
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
                val cellFlags = flags[col].toInt()
                // The off phase of a blink collapses the glyph into its own
                // background rather than skipping the drawText: the two look
                // the same on a cell with a background rect and very different
                // on one without, where skipping would leave the page fill and
                // collapsing leaves the page fill too - but the collapse also
                // takes the UNDERLINE with it, and skipping would not, because
                // the underline is drawn by the face rather than by us.
                val paint = glyphPaints[cellFlags and CELL_FACE_MASK]
                paint.color = if (cellFlags and CELL_BLINK != 0 && !textBlinkOn) {
                    if (bgs[col] == DEFAULT_BG) PAPER else bgs[col]
                } else {
                    colors[col]
                }
                canvas.drawText(ch.toString(), x, y, paint)
            }
        }
    }

    /**
     * Keep the blink phase turning while anything on the live screen is asking
     * for it, and stop as soon as nothing is.
     *
     * A repeating invalidate is a real cost on a device, and a terminal that
     * has never seen SGR 5 - which is nearly every session, because almost
     * nothing in CP/M blinks - must not pay it. So the tick is armed only when
     * a blinking cell is actually written, and each tick re-checks: the moment
     * the last one scrolls off or is erased, the loop ends and the phase is put
     * back to "showing" so a later still frame cannot be caught mid-blink.
     *
     * Only the LIVE screen is scanned, and a blinking cell that has scrolled
     * into history therefore stops blinking: it keeps its flag, so it will
     * blink again if something on the live screen restarts the tick, but
     * nothing re-arms on its own behalf. That is the right trade - the
     * alternative is scanning a thousand history lines every 500 ms to keep a
     * cell strobing that the user has scrolled away from - and it is written
     * down because the behaviour is otherwise indistinguishable from a bug.
     */
    private fun scheduleBlink() {
        if (blinkScheduled) return
        blinkScheduled = true
        postDelayed(blinkTick, BLINK_MS)
    }

    private var blinkScheduled = false

    private val blinkTick: Runnable = Runnable {
        blinkScheduled = false
        if (hasBlinkingCells()) {
            textBlinkOn = !textBlinkOn
            invalidate()
            scheduleBlink()
        } else if (!textBlinkOn) {
            textBlinkOn = true
            invalidate()
        }
    }

    private fun hasBlinkingCells(): Boolean {
        for (row in 0 until rows) {
            val line = attrBuffer[row]
            for (col in 0 until cols) {
                if (line[col].toInt() and CELL_BLINK != 0) return true
            }
        }
        return false
    }

    override fun onDetachedFromWindow() {
        // The blink re-posts itself, so nothing else would ever stop it.
        removeCallbacks(blinkTick)
        blinkScheduled = false
        removeCallbacks(abandonBellFocus)
        abandonBellAudioFocus()
        super.onDetachedFromWindow()
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
            ESC_NORMAL -> { // Normal state
                when (ch) {
                    0x1B -> { // ESC
                        escapeState = ESC_SEEN
                        escapeParams.clear()
                        escapeCurrentParam.clear()
                        escapePrivate = false
                    }
                    0x0D -> { cursorCol = 0; pendingWrap = false }   // CR
                    // LF, with an implicit carriage return. Both siblings do
                    // this at the same point and both wrote down why: without
                    // it a file with bare LFs - anything that came from a Unix
                    // host - stair-steps down and to the right, each line
                    // starting where the last one ended. It costs nothing for
                    // ordinary CP/M output, which sends CR before LF and has
                    // therefore already zeroed the column.
                    //
                    // lineFeed() itself deliberately does NOT do this: NEL and
                    // the deferred wrap both call it after setting the column
                    // themselves, and IND is defined as a row move alone.
                    0x0A -> { cursorCol = 0; lineFeed() }   // LF
                    0x08 -> { // BS
                        pendingWrap = false
                        if (cursorCol > 0) cursorCol--
                    }
                    0x09 -> { // TAB - next 8-column stop
                        pendingWrap = false
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
            ESC_SEEN -> processEscape(ch)
            ESC_VT52_ROW -> {
                // ESC Y takes its two coordinates as bytes biased by 0x20.
                vt52CursorRow = (ch - 0x20).coerceIn(0, rows - 1)
                escapeState = ESC_VT52_COL
            }
            ESC_VT52_COL -> {
                // Clearing the wrap here is a deliberate divergence from
                // z80cpmw, which does not. Its own conformance suite asserts
                // the rule this follows - "a cursor move cancels an armed wrap"
                // in tests/test_vt52.cpp - and ESC Y is the one cursor move
                // there exempt from it, so this sides with that port's tests
                // against that port's code. ioscpm clears it. An address that
                // did not resolve an armed wrap would be thrown
                // away by the next glyph, which takes the wrap first: the
                // character would land one row below the addressed row, at
                // column 0, and on the bottom row of a scrolling region it
                // would scroll the region as well.
                pendingWrap = false
                cursorRow = vt52CursorRow
                cursorCol = (ch - 0x20).coerceIn(0, cols - 1)
                escapeState = ESC_NORMAL
            }
            ESC_CONSUME_ONE -> {
                // The designator's argument: a character set for ESC ( ) * +,
                // a line size for ESC #. Swallowed whole, because nothing here
                // remaps a glyph or draws a double-height row, and printing the
                // argument is the one outcome that is certainly wrong.
                escapeState = ESC_NORMAL
            }
            ESC_CSI -> { // CSI sequence
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
                        // Consumed and NOT treated as a final: ESC[?25l has to
                        // reach processCSI with 'l' as its final and be acted on
                        // there. Ending the sequence at the '?' would print
                        // "25l" on screen, which is the bug z80cpmw's item 13
                        // records fixing in the other direction.
                        //
                        // The four markers are now REMEMBERED rather than only
                        // swallowed. Five finals need to know: 'h' and 'l' act
                        // only when the marker is present, 'n' and 'c' only
                        // when it is absent, and 'm' bails out entirely - so
                        // that the xterm modifyOtherKeys queries are not read
                        // as renditions. See the note on the 'm' handler for
                        // what each of the two forms would otherwise do.
                        if (ch == '?'.code || ch == '<'.code ||
                            ch == '='.code || ch == '>'.code) {
                            escapePrivate = true
                        }
                    }
                    ch in 0x20..0x2F -> {
                        // Intermediate bytes, including the space of CSI SP q.
                        // Consumed without joining the parameter list, so the
                        // real final still reaches processCSI.
                    }
                    ch in 0x40..0x7E -> {
                        // A trailing empty field is not a parameter: ESC[H and
                        // ESC[m must still arrive with an empty list, so that
                        // getOrElse's defaults and the SGR reset keep working.
                        endCsiParam(keepEmpty = false)
                        processCSI(ch.toChar())
                        escapeState = ESC_NORMAL
                    }
                    else -> escapeState = ESC_NORMAL
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
     * The byte after an ESC that was not '[', which this parser used to discard
     * outright - so VT52 did not exist, nothing could save a cursor, and a
     * program driving the screen with ESC 7 / ESC 8 had both halves swallowed
     * and its output left where the cursor happened to be.
     *
     * The table is z80cpmw's processEscapeChar, byte for byte, because that is
     * the port FEATURE_PARITY.md measures the family against and two of these
     * bytes mean DIFFERENT things depending on the mode already in force.
     * Guessing either would be worse than not having them.
     */
    private fun processEscape(ch: Int) {
        when (ch.toChar()) {
            '[' -> {
                escapeState = ESC_CSI
                escapeParams.clear()
                escapeCurrentParam.clear()
                escapePrivate = false
                return
            }
            // DECSC / DECRC. The whole rendition travels with the position -
            // both colours, the CELL_* bits and reverse - which is the half a
            // save that kept only a cursor gets wrong: a program that saves,
            // prints a highlighted status line and restores expects the
            // highlight to end there.
            '7' -> {
                savedCursorRow = cursorRow
                savedCursorCol = cursorCol
                savedFgColor = currentFgColor
                savedBgColor = currentBgColor
                savedFlags = currentFlags
                savedReverse = reverseVideo
            }
            '8' -> {
                pendingWrap = false
                cursorRow = savedCursorRow.coerceIn(0, rows - 1)
                // 0..cols, not 0..cols-1. The parked column is a real state -
                // truncate mode leaves the cursor one past the edge - and
                // coercing it away here would make the save/restore pair move
                // the cursor: a glyph after the restore would overwrite the
                // last cell where before the pair it was dropped. The bound is
                // still a bound, because resizeBuffers re-clamps both saved
                // values whenever the grid moves under them.
                cursorCol = savedCursorCol.coerceIn(0, cols)
                currentFgColor = savedFgColor
                currentBgColor = savedBgColor
                currentFlags = savedFlags
                reverseVideo = savedReverse
            }
            // Overloaded between the two modes, and deliberately does NOT
            // switch mode either way: which one is meant is decided by the mode
            // already in force.
            'D' -> if (vt52Mode) {
                pendingWrap = false
                if (cursorCol > 0) cursorCol--
            } else {
                lineFeed()  // IND
            }
            'E' -> if (vt52Mode) {
                eraseScreen()  // Heath/Zenith clear and home
            } else {
                cursorCol = 0  // NEL
                lineFeed()
            }
            // RI, in both modes. Only the exact top of the scrolling region
            // scrolls; above it the cursor just moves up, and at row 0 with a
            // region that starts lower, nothing happens at all.
            'M' -> {
                pendingWrap = false
                if (cursorRow == scrollTop) scrollRegionDown(1)
                else if (cursorRow > 0) cursorRow--
            }
            // The VT52-exclusive bytes. Receiving one IS the signal that the
            // guest is driving a VT52, so each sets the mode as its first act -
            // the same auto-detection z80cpmw does, and the reason a CP/M
            // program that never sends ESC [ ? 2 l still gets a VT52.
            'A' -> { vt52Mode = true; pendingWrap = false; if (cursorRow > 0) cursorRow-- }
            'B' -> { vt52Mode = true; pendingWrap = false; cursorRow = (cursorRow + 1).coerceAtMost(rows - 1) }
            'C' -> { vt52Mode = true; pendingWrap = false; cursorCol = (cursorCol + 1).coerceAtMost(cols - 1) }
            // Clamps at the top rather than scrolling, which is what a real
            // VT52 does and what z80cpmw documents doing.
            'I' -> { vt52Mode = true; pendingWrap = false; if (cursorRow > 0) cursorRow-- }
            // Erasing resolves an armed wrap, the same way ED does - z80cpmw
            // routes this byte through the function that clears it. ESC K
            // deliberately does not, which is also z80cpmw's behaviour and
            // ioscpm's: EL leaves the wrap alone in every port.
            'J' -> { vt52Mode = true; pendingWrap = false; clearToEnd() }
            'K' -> { vt52Mode = true; clearLineToEnd() }
            'Y' -> { vt52Mode = true; escapeState = ESC_VT52_ROW; return }
            // Enter/exit the VT52 graphics set. Consumed and remembered as
            // "this is a VT52"; no glyph is remapped, as in z80cpmw.
            'F', 'G' -> vt52Mode = true
            // Home, but ONLY if the VT52 is already established. In ANSI this
            // byte is HTS, and there are no settable tab stops here, so acting
            // on it would move the cursor for a sequence that asked for a tab
            // stop.
            'H' -> if (vt52Mode) {
                pendingWrap = false
                cursorRow = 0
                cursorCol = 0
            }
            'Z' -> sendAnswerback(if (vt52Mode) VT52_ID_RESPONSE else DA_RESPONSE)
            '<' -> vt52Mode = false
            // RIS. The one guest sequence that reaches the machine-level reset;
            // see the note on clear().
            'c' -> clear()
            // Keypad application/numeric. Accepted and ignored: this port sends
            // no keypad sequences that would differ between the two.
            '=', '>' -> { }
            // A designator whose argument is the next byte.
            '(', ')', '*', '+', '#', ' ' -> {
                escapeState = ESC_CONSUME_ONE
                return
            }
            // Anything else is swallowed, INCLUDING a second ESC - an ESC does
            // not restart the escape state, which is what z80cpmw does and what
            // keeps "ESC ESC [ 2 J" printing "[2J" on both ports rather than
            // clearing the screen on one of them.
            else -> { }
        }
        escapeState = ESC_NORMAL
    }

    /**
     * Send a reply to the guest as though it had been typed.
     *
     * The leading ESC is added here for the same reason sendEscapeSeq() adds
     * it. It goes straight to inputListener rather than through sendChar(),
     * which is where the two differ on the sibling ports: z80cpmw's key path
     * calls scrollToBottom() and drops the mouse selection, so its
     * sendAnswerback deliberately bypasses it - "the terminal answering a
     * question is not the user typing". Nothing here does that yet; sendChar()
     * is a bare invoke and the scroll position is reset by processOutput, on
     * output. Keeping the reply off the key path anyway is what stops that
     * from silently becoming untrue the first time sendChar() grows a side
     * effect, which on this port it eventually will - the on-screen Ctrl latch
     * is already one caller's worth of state away.
     */
    private fun sendAnswerback(s: String) {
        inputListener?.let { listener ->
            listener.invoke(0x1B)
            for (c in s) listener.invoke(c.code)
        }
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
                pendingWrap = false
                cursorRow = (csiParamOrOne(params, 0) - 1).coerceIn(0, rows - 1)
                cursorCol = (csiParamOrOne(params, 1) - 1).coerceIn(0, cols - 1)
            }
            // The four motions clamp to the SCREEN, not to the scrolling
            // region. Both siblings do the same, and it is what a VT100 does:
            // the region bounds scrolling, not addressing.
            'A' -> { pendingWrap = false; cursorRow = (cursorRow - csiParamOrOne(params, 0)).coerceAtLeast(0) }
            'B' -> { pendingWrap = false; cursorRow = (cursorRow + csiParamOrOne(params, 0)).coerceAtMost(rows - 1) }
            'C' -> { pendingWrap = false; cursorCol = (cursorCol + csiParamOrOne(params, 0)).coerceAtMost(cols - 1) }
            'D' -> { pendingWrap = false; cursorCol = (cursorCol - csiParamOrOne(params, 0)).coerceAtLeast(0) }
            // CHA and HPA - absolute column. The two finals share a handler in
            // both siblings; '`' is the older spelling.
            'G', '`' -> { pendingWrap = false; cursorCol = (csiParamOrOne(params, 0) - 1).coerceIn(0, cols - 1) }
            // VPA - absolute row, never region-relative (no origin mode).
            'd' -> { pendingWrap = false; cursorRow = (csiParamOrOne(params, 0) - 1).coerceIn(0, rows - 1) }
            'J' -> { // Erase display
                pendingWrap = false
                when (params.getOrElse(0) { 0 }) {
                    0 -> clearToEnd()
                    1 -> clearToBeginning()
                    2 -> eraseScreen()
                }
            }
            'K' -> { // Erase line
                when (params.getOrElse(0) { 0 }) {
                    0 -> clearLineToEnd()
                    1 -> clearLineToBeginning()
                    2 -> clearLine()
                }
            }
            // The seven editing commands - five that rewrite a line or a block
            // of lines, and the two scrolls. Each fills what it vacates through
            // the same blank helpers everything else uses, so an inserted blank
            // takes the current background like any other erase.
            '@' -> insertChars(csiParamOrOne(params, 0))
            'P' -> deleteChars(csiParamOrOne(params, 0))
            'X' -> eraseChars(csiParamOrOne(params, 0))
            'L' -> insertLines(csiParamOrOne(params, 0))
            'M' -> deleteLines(csiParamOrOne(params, 0))
            'S' -> repeat(csiParamOrOne(params, 0).coerceAtMost(rows)) { scrollRegionUp(1) }
            'T' -> repeat(csiParamOrOne(params, 0).coerceAtMost(rows)) { scrollRegionDown(1) }
            'r' -> setScrollRegion(params)
            // SCP/RCP, sharing the DECSC slot. CSI s saves the POSITION only -
            // that is the DEC/ANSI.SYS reading of it and what both siblings do -
            // while CSI u restores position and leaves the rendition alone.
            's' -> {
                savedCursorRow = cursorRow
                savedCursorCol = cursorCol
            }
            'u' -> {
                pendingWrap = false
                cursorRow = savedCursorRow.coerceIn(0, rows - 1)
                cursorCol = savedCursorCol.coerceIn(0, cols)
            }
            // SM/RM. Private modes only: the non-private ones (IRM 4, LNM 20)
            // are not implemented here or in either sibling, and acting on the
            // number without the marker would confuse the two sets.
            'h' -> if (escapePrivate) setPrivateModes(params, true)
            'l' -> if (escapePrivate) setPrivateModes(params, false)
            // DSR. Non-private only, so ESC[?6n is silent - a program asking
            // the private question is asking about something this terminal does
            // not have, and answering the public answer would be a lie about
            // which question was understood.
            'n' -> if (!escapePrivate) {
                when (params.getOrElse(0) { 0 }) {
                    5 -> sendAnswerback("[0n")   // "I am fine"
                    // CPR, 1-based. The column is clamped because cursorCol can
                    // legitimately sit one PAST the last column: that is how
                    // truncate mode parks after filling a line (see putChar).
                    // Reporting it unclamped answers "81" on an 80-column
                    // screen - a column CUP cannot address and neither sibling
                    // can produce, since neither ever parks past the edge.
                    6 -> sendAnswerback(
                        "[${cursorRow + 1};${(cursorCol + 1).coerceAtMost(cols)}R")
                }
            }
            // Primary Device Attributes. ESC[>c and ESC[=c are silent, as they
            // are in z80cpmw: they ask for a secondary/tertiary identity this
            // terminal does not have one of.
            'c' -> if (!escapePrivate && params.getOrElse(0) { 0 } == 0) sendAnswerback(DA_RESPONSE)
            'm' -> { // SGR - Select Graphic Rendition
                // A private marker means this is not a rendition at all.
                // ESC[>4;2m is how an xterm-aware program asks about
                // modifyOtherKeys; read as SGR its "4" would turn underline on.
                // The bare ESC[>m is worse: with the marker consumed and no
                // parameters left it is indistinguishable from ESC[m and resets
                // the whole rendition. z80cpmw's changelog records exactly that
                // bug, and its tests keep the two forms apart.
                if (escapePrivate) return
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
                        // their own right they land as renditions: the "33" of
                        // ESC[38;5;33m set a brown foreground, the "5" of
                        // ESC[38;5;1m would now start the cell blinking, and the
                        // "0" of ESC[38;2;0;128;255m reset the whole rendition
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
                        applySGR(p)
                        i++
                    }
                }
            }
        }
    }

    /**
     * One SGR parameter.
     *
     * Split out of the 'm' handler when the attribute half arrived: the loop
     * above owns the 38/48 lookahead, which is about the SHAPE of the parameter
     * list, and this owns what a single parameter means. Anything not listed is
     * a silent no-op, which is what both siblings do - a rendition nobody
     * implements is better ignored than approximated.
     */
    private fun applySGR(p: Int) {
        when {
            p == 0 -> resetRendition()
            // Bold. Unlike z80cpmw this does NOT also set an intensity bit in
            // the colour: that port packs a CGA byte whose bit 3 IS the bright
            // half of the palette, so bold and bright are the same storage
            // there and cannot be separated. A cell here holds a full ARGB
            // foreground and a CELL_BOLD bit beside it, so ESC[1m picks the
            // heavy face and leaves the colour exactly as the guest set it -
            // and ESC[22m undoes precisely what ESC[1m did.
            p == 1 -> currentFlags = currentFlags or CELL_BOLD
            p == 22 -> currentFlags = currentFlags and CELL_BOLD.inv()
            p == 4 -> currentFlags = currentFlags or CELL_UNDERLINE
            p == 24 -> currentFlags = currentFlags and CELL_UNDERLINE.inv()
            // 5 is slow blink and 6 is fast blink; one bit serves both, as it
            // does in z80cpmw. Two rates would need two timers to tell apart.
            p == 5 || p == 6 -> {
                currentFlags = currentFlags or CELL_BLINK
                scheduleBlink()
            }
            p == 25 -> currentFlags = currentFlags and CELL_BLINK.inv()
            p == 7 -> reverseVideo = true
            p == 27 -> reverseVideo = false
            // 39 and 49 are "back to the default", separately for each half.
            // Without them a program that said ESC[31m ... ESC[39m stayed red
            // for the rest of the session, because nothing but a full reset
            // could undo a colour. Neither sibling implements these; this port
            // does, and it is the one divergence in this handler that makes it
            // MORE conformant rather than less.
            p == 39 -> currentFgColor = DEFAULT_FG
            p == 49 -> currentBgColor = DEFAULT_BG
            p in 30..37 -> currentFgColor = cgaColors[ansiToCgaIndex(p - 30)]
            p in 40..47 -> currentBgColor = cgaColors[ansiToCgaIndex(p - 40)]
            p in 90..97 -> currentFgColor = cgaColors[ansiToCgaIndex(p - 90) + 8]
            // The bright backgrounds are NOT folded onto the normal ones the
            // way z80cpmw folds them. It has to: its cell is a packed CGA
            // attribute byte whose background field is three bits wide, with
            // bit 7 being blink, so a bright background could only be stored by
            // borrowing it. A cell here is a full ARGB Int - no nibble, no
            // blink bit, nothing to borrow - and the bright foregrounds already
            // render, so the background half is symmetric with them. Folding
            // would make ESC[104m indistinguishable from ESC[44m for a reason
            // that does not apply here.
            p in 100..107 -> currentBgColor = cgaColors[ansiToCgaIndex(p - 100) + 8]
        }
    }

    /**
     * DECSTBM, CSI <top> ; <bottom> r.
     *
     * Both parameters default when missing OR zero, so a bare ESC[r resets the
     * region to the whole screen. A region that is inverted or only one line
     * tall is rejected WHOLE - the old region survives and the cursor is not
     * homed - which is what both siblings do; a one-line scrolling region has
     * no scroll in it, and honouring it would park the guest's output on a
     * single row.
     *
     * The bottom is clamped rather than rejected so a 24-line program's ESC[1;24r
     * works on a screen that is 24 rows here, and the cursor homes to absolute
     * (0,0) rather than to the region's top, because there is no origin mode.
     */
    private fun setScrollRegion(params: List<Int>) {
        pendingWrap = false
        val top = if (params.getOrElse(0) { 0 } > 0) params[0] - 1 else 0
        var bottom = if (params.getOrElse(1) { 0 } > 0) params[1] - 1 else rows - 1
        if (bottom > rows - 1) bottom = rows - 1
        if (top < bottom) {
            scrollTop = top
            scrollBottom = bottom
            cursorRow = 0
            cursorCol = 0
        }
    }

    /**
     * The private modes this terminal has something to do about. Everything
     * else is consumed silently, which is the whole point of a private marker.
     */
    private fun setPrivateModes(params: List<Int>, set: Boolean) {
        for (p in params) {
            when (p) {
                // DECANM. Set means ANSI, reset means VT52 - the sense is
                // inverted relative to the name, and it is the standard's.
                2 -> vt52Mode = !set
                // DECAWM.
                7 -> {
                    autoWrap = set
                    // Turning it off drops a wrap already armed, rather than
                    // leaving it to fire on the next glyph after the guest has
                    // said it does not want one.
                    if (!set) pendingWrap = false
                }
                // DECTCEM.
                25 -> {
                    cursorVisible = set
                    invalidate()
                }
            }
        }
    }


    private fun putChar(ch: Char) {
        // When wrap is enabled, wrap at visible screen edge
        // When wrap is disabled, truncate at buffer edge (cols)
        val wrapAt = if (wrapLines) visibleCols else cols

        // Take a wrap armed by the PREVIOUS glyph, before writing this one.
        // The line the wrap moves onto is chosen by lineFeed(), so a wrap on
        // the last row of a scrolling region scrolls the region rather than the
        // screen.
        if (pendingWrap) {
            cursorCol = 0
            lineFeed()
            pendingWrap = false
        }

        // The cursor is past the edge, having arrived from somewhere OTHER
        // than the previous glyph - putChar itself never leaves it there while
        // wrapping. A TAB, a CUP, a CUF or a restored cursor can, because every
        // one of those clamps to `cols - 1` while `wrapAt` is `visibleCols`,
        // and visibleCols is the smaller of the two at any font size above the
        // default. Turning the wrap setting on mid-session does it too: the
        // truncate arm below parks the cursor at `cols`, and `wrapAt` then
        // becomes visibleCols under it.
        //
        // All three cases have to be answered, and the middle one is the one
        // this block lost when the pending flag arrived: for a while it was a
        // bare `return`, which threw away the rest of the line where the code
        // before it had wrapped. That is a character destroyed rather than
        // pushed off-screen, because nothing reaches the buffer at all.
        if (cursorCol >= wrapAt) {
            when {
                // Truncate: the setting promises the head of the line, so the
                // rest goes. This arm is the one the old code had.
                !wrapLines -> return
                // Wrapping: take the wrap now. This is what was lost.
                autoWrap -> { cursorCol = 0; lineFeed() }
                // Wrapping enabled but DECAWM off: the guest asked not to
                // wrap, which is not a request to discard. Overwrite the last
                // column, as both siblings do.
                else -> cursorCol = wrapAt - 1
            }
        }

        screenBuffer[cursorRow][cursorCol] = ch
        colorBuffer[cursorRow][cursorCol] = renditionFg()
        bgBuffer[cursorRow][cursorCol] = renditionBg()
        attrBuffer[cursorRow][cursorCol] = currentFlags.toByte()
        if (currentFlags and CELL_BLINK != 0) scheduleBlink()

        if (cursorCol >= wrapAt - 1) {
            // At the last column, and the three cases are genuinely different.
            //
            // The user's "wrap long lines" setting off means TRUNCATE, and
            // truncating means the head of the line: the cursor parks one past
            // the edge and the early return above drops the rest. That is what
            // this port did before there was a pending flag and it is what the
            // setting promises - a reader wants the first 80 columns of a wide
            // report, not the first 79 and the last one.
            //
            // Wrapping on, DECAWM on: arm the wrap rather than taking it. A
            // line that exactly fills the width must not scroll until something
            // is written on the line AFTER it, which is what a real VT100 does
            // and what stops a full-width line costing a blank one below it.
            //
            // Wrapping on, DECAWM off: the guest said "do not wrap", which is
            // not the same as "throw the rest away". The cursor stays ON the
            // last column and each further glyph overwrites it, which is what
            // both siblings do (z80cpmw's processNormalChar has no else arm at
            // all; ioscpm says it in words). Reading this case as the truncate
            // case was wrong: it let a guest that turned autowrap off lose
            // every character after the eightieth.
            if (!wrapLines) cursorCol = wrapAt
            else if (autoWrap) pendingWrap = true
        } else {
            cursorCol++
        }
    }

    /**
     * One line down, honouring the scrolling region - IND, NEL, a bare LF and
     * the deferred wrap all land here.
     *
     * Three cases, and the middle one is the whole point of a region: exactly
     * AT the region's bottom row the region scrolls and the cursor stays put;
     * above the region the cursor walks down normally and may walk into it;
     * below it the cursor moves down and stops at the last row. A cursor parked
     * under the region can never scroll it, which is what lets a program keep a
     * status line at the bottom of the screen.
     *
     * This replaced newLine(), which knew only about the whole screen.
     */
    private fun lineFeed() {
        pendingWrap = false
        if (cursorRow < scrollTop) {
            if (cursorRow < rows - 1) cursorRow++
        } else if (cursorRow >= scrollBottom) {
            if (cursorRow == scrollBottom) scrollRegionUp(1)
            else if (cursorRow < rows - 1) cursorRow++
        } else {
            cursorRow++
        }
    }

    private fun scrollUp() {
        // Push the top live line into scrollback history before it scrolls off.
        if (scrollbackLines > 0) {
            historyChars.addLast(screenBuffer[0].copyOf())
            historyColors.addLast(colorBuffer[0].copyOf())
            historyBg.addLast(bgBuffer[0].copyOf())
            historyFlags.addLast(attrBuffer[0].copyOf())
            while (historyChars.size > scrollbackLines) {
                historyChars.removeFirst()
                historyColors.removeFirst()
                historyBg.removeFirst()
                historyFlags.removeFirst()
            }
        } else if (historyChars.isNotEmpty()) {
            // Scrollback was turned off after lines were already kept.
            historyChars.clear()
            historyColors.clear()
            historyBg.clear()
            historyFlags.clear()
            userScrollUp = 0
        }
        for (row in 0 until rows - 1) {
            screenBuffer[row] = screenBuffer[row + 1].copyOf()
            colorBuffer[row] = colorBuffer[row + 1].copyOf()
            bgBuffer[row] = bgBuffer[row + 1].copyOf()
            attrBuffer[row] = attrBuffer[row + 1].copyOf()
        }
        // Fresh arrays because cols may have moved since these were allocated,
        // then blanked through the one definition of a blank cell - the new
        // bottom line is an erase like any other, and takes the current
        // background with it.
        screenBuffer[rows - 1] = CharArray(cols)
        colorBuffer[rows - 1] = IntArray(cols)
        bgBuffer[rows - 1] = IntArray(cols)
        attrBuffer[rows - 1] = ByteArray(cols)
        blankRow(rows - 1)
    }

    /**
     * Scroll the scrolling region up by `lines`, discarding what falls out of
     * the top of it.
     *
     * When the region is the whole screen this delegates to scrollUp(), which
     * is the ONLY path that feeds the scrollback. A partial region deliberately
     * does not: what falls out of the top of a region was never on screen above
     * it, so it is not history, and pushing it would interleave a status
     * window's discarded rows into the user's transcript.
     */
    private fun scrollRegionUp(lines: Int) {
        if (lines <= 0) return
        if (scrollTop == 0 && scrollBottom == rows - 1) {
            repeat(lines.coerceAtMost(rows)) { scrollUp() }
            return
        }
        val height = scrollBottom - scrollTop + 1
        val n = lines.coerceAtMost(height)
        for (row in scrollTop..scrollBottom - n) {
            screenBuffer[row] = screenBuffer[row + n].copyOf()
            colorBuffer[row] = colorBuffer[row + n].copyOf()
            bgBuffer[row] = bgBuffer[row + n].copyOf()
            attrBuffer[row] = attrBuffer[row + n].copyOf()
        }
        for (row in scrollBottom - n + 1..scrollBottom) blankRow(row)
    }

    /**
     * Scroll the region down by `lines`. There is no whole-screen special case
     * and nothing reaches the scrollback: history only ever grows off the top.
     */
    private fun scrollRegionDown(lines: Int) {
        if (lines <= 0) return
        val height = scrollBottom - scrollTop + 1
        val n = lines.coerceAtMost(height)
        for (row in scrollBottom downTo scrollTop + n) {
            screenBuffer[row] = screenBuffer[row - n].copyOf()
            colorBuffer[row] = colorBuffer[row - n].copyOf()
            bgBuffer[row] = bgBuffer[row - n].copyOf()
            attrBuffer[row] = attrBuffer[row - n].copyOf()
        }
        for (row in scrollTop until scrollTop + n) blankRow(row)
    }

    /**
     * ICH - open `n` cells at the cursor, pushing the rest of the line right.
     * What falls off the right-hand end is lost, not wrapped: this is a
     * line-local operation and there are no left/right margins here.
     */
    private fun insertChars(n: Int) {
        pendingWrap = false
        if (cursorRow >= rows || cursorCol >= cols) return
        val count = n.coerceAtMost(cols - cursorCol)
        for (col in cols - 1 downTo cursorCol + count) {
            screenBuffer[cursorRow][col] = screenBuffer[cursorRow][col - count]
            colorBuffer[cursorRow][col] = colorBuffer[cursorRow][col - count]
            bgBuffer[cursorRow][col] = bgBuffer[cursorRow][col - count]
            attrBuffer[cursorRow][col] = attrBuffer[cursorRow][col - count]
        }
        blankCells(cursorRow, cursorCol, cursorCol + count)
    }

    /** DCH - close `n` cells at the cursor, pulling the rest of the line left. */
    private fun deleteChars(n: Int) {
        pendingWrap = false
        if (cursorRow >= rows || cursorCol >= cols) return
        val count = n.coerceAtMost(cols - cursorCol)
        for (col in cursorCol until cols - count) {
            screenBuffer[cursorRow][col] = screenBuffer[cursorRow][col + count]
            colorBuffer[cursorRow][col] = colorBuffer[cursorRow][col + count]
            bgBuffer[cursorRow][col] = bgBuffer[cursorRow][col + count]
            attrBuffer[cursorRow][col] = attrBuffer[cursorRow][col + count]
        }
        blankCells(cursorRow, cols - count, cols)
    }

    /** ECH - blank `n` cells at the cursor, moving nothing. */
    private fun eraseChars(n: Int) {
        pendingWrap = false
        if (cursorRow >= rows || cursorCol >= cols) return
        blankCells(cursorRow, cursorCol, (cursorCol + n).coerceAtMost(cols))
    }

    /**
     * IL - open `n` lines at the cursor, within the scrolling region.
     *
     * A cursor outside the region makes this a complete no-op, which is the
     * VT100 rule and z80cpmw's: the region is what the command operates inside,
     * so a cursor that is not in one has nothing to insert into. Both commands
     * also move the cursor to column 1, which is the part of the spec a
     * from-scratch implementation usually misses.
     */
    private fun insertLines(n: Int) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        pendingWrap = false
        val count = n.coerceAtMost(scrollBottom - cursorRow + 1)
        for (row in scrollBottom downTo cursorRow + count) {
            screenBuffer[row] = screenBuffer[row - count].copyOf()
            colorBuffer[row] = colorBuffer[row - count].copyOf()
            bgBuffer[row] = bgBuffer[row - count].copyOf()
            attrBuffer[row] = attrBuffer[row - count].copyOf()
        }
        for (row in cursorRow until cursorRow + count) blankRow(row)
        cursorCol = 0
    }

    /** DL - close `n` lines at the cursor, within the scrolling region. */
    private fun deleteLines(n: Int) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        pendingWrap = false
        val count = n.coerceAtMost(scrollBottom - cursorRow + 1)
        for (row in cursorRow..scrollBottom - count) {
            screenBuffer[row] = screenBuffer[row + count].copyOf()
            colorBuffer[row] = colorBuffer[row + count].copyOf()
            bgBuffer[row] = bgBuffer[row + count].copyOf()
            attrBuffer[row] = attrBuffer[row + count].copyOf()
        }
        for (row in scrollBottom - count + 1..scrollBottom) blankRow(row)
        cursorCol = 0
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
        val fg = renditionFg()
        val bg = renditionBg()
        for (col in fromCol until toCol) {
            screenBuffer[row][col] = ' '
            colorBuffer[row][col] = fg
            bgBuffer[row][col] = bg
            attrBuffer[row][col] = 0
        }
    }

    private fun blankRow(row: Int) {
        screenBuffer[row].fill(' ')
        colorBuffer[row].fill(renditionFg())
        bgBuffer[row].fill(renditionBg())
        attrBuffer[row].fill(0)
    }

    /**
     * The foreground and background a cell filled RIGHT NOW takes, with reverse
     * video resolved into them.
     *
     * Reverse is resolved here and nowhere else - not stored on the cell and
     * not applied at paint time - which is what makes SGR 7 and SGR 27 exact
     * inverses: currentFgColor and currentBgColor are never touched by either,
     * so removing reverse gives back precisely the pair that was there before.
     *
     * The PAPER substitution is this port's own, and it is forced by
     * DEFAULT_BG being a sentinel rather than a colour. Reversing "no
     * background" would produce a FOREGROUND of "no background", and drawRow
     * draws nothing for that - a program that reversed a default screen would
     * have gone silent instead of inverting. The colour a DEFAULT_BG cell
     * actually shows is the page fill, so that is what the swap has to hand
     * back. The reversed BACKGROUND needs no such care: it is currentFgColor,
     * which is always a real colour, so a reversed cell always paints a rect.
     */
    private fun renditionFg(): Int = when {
        !reverseVideo -> currentFgColor
        currentBgColor == DEFAULT_BG -> PAPER
        else -> currentBgColor
    }

    private fun renditionBg(): Int = if (reverseVideo) currentFgColor else currentBgColor

    /**
     * ED 2, and the VT52 ESC E: blank every cell and home the cursor.
     *
     * Homing is a deliberate deviation from a strict VT100 ED, and it is the
     * one both siblings make - ANSI.SYS-era software clears the screen with
     * ESC[2J and then prints, expecting to print at the top left.
     *
     * It touches the SCREEN and nothing else: not the rendition, not the
     * scrolling region, not VT52 mode, not DECAWM. Resetting the region here
     * was the bug ioscpm's 0165dac fixed.
     */
    private fun eraseScreen() {
        for (row in 0 until rows) {
            blankRow(row)
        }
        cursorRow = 0
        cursorCol = 0
        pendingWrap = false
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

    /** Back to the power-on rendition, every part of it. */
    private fun resetRendition() {
        currentFgColor = DEFAULT_FG
        currentBgColor = DEFAULT_BG
        currentFlags = 0
        reverseVideo = false
    }

    /**
     * The machine-level clear - the power-on state of the whole terminal, not
     * just of the screen.
     *
     * It is no longer host-only. MainActivity.bootEmulation is still one
     * caller, but ESC c (RIS) is now the other, and a guest CAN reach it: that
     * is what RIS means, and z80cpmw wires it to the same function. ESC[2J
     * still does not - it goes to eraseScreen(), which touches the screen and
     * leaves every mode alone. The difference between the two is the whole
     * reason both exist.
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
        escapeState = ESC_NORMAL
        escapeParams.clear()
        escapeCurrentParam.clear()
        escapePrivate = false
        userScrollUp = 0

        // Every mode the parser can be left in, back to power-on. A guest that
        // reset the machine and then found itself still in VT52 with a
        // three-line scrolling region would have no way to ask for the state it
        // just asked for.
        savedCursorRow = 0
        savedCursorCol = 0
        savedFgColor = DEFAULT_FG
        savedBgColor = DEFAULT_BG
        savedFlags = 0
        savedReverse = false
        scrollTop = 0
        scrollBottom = rows - 1
        vt52Mode = false
        autoWrap = true
        pendingWrap = false
        cursorVisible = true
        textBlinkOn = true

        eraseScreen()
        processOutputCount = 0
        invalidate()
    }
}
