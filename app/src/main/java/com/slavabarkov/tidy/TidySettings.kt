/**
 * Copyright 2023 Viacheslav Barkov
 */

package com.slavabarkov.tidy

object TidySettings {
    const val PREFS_NAME = "tidy_settings"

    const val KEY_IMAGE_SIMILARITY_THRESHOLD = "image_similarity_threshold"
    const val DEFAULT_IMAGE_SIMILARITY_THRESHOLD = 0.80f

    const val KEY_SHOW_TEXT_SIMILARITY_SLIDER = "show_text_similarity_slider"
    const val DEFAULT_SHOW_TEXT_SIMILARITY_SLIDER = false

    const val KEY_TEXT_SIMILARITY_THRESHOLD = "text_similarity_threshold"
    const val DEFAULT_TEXT_SIMILARITY_THRESHOLD = 0.20f

    // Empty set => index all folders (except those explicitly excluded by the app, e.g. Screenshots)
    const val KEY_INDEXED_BUCKET_IDS = "indexed_bucket_ids"
    const val KEY_INDEX_FOLDERS_CONFIGURED = "index_folders_configured"

    // If available in the build, use Qualcomm QNN EP (Hexagon/HTP) for visual embedding inference.
    // Falls back to CPU if QNN isn't available or fails to initialize.
    const val KEY_INDEX_USE_QNN = "index_use_qnn"

    const val KEY_GRID_SPAN_COUNT = "grid_span_count"
    const val DEFAULT_GRID_SPAN_COUNT = 3
}
