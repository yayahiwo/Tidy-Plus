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
import android.widget.TextView
import android.widget.Toast
import androidx.core.net.toUri
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

        dateTextView = view.findViewById(R.id.dateTextView)
        infoTextView = view.findViewById(R.id.imageInfoTextView)
        singleImageView = view.findViewById(R.id.singeImageView)
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
            mSearchViewModel.searchResults = mORTImageViewModel.idxList.reversed()
            mSearchViewModel.lastSearchIsImageSearch = false
            mSearchViewModel.lastSearchEmbedding = null
            mSearchViewModel.clearSelection()
            mSearchViewModel.fromImg2ImgFlag = true
            parentFragmentManager.popBackStack()
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
        infoTextView?.text = "Location: $location\nSize: $sizeText\nDimensions: $dimensions"
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
