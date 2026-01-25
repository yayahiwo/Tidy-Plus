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

        // Get image metadata from image URI
        val cursor: Cursor =
            requireContext().contentResolver.query(imageUri!!, null, null, null, null)!!
        cursor.moveToFirst()

        val dateIdx: Int = cursor.getColumnIndex(MediaStore.Images.ImageColumns.DATE_MODIFIED)
        val date: Long =
            if (dateIdx >= 0) cursor.getLong(dateIdx) * 1000 else System.currentTimeMillis()

        val displayNameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
        val displayName: String? = if (displayNameIdx >= 0) cursor.getString(displayNameIdx) else null

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

        val dateTextView: TextView = view.findViewById(R.id.dateTextView)
        dateTextView.text = DateFormat.getDateInstance().format(date)

        val singleImageView: PhotoView = view.findViewById(R.id.singeImageView)
        Glide.with(view).load(imageUri).into(singleImageView)

        val infoTextView: TextView = view.findViewById(R.id.imageInfoTextView)
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
        infoTextView.text = "Location: $location\nSize: $sizeText\nDimensions: $dimensions"

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
