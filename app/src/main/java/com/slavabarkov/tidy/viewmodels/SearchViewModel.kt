/**
 * Copyright 2023 Viacheslav Barkov
 */

package com.slavabarkov.tidy.viewmodels

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import com.slavabarkov.tidy.TidySettings
import com.slavabarkov.tidy.dot

class SearchViewModel(application: Application) : AndroidViewModel(application) {
    var searchResults: List<Long>? = null
    var fromImg2ImgFlag: Boolean = false
    var lastSearchEmbedding: FloatArray? = null
    var lastSearchIsImageSearch: Boolean = false
    val selectedImageIds: LinkedHashSet<Long> = linkedSetOf()
    val imageDimensionsById: LinkedHashMap<Long, String> = linkedMapOf()

    private val prefs = application.getSharedPreferences(TidySettings.PREFS_NAME, Context.MODE_PRIVATE)
    private var imageSimilarityThreshold: Float =
        prefs.getFloat(
            TidySettings.KEY_IMAGE_SIMILARITY_THRESHOLD,
            TidySettings.DEFAULT_IMAGE_SIMILARITY_THRESHOLD
        )
    private var indexedBucketIds: Set<String> =
        prefs.getStringSet(TidySettings.KEY_INDEXED_BUCKET_IDS, emptySet())?.toSet() ?: emptySet()

    fun getImageSimilarityThreshold(): Float = imageSimilarityThreshold

    fun setImageSimilarityThreshold(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        imageSimilarityThreshold = clamped
        prefs.edit().putFloat(TidySettings.KEY_IMAGE_SIMILARITY_THRESHOLD, clamped).apply()
    }

    fun getIndexedBucketIds(): Set<String> = indexedBucketIds

    fun setIndexedBucketIds(value: Set<String>) {
        indexedBucketIds = value.toSet()
        prefs.edit().putStringSet(TidySettings.KEY_INDEXED_BUCKET_IDS, indexedBucketIds).apply()
    }

    fun sortByCosineDistance(searchEmbedding: FloatArray,
                          imageEmbeddingsList: List<FloatArray>,
                          imageIdxList: List<Long>,
                          minSimilarity: Float? = null,
                          isImageSearch: Boolean = false) {
        lastSearchEmbedding = searchEmbedding
        lastSearchIsImageSearch = isImageSearch

        val distances = LinkedHashMap<Long, Float>()
        for (i in imageEmbeddingsList.indices) {
            val dist = searchEmbedding.dot(imageEmbeddingsList[i])
            distances[imageIdxList[i]] = dist
        }
        val filtered = if (minSimilarity == null) {
            distances
        } else {
            distances.filterValues { it >= minSimilarity }
        }
        searchResults = filtered.toList().sortedByDescending { (_, v) -> v }.map { (k, _) -> k }
    }

    fun clearSelection() {
        selectedImageIds.clear()
    }


}
