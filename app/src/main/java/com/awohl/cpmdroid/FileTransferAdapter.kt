package com.awohl.cpmdroid

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.awohl.cpmdroid.data.formatDiskSize
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One line of the File Transfer list: a section header, a file, or a note.
 *
 * [dir] is carried beside [file] rather than being read back out of
 * file.parentFile, because it is the folder the row is CLAIMED to be in and
 * every action re-proves the file against it with resolveInsideDir before
 * touching anything. Deriving the folder from the file would make that check
 * ask the file whether the file is where it says it is.
 */
class TransferRow(
    val header: String? = null,
    val note: String? = null,
    val dir: File? = null,
    val file: File? = null
)

/**
 * The Imports and Exports listing.
 *
 * Built like DiskCatalogAdapter - a plain RecyclerView.Adapter over an
 * immutable list, rebuilt rather than mutated when the folders change. These
 * lists are a handful of rows; nothing here needs DiffUtil.
 */
class FileTransferAdapter(
    private val rows: List<TransferRow>,
    private val onFileClick: (TransferRow) -> Unit
) : RecyclerView.Adapter<FileTransferAdapter.ViewHolder>() {

    companion object {
        // Not const: a toInt() call is not a compile-time constant expression.
        private val NAME_COLOR = 0xFF00FF00.toInt()
        private val NOTE_COLOR = 0xFF888888.toInt()
    }

    // Fixed pattern and Locale.US: this is a filesystem timestamp shown next
    // to a filename, not prose, and it sorts and reads the same everywhere.
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val sectionHeader: TextView = view.findViewById(R.id.sectionHeader)
        val fileRow: View = view.findViewById(R.id.fileRow)
        val fileName: TextView = view.findViewById(R.id.fileName)
        val fileMeta: TextView = view.findViewById(R.id.fileMeta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_file_transfer, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = rows[position]
        val context = holder.itemView.context

        if (row.header != null) {
            holder.sectionHeader.visibility = View.VISIBLE
            holder.sectionHeader.text = row.header
        } else {
            holder.sectionHeader.visibility = View.GONE
        }

        // Every branch sets visibility, text, colour and the listener, because
        // views are recycled: a row that only sets what it needs inherits the
        // rest from whichever row scrolled off, and an "Empty." note would
        // arrive still clickable.
        val file = row.file
        when {
            file != null -> {
                holder.fileRow.visibility = View.VISIBLE
                holder.fileName.text = file.name
                holder.fileName.setTextColor(NAME_COLOR)
                holder.fileMeta.visibility = View.VISIBLE
                holder.fileMeta.text = context.getString(
                    R.string.transfer_file_meta,
                    formatDiskSize(file.length()),
                    stamp.format(Date(file.lastModified()))
                )
                holder.fileRow.isClickable = true
                holder.fileRow.setOnClickListener { onFileClick(row) }
            }
            row.note != null -> {
                holder.fileRow.visibility = View.VISIBLE
                holder.fileName.text = row.note
                holder.fileName.setTextColor(NOTE_COLOR)
                holder.fileMeta.visibility = View.GONE
                holder.fileRow.setOnClickListener(null)
                holder.fileRow.isClickable = false
            }
            else -> {
                holder.fileRow.visibility = View.GONE
                holder.fileRow.setOnClickListener(null)
                holder.fileRow.isClickable = false
            }
        }
    }

    override fun getItemCount(): Int = rows.size
}
