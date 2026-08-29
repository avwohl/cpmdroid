package com.awohl.cpmdroid

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/*
 * The two folders the guest can reach, and the rules for the names inside them.
 *
 * This file exists so there is exactly one copy of each rule. The containment
 * test used to be inline in MainActivity.handleHostFileWrite() and was its only
 * caller; every new way into these folders - a save-as, a share, a picker, a
 * file pushed in from another app - would otherwise have grown its own
 * near-copy. ioscpm build 52 is what a near-copy costs: "W8 ANYFILE.TXT .."
 * resolved to the parent of Exports and deleted the user's entire disk library
 * while telling the guest the export had succeeded, because the layer that
 * reduced the name and the layer that checked containment each assumed the
 * other had done it (romwbw_emu docs/DOWNSTREAM_2026-08-25.md section 0).
 *
 * Nothing here relaxes the sandbox. The UI on top of it moves BYTES between
 * these two folders and places the user picks by hand; no path from a guest,
 * and no path from another app, is ever used as a destination, and no path or
 * Uri crosses the JNI boundary in either direction - which is what keeps
 * emu_host_path_caps() honest (see its comment in emu_io_android.cpp).
 */

private const val TAG = "HostTransfer"

/** Folder R8 reads from, under getExternalFilesDir(null). */
const val IMPORTS_DIR_NAME = "Imports"

/** Folder W8 writes to, under getExternalFilesDir(null). */
const val EXPORTS_DIR_NAME = "Exports"

/** Stem used when an incoming name reduces to something R8 could not name. */
private const val IMPORT_FALLBACK_STEM = "import"

/** Name for shared text that arrives with no file behind it. */
private const val SHARED_TEXT_NAME = "shared.txt"

/**
 * Imports/ or Exports/, or null when external storage is not available.
 *
 * getExternalFilesDir(null) really can return null - an unmounted or otherwise
 * unavailable volume - and the whole transfer tree hangs off it, so the null
 * has to stay visible instead of being papered over. There is deliberately no
 * fallback to filesDir: README documents these folders as the literal
 * Android/data/com.awohl.cpmdroid/files/Imports and .../Exports paths and tells
 * the user to stage files into Imports with a file manager, and /data/data is
 * unreachable by any file manager on an unrooted device. A silent redirect
 * there would turn a rare unavailable-storage condition into a permanent
 * invisible one: R8 answering "No file in Imports folder" forever about a
 * folder nobody can open, and W8 reporting success into the same. Callers
 * report the condition where they already report every other outcome.
 *
 * File(root, name) with a null root is the accident this replaces - it yields
 * a relative "Imports" in the process working directory.
 */
fun transferDir(context: Context, name: String): File? {
    // A transfer folder is one of two constants and never data. Insisting on
    // that here is what lets every caller pass a name around - the save-as
    // that survives process death remembers "Exports" in a Bundle, for
    // instance - without any of them having to prove the name is safe: a
    // string with a separator or a ".." in it can only ever get null back,
    // rather than mkdirs() somewhere outside the app's own storage.
    if (name != IMPORTS_DIR_NAME && name != EXPORTS_DIR_NAME) {
        Log.e(TAG, "Refusing a transfer folder that is not Imports or Exports: $name")
        return null
    }
    val root = context.getExternalFilesDir(null)
    if (root == null) {
        Log.w(TAG, "getExternalFilesDir(null) returned null - external storage unavailable")
        return null
    }
    val dir = File(root, name)
    if (!dir.mkdirs() && !dir.isDirectory) {
        Log.w(TAG, "Could not create $name at ${dir.absolutePath}")
        return null
    }
    return dir
}

/**
 * [name] resolved against [dir], or null unless the result is still inside it.
 *
 * This is the test that used to sit inline in handleHostFileWrite(), moved here
 * unchanged so every route into these folders runs the same one rather than a
 * second implementation that drifts. Both halves of the original are kept
 * deliberately: a name that already looks like a path is resolved as one rather
 * than joined - the native side hands W8's destination back as a full path, see
 * emu_host_file_open_write - and the folder itself passes as well as anything
 * under it.
 *
 * Because the folder itself passes, a caller that DESTROYS or OVERWRITES must
 * also demand isFile. resolveInsideDir(exports, "") answers with Exports, and
 * File.delete() on an empty Exports would take the folder with it.
 *
 * canonicalPath, not absolutePath: canonicalPath is what collapses ".." and
 * resolves symlinks, and ".." is the exact string that cost ioscpm a user's
 * disk library. Kotlin's File(dir, name) has the same traversal property as the
 * iOS appendingPathComponent that made that possible - it does not escape it.
 */
