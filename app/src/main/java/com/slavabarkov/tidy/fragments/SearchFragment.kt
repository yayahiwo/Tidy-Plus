/**
 * Copyright 2023 Viacheslav Barkov
 */

package com.slavabarkov.tidy.fragments

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ScaleGestureDetector
import android.graphics.Rect
import android.widget.Button
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.slavabarkov.tidy.dot
import com.slavabarkov.tidy.viewmodels.ORTImageViewModel
import com.slavabarkov.tidy.viewmodels.ORTTextViewModel
import com.slavabarkov.tidy.R
import com.slavabarkov.tidy.viewmodels.SearchViewModel
import com.slavabarkov.tidy.adapters.ImageAdapter
import kotlinx.coroutines.Dispatchers
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
    private var indexFoldersButton: Button? = null
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
    private val mORTImageViewModel: ORTImageViewModel by activityViewModels()
    private val mORTTextViewModel: ORTTextViewModel by activityViewModels()
    private val mSearchViewModel: SearchViewModel by activityViewModels()

    private enum class PendingAction {
        Delete,
        MoveWrite,
    }

    private var pendingAction: PendingAction? = null
    private var pendingDeleteIds: List<Long> = emptyList()
    private var pendingMoveIds: List<Long> = emptyList()
    private var pendingMoveRelativePath: String? = null

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

        if (mSearchViewModel.pendingIndexRefresh) {
            reindexProgressContainer?.visibility = View.VISIBLE
            recyclerView.isEnabled = false
            val p = mORTImageViewModel.progress.value ?: 0.0
            val progressPercent: Int = (p * 100).toInt()
            reindexProgressBar?.progress = progressPercent
            reindexProgressText?.text = "Updating image index: ${progressPercent}%"
            reindexCountText?.text = "Indexed photos: ${mORTImageViewModel.indexedCount.value ?: 0}"
        }

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
            reindexProgressText?.text = "Updating image index: ${progressPercent}%"
            if (progress == 1.0) {
                finishReindex()
            }
        }

        mORTImageViewModel.indexedCount.observe(viewLifecycleOwner) { count ->
            if (!mSearchViewModel.pendingIndexRefresh) return@observe
            reindexCountText?.text = "Indexed photos: $count"
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
            mSearchViewModel.lastSearchEmbedding = null
            mSearchViewModel.similaritySortActive = false
            mSearchViewModel.similaritySortBaseResults = null
            mSearchViewModel.clearSelection()
            imageAdapter?.clearSelection()
            setResults(recyclerView, mSearchViewModel.searchResults ?: emptyList())
            recyclerView.scrollToPosition(0)
        }

        sortBySimilarityButton?.setOnClickListener {
            toggleSimilaritySort()
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

        indexFoldersButton = view.findViewById(R.id.indexFoldersButton)
        indexFoldersButton?.setOnClickListener {
            showIndexFoldersDialog()
        }

        searchButton?.setOnClickListener {
            val textEmbedding: FloatArray =
                mORTTextViewModel.getTextEmbedding(searchText?.text.toString())
            mSearchViewModel.sortByCosineDistance(
                textEmbedding,
                mORTImageViewModel.embeddingsList,
                mORTImageViewModel.idxList,
                isImageSearch = false
            )
            setResults(recyclerView, mSearchViewModel.searchResults ?: emptyList())
        }

        clearButton = view.findViewById(R.id.clearButton)
        clearButton?.setOnClickListener{
            searchText?.text = null
            mSearchViewModel.searchResults = mORTImageViewModel.idxList.reversed()
            mSearchViewModel.lastSearchIsImageSearch = false
            mSearchViewModel.lastSearchEmbedding = null
            mSearchViewModel.similaritySortActive = false
            mSearchViewModel.similaritySortBaseResults = null
            setResults(recyclerView, mSearchViewModel.searchResults ?: emptyList())
        }
        return view
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
                    .setPositiveButton("OK") { _, _ ->
                        val selected = buckets
                            .filterIndexed { idx, _ -> checked[idx + 1] }
                            .map { it.first.toString() }
                            .toSet()

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
                        imageAdapter?.clearSelection()
                        updateSelectionUI()
                        startReindex()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun startReindex() {
        val recyclerView = recyclerView ?: return

        val permission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_IMAGES
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val granted =
            ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Toast.makeText(requireContext(), "Grant photo access to re-index", Toast.LENGTH_SHORT)
                .show()
            val args = Bundle().apply { putBoolean("auto_start", true) }
            findNavController().navigate(R.id.action_searchFragment_to_indexFragment, args)
            return
        }

        mSearchViewModel.pendingIndexRefresh = true
        reindexProgressContainer?.visibility = View.VISIBLE
        reindexProgressBar?.progress = 0
        reindexProgressText?.text = "Updating image index: 0%"
        reindexCountText?.text = "Indexed photos: 0"

        recyclerView.isEnabled = false
        mORTImageViewModel.generateIndex()
    }

    private fun finishReindex() {
        val recyclerView = recyclerView ?: return

        mSearchViewModel.pendingIndexRefresh = false
        reindexProgressContainer?.visibility = View.GONE
        recyclerView.isEnabled = true

        mSearchViewModel.lastSearchIsImageSearch = false
        mSearchViewModel.lastSearchEmbedding = null
        mSearchViewModel.searchResults = mORTImageViewModel.idxList.reversed()
        setResults(recyclerView, mSearchViewModel.searchResults ?: emptyList())
        recyclerView.scrollToPosition(0)
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

        val showDimensions = mSearchViewModel.lastSearchIsImageSearch
        imageSimilaritySection?.visibility = if (showDimensions) View.VISIBLE else View.GONE
        topButtonsRow?.let { row ->
            val bottomPaddingPx = if (showDimensions) 0 else 30
            row.setPadding(row.paddingLeft, row.paddingTop, row.paddingRight, bottomPaddingPx)
        }
        imageAdapter = ImageAdapter(
            requireContext(),
            results,
            mSearchViewModel.selectedImageIds,
            onSelectionChanged = { updateSelectionUI() },
            showDimensions = showDimensions,
            dimensionsById = mSearchViewModel.imageDimensionsById
        )
        recyclerView.adapter = imageAdapter
        updateSelectionUI()

        if (showDimensions) {
            loadDimensionsIfNeeded(results)
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
            if (mSearchViewModel.lastSearchIsImageSearch) View.VISIBLE else View.GONE
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
