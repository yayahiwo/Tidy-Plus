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
    var showBackToAllImages: Boolean = false
    var lastResultsAreNearDuplicates: Boolean = false
    var showNearDuplicateDimensions: Boolean = true
    var startupRequested: Boolean = false
    var pendingIndexRefresh: Boolean = false
    var indexPaused: Boolean = false
    var showImageSearchDimensions: Boolean = true
    val selectedImageIds: LinkedHashSet<Long> = linkedSetOf()
    val imageDimensionsById: LinkedHashMap<Long, String> = linkedMapOf()
    var similaritySortActive: Boolean = false
    var similaritySortBaseResults: List<Long>? = null
    val textSearchTokens: MutableList<String> = mutableListOf()
    val textSearchEmbeddingByToken: LinkedHashMap<String, FloatArray> = linkedMapOf()

    // Metadata Tags cache (derived from image XMP dc:subject). This is kept in-memory only to avoid
    // re-scanning all images on every open/click.
    var metadataTagsCacheKey: String? = null
    var metadataTagsCacheScopeLabel: String? = null
    var metadataTagsCacheScannedImages: Int = 0
    var metadataTagsCacheTaggedImages: Int = 0
    val metadataTagCountsByNorm: LinkedHashMap<String, Long> = linkedMapOf()
    val metadataTagDisplayByNorm: LinkedHashMap<String, String> = linkedMapOf()
    val metadataTagImageIdsByNorm: LinkedHashMap<String, LongArray> = linkedMapOf()
    val metadataTagImageLocationById: LinkedHashMap<Long, String> = linkedMapOf()

    private val prefs = application.getSharedPreferences(TidySettings.PREFS_NAME, Context.MODE_PRIVATE)
    private var imageSimilarityThreshold: Float =
        prefs.getFloat(
            TidySettings.KEY_IMAGE_SIMILARITY_THRESHOLD,
            TidySettings.DEFAULT_IMAGE_SIMILARITY_THRESHOLD
        )
    private var showTextSimilaritySlider: Boolean =
        prefs.getBoolean(
            TidySettings.KEY_SHOW_TEXT_SIMILARITY_SLIDER,
            TidySettings.DEFAULT_SHOW_TEXT_SIMILARITY_SLIDER
        )
    private var textSimilarityThreshold: Float =
        prefs.getFloat(
            TidySettings.KEY_TEXT_SIMILARITY_THRESHOLD,
            TidySettings.DEFAULT_TEXT_SIMILARITY_THRESHOLD
        )
    private var indexedBucketIds: Set<String> =
        prefs.getStringSet(TidySettings.KEY_INDEXED_BUCKET_IDS, emptySet())?.toSet() ?: emptySet()
    private var gridSpanCount: Int =
        prefs.getInt(TidySettings.KEY_GRID_SPAN_COUNT, TidySettings.DEFAULT_GRID_SPAN_COUNT)

    fun getImageSimilarityThreshold(): Float = imageSimilarityThreshold

    fun setImageSimilarityThreshold(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        imageSimilarityThreshold = clamped
        prefs.edit().putFloat(TidySettings.KEY_IMAGE_SIMILARITY_THRESHOLD, clamped).apply()
    }

    fun getShowTextSimilaritySlider(): Boolean = showTextSimilaritySlider

    fun setShowTextSimilaritySlider(value: Boolean) {
        showTextSimilaritySlider = value
        prefs.edit().putBoolean(TidySettings.KEY_SHOW_TEXT_SIMILARITY_SLIDER, value).apply()
    }

    fun getTextSimilarityThreshold(): Float = textSimilarityThreshold

    fun setTextSimilarityThreshold(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        textSimilarityThreshold = clamped
        prefs.edit().putFloat(TidySettings.KEY_TEXT_SIMILARITY_THRESHOLD, clamped).apply()
    }

    fun getIndexedBucketIds(): Set<String> = indexedBucketIds

    fun setIndexedBucketIds(value: Set<String>) {
        indexedBucketIds = value.toSet()
        prefs.edit()
            .putStringSet(TidySettings.KEY_INDEXED_BUCKET_IDS, indexedBucketIds)
            .putBoolean(TidySettings.KEY_INDEX_FOLDERS_CONFIGURED, true)
            .apply()

        // Folder scope changed => cached tag scan is stale.
        clearMetadataTagsCache()
    }

    fun getGridSpanCount(): Int = gridSpanCount

    fun setGridSpanCount(value: Int) {
        val clamped = value.coerceIn(2, 24)
        gridSpanCount = clamped
        prefs.edit().putInt(TidySettings.KEY_GRID_SPAN_COUNT, clamped).apply()
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

    fun clearTextSearchTokens() {
        textSearchTokens.clear()
        textSearchEmbeddingByToken.clear()
    }

    fun clearMetadataTagsCache() {
        metadataTagsCacheKey = null
        metadataTagsCacheScopeLabel = null
        metadataTagsCacheScannedImages = 0
        metadataTagsCacheTaggedImages = 0
        metadataTagCountsByNorm.clear()
        metadataTagDisplayByNorm.clear()
        metadataTagImageIdsByNorm.clear()
        metadataTagImageLocationById.clear()
    }

}
