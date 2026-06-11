package com.repository.listener.ui

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.repository.listener.R
import java.util.concurrent.Executors

sealed class CatalogueItem {
    data class Header(val title: String, val count: Int) : CatalogueItem()
    data class MediaFile(
        val uri: Uri,
        val name: String,
        val mimeType: String,
        val size: Long,
        val dateModified: Long = 0
    ) : CatalogueItem()
}

class FileCatalogueAdapter(
    private val contentResolver: ContentResolver,
    private val onItemClick: (CatalogueItem.MediaFile) -> Unit = {}
) : ListAdapter<CatalogueItem, RecyclerView.ViewHolder>(DiffCallback) {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_MEDIA = 1
        private const val TYPE_AUDIO = 2
        private const val TYPE_OTHER = 3

        private val THUMBNAIL_SIZE = Size(200, 200)
        private const val CACHE_MAX_BYTES = 20 * 1024 * 1024 // 20MB
    }

    private val executor = Executors.newFixedThreadPool(4)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val thumbnailCache = object : LruCache<Uri, Bitmap>(CACHE_MAX_BYTES) {
        override fun sizeOf(key: Uri, value: Bitmap): Int = value.byteCount
    }

    private var fullList: MutableList<CatalogueItem> = mutableListOf()

    /**
     * Thumbnail loader that supports both content:// (MediaStore) and file://
     * (app-private) URIs. ContentResolver.loadThumbnail throws for file URIs
     * so we have to read the file directly for the private-dir gallery.
     */
    private fun loadThumbnailCompat(uri: Uri, mimeType: String): Bitmap? {
        // content:// URIs get the MediaStore fast path.
        if (uri.scheme == "content") {
            return try { contentResolver.loadThumbnail(uri, THUMBNAIL_SIZE, null) } catch (_: Exception) { null }
        }
        // file:// URIs -- decode from disk.
        val path = uri.path ?: return null
        val targetPx = THUMBNAIL_SIZE.width
        return when {
            mimeType.startsWith("image/") -> {
                // Inspect dimensions first, pick sample size so decoded bitmap is
                // <=2x target, then final decode.
                val opts = android.graphics.BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                android.graphics.BitmapFactory.decodeFile(path, opts)
                val long = maxOf(opts.outWidth, opts.outHeight)
                var sample = 1
                while (long / sample > targetPx * 2) sample *= 2
                val real = android.graphics.BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                android.graphics.BitmapFactory.decodeFile(path, real)
            }
            mimeType.startsWith("video/") -> {
                val mmr = android.media.MediaMetadataRetriever()
                try {
                    mmr.setDataSource(path)
                    // Middle-ish frame -- avoids all-black opening frames common in
                    // glasses-recorded MP4s.
                    val frame = mmr.getFrameAtTime(1_000_000L)
                    frame
                } catch (_: Exception) {
                    null
                } finally {
                    try { mmr.release() } catch (_: Exception) {}
                }
            }
            else -> null
        }
    }

    // Selection state
    private val selectedUris = mutableSetOf<Uri>()
    var inSelectionMode = false
        private set
    var onSelectionModeChanged: ((Boolean) -> Unit)? = null
    var onSelectionChanged: ((Int) -> Unit)? = null

    object DiffCallback : DiffUtil.ItemCallback<CatalogueItem>() {
        override fun areItemsTheSame(a: CatalogueItem, b: CatalogueItem): Boolean = when {
            a is CatalogueItem.Header && b is CatalogueItem.Header -> a.title == b.title
            a is CatalogueItem.MediaFile && b is CatalogueItem.MediaFile -> a.uri == b.uri
            else -> false
        }

        override fun areContentsTheSame(a: CatalogueItem, b: CatalogueItem): Boolean = a == b
    }

    override fun getItemViewType(position: Int): Int = when (val item = getItem(position)) {
        is CatalogueItem.Header -> TYPE_HEADER
        is CatalogueItem.MediaFile -> {
            val mime = item.mimeType
            when {
                mime.startsWith("audio/") -> TYPE_AUDIO
                mime.startsWith("image/") || mime.startsWith("video/") -> TYPE_MEDIA
                else -> TYPE_OTHER
            }
        }
    }

    fun getSpanSize(position: Int): Int = when (getItemViewType(position)) {
        TYPE_HEADER, TYPE_AUDIO, TYPE_OTHER -> 3
        else -> 1
    }

    fun submitFullList(items: List<CatalogueItem>) {
        fullList = items.toMutableList()
        submitList(fullList.toList())
    }

    // --- Selection API ---

    fun enterSelectionMode() {
        if (inSelectionMode) return
        inSelectionMode = true
        onSelectionModeChanged?.invoke(true)
        notifyDataSetChanged()
    }

    fun exitSelectionMode() {
        if (!inSelectionMode) return
        inSelectionMode = false
        selectedUris.clear()
        onSelectionModeChanged?.invoke(false)
        notifyDataSetChanged()
    }

    fun toggleSelection(uri: Uri) {
        if (uri in selectedUris) {
            selectedUris.remove(uri)
        } else {
            selectedUris.add(uri)
        }
        // Exit selection mode if nothing selected
        if (selectedUris.isEmpty()) {
            exitSelectionMode()
            return
        }
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedUris.size)
    }

    fun selectAllInCategory(title: String) {
        val categoryFiles = mutableListOf<CatalogueItem.MediaFile>()
        var inCategory = false
        for (item in fullList) {
            when (item) {
                is CatalogueItem.Header -> {
                    if (inCategory) break
                    inCategory = item.title == title
                }
                is CatalogueItem.MediaFile -> {
                    if (inCategory) categoryFiles.add(item)
                }
            }
        }

        val allSelected = categoryFiles.all { it.uri in selectedUris }
        if (allSelected) {
            categoryFiles.forEach { selectedUris.remove(it.uri) }
        } else {
            categoryFiles.forEach { selectedUris.add(it.uri) }
        }

        if (selectedUris.isEmpty()) {
            exitSelectionMode()
            return
        }
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedUris.size)
    }

    fun getSelectedFiles(): List<CatalogueItem.MediaFile> {
        return fullList.filterIsInstance<CatalogueItem.MediaFile>()
            .filter { it.uri in selectedUris }
    }

    fun removeItems(uris: Set<Uri>) {
        selectedUris.removeAll(uris)
        fullList = fullList.filter { item ->
            when (item) {
                is CatalogueItem.MediaFile -> item.uri !in uris
                is CatalogueItem.Header -> true
            }
        }.toMutableList()

        // Recalculate header counts, remove empty headers
        val updated = mutableListOf<CatalogueItem>()
        var i = 0
        while (i < fullList.size) {
            val item = fullList[i]
            if (item is CatalogueItem.Header) {
                var count = 0
                var j = i + 1
                while (j < fullList.size && fullList[j] is CatalogueItem.MediaFile) {
                    count++
                    j++
                }
                if (count > 0) {
                    updated.add(CatalogueItem.Header(item.title, count))
                    for (k in (i + 1) until j) {
                        updated.add(fullList[k])
                    }
                }
                i = j
            } else {
                updated.add(item)
                i++
            }
        }
        fullList = updated

        submitList(fullList.toList())
        onSelectionChanged?.invoke(selectedUris.size)
    }

    private fun isAllInCategorySelected(title: String): Boolean {
        var inCategory = false
        for (item in fullList) {
            when (item) {
                is CatalogueItem.Header -> {
                    if (inCategory) return true
                    inCategory = item.title == title
                }
                is CatalogueItem.MediaFile -> {
                    if (inCategory && item.uri !in selectedUris) return false
                }
            }
        }
        return inCategory
    }

    // --- ViewHolder creation ---

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderViewHolder(
                inflater.inflate(R.layout.item_catalogue_header, parent, false)
            )
            TYPE_AUDIO -> AudioViewHolder(
                inflater.inflate(R.layout.item_catalogue_audio, parent, false)
            )
            TYPE_OTHER -> OtherViewHolder(
                inflater.inflate(R.layout.item_catalogue_other, parent, false)
            )
            else -> MediaViewHolder(
                inflater.inflate(R.layout.item_catalogue_media, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is CatalogueItem.Header -> (holder as HeaderViewHolder).bind(item)
            is CatalogueItem.MediaFile -> {
                when (holder) {
                    is AudioViewHolder -> holder.bind(item)
                    is OtherViewHolder -> holder.bind(item)
                    is MediaViewHolder -> holder.bind(item)
                }
            }
        }
    }

    inner class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val headerRow: View = view.findViewById(R.id.headerRow)
        private val txtHeader: TextView = view.findViewById(R.id.txtHeader)
        private val imgSelectAll: ImageView = view.findViewById(R.id.imgSelectAll)

        fun bind(item: CatalogueItem.Header) {
            txtHeader.text = item.title

            if (inSelectionMode) {
                imgSelectAll.visibility = View.VISIBLE
                val allSelected = isAllInCategorySelected(item.title)
                imgSelectAll.setImageResource(
                    if (allSelected) R.drawable.ic_check_circle else R.drawable.ic_circle_outline
                )
                imgSelectAll.setOnClickListener {
                    selectAllInCategory(item.title)
                }
                headerRow.setOnClickListener {
                    selectAllInCategory(item.title)
                }
            } else {
                imgSelectAll.visibility = View.GONE
                imgSelectAll.setOnClickListener(null)
                headerRow.setOnClickListener(null)
                headerRow.isClickable = false
            }
        }
    }

    inner class MediaViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val imgThumbnail: ImageView = view.findViewById(R.id.imgThumbnail)
        private val imgPlayOverlay: ImageView = view.findViewById(R.id.imgPlayOverlay)
        private val selectionOverlay: View = view.findViewById(R.id.selectionOverlay)
        private val imgCheckbox: ImageView = view.findViewById(R.id.imgCheckbox)

        fun bind(item: CatalogueItem.MediaFile) {
            imgPlayOverlay.visibility =
                if (item.mimeType.startsWith("video/")) View.VISIBLE else View.GONE

            // Selection state
            val selected = item.uri in selectedUris
            if (inSelectionMode) {
                imgCheckbox.visibility = View.VISIBLE
                selectionOverlay.visibility = if (selected) View.VISIBLE else View.GONE
                imgCheckbox.setImageResource(
                    if (selected) R.drawable.ic_check_circle else R.drawable.ic_circle_outline
                )
                itemView.setOnClickListener { toggleSelection(item.uri) }
            } else {
                imgCheckbox.visibility = View.GONE
                selectionOverlay.visibility = View.GONE
                itemView.setOnClickListener { onItemClick(item) }
            }

            itemView.setOnLongClickListener {
                if (!inSelectionMode) {
                    enterSelectionMode()
                    toggleSelection(item.uri)
                }
                true
            }

            imgThumbnail.tag = item.uri
            val cached = thumbnailCache.get(item.uri)
            if (cached != null) {
                imgThumbnail.setImageBitmap(cached)
            } else {
                imgThumbnail.setImageDrawable(null)
                val uri = item.uri
                val mime = item.mimeType
                executor.execute {
                    try {
                        val bitmap = loadThumbnailCompat(uri, mime)
                        if (bitmap != null) {
                            thumbnailCache.put(uri, bitmap)
                            mainHandler.post {
                                if (imgThumbnail.tag == uri) {
                                    imgThumbnail.setImageBitmap(bitmap)
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // Thumbnail unavailable
                    }
                }
            }
        }
    }

    inner class AudioViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val txtAudioName: TextView = view.findViewById(R.id.txtAudioName)
        private val txtAudioSize: TextView = view.findViewById(R.id.txtAudioSize)
        private val imgCheckbox: ImageView = view.findViewById(R.id.imgCheckbox)

        fun bind(item: CatalogueItem.MediaFile) {
            txtAudioName.text = item.name
            txtAudioSize.text = formatSize(item.size)

            val selected = item.uri in selectedUris
            if (inSelectionMode) {
                imgCheckbox.visibility = View.VISIBLE
                imgCheckbox.setImageResource(
                    if (selected) R.drawable.ic_check_circle else R.drawable.ic_circle_outline
                )
                itemView.setOnClickListener { toggleSelection(item.uri) }
            } else {
                imgCheckbox.visibility = View.GONE
                itemView.setOnClickListener { onItemClick(item) }
            }

            itemView.setOnLongClickListener {
                if (!inSelectionMode) {
                    enterSelectionMode()
                    toggleSelection(item.uri)
                }
                true
            }
        }
    }

    inner class OtherViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val txtOtherName: TextView = view.findViewById(R.id.txtOtherName)
        private val txtOtherSize: TextView = view.findViewById(R.id.txtOtherSize)
        private val imgCheckbox: ImageView = view.findViewById(R.id.imgCheckbox)

        fun bind(item: CatalogueItem.MediaFile) {
            txtOtherName.text = item.name
            txtOtherSize.text = formatSize(item.size)

            val selected = item.uri in selectedUris
            if (inSelectionMode) {
                imgCheckbox.visibility = View.VISIBLE
                imgCheckbox.setImageResource(
                    if (selected) R.drawable.ic_check_circle else R.drawable.ic_circle_outline
                )
                itemView.setOnClickListener { toggleSelection(item.uri) }
            } else {
                imgCheckbox.visibility = View.GONE
                itemView.setOnClickListener { onItemClick(item) }
            }

            itemView.setOnLongClickListener {
                if (!inSelectionMode) {
                    enterSelectionMode()
                    toggleSelection(item.uri)
                }
                true
            }
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }

    fun shutdown() {
        executor.shutdown()
    }
}