fun resolveInsideDir(dir: File, name: String): File? {
    val candidate = if (name.contains('/') || name.contains('\\')) {
        File(name)
    } else {
        File(dir, name)
    }
    val root = try {
        dir.canonicalPath
    } catch (e: Exception) {
        Log.e(TAG, "Cannot resolve ${dir.absolutePath}", e)
        return null
    }
    val resolved = try {
        candidate.canonicalPath
    } catch (e: Exception) {
        Log.e(TAG, "Cannot resolve $name", e)
        return null
    }
    if (resolved != root && !resolved.startsWith(root + File.separator)) {
        Log.w(TAG, "Refusing a path outside ${dir.name}: $resolved")
        return null
    }
    return candidate
}

// fcb_bad_chars from r8.asm, in its order: the CP/M command-line delimiter set,
// the two wildcards, and the two separators. Backslash and underscore are the
// last two entries there (spelled 5Ch and 5Fh so no assembler has to agree
// about quoting them).
private const val FCB_BAD_CHARS = "?*<>.,;:=[]|/\\_"

/**
 * One character of a host filename, mapped the way R8 maps it.
 *
 * Transcribed from fcb_char in romwbw_emu/src/r8.asm, and it is a BLACKLIST,
 * not the A-Z 0-9 whitelist it looks like: toupper, then reject anything at or
 * below space and anything at or above 7Fh (the high bits of an FCB name byte
 * are attribute flags rather than name), and then only the members of
 * fcb_bad_chars. Characters such as ! # $ % and ( ) + @ ^ ~ { } and - all
 * survive there, so they survive here; being harsher than R8 would rename files
 * R8 would have left alone.
 *
 * Two entries in that table are not guessable, which is why this is transcribed
 * rather than reinvented. '?' and '*' make an FCB ambiguous and R8 hands its
 * FCB to F_DELETE before F_MAKE, so importing a?b.txt used to erase every file
 * matching A?B.TXT first. And '_' is a CP/M 2.2 CCP filename delimiter, so
 * MY_FILE.TXT is an entry the CCP cannot name back - which is also why the
 * substitute character is '-' and not the obvious '_'.
 */
private fun fcbChar(c: Char): Char {
    val upper = if (c in 'a'..'z') c - 32 else c
    // r8.asm: cp ' '+1 / jr c, then cp 7Fh / jr nc.
    if (upper.code <= 0x20 || upper.code >= 0x7F) return '-'
    if (FCB_BAD_CHARS.indexOf(upper) >= 0) return '-'
    return upper
}

/**
 * The name an imported file gets in Imports/, mangled the way R8 mangles it.
 *
 * The point is a fixed point, not tidiness: run R8's path_to_fcb over the name
 * this returns and the same name comes back in the FCB, so what the Toast says
 * and what the user types at the CCP are one string. Unmangled, "my long
 * name.tar.gz" imports as an entry the CCP's own parser cannot address and W8
 * cannot export back out.
 *
 * The shape is path_to_fcb's, step for step: basename over both separators,
 * drop leading dots (a leading dot names a hidden file, so .profile is
 * PROFILE), the type comes from the LAST dot while the name stops at the FIRST
 * one (archive.tar.gz is ARCHIVE.GZ, not a file named ARCHIVE with type
 * "TAR.GZ"), 8 and 3 characters, each through fcbChar.
 *
 * Then lowercased, which is convention rather than recovery: the CCP has
 * uppercased the command tail long before R8 sees it, and MainActivity's lookup
 * falls back to a case-insensitive scan anyway - but android_host_leaf() in
 * emu_io_android.cpp lowercases what it asks for, so a lowercase file is the
 * one the exact-match probe finds first.
 *
 * Mangling to the NEW R8's rules also makes both R8 vintages agree, which
 * matters because the shipped disk images still carry the pre-98eb6a1 R8 that
 * has no fcb_char, no skip_dots and no find_last_dot. Unmangled,
 * archive.tar.gz is ARCHIVE.TAR on the old one and ARCHIVE.GZ on the new;
 * mangled to archive.gz it is ARCHIVE.GZ on both, because one dot and no spaces
 * is all either parser can disagree about.
 */
