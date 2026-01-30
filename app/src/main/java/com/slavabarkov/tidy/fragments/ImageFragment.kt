/**
 * Copyright 2023 Viacheslav Barkov
 */

package com.slavabarkov.tidy.fragments

import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.GridLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.bumptech.glide.Glide
import com.github.chrisbanes.photoview.PhotoView
import com.slavabarkov.tidy.R
import com.slavabarkov.tidy.viewmodels.ORTImageViewModel
import com.slavabarkov.tidy.viewmodels.SearchViewModel
import java.text.DateFormat


class ImageFragment : Fragment() {
    private var imageUri: Uri? = null
    private var imageId: Long? = null
    private val mORTImageViewModel: ORTImageViewModel by activityViewModels()
    private val mSearchViewModel: SearchViewModel by activityViewModels()

    private var dateTextView: TextView? = null
    private var infoTextView: TextView? = null
    private var singleImageView: PhotoView? = null
    private var xmpTextView: TextView? = null
    private var buttonExif: Button? = null
    private var exifGrid: GridLayout? = null
    private var showExif: Boolean = false
    private var basicInfoText: String = ""
    private var exifHeaderText: String? = null
    private var exifGridItems: List<Pair<String, String>>? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        val view = inflater.inflate(R.layout.fragment_image, container, false)
        val bundle = this.arguments
        bundle?.let {
            imageId = it.getLong("image_id")
            imageUri = it.getString("image_uri")?.toUri()
        }

        showExif = savedInstanceState?.getBoolean("show_exif") ?: false

        dateTextView = view.findViewById(R.id.dateTextView)
        infoTextView = view.findViewById(R.id.imageInfoTextView)
        singleImageView = view.findViewById(R.id.singeImageView)
        xmpTextView = view.findViewById(R.id.xmpTextView)
        buttonExif = view.findViewById(R.id.buttonExif)
        exifGrid = view.findViewById(R.id.imageExifGrid)
        singleImageView?.apply {
            // Explicitly enable and configure pinch-to-zoom.
            setZoomable(true)
            minimumScale = 1.0f
            mediumScale = 2.0f
            maximumScale = 5.0f
        }

        // Don't override PhotoView's touch listener (it breaks pinch-to-zoom on some devices).
        // Instead, hook into PhotoView's built-in fling callback for left/right navigation.
        singleImageView?.setOnSingleFlingListener { e1, e2, velocityX, _ ->
            val photoView = singleImageView ?: return@setOnSingleFlingListener false
            // Only navigate when not zoomed in (otherwise swipes should pan the image).
            if (photoView.scale > 1.05f) return@setOnSingleFlingListener false

            val dx = e2.x - e1.x
            val dy = e2.y - e1.y
            if (kotlin.math.abs(dx) < kotlin.math.abs(dy)) return@setOnSingleFlingListener false
            if (kotlin.math.abs(dx) < 120) return@setOnSingleFlingListener false
            if (kotlin.math.abs(velocityX) < 400) return@setOnSingleFlingListener false

            if (dx < 0) showAdjacentImage(+1) else showAdjacentImage(-1)
            true
        }

        // Initial render
        imageId?.let { showImageById(it) }

        val buttonBackToAllImages: Button = view.findViewById(R.id.buttonBackToAllImages)
        buttonBackToAllImages.setOnClickListener {
            // Keep the current search/results state (text search, near-duplicates, image-to-image, etc.)
            // and simply go back to the grid.
            parentFragmentManager.popBackStack()
        }

        buttonExif?.setOnClickListener {
            showExif = !showExif
            updateInfoText()
        }

        val buttonImage2Image: Button = view.findViewById(R.id.buttonImage2Image)
        buttonImage2Image.setOnClickListener {
            imageId?.let {
                val imageIndex = mORTImageViewModel.idxList.indexOf(it)
                if (imageIndex < 0) {
                    Toast.makeText(requireContext(), "Image is not indexed", Toast.LENGTH_SHORT)
                        .show()
                    return@setOnClickListener
                }
                val imageEmbedding = mORTImageViewModel.embeddingsList[imageIndex]
                mSearchViewModel.sortByCosineDistance(
                    imageEmbedding,
                    mORTImageViewModel.embeddingsList,
                    mORTImageViewModel.idxList,
                    minSimilarity = mSearchViewModel.getImageSimilarityThreshold(),
                    isImageSearch = true
                )
            }
            mSearchViewModel.showBackToAllImages = true
            mSearchViewModel.lastResultsAreNearDuplicates = false
            mSearchViewModel.fromImg2ImgFlag = true
            parentFragmentManager.popBackStack()
        }

