package com.romwbw.cpmdroid

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.romwbw.cpmdroid.data.DiskInfo

class DiskCatalogAdapter(
    private val disks: List<DiskInfo>,
    private val downloadedDisks: Set<String>,
    private val onDiskClick: (DiskInfo) -> Unit
) : RecyclerView.Adapter<DiskCatalogAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val diskName: TextView = view.findViewById(R.id.diskName)
        val diskDescription: TextView = view.findViewById(R.id.diskDescription)
        val diskSize: TextView = view.findViewById(R.id.diskSize)
        val diskLicense: TextView = view.findViewById(R.id.diskLicense)
        val downloadedIcon: ImageView = view.findViewById(R.id.downloadedIcon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_disk_catalog, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val disk = disks[position]
        val isDownloaded = downloadedDisks.contains(disk.filename)

        holder.diskName.text = disk.name
        holder.diskDescription.text = disk.description
        holder.diskSize.text = formatSize(disk.size)
        holder.diskLicense.text = disk.license
        holder.downloadedIcon.visibility = if (isDownloaded) View.VISIBLE else View.GONE

        holder.itemView.setOnClickListener {
            onDiskClick(disk)
        }
    }

    override fun getItemCount(): Int = disks.size

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000.0)
            bytes >= 1_000 -> String.format("%.1f KB", bytes / 1_000.0)
            else -> "$bytes B"
        }
    }
}
