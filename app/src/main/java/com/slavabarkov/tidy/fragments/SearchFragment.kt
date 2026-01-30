/**
 * Copyright 2023 Viacheslav Barkov
 */

package com.slavabarkov.tidy.fragments

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.Context
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.PackageManager
import android.content.ClipboardManager
import android.content.ClipData
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ScaleGestureDetector
import android.graphics.Rect
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.exifinterface.media.ExifInterface
import com.google.android.material.button.MaterialButton
import com.slavabarkov.tidy.dot
import com.slavabarkov.tidy.viewmodels.ORTImageViewModel
import com.slavabarkov.tidy.viewmodels.ORTTextViewModel
import com.slavabarkov.tidy.R
import com.slavabarkov.tidy.TidySettings
import com.slavabarkov.tidy.viewmodels.SearchViewModel
import com.slavabarkov.tidy.adapters.ImageAdapter
import com.slavabarkov.tidy.data.ImageEmbeddingDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt


class SearchFragment : Fragment() {
    private var searchText: TextView? = null
    private var searchButton: Button? = null
    private var clearButton: Button? = null
    private var topButtonsRow: View? = null
    private var imageSimilaritySection: View? = null
    private var imageSimilaritySeekBar: SeekBar? = null
    private var imageSimilarityValue: TextView? = null
    private var toolsButton: Button? = null
    private var imageCountText: TextView? = null
    private var backToAllImagesButton: Button? = null
    private var sortBySimilarityButton: Button? = null
    private var selectionActions: View? = null
    private var selectedCountText: TextView? = null
    private var moveSelectedButton: Button? = null
    private var deleteSelectedButton: Button? = null
    private var clearSelectionButton: Button? = null
    private var imageAdapter: ImageAdapter? = null
    private var scaleGestureDetector: ScaleGestureDetector? = null
    private var accumulatedScale: Float = 1.0f
    private var recyclerView: RecyclerView? = null
    private var reindexProgressContainer: View? = null
    private var reindexProgressText: TextView? = null
    private var reindexProgressBar: ProgressBar? = null
    private var reindexCountText: TextView? = null
    private var findDuplicatesJob: Job? = null
    private var pendingDbBackupText: String? = null
    private val mORTImageViewModel: ORTImageViewModel by activityViewModels()
    private val mORTTextViewModel: ORTTextViewModel by activityViewModels()
    private val mSearchViewModel: SearchViewModel by activityViewModels()

    private enum class PendingAction {
        Delete,
        MoveWrite,
    }

    private enum class PendingPermissionAction {
        Startup,
        Reindex,
        ToolsStats,
        ToolsTags,
    }

    private var pendingAction: PendingAction? = null
    private var pendingDeleteIds: List<Long> = emptyList()
    private var pendingMoveIds: List<Long> = emptyList()
    private var pendingMoveRelativePath: String? = null
    private var pendingPermissionAction: PendingPermissionAction? = null

    private val permissionsRequest: ActivityResultLauncher<String> = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        val action = pendingPermissionAction
        pendingPermissionAction = null

        if (!isGranted) {
            Toast.makeText(context, "The app requires photo access!", Toast.LENGTH_SHORT)
                .show()
            return@registerForActivityResult
        }