        val buttonShare: Button = view.findViewById(R.id.buttonShare)
        buttonShare.setOnClickListener {
            val sendIntent: Intent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_STREAM, imageUri)
                type = "image/*"
            }
            val shareIntent = Intent.createChooser(sendIntent, null)
            startActivity(shareIntent)
        }
        return view
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("show_exif", showExif)
    }

    private fun showAdjacentImage(delta: Int) {
        val currentId = imageId ?: return
        val list = mSearchViewModel.searchResults ?: mORTImageViewModel.idxList.reversed()
        val idx = list.indexOf(currentId)
        if (idx < 0) return
        val nextIdx = idx + delta
        if (nextIdx !in list.indices) {
            Toast.makeText(requireContext(), "No more images", Toast.LENGTH_SHORT).show()
            return
        }
        showImageById(list[nextIdx])
    }

    private fun showImageById(id: Long) {
        imageId = id
        imageUri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
        exifHeaderText = null
        exifGridItems = null

        val ctx = context ?: return
        val cursor: Cursor? = ctx.contentResolver.query(imageUri!!, null, null, null, null)
        if (cursor == null || !cursor.moveToFirst()) {
            cursor?.close()
            return
        }

        val dateIdx: Int = cursor.getColumnIndex(MediaStore.Images.ImageColumns.DATE_MODIFIED)
        val date: Long =
            if (dateIdx >= 0) cursor.getLong(dateIdx) * 1000 else System.currentTimeMillis()

        val displayNameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
        val displayName: String? =
            if (displayNameIdx >= 0) cursor.getString(displayNameIdx) else null

        val relativePathIdx = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
        val relativePath: String? =
            if (relativePathIdx >= 0) cursor.getString(relativePathIdx) else null

        val dataPathIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
        val dataPath: String? = if (dataPathIdx >= 0) cursor.getString(dataPathIdx) else null

        val sizeIdx = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
        val sizeBytes: Long? = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else null

        val widthIdx = cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH)
        val heightIdx = cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
        val width: Int? = if (widthIdx >= 0) cursor.getInt(widthIdx) else null
        val height: Int? = if (heightIdx >= 0) cursor.getInt(heightIdx) else null

        cursor.close()

        dateTextView?.text = DateFormat.getDateInstance().format(date)
        singleImageView?.let { Glide.with(it).load(imageUri).into(it) }
        updateXmpHeader(ctx, imageUri!!)

        val location = when {
            !relativePath.isNullOrBlank() && !displayName.isNullOrBlank() -> relativePath + displayName
            !relativePath.isNullOrBlank() -> relativePath
            !dataPath.isNullOrBlank() -> dataPath
            else -> imageUri.toString()
        }
        val dimensions = if (width != null && height != null && width > 0 && height > 0) {
            "${width}×${height}px"
        } else {
            "Unknown"
        }
        val sizeText = sizeBytes?.let { formatBytes(it) } ?: "Unknown"

        basicInfoText = "Location: $location\nSize: $sizeText\nDimensions: $dimensions"
        updateInfoText()
    }

    private fun updateXmpHeader(ctx: android.content.Context, uri: Uri) {
        val tv = xmpTextView ?: return
        val summary = loadXmpSummary(ctx, uri)
        if (summary.isNullOrBlank()) {
            tv.text = ""
            tv.visibility = View.GONE
        } else {
            tv.text = summary
            tv.visibility = View.VISIBLE
        }
    }

    private fun loadXmpSummary(ctx: android.content.Context, uri: Uri): String? {
        return try {
            val exif = ctx.contentResolver.openInputStream(uri)?.use { input ->
                ExifInterface(input)
            } ?: return null

            val xmp = exif.getAttribute(ExifInterface.TAG_XMP)?.trim()?.takeIf { it.isNotEmpty() }
                ?: return null

            // Extract a few common XMP/IPTC fields (best-effort, string-based parsing).
            fun extractFirstLi(tag: String): String? {
                val start = xmp.indexOf("<$tag")
                if (start < 0) return null
                val end = xmp.indexOf("</$tag>", start)
                if (end < 0) return null
                val block = xmp.substring(start, end)
                val liStart = block.indexOf("<rdf:li")
                if (liStart < 0) return null
                val liClose = block.indexOf('>', liStart)
                if (liClose < 0) return null
                val liEnd = block.indexOf("</rdf:li>", liClose)
                if (liEnd < 0) return null
                return decodeXml(block.substring(liClose + 1, liEnd).trim())
            }

            fun extractAllLi(tag: String, limit: Int = 3): List<String> {
                val start = xmp.indexOf("<$tag")
                if (start < 0) return emptyList()
                val end = xmp.indexOf("</$tag>", start)
                if (end < 0) return emptyList()
                val block = xmp.substring(start, end)
                val results = ArrayList<String>(limit)
                var idx = 0
                while (results.size < limit) {
                    val liStart = block.indexOf("<rdf:li", idx)
                    if (liStart < 0) break
                    val liClose = block.indexOf('>', liStart)
                    if (liClose < 0) break
                    val liEnd = block.indexOf("</rdf:li>", liClose)
                    if (liEnd < 0) break
                    val value = decodeXml(block.substring(liClose + 1, liEnd).trim())
                    if (value.isNotEmpty()) results.add(value)
                    idx = liEnd + 9
                }
                return results
            }

            val title = extractFirstLi("dc:title")
            val description = extractFirstLi("dc:description")
            val creator = extractAllLi("dc:creator", limit = 1).firstOrNull()
            val keywords = extractAllLi("dc:subject", limit = 3)

            val lines = ArrayList<String>(3)
            if (!title.isNullOrBlank()) lines.add(title)
            if (!description.isNullOrBlank() && description != title) lines.add(description)
            if (!creator.isNullOrBlank() && lines.size < 2) lines.add("By: $creator")

            if (lines.isEmpty() && keywords.isNotEmpty()) {
                lines.add("Keywords: " + keywords.joinToString(", "))
            }

            if (lines.isEmpty()) {
                // Fallback: show that XMP exists without dumping the full packet.
                "Metadata available"
            } else {
                lines.joinToString(" • ")
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeXml(s: String): String {
        return s
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
    }

    private fun updateInfoText() {
        if (!showExif) {
            exifGrid?.visibility = View.GONE
            infoTextView?.text = basicInfoText
            return
        }

        val uri = imageUri
        val ctx = context
        if (uri == null || ctx == null) {
            exifGrid?.visibility = View.GONE
            infoTextView?.text = "No EXIF data"
            return
        }

        if (exifHeaderText == null || exifGridItems == null) {
            val (header, items) = loadExifDisplay(ctx, uri)
            exifHeaderText = header
            exifGridItems = items
        }
        val header = exifHeaderText ?: "No EXIF data"
        val items = exifGridItems ?: emptyList()

        infoTextView?.text = header
        renderExifGrid(items)
    }

    private fun loadExifDisplay(
        ctx: android.content.Context,
        uri: Uri
    ): Pair<String, List<Pair<String, String>>> {
        return try {
            val contentResolver = ctx.contentResolver
            val exif = contentResolver.openInputStream(uri)?.use { input ->
                ExifInterface(input)
            } ?: return "No EXIF data" to emptyList()

            fun cleaned(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

            val make = cleaned(exif.getAttribute(ExifInterface.TAG_MAKE))
            val model = cleaned(exif.getAttribute(ExifInterface.TAG_MODEL))
            val camera = listOfNotNull(make, model).joinToString(" ").takeIf { it.isNotBlank() }
            val taken = cleaned(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))

            val headerLines = ArrayList<String>(2)
            if (camera != null) headerLines.add("Camera: $camera")
            if (taken != null) headerLines.add("Taken: $taken")
            val header = if (headerLines.isEmpty()) "No EXIF data" else headerLines.joinToString("\n")

            val items = ArrayList<Pair<String, String>>(8)

            cleaned(exif.getAttribute(ExifInterface.TAG_LENS_MODEL))?.let { items.add("Lens" to it) }

            cleaned(exif.getAttribute(ExifInterface.TAG_F_NUMBER))?.let { raw ->
                val v = if (raw.startsWith("f/")) raw else "f/$raw"
                items.add("F" to v)
            }

            cleaned(exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME))?.let { raw ->
                val v = if (raw.endsWith("s")) raw else "${raw}s"
                items.add("Shutter" to v)
            }

            cleaned(exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY))?.let {
                items.add("ISO" to it)
            }

            cleaned(exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH))?.let { raw ->
                val v = if (raw.endsWith("mm")) raw else "${raw}mm"
                items.add("Focal" to v)
            }

            val latLong = exif.latLong
            if (latLong != null) {
                val gps =
                    "${String.format(java.util.Locale.US, "%.5f", latLong[0])}, ${
                        String.format(java.util.Locale.US, "%.5f", latLong[1])
                    }"
                items.add("GPS" to gps)
            }

            header to items
        } catch (_: Exception) {
            "No EXIF data" to emptyList()
        }
    }

    private fun renderExifGrid(items: List<Pair<String, String>>) {
        val grid = exifGrid ?: return
        val ctx = grid.context

        grid.removeAllViews()
        if (items.isEmpty() || !showExif) {
            grid.visibility = View.GONE
            return
        }

        val colCount =
            if (ctx.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                3
            } else {
                2
            }
        grid.columnCount = colCount

        val infoColor = infoTextView?.currentTextColor
        val density = ctx.resources.displayMetrics.density
        val colGapPx = (6 * density).toInt()
        val rowGapPx = 1

        items.forEachIndexed { index, (label, value) ->
            val tv = TextView(ctx).apply {
                text = "$label: $value"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                alpha = 0.7f
                if (infoColor != null) setTextColor(infoColor)
            }

            val col = index % colCount
            val lp = GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                val left = if (col == 0) 0 else colGapPx
                val right = if (col == colCount - 1) 0 else colGapPx
                setMargins(left, rowGapPx, right, rowGapPx)
            }
            grid.addView(tv, lp)
        }

        grid.visibility = View.VISIBLE
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "${bytes} B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(java.util.Locale.US, "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(java.util.Locale.US, "%.1f GB", gb)
    }
}
