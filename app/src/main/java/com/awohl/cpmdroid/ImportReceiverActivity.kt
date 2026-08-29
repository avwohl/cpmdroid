package com.awohl.cpmdroid

import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.IntentCompat

/**
 * The share target: takes a file another app sends or opens with CPMDroid,
 * stages it in Imports under a CP/M-legal name, says what that name is, and
 * finishes. It has no UI and never touches the emulator.
 *
 * WHY THIS IS NOT AN INTENT-FILTER ON MainActivity, which is where it would
 * naturally go. The native engine is a process-global singleton - g_initialized
 * and g_emu in Java_com_awohl_cpmdroid_EmulatorEngine_nativeInit - and
 * MainActivity calls emulator.init() from onCreate. A share sheet starts its
 * target with FLAG_ACTIVITY_NEW_TASK, so a filter on MainActivity could bring a
 * SECOND MainActivity instance up and re-initialise the engine underneath the
 * running one, with the guest's memory and open disks halfway through
 * something. A separate activity that speaks to no native code cannot do that,
 * however it is launched.
 *
 * WHY android.app.Activity RATHER THAN AppCompatActivity, which every other
 * activity here extends. This one wants to be invisible, and the manifest gives
 * it @android:style/Theme.Translucent.NoTitleBar; an AppCompatActivity under a
 * non-AppCompat theme throws "You need to use a Theme.AppCompat theme (or
 * descendant) with this activity" in onCreate. Leaving the theme unset is not
 * an option either - it would inherit the full-screen emulator theme and flash
 * a black window over the sending app. It inflates no views, so it needs
 * nothing AppCompat provides.
 *
 * WHY android:taskAffinity="" IN THE MANIFEST. Started with NEW_TASK and the
 * default affinity, this activity would be placed in CPMDroid's existing task,
 * and finishing it would leave the emulator in front instead of returning the
 * user to the app they shared from. excludeFromRecents does not prevent that;
 * an empty affinity does.
 */
class ImportReceiverActivity : Activity() {

    companion object {
        private const val TAG = "ImportReceiver"
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // A recreate (the system rebuilding us behind the sender) must not
        // stage the same file a second time. The first instance either finished
        // the copy or died with it; either way, re-running it here would only
        // overwrite what is already staged.
        if (savedInstanceState != null) {
            finish()
            return
        }

        val uris = incomingUris(intent)
        val sharedText = if (uris.isEmpty()) {
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.toByteArray(Charsets.UTF_8)
        } else {
            null
        }
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)

        if (uris.isEmpty() && sharedText == null) {
            // ACTION_SEND text/plain also matches a shared snippet with neither
            // a stream nor text, and a null EXTRA_STREAM must not be
            // dereferenced in an activity the user cannot see.
            Log.w(TAG, "Nothing usable in ${intent.action}")
            Toast.makeText(
                applicationContext,
                getString(R.string.transfer_import_unreadable),
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }

        // Off the main thread, and finish() only afterwards: the sender can be
        // a cloud provider, so the read can block on the network, and the Uri
        // read grant this activity was given is revoked when it finishes.
        // Copying first and finishing second is what makes the grant outlive
        // the copy rather than the other way round.
        val context = applicationContext
        Thread {
            val results = when {
                uris.isNotEmpty() -> uris.map { stageImportStream(context, it) }
                sharedText != null -> listOf(stageImportBytes(context, subject, sharedText))
                else -> emptyList<ImportStaged>()
            }
            val message = importResultMessage(context, results)
            mainHandler.post {
                // applicationContext, because by the time this runs the activity
                // may already be gone; a Toast is a system window and does not
                // need a live one.
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                finish()
            }
        }.start()
    }

    /**
     * The Uris this intent is offering, in the three shapes the manifest filter
     * accepts, minus the ones this app must not dereference.
     *
     * IntentCompat rather than the bare getParcelableExtra, which is deprecated
     * from API 33 and this app targets 36; androidx.core supplies the typed
     * form and is already a dependency.
     *
     * content: AND NOTHING ELSE. Neither the mimeType nor the scheme="content"
     * in this activity's intent-filter constrains what arrives here, which is
     * the trap: a filter is consulted only for IMPLICIT intents and only
     * against the intent's own data Uri, EXTRA_STREAM is an extra that no
     * filter ever looks at, and any app may aim an EXPLICIT intent at an
     * exported component and skip filter matching altogether.
     * ContentResolver.openInputStream() honours file: and android.resource: as
     * happily as content:, so an unchecked EXTRA_STREAM of
     * file:///data/data/com.awohl.cpmdroid/... would have this app open its OWN
     * private file with its OWN uid and copy it into Imports/, where R8 can
     * read it into the guest. That is a confused deputy, and the containment
     * checks downstream do not answer it: they prove where the bytes LAND, not
     * where they were read from. stageImportStream refuses the same thing at
     * the point of use; this layer keeps a refused Uri out of the count the
     * Toast reports.
     *
     * The nulls are filtered for a related reason. EXTRA_STREAM on a
     * SEND_MULTIPLE is an ArrayList another process built, so it can contain a
     * null however the type reads here, and an unguarded uri.scheme on it would
     * take the whole process - the running emulator with it - down with an NPE
     * from a share the user never asked for.
     */
    private fun incomingUris(intent: Intent): List<Uri> {
        val offered: List<Uri?> = when (intent.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))

            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?: emptyList()

            Intent.ACTION_VIEW ->
                listOfNotNull(intent.data)

            else -> emptyList()
        }

        return offered.mapNotNull { uri ->
            when {
                uri == null -> null
                uri.scheme == ContentResolver.SCHEME_CONTENT -> uri
                else -> {
                    // The scheme only. The Uri itself can name a private path
                    // in the sender or in us, and logcat is world-readable to
                    // anyone holding the device.
                    Log.w(TAG, "Refusing an incoming Uri with scheme ${uri.scheme}")
                    null
                }
            }
        }
    }
}
