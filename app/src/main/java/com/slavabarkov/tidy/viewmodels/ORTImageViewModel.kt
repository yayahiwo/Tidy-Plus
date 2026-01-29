/**
 * Copyright 2023 Viacheslav Barkov
 */

package com.slavabarkov.tidy.viewmodels

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import android.app.Application
import android.content.ContentUris
import android.content.ContentResolver
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Size
import androidx.lifecycle.*
import com.slavabarkov.tidy.R
import com.slavabarkov.tidy.TidySettings
import com.slavabarkov.tidy.data.ImageEmbedding
import com.slavabarkov.tidy.data.ImageEmbeddingDatabase
import com.slavabarkov.tidy.data.ImageEmbeddingRepository
import com.slavabarkov.tidy.allocateModelInputBuffer
import com.slavabarkov.tidy.normalizeL2
import com.slavabarkov.tidy.preProcessInto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*

class ORTImageViewModel(application: Application) : AndroidViewModel(application) {
    private var ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var repository: ImageEmbeddingRepository
    private val imageSession = run {
        val resources = getApplication<Application>().resources
        val model = resources.openRawResource(R.raw.visual_quant).readBytes()
        ortEnv.createSession(model)
    }
    var idxList: ArrayList<Long> = arrayListOf()
    var embeddingsList: ArrayList<FloatArray> = arrayListOf()
    var progress: MutableLiveData<Double> = MutableLiveData(0.0)
    var indexedCount: MutableLiveData<Int> = MutableLiveData(0)
    var isIndexing: MutableLiveData<Boolean> = MutableLiveData(false)
    private var indexingJob: Job? = null
    private var indexingRunId: Int = 0

    private companion object {
        private const val DB_ID_BATCH_SIZE = 750
        private const val INSERT_BATCH_SIZE = 100
    }