fun cpmImportLeafName(displayName: String?): String {
    // A DISPLAY_NAME is not required to be a leaf, and the Uri fallback at the
    // call sites below certainly is not.
    val base = (displayName ?: "")
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .trimStart('.')

    val lastDot = base.lastIndexOf('.')
    val stemSource = if (lastDot >= 0) base.substring(0, lastDot) else base
    val extSource = if (lastDot >= 0) base.substring(lastDot + 1) else ""

    val stem = buildString {
        for (c in stemSource.substringBefore('.').take(8)) append(fcbChar(c))
    }
    val ext = buildString {
        for (c in extSource.take(3)) append(fcbChar(c))
    }

    val name = if (stem.isEmpty()) IMPORT_FALLBACK_STEM else stem
    return (if (ext.isEmpty()) name else "$name.$ext").lowercase()
}

/**
 * What a file that just landed in Imports/ is called, or why it did not.
 *
 * [leaf] is null exactly when nothing was written. [replaced] says a file of
 * that name was already in the folder - see the collision note on
 * stageIntoImports - and it is set on the error results too, where it means the
 * older file is still there: a failed import cannot destroy one, because the
 * bytes are renamed onto the name rather than streamed into it.
 */
class ImportStaged(val leaf: String?, val replaced: Boolean, val error: String?)

/** DISPLAY_NAME for [uri], or null if the provider will not say. */
private fun displayNameOf(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && !cursor.isNull(column)) cursor.getString(column) else null
        }
    } catch (e: Exception) {
        // A provider can be gone, can revoke access, or can simply not
        // implement the column. The name is a nicety here; the copy is not.
        Log.w(TAG, "No display name for $uri", e)
        null
    }
}

/**
 * The most an import is allowed to write.
 *
 * 16 MB, against a CP/M 2.2 slice that holds 8 MB: the largest file the guest
 * could ever read is already smaller than this, so the cap cannot refuse a
 * transfer anybody wants, and it is not a guess about disk space. The reason it
 * exists is that ImportReceiverActivity is exported - any app on the device can
 * hand this one a stream, a SAF provider is free to be a network mount, and an
 * unbounded copyTo() from an untrusted sender is a way to fill the user's
 * storage with a file only R8 can name and only this screen can delete.
 */
private const val MAX_IMPORT_BYTES = 16L * 1024L * 1024L

/** Thrown by [copyBounded] when the source runs past [MAX_IMPORT_BYTES]. */
private class ImportTooLargeException : Exception()

/**
 * [InputStream.copyTo] with a ceiling.
 *
 * Counts as it goes rather than trusting a length reported up front: OpenableColumns.SIZE
 * is optional, a provider may answer -1 or lie, and the stream is what actually
 * arrives.
 */
private fun copyBounded(source: InputStream, sink: FileOutputStream) {
    val buffer = ByteArray(8192)
    var total = 0L
    while (true) {
        val read = source.read(buffer)
        if (read < 0) break
        total += read
        if (total > MAX_IMPORT_BYTES) throw ImportTooLargeException()
        sink.write(buffer, 0, read)
    }
}

/** Suffix the bytes land under while a copy is still in flight. */
private const val IMPORT_TEMP_SUFFIX = ".part"

/**
 * Write one staged file into Imports/ under [leaf], bytes supplied by [write].
 *
 * THE NAME IS CLAIMED LAST. The bytes go to a sibling <leaf>.part and are
 * renamed onto [leaf] only after [write] has returned. Streaming straight into
 * the final name - which is the obvious shape, and what this used to do -
 * publishes a TRUNCATED file under exactly the name R8 will be asked for: a
 * 20 MB pick from Drive that loses the network at 8 MB left 8 MB of it staged
 * as myfile.txt while the Toast said the import had failed, and R8 MYFILE.TXT
 * then imported that half file into CP/M and told the guest it had succeeded.
 * That is the same silent-substitution shape as the "otherwise take the first
 * file in Imports" fallback that handleHostFileRead() deliberately lost. The
 * window is not theoretical: ImportReceiverActivity is android:noHistory, so
 * the system finishing it mid-copy revokes the incoming Uri grant underneath
 * the read.
 *
 * The temp file is a SIBLING inside Imports/, and is proved by resolveInsideDir
 * like every other name here. cacheDir is the obvious alternative and is wrong:
 * it sits on internal storage while Imports/ is on external, and renameTo
 * across two mounts fails, which would put us straight back to copying into the
 * live name. FileOutputStream truncates whatever an earlier crash left in the
 * temp, so a stale .part costs a listing row and nothing else.
 *
 * COLLISIONS OVERWRITE, and the caller is told so it can say so. 8.3 makes
 * collisions ordinary rather than rare: "my long name.tar.gz" and "my longer
 * name.tar.gz" are both my-long-.gz. Overwrite is what ioscpm's
 * handleImportToInbox chose, and it keeps the leaf a pure function of the name
 * the user picked - a disambiguating suffix would have to evict one of the
 * eight characters they can see, and would leave near-duplicate staged copies
 * that R8 cannot tell apart. What is not acceptable is doing it silently, which
 * is why ImportStaged carries the flag.
 *
 * The overwrite is renameTo replacing the destination in one step, and the only
 * delete calls in the whole import path are on the temp. A staged file the user
 * is relying on therefore survives a failed import untouched - which is why
 * [ImportStaged.replaced] is carried through the error results too: on an error
 * it means "a file of that name is still there", not "it was destroyed".
 */