        when (action) {
            PendingPermissionAction.Startup -> runStartupFlow()
            PendingPermissionAction.Reindex -> startReindexInternal()
            PendingPermissionAction.ToolsStats -> showStatsDialogInternal()
            PendingPermissionAction.ToolsTags -> showTagsDialogInternal()
            null -> Unit
        }
    }

    private val intentSenderLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val action = pendingAction
            pendingAction = null

            if (result.resultCode != Activity.RESULT_OK || action == null) return@registerForActivityResult

            when (action) {
                PendingAction.Delete -> {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        onMediaItemsDeleted(pendingDeleteIds)
                    } else {
                        startDelete(pendingDeleteIds)
                    }
                }
                PendingAction.MoveWrite -> applyMove(pendingMoveIds, pendingMoveRelativePath)
            }
        }

    private val folderPickerLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { treeUri ->
            if (treeUri == null) return@registerForActivityResult
            val relativePath = getRelativePathFromTreeUri(treeUri)
            if (relativePath == null) {
                Toast.makeText(
                    requireContext(),
                    "Selected folder is not supported for Move",
                    Toast.LENGTH_SHORT
                ).show()
                return@registerForActivityResult
            }
            performMove(pendingMoveIds, relativePath)
        }

    private val backupDbLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri == null) return@registerForActivityResult
            val expectedText = pendingDbBackupText ?: return@registerForActivityResult
            pendingDbBackupText = null
            backupDatabaseTo(uri, expectedText)
        }

    private val restoreDbLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            restoreDatabaseFrom(uri)
        }

    override fun onResume() {
        super.onResume()
        searchText = view?.findViewById(R.id.searchText)
        val recyclerView = view?.findViewById<RecyclerView>(R.id.recycler_view)

        if (mSearchViewModel.fromImg2ImgFlag) {
            searchText?.text = null
            val results = mSearchViewModel.searchResults ?: mORTImageViewModel.idxList.reversed()
            mSearchViewModel.searchResults = results
            recyclerView?.let { setResults(it, results) }
            recyclerView?.scrollToPosition(0)
            mSearchViewModel.fromImg2ImgFlag = false
            return
        }

        // If we navigated away (e.g., opened an image) during indexing, ensure we still show a
        // non-empty grid when coming back.
        if (mSearchViewModel.pendingIndexRefresh) {
            loadDisplayImagesIfNeeded()
        } else if ((mSearchViewModel.searchResults == null || mSearchViewModel.searchResults!!.isEmpty()) &&
            mORTImageViewModel.idxList.isNotEmpty()
        ) {
            val results = mORTImageViewModel.idxList.reversed()
            mSearchViewModel.searchResults = results
            recyclerView?.let { setResults(it, results) }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        val view = inflater.inflate(R.layout.fragment_search, container, false)
        recyclerView = view.findViewById(R.id.recycler_view)
        val recyclerView = recyclerView!!

        val initialSpanCount = mSearchViewModel.getGridSpanCount()
        val gridLayoutManager = (recyclerView.layoutManager as? GridLayoutManager)
            ?: GridLayoutManager(requireContext(), initialSpanCount).also { recyclerView.layoutManager = it }
        gridLayoutManager.spanCount = initialSpanCount
        ensureGridSpacing(recyclerView)
        setupPinchToZoom(recyclerView, gridLayoutManager)

        topButtonsRow = view.findViewById(R.id.topButtonsRow)
        imageSimilaritySection = view.findViewById(R.id.imageSimilaritySection)
        reindexProgressContainer = view.findViewById(R.id.reindexProgressContainer)
        reindexProgressText = view.findViewById(R.id.reindexProgressText)
        reindexProgressBar = view.findViewById(R.id.reindexProgressBar)
        reindexCountText = view.findViewById(R.id.reindexCountText)
        imageCountText = view.findViewById(R.id.imageCountText)

        // If indexing was started from elsewhere (e.g., older flow / restored back stack),
        // ensure the main screen shows progress and can populate the grid during indexing.
        if (!mSearchViewModel.pendingIndexRefresh && mORTImageViewModel.isIndexing.value == true) {
            mSearchViewModel.pendingIndexRefresh = true
            mSearchViewModel.indexPaused = false
        }

        if (mSearchViewModel.pendingIndexRefresh) {
            reindexProgressContainer?.visibility = View.VISIBLE
            val p = mORTImageViewModel.progress.value ?: 0.0
            val progressPercent: Int = (p * 100).toInt()
            reindexProgressBar?.progress = progressPercent
            reindexProgressText?.text =
                if (mSearchViewModel.indexPaused) "Indexing paused: ${progressPercent}%"
                else "Updating image index: ${progressPercent}%"
            reindexCountText?.text = "Indexed photos: ${mORTImageViewModel.indexedCount.value ?: 0}"
        }
        updateIndexingControls()

        val initialResults = mSearchViewModel.searchResults ?: mORTImageViewModel.idxList.reversed()
        mSearchViewModel.searchResults = initialResults
        setResults(recyclerView, initialResults)
        recyclerView.scrollToPosition(0)

        mORTTextViewModel.init()

        searchText = view.findViewById(R.id.searchText)
        searchButton = view.findViewById(R.id.searchButton)

        imageSimilaritySeekBar = view.findViewById(R.id.imageSimilaritySeekBar)
        imageSimilarityValue = view.findViewById(R.id.imageSimilarityValue)
        val initialThreshold = mSearchViewModel.getImageSimilarityThreshold()
        imageSimilaritySeekBar?.progress = (initialThreshold * 100).roundToInt()
        imageSimilarityValue?.text = String.format(Locale.US, "%.2f", initialThreshold)
        imageSimilaritySeekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val threshold = progress / 100f
                mSearchViewModel.setImageSimilarityThreshold(threshold)
                imageSimilarityValue?.text = String.format(Locale.US, "%.2f", threshold)

                val embedding = mSearchViewModel.lastSearchEmbedding ?: return
                if (!mSearchViewModel.lastSearchIsImageSearch) return

                mSearchViewModel.sortByCosineDistance(
                    embedding,
                    mORTImageViewModel.embeddingsList,
                    mORTImageViewModel.idxList,
                    minSimilarity = threshold,
                    isImageSearch = true
                )
                setResults(recyclerView, mSearchViewModel.searchResults ?: emptyList())
                recyclerView.scrollToPosition(0)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        mORTImageViewModel.progress.observe(viewLifecycleOwner) { progress ->
            if (!mSearchViewModel.pendingIndexRefresh) return@observe
            val progressPercent: Int = (progress * 100).toInt()
            reindexProgressBar?.progress = progressPercent
            if (!mSearchViewModel.indexPaused) {
                reindexProgressText?.text = "Updating image index: ${progressPercent}%"
            }
            if (progress == 1.0) {
                finishReindex()
            }
        }

        mORTImageViewModel.indexedCount.observe(viewLifecycleOwner) { count ->
            if (!mSearchViewModel.pendingIndexRefresh) return@observe
            reindexCountText?.text = "Indexed photos: $count"
        }

        mORTImageViewModel.isIndexing.observe(viewLifecycleOwner) { indexing ->
            if (!mSearchViewModel.pendingIndexRefresh) return@observe
            updateIndexingControls()
            if (indexing) return@observe
            if (mSearchViewModel.indexPaused) {
                showPartialIndexResults()
            } else if ((mORTImageViewModel.progress.value ?: 0.0) >= 1.0) {
                finishReindex()
            }
        }

        selectionActions = view.findViewById(R.id.selectionActions)
        selectedCountText = view.findViewById(R.id.selectedCountText)
        moveSelectedButton = view.findViewById(R.id.moveSelectedButton)
        deleteSelectedButton = view.findViewById(R.id.deleteSelectedButton)
        clearSelectionButton = view.findViewById(R.id.clearSelectionButton)
        backToAllImagesButton = view.findViewById(R.id.backToAllImagesButton)
        sortBySimilarityButton = view.findViewById(R.id.sortBySimilarityButton)
        updateSelectionUI()

        backToAllImagesButton?.setOnClickListener {
            mSearchViewModel.searchResults = mORTImageViewModel.idxList.reversed()
            mSearchViewModel.lastSearchIsImageSearch = false
            mSearchViewModel.showBackToAllImages = false
            mSearchViewModel.lastResultsAreNearDuplicates = false
            mSearchViewModel.lastSearchEmbedding = null
            mSearchViewModel.similaritySortActive = false
            mSearchViewModel.similaritySortBaseResults = null
            mSearchViewModel.clearSelection()
            imageAdapter?.clearSelection()
            setResults(recyclerView, mSearchViewModel.searchResults ?: emptyList())
            recyclerView.scrollToPosition(0)
        }

        sortBySimilarityButton?.setOnClickListener {
            if (mSearchViewModel.lastSearchIsImageSearch) {
                val firstVisible =
                    (recyclerView.layoutManager as? GridLayoutManager)?.findFirstVisibleItemPosition()
                        ?.coerceAtLeast(0) ?: 0
                mSearchViewModel.showImageSearchDimensions = !mSearchViewModel.showImageSearchDimensions
                setResults(recyclerView, mSearchViewModel.searchResults ?: emptyList())
                recyclerView.scrollToPosition(firstVisible)
            } else if (mSearchViewModel.lastResultsAreNearDuplicates) {
                val firstVisible =
                    (recyclerView.layoutManager as? GridLayoutManager)?.findFirstVisibleItemPosition()
                        ?.coerceAtLeast(0) ?: 0
                mSearchViewModel.showNearDuplicateDimensions =
                    !mSearchViewModel.showNearDuplicateDimensions
                setResults(recyclerView, mSearchViewModel.searchResults ?: emptyList())
                recyclerView.scrollToPosition(firstVisible)
            } else {
                toggleSimilaritySort()
            }
        }

        deleteSelectedButton?.setOnClickListener {
            val ids = mSearchViewModel.selectedImageIds.toList()
            if (ids.isEmpty()) return@setOnClickListener
            startDelete(ids)
        }

        moveSelectedButton?.setOnClickListener {
            val ids = mSearchViewModel.selectedImageIds.toList()
            if (ids.isEmpty()) return@setOnClickListener
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
                Toast.makeText(requireContext(), "Move requires Android 10+", Toast.LENGTH_SHORT)
                    .show()
                return@setOnClickListener
            }
            pendingMoveIds = ids
            folderPickerLauncher.launch(null)
        }

        clearSelectionButton?.setOnClickListener {
            mSearchViewModel.clearSelection()
            imageAdapter?.clearSelection()
            updateSelectionUI()
        }

        toolsButton = view.findViewById(R.id.indexFoldersButton)
        toolsButton?.setOnClickListener { showToolsDialog() }

        searchButton?.setOnClickListener {
            val textEmbedding: FloatArray =
                mORTTextViewModel.getTextEmbedding(searchText?.text.toString())
            mSearchViewModel.sortByCosineDistance(
                textEmbedding,
                mORTImageViewModel.embeddingsList,
                mORTImageViewModel.idxList,
                isImageSearch = false
            )
            mSearchViewModel.lastResultsAreNearDuplicates = false
            mSearchViewModel.showBackToAllImages = false
            setResults(recyclerView, mSearchViewModel.searchResults ?: emptyList())
        }

        clearButton = view.findViewById(R.id.clearButton)
        clearButton?.setOnClickListener{
            if (mSearchViewModel.pendingIndexRefresh) {
                if (mSearchViewModel.indexPaused) {
                    resumeReindex()
                } else {
                    pauseReindex()
                }
                return@setOnClickListener
            }
            searchText?.text = null
            mSearchViewModel.searchResults = mORTImageViewModel.idxList.reversed()
            mSearchViewModel.lastSearchIsImageSearch = false
            mSearchViewModel.showBackToAllImages = false
            mSearchViewModel.lastResultsAreNearDuplicates = false
            mSearchViewModel.lastSearchEmbedding = null
            mSearchViewModel.similaritySortActive = false
            mSearchViewModel.similaritySortBaseResults = null
            setResults(recyclerView, mSearchViewModel.searchResults ?: emptyList())
        }

        runStartupFlow()
        return view
    }

    private fun queryImageIdsForDisplay(ctx: Context): List<Long> {
        val contentResolver = ctx.contentResolver
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        )

        val bucketIds = mSearchViewModel.getIndexedBucketIds()
        val selection: String?
        val selectionArgs: Array<String>?
        if (bucketIds.isEmpty()) {
            selection = null
            selectionArgs = null
        } else {
            val placeholders = bucketIds.joinToString(",") { "?" }
            selection = "${MediaStore.Images.Media.BUCKET_ID} IN ($placeholders)"
            selectionArgs = bucketIds.toTypedArray()
        }

        val ids = ArrayList<Long>()
        contentResolver.query(uri, projection, selection, selectionArgs, "${MediaStore.Images.Media._ID} DESC")
            ?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val bucketNameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val bucketName = cursor.getString(bucketNameCol)
                    if (bucketName == "Screenshots") continue
                    ids.add(cursor.getLong(idCol))
                }
            }
        return ids
    }

    private fun loadDisplayImagesIfNeeded() {
        if (!isAdded) return
        if (!hasReadPermission()) return
        if (mSearchViewModel.searchResults != null && mSearchViewModel.searchResults!!.isNotEmpty()) return

        val ctx = context ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val ids = queryImageIdsForDisplay(ctx)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                val rv = recyclerView ?: return@withContext
                // Only auto-populate when we don't have a search in progress.
                if (mSearchViewModel.lastSearchEmbedding != null) return@withContext
                mSearchViewModel.searchResults = ids
                setResults(rv, ids)
            }
        }
    }

    private fun requiredPermission(): String {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_IMAGES
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    private fun hasReadPermission(): Boolean {
        val ctx = context ?: return false
        return ContextCompat.checkSelfPermission(ctx, requiredPermission()) == PackageManager.PERMISSION_GRANTED
    }

    private fun runStartupFlow() {
        val ctx = context ?: return
        if (!hasReadPermission()) {
            pendingPermissionAction = PendingPermissionAction.Startup
            permissionsRequest.launch(requiredPermission())
            return
        }

        val prefs = ctx.getSharedPreferences(TidySettings.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val configured = prefs.getBoolean(TidySettings.KEY_INDEX_FOLDERS_CONFIGURED, false)
        if (!configured) {
            if (!mSearchViewModel.startupRequested) {
                mSearchViewModel.startupRequested = true
                showIndexFoldersDialog()
            }
            return
        }

        // If we have no in-memory index yet (cold start), kick off indexing immediately and show progress here.
        if (!mSearchViewModel.pendingIndexRefresh && mORTImageViewModel.idxList.isEmpty()) {
            startReindexInternal()
        }

        // Always ensure we have something to show in the grid (even if we've already run the
        // startup prompt and the fragment/view got recreated).
        loadDisplayImagesIfNeeded()
    }

    private fun pauseReindex() {
        mSearchViewModel.indexPaused = true
        updateIndexingControls()
        reindexProgressText?.text = "Stopping…"
        val job = mORTImageViewModel.cancelIndexing()
        lifecycleScope.launch {
            try {
                job?.join()
            } catch (_: Exception) {
            }
            if (!isAdded) return@launch
            if (mSearchViewModel.pendingIndexRefresh && mSearchViewModel.indexPaused) {
                showPartialIndexResults()
            }
        }
    }

    private fun resumeReindex() {
        mSearchViewModel.indexPaused = false
        startReindex()
    }

    private fun updateIndexingControls() {
        val button = clearButton as? MaterialButton ?: return
        when {
            mSearchViewModel.pendingIndexRefresh && mSearchViewModel.indexPaused -> {
                button.setIconResource(R.drawable.ic_play)
                button.contentDescription = "Resume indexing"
            }
            mSearchViewModel.pendingIndexRefresh -> {
                button.setIconResource(R.drawable.ic_pause)
                button.contentDescription = "Pause indexing"
            }
            else -> {
                button.setIconResource(R.drawable.ic_clear)
                button.contentDescription = "Clear"
            }
        }
    }

    private fun showPartialIndexResults() {
        val recyclerView = recyclerView ?: return
        val p = mORTImageViewModel.progress.value ?: 0.0
        val progressPercent: Int = (p * 100).toInt()
        reindexProgressContainer?.visibility = View.VISIBLE
        reindexProgressBar?.progress = progressPercent
        reindexProgressText?.text = "Indexing paused: ${progressPercent}%"
        reindexCountText?.text = "Indexed photos: ${mORTImageViewModel.indexedCount.value ?: 0}"

        mSearchViewModel.lastSearchIsImageSearch = false
        mSearchViewModel.showBackToAllImages = false
        mSearchViewModel.lastResultsAreNearDuplicates = false
        mSearchViewModel.lastSearchEmbedding = null
        mSearchViewModel.searchResults = mORTImageViewModel.idxList.reversed()
        setResults(recyclerView, mSearchViewModel.searchResults ?: emptyList())
        recyclerView.scrollToPosition(0)
    }

    private fun toggleSimilaritySort() {
        if (mORTImageViewModel.isIndexing.value == true) return

        val recyclerView = recyclerView ?: return
        val currentResults = mSearchViewModel.searchResults ?: mORTImageViewModel.idxList.reversed()

        if (mSearchViewModel.similaritySortActive) {
            val base = mSearchViewModel.similaritySortBaseResults
            mSearchViewModel.similaritySortActive = false
            mSearchViewModel.similaritySortBaseResults = null
            mSearchViewModel.searchResults = base ?: mORTImageViewModel.idxList.reversed()
            setResults(recyclerView, mSearchViewModel.searchResults ?: emptyList())
            recyclerView.scrollToPosition(0)
            return
        }

        // Use selected image(s) as the reference if any are selected; otherwise fall back to last search embedding.
        val referenceEmbedding = buildReferenceEmbeddingFromSelection()
            ?: mSearchViewModel.lastSearchEmbedding
        if (referenceEmbedding == null) {
            Toast.makeText(
                requireContext(),
                "Select image(s) or run a search first",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        mSearchViewModel.similaritySortActive = true
        mSearchViewModel.similaritySortBaseResults = currentResults.toList()

        lifecycleScope.launch(Dispatchers.Default) {
            val idToEmbedding = HashMap<Long, FloatArray>(mORTImageViewModel.idxList.size)
            for (i in mORTImageViewModel.idxList.indices) {
                idToEmbedding[mORTImageViewModel.idxList[i]] = mORTImageViewModel.embeddingsList[i]
            }

            val sorted = currentResults
                .mapNotNull { id ->
                    val emb = idToEmbedding[id] ?: return@mapNotNull null
                    id to referenceEmbedding.dot(emb)
                }
                .sortedByDescending { it.second }
                .map { it.first }

            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                if (!mSearchViewModel.similaritySortActive) return@withContext
                mSearchViewModel.searchResults = sorted
                setResults(recyclerView, sorted)
                recyclerView.scrollToPosition(0)
            }
        }
    }

    private fun buildReferenceEmbeddingFromSelection(): FloatArray? {
        val selected = mSearchViewModel.selectedImageIds.toList()
        if (selected.isEmpty()) return null

        val idToEmbedding = HashMap<Long, FloatArray>(mORTImageViewModel.idxList.size)
        for (i in mORTImageViewModel.idxList.indices) {
            idToEmbedding[mORTImageViewModel.idxList[i]] = mORTImageViewModel.embeddingsList[i]
        }

        val embeddings = selected.mapNotNull { idToEmbedding[it] }
        if (embeddings.isEmpty()) return null

        val dim = embeddings[0].size
        val sum = FloatArray(dim)
        for (emb in embeddings) {
            if (emb.size != dim) return null
            for (i in 0 until dim) sum[i] += emb[i]
        }
        for (i in 0 until dim) sum[i] /= embeddings.size.toFloat()

        // Normalize for cosine similarity.
        var norm = 0.0f
        for (i in 0 until dim) norm += sum[i] * sum[i]
        norm = kotlin.math.sqrt(norm)
        if (norm <= 0f) return null
        for (i in 0 until dim) sum[i] /= norm
        return sum
    }

    private fun setupPinchToZoom(recyclerView: RecyclerView, gridLayoutManager: GridLayoutManager) {
        // Discrete zoom steps: fewer columns = larger thumbnails, more columns = smaller thumbnails.
        val minSpanCount = 2
        val maxSpanCount = 24
        accumulatedScale = 1.0f

        scaleGestureDetector = ScaleGestureDetector(
            requireContext(),
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    accumulatedScale *= detector.scaleFactor
                    var spanCount = gridLayoutManager.spanCount

                    // Zoom in: scale > 1 => decrease columns
                    while (accumulatedScale > 1.20f && spanCount > minSpanCount) {
                        spanCount -= 1
                        accumulatedScale /= 1.20f
                    }
                    // Zoom out: scale < 1 => increase columns
                    while (accumulatedScale < 0.83f && spanCount < maxSpanCount) {
                        spanCount += 1
                        accumulatedScale /= 0.83f
                    }

                    if (spanCount != gridLayoutManager.spanCount) {
                        val firstVisible =
                            gridLayoutManager.findFirstVisibleItemPosition().coerceAtLeast(0)
                        gridLayoutManager.spanCount = spanCount
                        mSearchViewModel.setGridSpanCount(spanCount)
                        recyclerView.post { recyclerView.scrollToPosition(firstVisible) }
                    }

                    return true
                }
            }
        )

        recyclerView.setOnTouchListener { _, event ->
            scaleGestureDetector?.onTouchEvent(event)
            false
        }
    }

    private fun ensureGridSpacing(recyclerView: RecyclerView) {
        if (recyclerView.itemDecorationCount > 0) return

        // 2px between items (1px on each side), plus 1px RecyclerView padding => 2px outer edge.
        val spacingPx = 2
        val half = spacingPx / 2
        recyclerView.setPadding(half, half, half, half)
        recyclerView.clipToPadding = false
        recyclerView.addItemDecoration(UniformSpacingItemDecoration(half))
    }

    private class UniformSpacingItemDecoration(private val halfSpacePx: Int) :
        RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State,
        ) {
            outRect.set(halfSpacePx, halfSpacePx, halfSpacePx, halfSpacePx)
        }
    }

    private fun showIndexFoldersDialog() {
        val ctx = context ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val buckets = queryImageBuckets(ctx)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext

                val saved = mSearchViewModel.getIndexedBucketIds()
                val items = ArrayList<String>(buckets.size + 1)
                items.add("All folders")
                for ((_, name) in buckets) items.add(name)

                val checked = BooleanArray(items.size)
                if (saved.isEmpty()) {
                    checked[0] = true
                } else {
                    for (i in buckets.indices) {
                        checked[i + 1] = saved.contains(buckets[i].first.toString())
                    }
                }

                val prefs = requireContext().getSharedPreferences(TidySettings.PREFS_NAME, Context.MODE_PRIVATE)
                val qnnAvailable = mORTImageViewModel.isQnnSupported()
                val useQnnCheckbox = CheckBox(requireContext()).apply {
                    text = if (qnnAvailable) {
                        "Use Qualcomm QNN (Hexagon) acceleration (experimental)"
                    } else {
                        "Use Qualcomm QNN (Hexagon) acceleration (not available)"
                    }
                    isEnabled = qnnAvailable
                    isChecked = qnnAvailable && prefs.getBoolean(TidySettings.KEY_INDEX_USE_QNN, false)
                }
                val optionsView = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(48, 16, 48, 0)
                    addView(useQnnCheckbox)
                }

                AlertDialog.Builder(requireContext())
                    .setTitle("Folders to index")
                    .setMultiChoiceItems(items.toTypedArray(), checked) { _, which, isChecked ->
                        if (which == 0) {
                            if (isChecked) {
                                for (i in 1 until checked.size) checked[i] = false
                            }
                            checked[0] = isChecked
                        } else {
                            checked[which] = isChecked
                            if (isChecked) checked[0] = false
                        }
                    }
                    .setView(optionsView)
                    .setPositiveButton("OK") { _, _ ->
                        val selected = buckets
                            .filterIndexed { idx, _ -> checked[idx + 1] }
                            .map { it.first.toString() }
                            .toSet()

                        prefs.edit()
                            .putBoolean(TidySettings.KEY_INDEX_USE_QNN, useQnnCheckbox.isChecked)
                            .apply()

                        if (checked[0] || selected.isEmpty()) {
                            mSearchViewModel.setIndexedBucketIds(emptySet())
                            Toast.makeText(requireContext(), "Indexing all folders", Toast.LENGTH_SHORT)
                                .show()
                        } else {
                            mSearchViewModel.setIndexedBucketIds(selected)
                            Toast.makeText(
                                requireContext(),
                                "Indexing ${selected.size} folder(s)",
                                Toast.LENGTH_SHORT
                            ).show()
                        }

                        mSearchViewModel.searchResults = null
                        mSearchViewModel.clearSelection()
                        mSearchViewModel.lastSearchIsImageSearch = false
                        mSearchViewModel.showBackToAllImages = false
                        mSearchViewModel.lastResultsAreNearDuplicates = false
                        mSearchViewModel.lastSearchEmbedding = null
                        mSearchViewModel.similaritySortActive = false
                        mSearchViewModel.similaritySortBaseResults = null
                        imageAdapter?.clearSelection()
                        updateSelectionUI()

                        // Populate the grid immediately from MediaStore (cheap) so the user can browse
                        // while embeddings are being computed.
                        startReindex()
                        loadDisplayImagesIfNeeded()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun startReindex() {
        if (!hasReadPermission()) {
            pendingPermissionAction = PendingPermissionAction.Reindex
            permissionsRequest.launch(requiredPermission())
            return
        }
        startReindexInternal()
    }

    private fun startReindexInternal() {
        mSearchViewModel.pendingIndexRefresh = true
        mSearchViewModel.indexPaused = false
        updateIndexingControls()
        reindexProgressContainer?.visibility = View.VISIBLE
        reindexProgressBar?.progress = 0
        reindexProgressText?.text = "Updating image index: 0%"
        reindexCountText?.text = "Indexed photos: 0"

        mORTImageViewModel.generateIndex()
    }

    private fun finishReindex() {
        val recyclerView = recyclerView ?: return

        if (!mSearchViewModel.pendingIndexRefresh) return
        mSearchViewModel.pendingIndexRefresh = false
        mSearchViewModel.indexPaused = false
        updateIndexingControls()
        reindexProgressContainer?.visibility = View.GONE

        mSearchViewModel.lastSearchIsImageSearch = false
        mSearchViewModel.showBackToAllImages = false
        mSearchViewModel.lastResultsAreNearDuplicates = false
        mSearchViewModel.lastSearchEmbedding = null
        mSearchViewModel.searchResults = mORTImageViewModel.idxList.reversed()
        setResults(recyclerView, mSearchViewModel.searchResults ?: emptyList())
        recyclerView.scrollToPosition(0)
    }

    private fun showToolsDialog() {
        val ctx = context ?: return
        val dialogView = layoutInflater.inflate(R.layout.dialog_tools, null)
        val foldersButton = dialogView.findViewById<Button>(R.id.toolsFoldersButton)
        val duplicatesButton = dialogView.findViewById<Button>(R.id.toolsDuplicatesButton)
        val tagsButton = dialogView.findViewById<Button>(R.id.toolsTagsButton)
        val statsButton = dialogView.findViewById<Button>(R.id.toolsStatsButton)

        val indexing = (mORTImageViewModel.isIndexing.value == true)
        duplicatesButton.isEnabled = !indexing
        tagsButton.isEnabled = !indexing
        statsButton.isEnabled = !indexing
        if (indexing) {
            duplicatesButton.text = "Find near-duplicates (finish indexing first)"
            tagsButton.text = "Metadata Tags (finish indexing first)"
            statsButton.text = "Stats (finish indexing first)"
        }

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("Tools")
            .setView(dialogView)
            .setNegativeButton("Close", null)
            .create()

        foldersButton.setOnClickListener {
            dialog.dismiss()
            showIndexFoldersDialog()
        }
        duplicatesButton.setOnClickListener {
            dialog.dismiss()
            startFindNearDuplicates(threshold = 0.99f)
        }
        tagsButton.setOnClickListener {
            dialog.dismiss()
            showMetadataTagsDialog()
        }
        statsButton.setOnClickListener {
            dialog.dismiss()
            showStatsDialog()
        }

        dialog.show()
    }

    private fun decodeXml(s: String): String {
        return s
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
    }

    private fun extractXmpTagKeywords(xmp: String): List<String> {
        val start = xmp.indexOf("<dc:subject")
        if (start < 0) return emptyList()
        val end = xmp.indexOf("</dc:subject>", start)
        if (end < 0) return emptyList()
        val block = xmp.substring(start, end)

        val results = ArrayList<String>(8)
        var idx = 0
        while (true) {
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

    private fun showMetadataTagsDialog() {
        if (!hasReadPermission()) {
            pendingPermissionAction = PendingPermissionAction.ToolsTags
            permissionsRequest.launch(requiredPermission())
            return
        }
        showTagsDialogInternal()
    }

    private fun showTagsDialogInternal() {
        if (mORTImageViewModel.isIndexing.value == true) {
            Toast.makeText(requireContext(), "Wait for indexing to finish first", Toast.LENGTH_SHORT)
                .show()
            return
        }

        val bucketIds = mSearchViewModel.getIndexedBucketIds()
        val scopeKey = buildMetadataTagsScopeKey(bucketIds)
        val cachedCounts = mSearchViewModel.metadataTagCountsByNorm
        val cachedIdsByTag = mSearchViewModel.metadataTagImageIdsByNorm
        val cachedDisplayByTag = mSearchViewModel.metadataTagDisplayByNorm
        val cachedScopeLabel = mSearchViewModel.metadataTagsCacheScopeLabel
        val cachedScanned = mSearchViewModel.metadataTagsCacheScannedImages
        val cachedTagged = mSearchViewModel.metadataTagsCacheTaggedImages

        if (mSearchViewModel.metadataTagsCacheKey == scopeKey &&
            cachedCounts.isNotEmpty() &&
            cachedIdsByTag.isNotEmpty()
        ) {
            showMetadataTagsListDialog(
                shown = cachedCounts.entries.toList(),
                displayByNorm = cachedDisplayByTag,
                scopeLabel = cachedScopeLabel ?: "Cached",
                scanned = cachedScanned,
                taggedImages = cachedTagged,
                totalUniqueTags = cachedCounts.size,
                allowRescan = true
            )
            return
        }

        val statusText = TextView(requireContext()).apply {
            setPadding(48, 24, 48, 0)
            text = "Preparing…"
        }
        val progressBar = ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
        }
        val progressWrap = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 16)
            addView(statusText)
            addView(progressBar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        var job: Job? = null
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Metadata Tags")
            .setView(progressWrap)
            .setNegativeButton("Cancel") { _, _ -> job?.cancel() }
            .setCancelable(false)
            .create()
        dialog.show()

        val appCtx = requireContext().applicationContext
        job = lifecycleScope.launch(Dispatchers.IO) {
            val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.Images.Media.DATA,
            )

            val selection: String?
            val selectionArgs: Array<String>?
            val scopeLabel: String
            if (bucketIds.isEmpty()) {
                selection = null
                selectionArgs = null
                scopeLabel = "All folders"
            } else {
                val placeholders = bucketIds.joinToString(",") { "?" }
                selection = "${MediaStore.Images.Media.BUCKET_ID} IN ($placeholders)"
                selectionArgs = bucketIds.toTypedArray()
                scopeLabel = "${bucketIds.size} folder(s)"
            }

            data class MediaRow(val id: Long, val location: String?)
            val rows = ArrayList<MediaRow>(4096)
            appCtx.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val bucketNameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                val relPathCol = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                val displayNameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val dataCol = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                while (cursor.moveToNext()) {
                    if (!isActive) return@launch
                    val bucketName = cursor.getString(bucketNameCol)
                    if (bucketName == "Screenshots") continue

                    val id = cursor.getLong(idCol)
                    val relPath = if (relPathCol >= 0) cursor.getString(relPathCol) else null
                    val displayName = if (displayNameCol >= 0) cursor.getString(displayNameCol) else null
                    val dataPath = if (dataCol >= 0) cursor.getString(dataCol) else null
                    val location = when {
                        !relPath.isNullOrBlank() && !displayName.isNullOrBlank() -> relPath + displayName
                        !dataPath.isNullOrBlank() -> dataPath
                        !displayName.isNullOrBlank() -> displayName
                        else -> null
                    }
                    rows.add(MediaRow(id = id, location = location))
                }
            }

            val counts = HashMap<String, Long>(1024)
            val casing = HashMap<String, String>(1024)
            val idsByTag = HashMap<String, LongArrayList>(1024)
            val locationById = HashMap<Long, String>(1024)
            var processed = 0
            var taggedImages = 0

            for (row in rows) {
                if (!isActive) return@launch
                processed++

                val imageUri = ContentUris.withAppendedId(uri, row.id)
                val xmp = try {
                    appCtx.contentResolver.openInputStream(imageUri)?.use { input ->
                        val exif = ExifInterface(input)
                        exif.getAttribute(ExifInterface.TAG_XMP)
                    }
                } catch (_: Exception) {
                    null
                }

                if (!xmp.isNullOrBlank()) {
                    val kws = extractXmpTagKeywords(xmp)
                    if (kws.isNotEmpty()) {
                        taggedImages++
                        val unique = HashSet<String>(kws.size)
                        for (kw in kws) {
                            val trimmed = kw.trim()
                            if (trimmed.isEmpty()) continue
                            val norm = trimmed.lowercase(Locale.US)
                            if (!unique.add(norm)) continue
                            casing.putIfAbsent(norm, trimmed)
                            counts[norm] = (counts[norm] ?: 0L) + 1L
                            idsByTag.getOrPut(norm) { LongArrayList() }.add(row.id)
                        }
                        if (!row.location.isNullOrBlank()) {
                            locationById[row.id] = row.location
                        }
                    }
                }

                if (processed % 100 == 0 || processed == rows.size) {
                    val frac = if (rows.isEmpty()) 0.0 else processed.toDouble() / rows.size.toDouble()
                    withContext(Dispatchers.Main) {
                        if (!isAdded) return@withContext
                        statusText.text = "Scanning metadata tags… $processed / ${rows.size} ($scopeLabel)"
                        progressBar.progress = (frac * progressBar.max).toInt().coerceIn(0, progressBar.max)
                    }
                }
            }

            val sorted = counts.entries
                .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })

            val maxToShow = 2000
            val shown = sorted.take(maxToShow)

            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                try {
                    dialog.dismiss()
                } catch (_: Exception) {
                }

                // Cache results so future opens/clicks don't rescan.
                mSearchViewModel.metadataTagsCacheKey = scopeKey
                mSearchViewModel.metadataTagsCacheScopeLabel = scopeLabel
                mSearchViewModel.metadataTagsCacheScannedImages = rows.size
                mSearchViewModel.metadataTagsCacheTaggedImages = taggedImages
                mSearchViewModel.metadataTagCountsByNorm.clear()
                mSearchViewModel.metadataTagDisplayByNorm.clear()
                mSearchViewModel.metadataTagImageIdsByNorm.clear()
                mSearchViewModel.metadataTagImageLocationById.clear()

                for ((k, v) in counts.entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })) {
                    mSearchViewModel.metadataTagCountsByNorm[k] = v
                }
                for ((k, v) in casing) {
                    mSearchViewModel.metadataTagDisplayByNorm[k] = v
                }
                for ((k, list) in idsByTag) {
                    mSearchViewModel.metadataTagImageIdsByNorm[k] = list.toLongArray()
                }
                for ((k, v) in locationById) {
                    mSearchViewModel.metadataTagImageLocationById[k] = v
                }

                showMetadataTagsListDialog(
                    shown = shown,
                    displayByNorm = casing,
                    scopeLabel = scopeLabel,
                    scanned = rows.size,
                    taggedImages = taggedImages,
                    totalUniqueTags = counts.size,
                    allowRescan = true
                )
            }
        }
    }

    private class LongArrayList(initialCapacity: Int = 8) {
        private var arr: LongArray = LongArray(initialCapacity)
        private var size: Int = 0

        fun add(value: Long) {
            if (size == arr.size) arr = arr.copyOf(maxOf(8, arr.size * 2))
            arr[size] = value
            size += 1
        }

        fun toLongArray(): LongArray = arr.copyOf(size)
    }

    private fun buildMetadataTagsScopeKey(bucketIds: Set<String>): String {
        if (bucketIds.isEmpty()) return "ALL"
        return bucketIds.sorted().joinToString(",")
    }

    private fun showMetadataTagsListDialog(
        shown: List<Map.Entry<String, Long>>,
        displayByNorm: Map<String, String>,
        scopeLabel: String,
        scanned: Int,
        taggedImages: Int,
        totalUniqueTags: Int,
        allowRescan: Boolean,
    ) {
        val maxToShow = 2000
        val header = buildString {
            appendLine("Scope: $scopeLabel (excluding Screenshots)")
            appendLine("Images scanned: $scanned")
            appendLine("Images with tags: $taggedImages")
            appendLine("Unique tags: $totalUniqueTags")
            if (totalUniqueTags > maxToShow) appendLine("Showing top $maxToShow tags:")
        }.trimEnd()

        val norms = shown.map { it.key }
        val displayItems = shown.map { (norm, c) ->
            "${displayByNorm[norm] ?: norm} ($c)"
        }

        val listView = android.widget.ListView(requireContext()).apply {
            adapter = android.widget.ArrayAdapter(
                requireContext(),
                android.R.layout.simple_list_item_1,
                displayItems
            )
            setPadding(24, 8, 24, 8)
        }

        val headerView = TextView(requireContext()).apply {
            setPadding(48, 32, 48, 16)
            text = header
        }
        listView.addHeaderView(headerView, null, false)

        val reportText = buildString {
            appendLine(header)
            appendLine()
            for ((norm, c) in shown) {
                append(displayByNorm[norm] ?: norm).append(": ").append(c).appendLine()
            }
        }

        val builder = AlertDialog.Builder(requireContext())
            .setTitle("Metadata Tags")
            .setView(listView)
            .setPositiveButton("Copy") { _, _ ->
                val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Tidy metadata tags", reportText))
                Toast.makeText(requireContext(), "Copied", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)

        if (allowRescan) {
            builder.setNeutralButton("Rescan") { _, _ ->
                mSearchViewModel.clearMetadataTagsCache()
                showTagsDialogInternal()
            }
        }

        val dialog = builder.create()

        listView.setOnItemClickListener { _, _, position, _ ->
            val idx = position - 1
            if (idx !in norms.indices) return@setOnItemClickListener
            val selectedNorm = norms[idx]
            val display = displayByNorm[selectedNorm] ?: selectedNorm
            dialog.dismiss()
            showImagesForMetadataTagFromCacheOrScan(
                tagNorm = selectedNorm,
                tagDisplay = display,
                scopeLabel = scopeLabel
            )
        }

        dialog.show()
    }

    private fun showImagesForMetadataTagFromCacheOrScan(tagNorm: String, tagDisplay: String, scopeLabel: String) {
        val cached = mSearchViewModel.metadataTagImageIdsByNorm[tagNorm]
        if (cached != null) {
            val rv = recyclerView ?: return
            val list = cached.toList()
            mSearchViewModel.searchResults = list
            mSearchViewModel.lastSearchIsImageSearch = false
            mSearchViewModel.showBackToAllImages = true
            mSearchViewModel.lastResultsAreNearDuplicates = false
            mSearchViewModel.lastSearchEmbedding = null
            mSearchViewModel.similaritySortActive = false
            mSearchViewModel.similaritySortBaseResults = null
            mSearchViewModel.clearSelection()
            imageAdapter?.clearSelection()
            setResults(rv, list)
            rv.scrollToPosition(0)
            Toast.makeText(
                requireContext(),
                "Tag \"$tagDisplay\": ${list.size} image(s) ($scopeLabel)",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Fallback: no cache available for some reason.
        startShowImagesForMetadataTag(tagNorm, tagDisplay, scopeLabel)
    }

    private fun startShowImagesForMetadataTag(tagNorm: String, tagDisplay: String, scopeLabel: String) {
        if (!hasReadPermission()) return
        if (mORTImageViewModel.isIndexing.value == true) return

        val progress = ProgressBar(requireContext()).apply {
            isIndeterminate = true
            setPadding(48, 32, 48, 32)
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Metadata Tags")
            .setMessage("Finding images tagged \"$tagDisplay\"…")
            .setView(progress)
            .setNegativeButton("Cancel", null)
            .setCancelable(false)
            .create()
        dialog.show()

        val appCtx = requireContext().applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.BUCKET_ID,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            )

            val bucketIds = mSearchViewModel.getIndexedBucketIds()
            val selection: String?
            val selectionArgs: Array<String>?
            if (bucketIds.isEmpty()) {
                selection = null
                selectionArgs = null
            } else {
                val placeholders = bucketIds.joinToString(",") { "?" }
                selection = "${MediaStore.Images.Media.BUCKET_ID} IN ($placeholders)"
                selectionArgs = bucketIds.toTypedArray()
            }

            val matched = ArrayList<Long>(256)
            appCtx.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val bucketNameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val bucketName = cursor.getString(bucketNameCol)
                    if (bucketName == "Screenshots") continue
                    val id = cursor.getLong(idCol)
                    val imageUri = ContentUris.withAppendedId(uri, id)
                    val xmp = try {
                        appCtx.contentResolver.openInputStream(imageUri)?.use { input ->
                            val exif = ExifInterface(input)
                            exif.getAttribute(ExifInterface.TAG_XMP)
                        }
                    } catch (_: Exception) {
                        null
                    }
                    if (xmp.isNullOrBlank()) continue
                    val kws = extractXmpTagKeywords(xmp)
                    if (kws.any { it.trim().lowercase(Locale.US) == tagNorm }) {
                        matched.add(id)
                    }
                }
            }

            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                try {
                    dialog.dismiss()
                } catch (_: Exception) {
                }

                val rv = recyclerView ?: return@withContext
                mSearchViewModel.searchResults = matched.reversed()
                mSearchViewModel.lastSearchIsImageSearch = false
                mSearchViewModel.showBackToAllImages = true
                mSearchViewModel.lastResultsAreNearDuplicates = false
                mSearchViewModel.lastSearchEmbedding = null
                mSearchViewModel.similaritySortActive = false
                mSearchViewModel.similaritySortBaseResults = null
                mSearchViewModel.clearSelection()
                imageAdapter?.clearSelection()
                setResults(rv, mSearchViewModel.searchResults ?: emptyList())
                rv.scrollToPosition(0)
                Toast.makeText(
                    requireContext(),
                    "Tag \"$tagDisplay\": ${matched.size} image(s) ($scopeLabel)",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private data class StatsReport(
        val scopeLabel: String,
        val totalImages: Long,
        val totalBytes: Long,
        val typeCounts: Map<String, Long>,
        val dbRecords: Long,
        val dbBytes: Long,
        val dbWalBytes: Long,
        val dbShmBytes: Long,
        val indexedInMemory: Int,
    )

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = bytes.toDouble()
        var idx = 0
        while (v >= 1024.0 && idx < units.lastIndex) {
            v /= 1024.0
            idx++
        }
        return String.format(Locale.US, "%.2f %s", v, units[idx])
    }

    private fun normalizeType(mime: String?, displayName: String?): String {
        fun extFromName(name: String): String? {
            val dot = name.lastIndexOf('.')
            if (dot <= 0 || dot >= name.lastIndex) return null
            return name.substring(dot + 1).lowercase(Locale.US)
        }

        val m = mime?.lowercase(Locale.US)
        val ext = displayName?.let(::extFromName)
        return when {
            m == "image/jpeg" || ext == "jpg" || ext == "jpeg" -> "jpeg"
            m == "image/png" || ext == "png" -> "png"
            m == "image/bmp" || ext == "bmp" -> "bmp"
            m == "image/webp" || ext == "webp" -> "webp"
            m == "image/heic" || ext == "heic" -> "heic"
            m == "image/heif" || ext == "heif" -> "heif"
            m == "image/gif" || ext == "gif" -> "gif"
            ext != null -> ext
            m != null -> m.removePrefix("image/")
            else -> "unknown"
        }
    }

    private suspend fun computeStatsReport(ctx: Context): StatsReport {
        val contentResolver = ctx.contentResolver
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DISPLAY_NAME,
        )

        val bucketIds = mSearchViewModel.getIndexedBucketIds()
        val selection: String?
        val selectionArgs: Array<String>?
        val scopeLabel: String
        if (bucketIds.isEmpty()) {
            selection = null
            selectionArgs = null
            scopeLabel = "All folders"
        } else {
            val placeholders = bucketIds.joinToString(",") { "?" }
            selection = "${MediaStore.Images.Media.BUCKET_ID} IN ($placeholders)"
            selectionArgs = bucketIds.toTypedArray()
            scopeLabel = "${bucketIds.size} folder(s)"
        }

        var totalImages = 0L
        var totalBytes = 0L
        val typeCounts = linkedMapOf<String, Long>()

        contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
            val bucketNameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val bucketName = cursor.getString(bucketNameCol)
                if (bucketName == "Screenshots") continue

                totalImages += 1
                val size = cursor.getLong(sizeCol).coerceAtLeast(0L)
                totalBytes += size
                val t = normalizeType(cursor.getString(mimeCol), cursor.getString(nameCol))
                typeCounts[t] = (typeCounts[t] ?: 0L) + 1L
            }
        }

        val db = ImageEmbeddingDatabase.getDatabase(ctx)
        val dbRecords = try {
            db.imageEmbeddingDao().countRecords()
        } catch (_: Exception) {
            -1L
        }

        val dbFile = ctx.getDatabasePath("image_embedding_database")
        val walFile = java.io.File(dbFile.parentFile, dbFile.name + "-wal")
        val shmFile = java.io.File(dbFile.parentFile, dbFile.name + "-shm")
        val dbBytes = if (dbFile.exists()) dbFile.length() else 0L
        val dbWalBytes = if (walFile.exists()) walFile.length() else 0L
        val dbShmBytes = if (shmFile.exists()) shmFile.length() else 0L

        return StatsReport(
            scopeLabel = scopeLabel,
            totalImages = totalImages,
            totalBytes = totalBytes,
            typeCounts = typeCounts.toList().sortedByDescending { it.second }.toMap(),
            dbRecords = dbRecords,
            dbBytes = dbBytes,
            dbWalBytes = dbWalBytes,
            dbShmBytes = dbShmBytes,
            indexedInMemory = mORTImageViewModel.idxList.size,
        )
    }

    private fun formatStatsReport(r: StatsReport): String {
        val types = r.typeCounts.entries.joinToString("\n") { (k, v) -> "  - $k: $v" }
        val dbTotal = r.dbBytes + r.dbWalBytes + r.dbShmBytes
        return buildString {
            appendLine("Scope: ${r.scopeLabel} (excluding Screenshots)")
            appendLine("Images on disk: ${r.totalImages}")
            appendLine("Total size on disk: ${formatBytes(r.totalBytes)} (${r.totalBytes} bytes)")
            appendLine()
            appendLine("File types:")
            appendLine(if (types.isBlank()) "  (none)" else types)
            appendLine()
            appendLine("Database:")
            appendLine("  - records: ${r.dbRecords}")
            appendLine("  - in-memory indexed: ${r.indexedInMemory}")
            appendLine("  - db: ${formatBytes(r.dbBytes)}")
            appendLine("  - wal: ${formatBytes(r.dbWalBytes)}")
            appendLine("  - shm: ${formatBytes(r.dbShmBytes)}")
            appendLine("  - total: ${formatBytes(dbTotal)} (${dbTotal} bytes)")
        }
    }

    private fun showStatsDialog() {
        if (!hasReadPermission()) {
            pendingPermissionAction = PendingPermissionAction.ToolsStats
            permissionsRequest.launch(requiredPermission())
            return
        }
        showStatsDialogInternal()
    }

    private fun showStatsDialogInternal() {
        if (mORTImageViewModel.isIndexing.value == true) {
            Toast.makeText(requireContext(), "Wait for indexing to finish first", Toast.LENGTH_SHORT)
                .show()
            return
        }

        val progress = ProgressBar(requireContext()).apply {
            isIndeterminate = true
            setPadding(48, 32, 48, 32)
        }
        val progressDialog = AlertDialog.Builder(requireContext())
            .setTitle("Stats")
            .setMessage("Calculating…")
            .setView(progress)
            .setNegativeButton("Cancel", null)
            .create()
        progressDialog.show()

        val appCtx = requireContext().applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            val report = runCatching { computeStatsReport(appCtx) }.getOrNull()
            val reportText = if (report == null) "Failed to compute stats." else formatStatsReport(report)

            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                try {
                    progressDialog.dismiss()
                } catch (_: Exception) {
                }

                val textView = TextView(requireContext()).apply {
                    setPadding(48, 32, 48, 16)
                    setTextIsSelectable(true)
                    this.text = reportText
                }
                val scroll = android.widget.ScrollView(requireContext()).apply {
                    addView(textView)
                }

                val actionsRow = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(48, 0, 48, 24)
                }
                val backupButton = Button(requireContext()).apply {
                    this.text = "Backup DB"
                    setOnClickListener { startBackupDatabase(reportText) }
                }
                val restoreButton = Button(requireContext()).apply {
                    this.text = "Restore DB"
                    setOnClickListener { confirmAndStartRestoreDatabase() }
                }
                actionsRow.addView(
                    backupButton,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = 16
                    }
                )
                actionsRow.addView(
                    restoreButton,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                )

                val container = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        scroll,
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            0,
                            1f
                        )
                    )
                    addView(
                        actionsRow,
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                    )
                }
                AlertDialog.Builder(requireContext())
                    .setTitle("Stats")
                    .setView(container)
                    .setPositiveButton("Copy") { _, _ ->
                        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Tidy stats", reportText))
                        Toast.makeText(requireContext(), "Copied", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Close", null)
                    .show()
            }
        }
    }

    private fun startBackupDatabase(statsText: String) {
        if (mORTImageViewModel.isIndexing.value == true) {
            Toast.makeText(requireContext(), "Wait for indexing to finish first", Toast.LENGTH_SHORT)
                .show()
            return
        }
        pendingDbBackupText = statsText
        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date())
        val filename = "tidy_image_embedding_database_$ts.sqlite"
        backupDbLauncher.launch(filename)
    }

    private fun backupDatabaseTo(uri: android.net.Uri, statsText: String) {
        val progress = ProgressBar(requireContext()).apply {
            isIndeterminate = true
            setPadding(48, 32, 48, 32)
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Backup database")
            .setMessage("Writing backup…")
            .setView(progress)
            .setCancelable(false)
            .create()
        dialog.show()

        val appCtx = requireContext().applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val db = ImageEmbeddingDatabase.getDatabase(appCtx)
                // Ensure any WAL changes are flushed into the main db file for a single-file backup.
                runCatching {
                    db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close()
                }

                val dbFile = appCtx.getDatabasePath("image_embedding_database")
                val totalBytes = dbFile.length().coerceAtLeast(0L)
                appCtx.contentResolver.openOutputStream(uri, "w")?.use { os ->
                    java.io.FileInputStream(dbFile).use { input ->
                        input.copyTo(os)
                    }
                } ?: error("Failed to open output")
                totalBytes
            }

            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                try {
                    dialog.dismiss()
                } catch (_: Exception) {
                }
                if (result.isFailure) {
                    Toast.makeText(requireContext(), "Backup failed", Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Tidy stats", statsText))
                Toast.makeText(
                    requireContext(),
                    "Backup saved (${formatBytes(result.getOrThrow())}). Stats copied.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun confirmAndStartRestoreDatabase() {
        if (mORTImageViewModel.isIndexing.value == true) {
            Toast.makeText(requireContext(), "Wait for indexing to finish first", Toast.LENGTH_SHORT)
                .show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Restore database")
            .setMessage("This will replace the current index database on this device. Continue?")
            .setPositiveButton("Choose file") { _, _ ->
                restoreDbLauncher.launch(arrayOf("*/*"))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun restoreDatabaseFrom(uri: android.net.Uri) {
        val progress = ProgressBar(requireContext()).apply {
            isIndeterminate = true
            setPadding(48, 32, 48, 32)
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Restore database")
            .setMessage("Restoring…")
            .setView(progress)
            .setCancelable(false)
            .create()
        dialog.show()

        val appCtx = requireContext().applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching {
                // Close Room before replacing files.
                ImageEmbeddingDatabase.closeDatabase()

                val dbFile = appCtx.getDatabasePath("image_embedding_database")
                val walFile = java.io.File(dbFile.parentFile, dbFile.name + "-wal")
                val shmFile = java.io.File(dbFile.parentFile, dbFile.name + "-shm")

                appCtx.contentResolver.openInputStream(uri)?.use { input ->
                    java.io.FileOutputStream(dbFile, false).use { out ->
                        input.copyTo(out)
                        out.flush()
                    }
                } ?: error("Failed to open input")

                // Drop WAL/SHM so SQLite rebuilds a clean state.
                runCatching { walFile.delete() }
                runCatching { shmFile.delete() }
            }

            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                try {
                    dialog.dismiss()
                } catch (_: Exception) {
                }
                if (result.isFailure) {
                    Toast.makeText(requireContext(), "Restore failed", Toast.LENGTH_SHORT).show()
                    return@withContext
                }

                AlertDialog.Builder(requireContext())
                    .setTitle("Restore complete")
                    .setMessage("Database restored. Restart the app to use the restored index.")
                    .setPositiveButton("Restart now") { _, _ ->
                        val pm = requireContext().packageManager
                        val intent = pm.getLaunchIntentForPackage(requireContext().packageName)
                        if (intent != null) {
                            intent.addFlags(
                                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                            )
                            startActivity(intent)
                        }
                        requireActivity().finishAffinity()
                        Runtime.getRuntime().exit(0)
                    }
                    .setNegativeButton("Later", null)
                    .show()
            }
        }
    }

    private class IntList(initialCapacity: Int = 8) {
        var arr: IntArray = IntArray(initialCapacity)
        var size: Int = 0

        fun add(value: Int) {
            if (size == arr.size) arr = arr.copyOf(maxOf(8, arr.size * 2))
            arr[size] = value
            size += 1
        }
    }

    private fun fastDot(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        var sum = 0.0f
        for (i in 0 until n) sum += a[i] * b[i]
        return sum
    }

    private fun startFindNearDuplicates(threshold: Float) {
        if (mORTImageViewModel.isIndexing.value == true) {
            Toast.makeText(requireContext(), "Wait for indexing to finish first", Toast.LENGTH_SHORT)
                .show()
            return
        }
        if (findDuplicatesJob?.isActive == true) return

        val ids = mORTImageViewModel.idxList
        val embeddings = mORTImageViewModel.embeddingsList
        if (ids.isEmpty() || embeddings.isEmpty() || ids.size != embeddings.size) {
            Toast.makeText(requireContext(), "Index is not ready", Toast.LENGTH_SHORT).show()
            return
        }

        val progress = ProgressBar(requireContext()).apply {
            isIndeterminate = true
            setPadding(48, 32, 48, 32)
        }
        val progressDialog = AlertDialog.Builder(requireContext())
            .setTitle("Finding near-duplicates…")
            .setMessage("Scanning embeddings (threshold ≥ ${String.format(Locale.US, "%.2f", threshold)})")
            .setView(progress)
            .setNegativeButton("Cancel") { _, _ -> findDuplicatesJob?.cancel() }
            .setCancelable(false)
            .create()

        progressDialog.show()

        findDuplicatesJob = lifecycleScope.launch(Dispatchers.Default) {
            val duplicateFlags = BooleanArray(ids.size)
            val dim = embeddings[0].size
            val sigBits = minOf(64, dim)

            val selectedDims = IntArray(sigBits)
            run {
                val used = BooleanArray(dim)
                val rng = java.util.Random(1337L)
                var filled = 0
                while (filled < sigBits) {
                    val idx = rng.nextInt(dim)
                    if (used[idx]) continue
                    used[idx] = true
                    selectedDims[filled] = idx
                    filled += 1
                }
            }

            fun signature(e: FloatArray): Long {
                var sig = 0L
                for (b in 0 until sigBits) {
                    if (e[selectedDims[b]] >= 0f) sig = sig or (1L shl b)
                }
                return sig
            }

            val tableCount = minOf(4, ((sigBits + 15) / 16).coerceAtLeast(1))
            val tables: Array<HashMap<Int, IntList>> = Array(tableCount) { HashMap() }
            val signatures = LongArray(ids.size)
            for (i in ids.indices) {
                if (!isActive) return@launch
                val sig = signature(embeddings[i])
                signatures[i] = sig
                for (t in 0 until tableCount) {
                    val key = ((sig ushr (t * 16)) and 0xFFFF).toInt()
                    val list = tables[t].getOrPut(key) { IntList() }
                    list.add(i)
                }
            }

            val candidateSet = HashSet<Int>(128)
            val edges = HashMap<Int, IntList>()
            fun addEdge(a: Int, b: Int) {
                edges.getOrPut(a) { IntList() }.add(b)
                edges.getOrPut(b) { IntList() }.add(a)
            }
            for (i in ids.indices) {
                if (!isActive) return@launch
                candidateSet.clear()

                val sig = signatures[i]
                for (t in 0 until tableCount) {
                    val key = ((sig ushr (t * 16)) and 0xFFFF).toInt()
                    val list = tables[t][key] ?: continue
                    val arr = list.arr
                    val sz = list.size
                    for (k in 0 until sz) {
                        val j = arr[k]
                        if (j > i) candidateSet.add(j)
                    }
                }

                if (candidateSet.isEmpty()) continue
                val a = embeddings[i]
                for (j in candidateSet) {
                    val b = embeddings[j]
                    if (a.size != b.size) continue
                    if (fastDot(a, b) >= threshold) {
                        duplicateFlags[i] = true
                        duplicateFlags[j] = true
                        addEdge(i, j)
                    }
                }
            }

            class IntStack(initialCapacity: Int = 32) {
                private var arr = IntArray(initialCapacity)
                private var size = 0
                fun push(v: Int) {
                    if (size == arr.size) arr = arr.copyOf(maxOf(32, arr.size * 2))
                    arr[size++] = v
                }
                fun pop(): Int = arr[--size]
                fun isEmpty(): Boolean = size == 0
            }

            fun orderComponentGreedy(component: List<Int>): List<Int> {
                if (component.size <= 2) return component.sorted()
                val remaining = component.toMutableList()
                remaining.sort()
                val ordered = ArrayList<Int>(component.size)
                var current = remaining.removeAt(0)
                ordered.add(current)
                while (remaining.isNotEmpty()) {
                    val a = embeddings[current]
                    var bestIdx = 0
                    var bestScore = -Float.MAX_VALUE
                    for (k in remaining.indices) {
                        val j = remaining[k]
                        val score = fastDot(a, embeddings[j])
                        if (score > bestScore) {
                            bestScore = score
                            bestIdx = k
                        }
                    }
                    current = remaining.removeAt(bestIdx)
                    ordered.add(current)
                }
                return ordered
            }

            val visited = BooleanArray(ids.size)
            val components = ArrayList<ArrayList<Int>>()
            for (i in ids.indices) {
                if (!duplicateFlags[i] || visited[i]) continue
                val comp = ArrayList<Int>()
                val stack = IntStack()
                visited[i] = true
                stack.push(i)
                while (!stack.isEmpty()) {
                    val cur = stack.pop()
                    comp.add(cur)
                    val neigh = edges[cur] ?: continue
                    val arr = neigh.arr
                    val sz = neigh.size
                    for (k in 0 until sz) {
                        val n = arr[k]
                        if (n < 0 || n >= ids.size) continue
                        if (!duplicateFlags[n] || visited[n]) continue
                        visited[n] = true
                        stack.push(n)
                    }
                }
                components.add(comp)
            }

            components.sortWith(
                compareByDescending<ArrayList<Int>> { it.size }
                    .thenBy { it.minOrNull() ?: Int.MAX_VALUE }
            )

            val dupIds = ArrayList<Long>()
            for (comp in components) {
                val orderedIdxs = orderComponentGreedy(comp)
                for (idx in orderedIdxs) {
                    dupIds.add(ids[idx])
                }
            }

            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                progressDialog.dismiss()

                if (dupIds.isEmpty()) {
                    Toast.makeText(requireContext(), "No near-duplicates found", Toast.LENGTH_SHORT)
                        .show()
                    return@withContext
                }

                mSearchViewModel.searchResults = dupIds
                mSearchViewModel.lastSearchIsImageSearch = false
                mSearchViewModel.showBackToAllImages = true
                mSearchViewModel.lastResultsAreNearDuplicates = true
                mSearchViewModel.lastSearchEmbedding = null
                mSearchViewModel.similaritySortActive = false
                mSearchViewModel.similaritySortBaseResults = null
                mSearchViewModel.clearSelection()
                imageAdapter?.clearSelection()

                val rv = recyclerView ?: return@withContext
                setResults(rv, dupIds)
                rv.scrollToPosition(0)
                Toast.makeText(
                    requireContext(),
                    "Found ${dupIds.size} near-duplicates in ${components.size} group(s)",
                    Toast.LENGTH_SHORT
                )
                    .show()
            }
        }
    }

    private fun queryImageBuckets(ctx: android.content.Context): List<Pair<Long, String>> {
        val contentResolver = ctx.contentResolver
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        )

        val bucketMap = linkedMapOf<Long, String>()
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val bucketId = cursor.getLong(idCol)
                val bucketName = cursor.getString(nameCol) ?: "(Unnamed)"
                if (bucketName == "Screenshots") continue
                if (!bucketMap.containsKey(bucketId)) bucketMap[bucketId] = bucketName
            }
        }

        return bucketMap.entries
            .sortedBy { it.value.lowercase(Locale.getDefault()) }
            .map { it.key to it.value }
    }

    private fun setResults(recyclerView: RecyclerView, results: List<Long>) {
        val allowed = results.toHashSet()
        mSearchViewModel.selectedImageIds.retainAll(allowed)
        imageCountText?.text = results.size.toString()

        val isImageSearch = mSearchViewModel.lastSearchIsImageSearch
        val showDimensions =
            (isImageSearch && mSearchViewModel.showImageSearchDimensions) ||
                (mSearchViewModel.lastResultsAreNearDuplicates && mSearchViewModel.showNearDuplicateDimensions)
        imageSimilaritySection?.visibility = if (isImageSearch) View.VISIBLE else View.GONE
        topButtonsRow?.let { row ->
            val bottomPaddingPx = if (isImageSearch) 0 else 30
            row.setPadding(row.paddingLeft, row.paddingTop, row.paddingRight, bottomPaddingPx)
        }
        updateSortInfoButton()
        imageAdapter = ImageAdapter(
            requireContext(),
            results,
            mSearchViewModel.selectedImageIds,
            onSelectionChanged = { updateSelectionUI() },
            showDimensions = showDimensions,
            dimensionsById = mSearchViewModel.imageDimensionsById,
            lowPriorityThumbnails = mSearchViewModel.pendingIndexRefresh
        )
        recyclerView.adapter = imageAdapter
        updateSelectionUI()

        if (showDimensions) {
            loadDimensionsIfNeeded(results)
        }
    }

    private fun updateSortInfoButton() {
        val button = sortBySimilarityButton as? MaterialButton ?: return
        if (mSearchViewModel.lastSearchIsImageSearch) {
            button.setIconResource(R.drawable.ic_info)
            button.contentDescription =
                if (mSearchViewModel.showImageSearchDimensions) "Hide dimensions"
                else "Show dimensions"
        } else if (mSearchViewModel.lastResultsAreNearDuplicates) {
            button.setIconResource(R.drawable.ic_info)
            button.contentDescription =
                if (mSearchViewModel.showNearDuplicateDimensions) "Hide dimensions"
                else "Show dimensions"
        } else {
            button.setIconResource(R.drawable.ic_sort)
            button.contentDescription = "Sort by similarity"
        }
    }

    private fun loadDimensionsIfNeeded(results: List<Long>) {
        val missingIds = results.filter { !mSearchViewModel.imageDimensionsById.containsKey(it) }
        if (missingIds.isEmpty()) return

        val ctx = context ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val dims = queryDimensions(ctx, missingIds)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                mSearchViewModel.imageDimensionsById.putAll(dims)
                imageAdapter?.notifyDataSetChanged()
            }
        }
    }

    private fun queryDimensions(
        ctx: android.content.Context,
        ids: List<Long>
    ): Map<Long, String> {
        val contentResolver = ctx.contentResolver
        val result = linkedMapOf<Long, String>()

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT
        )

        // Query in chunks to avoid overly long SQL "IN (...)" clauses.
        val chunkSize = 500
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        for (chunk in ids.chunked(chunkSize)) {
            val placeholders = chunk.joinToString(",") { "?" }
            val selection = "${MediaStore.Images.Media._ID} IN ($placeholders)"
            val selectionArgs = chunk.map { it.toString() }.toTypedArray()
            contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val wCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
                val hCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val w = cursor.getInt(wCol)
                    val h = cursor.getInt(hCol)
                    if (w > 0 && h > 0) result[id] = "${w}x${h}"
                }
            }
        }
        return result
    }

    private fun updateSelectionUI() {
        val count = mSearchViewModel.selectedImageIds.size
        selectedCountText?.text = "Selected: $count"
        selectionActions?.visibility = if (count > 0) View.VISIBLE else View.GONE
        backToAllImagesButton?.visibility =
            if (mSearchViewModel.lastSearchIsImageSearch || mSearchViewModel.showBackToAllImages) View.VISIBLE else View.GONE
    }

    private fun startDelete(ids: List<Long>) {
        val contentResolver = requireContext().contentResolver
        val uris = ids.map {
            ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it)
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val pendingIntent = MediaStore.createDeleteRequest(contentResolver, uris)
            pendingAction = PendingAction.Delete
            pendingDeleteIds = ids
            intentSenderLauncher.launch(
                IntentSenderRequest.Builder(pendingIntent.intentSender).build()
            )
            return
        }

        val deletedIds = mutableListOf<Long>()
        for ((idx, uri) in uris.withIndex()) {
            try {
                val deleted = contentResolver.delete(uri, null, null)
                if (deleted > 0) deletedIds.add(ids[idx])
            } catch (e: SecurityException) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
                    e is RecoverableSecurityException
                ) {
                    pendingAction = PendingAction.Delete
                    pendingDeleteIds = ids
                    intentSenderLauncher.launch(
                        IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build()
                    )
                    return
                }
            }
        }

        if (deletedIds.isEmpty()) {
            Toast.makeText(requireContext(), "No items were deleted", Toast.LENGTH_SHORT).show()
            return
        }
        onMediaItemsDeleted(deletedIds)
    }

    private fun performMove(ids: List<Long>, relativePath: String?) {
        if (relativePath == null) return
        startMove(ids, relativePath)
    }

    private fun startMove(ids: List<Long>, relativePath: String) {
        val contentResolver = requireContext().contentResolver
        val uris = ids.map {
            ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it)
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val pendingIntent = MediaStore.createWriteRequest(contentResolver, uris)
            pendingAction = PendingAction.MoveWrite
            pendingMoveIds = ids
            pendingMoveRelativePath = relativePath
            intentSenderLauncher.launch(
                IntentSenderRequest.Builder(pendingIntent.intentSender).build()
            )
            return
        }

        applyMove(ids, relativePath)
    }

    private fun applyMove(ids: List<Long>, relativePath: String?) {
        if (relativePath == null) return

        val contentResolver = requireContext().contentResolver
        val uris = ids.map {
            ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it)
        }

        for (uri in uris) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                }
                contentResolver.update(uri, values, null, null)
            } catch (e: SecurityException) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
                    e is RecoverableSecurityException
                ) {
                    pendingAction = PendingAction.MoveWrite
                    pendingMoveIds = ids
                    pendingMoveRelativePath = relativePath
                    intentSenderLauncher.launch(
                        IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build()
                    )
                    return
                }
            }
        }

        Toast.makeText(requireContext(), "Moved ${ids.size} photo(s)", Toast.LENGTH_SHORT).show()
        mSearchViewModel.clearSelection()
        imageAdapter?.clearSelection()
        updateSelectionUI()
    }

    private fun onMediaItemsDeleted(ids: List<Long>) {
        val idSet = ids.toHashSet()
        mORTImageViewModel.removeFromIndex(ids)
        mSearchViewModel.searchResults =
            mSearchViewModel.searchResults?.filterNot { idSet.contains(it) }
        mSearchViewModel.selectedImageIds.removeAll(idSet)
        imageAdapter?.clearSelection()
        updateSelectionUI()

        val recyclerView = view?.findViewById<RecyclerView>(R.id.recycler_view)
        recyclerView?.let {
            setResults(it, mSearchViewModel.searchResults ?: emptyList())
        }
    }

    private fun getRelativePathFromTreeUri(treeUri: android.net.Uri): String? {
        val docId = DocumentsContract.getTreeDocumentId(treeUri) // e.g. "primary:Pictures/Foo"
        val parts = docId.split(':', limit = 2)
        if (parts.size != 2) return null

        val volume = parts[0]
        if (volume != "primary") return null

        val rawPath = parts[1].trim('/')
        if (rawPath.isBlank()) return null

        return if (rawPath.endsWith("/")) rawPath else "$rawPath/"
    }

}