    init {
        val imageEmbeddingDao = ImageEmbeddingDatabase.getDatabase(application).imageEmbeddingDao()
        repository = ImageEmbeddingRepository(imageEmbeddingDao)
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqSizePx: Int): Int {
        val (height, width) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqSizePx || width > reqSizePx) {
            var halfHeight = height / 2
            var halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqSizePx && (halfWidth / inSampleSize) >= reqSizePx) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }

    private fun decodeSampledBitmap(
        contentResolver: ContentResolver,
        uri: Uri,
        reqSizePx: Int,
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val inSampleSize = calculateInSampleSize(bounds, reqSizePx)
        val decodeOpts = BitmapFactory.Options().apply {
            inJustDecodeBounds = false
            this.inSampleSize = inSampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }

        contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            return BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, decodeOpts)
        }
        return null
    }

    private fun decodeBitmapForEmbedding(
        contentResolver: ContentResolver,
        uri: Uri,
    ): Bitmap? {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                contentResolver.loadThumbnail(uri, Size(224, 224), null)
            } else {
                // Slightly bigger than model input to preserve detail before center-crop+scale.
                decodeSampledBitmap(contentResolver, uri, 512)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun generateIndex() {
        indexingJob?.cancel()
        indexingRunId += 1
        val runId = indexingRunId

        indexingJob = viewModelScope.launch(Dispatchers.Default) {
            fun isLatest(): Boolean = indexingRunId == runId
            fun postIsIndexing(value: Boolean) {
                if (isLatest()) isIndexing.postValue(value)
            }
            fun postProgress(value: Double) {
                if (isLatest()) progress.postValue(value)
            }
            fun postIndexedCount(value: Int) {
                if (isLatest()) indexedCount.postValue(value)
            }

            postIsIndexing(true)
            postProgress(0.0)
            postIndexedCount(0)

            val pm = getApplication<Application>().getSystemService(PowerManager::class.java)
            val localWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Tidy:Indexing").apply {
                setReferenceCounted(false)
                // Keep CPU running if the screen turns off during indexing (safety timeout).
                acquire(2 * 60 * 60 * 1000L)
            }
            val session = imageSession

            val newIdxList = ArrayList<Long>()
            val newEmbeddingsList = ArrayList<FloatArray>()

            val uri: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val inputName = "pixel_values"
            val inputShape = longArrayOf(1, 3, 224, 224)
            val inputMap = HashMap<String, OnnxTensor>(1)

            val workBitmap = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888)
            val workCanvas = Canvas(workBitmap)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            val srcRect = Rect()
            val dstRect = Rect(0, 0, 224, 224)

            val stride = 224 * 224
            val bmpData = IntArray(stride)
            val floats = FloatArray(3 * stride)
            val imgData = allocateModelInputBuffer()

            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media.BUCKET_ID
            )
            val sortOrder = "${MediaStore.Images.Media._ID} ASC"
            val contentResolver: ContentResolver = getApplication<Application>().contentResolver

            val prefs = getApplication<Application>().getSharedPreferences(
                TidySettings.PREFS_NAME,
                android.content.Context.MODE_PRIVATE
            )
            val bucketIds: Set<String> =
                prefs.getStringSet(TidySettings.KEY_INDEXED_BUCKET_IDS, emptySet())?.toSet()
                    ?: emptySet()
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

            val cursor: Cursor? =
                contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
            val totalImages = cursor?.count ?: 0
            val desiredIds: HashSet<Long> = hashSetOf()
            var completedNormally = false
            try {
                cursor?.use {
                    val idColumn: Int = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val dateColumn: Int =
                        it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                    val bucketColumn: Int =
                        it.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)

                    data class MediaRow(val id: Long, val date: Long)

                    suspend fun processBatch(batch: List<MediaRow>) {
                        if (batch.isEmpty()) return
                        if (!isActive || !isLatest()) return

                        val ids = batch.map { row -> row.id }
                        val records = withContext(Dispatchers.IO) {
                            repository.getRecordsByIds(ids)
                        }
                        val recordById = HashMap<Long, ImageEmbedding>(records.size)
                        for (r in records) recordById[r.id] = r

                        val toInsert = ArrayList<ImageEmbedding>(INSERT_BATCH_SIZE)
                        suspend fun flushInserts() {
                            if (toInsert.isEmpty()) return
                            withContext(Dispatchers.IO) {
                                repository.addImageEmbeddings(toInsert)
                            }
                            toInsert.clear()
                        }

                        for (row in batch) {
                            if (!isActive || !isLatest()) return

                            val record = recordById[row.id]
                            if (record != null) {
                                newIdxList.add(record.id)
                                newEmbeddingsList.add(record.embedding)
                                postIndexedCount(newIdxList.size)
                                continue
                            }

                            val imageUri: Uri = ContentUris.withAppendedId(uri, row.id)
                            val decoded = decodeBitmapForEmbedding(contentResolver, imageUri) ?: continue
                            try {
                                val cropSize = minOf(decoded.width, decoded.height)
                                val cropX = (decoded.width - cropSize) / 2
                                val cropY = (decoded.height - cropSize) / 2
                                srcRect.set(cropX, cropY, cropX + cropSize, cropY + cropSize)
                                workCanvas.drawBitmap(decoded, srcRect, dstRect, paint)

                                preProcessInto(workBitmap, bmpData, floats, imgData)
                                val inputTensor = OnnxTensor.createTensor(ortEnv, imgData, inputShape)

                                inputTensor.use {
                                    inputMap[inputName] = inputTensor
                                    val output = session.run(inputMap)
                                    output.use {
                                        @Suppress("UNCHECKED_CAST") val rawOutput =
                                            ((output[0].value) as Array<FloatArray>)[0]
                                        normalizeL2(rawOutput)

                                        toInsert.add(ImageEmbedding(row.id, row.date, rawOutput))
                                        if (toInsert.size >= INSERT_BATCH_SIZE) flushInserts()

                                        newIdxList.add(row.id)
                                        newEmbeddingsList.add(rawOutput)
                                        postIndexedCount(newIdxList.size)
                                    }
                                    inputMap.clear()
                                }
                            } finally {
                                try {
                                    decoded.recycle()
                                } catch (_: Exception) {
                                }
                            }
                        }

                        flushInserts()
                    }

                    val pending = ArrayList<MediaRow>(DB_ID_BATCH_SIZE)
                    while (it.moveToNext()) {
                        if (!isActive || !isLatest()) return@use

                        val id: Long = it.getLong(idColumn)
                        val date: Long = it.getLong(dateColumn)
                        val bucket: String = it.getString(bucketColumn)
                        // Don't add screenshots to image index
                        if (bucket == "Screenshots") {
                            if (totalImages > 0) {
                                postProgress(it.position.toDouble() / totalImages.toDouble())
                            }
                            continue
                        }

                        desiredIds.add(id)
                        pending.add(MediaRow(id = id, date = date))
                        if (pending.size >= DB_ID_BATCH_SIZE) {
                            processBatch(pending)
                            pending.clear()
                        }

                        if (totalImages > 0) {
                            postProgress(it.position.toDouble() / totalImages.toDouble())
                        }
                    }

                    processBatch(pending)
                }

                if (isActive && isLatest()) {
                    completedNormally = true

                    viewModelScope.launch(Dispatchers.IO) purge@{
                        if (!isLatest()) return@purge
                        val existingIds = repository.getAllIds().toHashSet()
                        existingIds.removeAll(desiredIds)
                        if (existingIds.isNotEmpty()) repository.deleteByIds(existingIds.toList())
                    }
                }
            } finally {
                cursor?.close()
                try {
                    if (localWakeLock.isHeld) localWakeLock.release()
                } catch (_: Exception) {
                }

                if (isLatest()) {
                    // Commit partial results even if the job was cancelled (e.g. user tapped X).
                    withContext(NonCancellable + Dispatchers.Main) {
                        idxList = newIdxList
                        embeddingsList = newEmbeddingsList
                    }
                    if (completedNormally) {
                        withContext(NonCancellable) {
                            postProgress(1.0)
                        }
                    }
                    withContext(NonCancellable) {
                        postIsIndexing(false)
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            imageSession.close()
        } catch (_: Exception) {
        }
    }

    fun cancelIndexing(): Job? {
        val job = indexingJob
        job?.cancel()
        return job
    }

    fun removeFromIndex(ids: List<Long>) {
        if (ids.isEmpty()) return

        val idsSet = ids.toHashSet()
        val newIdxList = ArrayList<Long>(idxList.size)
        val newEmbeddingsList = ArrayList<FloatArray>(embeddingsList.size)
        for (i in idxList.indices) {
            val id = idxList[i]
            if (!idsSet.contains(id)) {
                newIdxList.add(id)
                newEmbeddingsList.add(embeddingsList[i])
            }
        }
        idxList = newIdxList
        embeddingsList = newEmbeddingsList

        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteByIds(ids)
        }
    }
}