private fun stageIntoImports(
    context: Context,
    leaf: String,
    write: (FileOutputStream) -> Unit
): ImportStaged {
    val imports = transferDir(context, IMPORTS_DIR_NAME)
        ?: return ImportStaged(null, false, context.getString(R.string.transfer_storage_unavailable))

    val destination = resolveInsideDir(imports, leaf)
        ?: return ImportStaged(null, false, context.getString(R.string.transfer_refused_outside))
    if (destination.isDirectory) {
        return ImportStaged(null, false, context.getString(R.string.transfer_refused_outside))
    }
    val replaced = destination.isFile

    // Proved on its own rather than derived from `destination`: the rule in
    // this file is that a name is checked against the folder where it is used,
    // and appending IMPORT_TEMP_SUFFIX is still string concatenation.
    val temp = resolveInsideDir(imports, leaf + IMPORT_TEMP_SUFFIX)
        ?: return ImportStaged(null, false, context.getString(R.string.transfer_refused_outside))
    if (temp.isDirectory) {
        return ImportStaged(null, false, context.getString(R.string.transfer_refused_outside))
    }

    return try {
        FileOutputStream(temp).use { sink -> write(sink) }
        if (temp.renameTo(destination)) {
            Log.i(TAG, "Imported $leaf (${destination.length()} bytes)")
            ImportStaged(leaf, replaced, null)
        } else {
            // renameTo answers false instead of throwing. The temp goes even
            // so: a leftover half-import is a file R8 can be asked for under a
            // name no Toast ever mentioned.
            temp.delete()
            Log.e(TAG, "Could not rename ${temp.name} onto $leaf")
            ImportStaged(null, replaced, context.getString(R.string.transfer_import_failed))
        }
    } catch (e: ImportTooLargeException) {
        temp.delete()
        Log.w(TAG, "Refused an import over $MAX_IMPORT_BYTES bytes: $leaf")
        ImportStaged(null, replaced, context.getString(R.string.transfer_import_too_large))
    } catch (e: Exception) {
        temp.delete()
        Log.e(TAG, "Import copy failed for $leaf", e)
        ImportStaged(null, replaced, context.getString(R.string.transfer_import_failed))
    }
}

/**
 * Copy the document at [uri] into Imports/ under a CP/M-legal leaf.
 *
 * Blocks - callers run it off the main thread, because a SAF provider can be a
 * cloud one and the read is then over the network. stageIntoImports owns what
 * happens to the destination name while that read is in flight, and owns the
 * collision rule.
 */
