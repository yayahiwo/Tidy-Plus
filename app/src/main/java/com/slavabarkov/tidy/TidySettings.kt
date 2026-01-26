/**
 * Copyright 2023 Viacheslav Barkov
 */

package com.slavabarkov.tidy

object TidySettings {
    const val PREFS_NAME = "tidy_settings"

    const val KEY_IMAGE_SIMILARITY_THRESHOLD = "image_similarity_threshold"
    const val DEFAULT_IMAGE_SIMILARITY_THRESHOLD = 0.80f

    // Empty set => index all folders (except those explicitly excluded by the app, e.g. Screenshots)
    const val KEY_INDEXED_BUCKET_IDS = "indexed_bucket_ids"
    const val KEY_INDEX_FOLDERS_CONFIGURED = "index_folders_configured"

    const val KEY_GRID_SPAN_COUNT = "grid_span_count"
    const val DEFAULT_GRID_SPAN_COUNT = 3
}