fun stageImportStream(context: Context, uri: Uri): ImportStaged {
    // content: and nothing else, checked HERE because this is the one place a
    // Uri from outside the app is dereferenced - the picker result and the
    // share target's EXTRA_STREAM both end up on the openInputStream below.
    //
    // ContentResolver.openInputStream() also honours file: (it just opens a
    // FileInputStream on the path) and android.resource:. ImportReceiverActivity
    // is exported, and the manifest's <data android:scheme="content"/> does NOT
    // constrain this: an intent-filter matches only IMPLICIT intents and only on
    // the intent's own data Uri, while EXTRA_STREAM is an extra that no filter
    // looks at, and any app may send an EXPLICIT intent to an exported component
    // and bypass filter matching entirely. So a hostile caller could name
    // file:///data/data/com.awohl.cpmdroid/... and have this app open its OWN
    // private file with its own uid and copy it into Imports/ - a confused
    // deputy, lifting a file the guest can then read with R8.
    //
    // Not "senders cannot make a file: Uri": FileUriExposedException is a
    // StrictMode check on the SENDER, which a sender can disable with a lax
    // VmPolicy, target away from, or sidestep by setting ClipData so that
    // migrateExtraStreamToClipData() never promotes the extra into the slot
    // that is checked. ImportReceiverActivity refuses these as well, one layer
    // out; this is the check at the point of use, which is the one that counts.
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
        Log.w(TAG, "Refusing a Uri whose scheme is not content: ${uri.scheme}")
        return ImportStaged(null, false, context.getString(R.string.transfer_refused_scheme))
    }

    // lastPathSegment only as a fallback: it is a provider-internal document id
    // as often as it is a name, and cpmImportLeafName has to survive either.
    val leaf = cpmImportLeafName(displayNameOf(context, uri) ?: uri.lastPathSegment)

    val input: InputStream? = try {
        context.contentResolver.openInputStream(uri)
    } catch (e: Exception) {
        // SecurityException when the grant has already been revoked,
        // FileNotFoundException when the document is gone, and providers throw
        // their own things besides. It can also answer null without throwing.
        Log.e(TAG, "Cannot open $uri", e)
        null
    }
    if (input == null) {
        return ImportStaged(null, false, context.getString(R.string.transfer_import_unreadable))
    }

    return input.use { source ->
        stageIntoImports(context, leaf) { sink -> copyBounded(source, sink) }
    }
}

/**
 * Stage [bytes] into Imports/ under a CP/M-legal leaf derived from [name].
 *
 * ACTION_SEND with text/plain is a shared snippet as often as it is a file, and
 * it arrives as EXTRA_TEXT with no EXTRA_STREAM at all. Writing it out is more
 * use to a CP/M box than refusing it, and the alternative - dereferencing a
 * null EXTRA_STREAM - is a crash in a receiver the user cannot even see.
 */
fun stageImportBytes(context: Context, name: String?, bytes: ByteArray): ImportStaged {
    val leaf = cpmImportLeafName(if (name.isNullOrBlank()) SHARED_TEXT_NAME else name)
    return stageIntoImports(context, leaf) { sink ->
        // The same ceiling as the stream path, tested before the write instead
        // of counted during it: EXTRA_TEXT arrives whole in a Binder
        // transaction, so the size is already known here. Thrown rather than
        // returned so that both routes report "too large" from one place.
        if (bytes.size > MAX_IMPORT_BYTES) throw ImportTooLargeException()
        Log.i(TAG, "Staging ${bytes.size} bytes of shared text as $leaf")
        sink.write(bytes)
    }
}

/**
 * One line saying what just landed in Imports/, for the two places that stage
 * files: the picker in FileTransferActivity and the share target in
 * ImportReceiverActivity.
 *
 * It names the leaf because the user cannot use the file without typing that
 * name at the CCP, and the name they picked is usually not the name they got.
 * It says "Replaced" out loud when 8.3 collapsed the new name onto a staged
 * one - a silent clobber here would be the same shape of surprise as the
 * "otherwise take the first file in Imports" fallback that was deliberately
 * removed from handleHostFileRead().
 */
fun importResultMessage(context: Context, results: List<ImportStaged>): String {
    val names = results.mapNotNull { it.leaf }
    if (names.isEmpty()) {
        return results.firstOrNull()?.error
            ?: context.getString(R.string.transfer_import_failed)
    }
    if (results.size == 1) {
        val only = results[0]
        return if (only.replaced) {
            context.getString(R.string.transfer_import_replaced, names[0])
        } else {
            context.getString(R.string.transfer_import_done, names[0])
        }
    }
    // Counts, so a partial failure in a multi-select is visible rather than
    // being reported as the successes alone.
    return context.getString(
        R.string.transfer_import_done_many,
        names.size, results.size, names.joinToString(", ")
    )
}

/**
 * A MIME type for [name], for the save-as dialog and the share sheet.
 *
 * Keyed off the extension of a file already sitting in one of the two folders,
 * never off anything a guest said. A fixed application/octet-stream would make
 * ACTION_CREATE_DOCUMENT offer to save MYFILE.TXT as a .bin; an honest generic
 * type for the many CP/M files with no registered extension (.COM, .REL, .LBR)
 * beats a sniffed wrong one.
 */
fun transferMimeType(name: String): String {
    val extension = name.substringAfterLast('.', "").lowercase()
    if (extension.isEmpty()) return "application/octet-stream"
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        ?: "application/octet-stream"
}
