/**
 * Copyright 2023 Viacheslav Barkov
 */

package com.slavabarkov.tidy.viewmodels

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import android.app.Application
import android.content.ContentResolver
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.PowerManager
import android.provider.MediaStore
import androidx.lifecycle.*
import com.slavabarkov.tidy.R
import com.slavabarkov.tidy.TidySettings
import com.slavabarkov.tidy.centerCrop
import com.slavabarkov.tidy.data.ImageEmbedding
import com.slavabarkov.tidy.data.ImageEmbeddingDatabase
import com.slavabarkov.tidy.data.ImageEmbeddingRepository
import com.slavabarkov.tidy.normalizeL2
import com.slavabarkov.tidy.preProcess
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
    var idxList: ArrayList<Long> = arrayListOf()
    var embeddingsList: ArrayList<FloatArray> = arrayListOf()
    var progress: MutableLiveData<Double> = MutableLiveData(0.0)
    var indexedCount: MutableLiveData<Int> = MutableLiveData(0)
    var isIndexing: MutableLiveData<Boolean> = MutableLiveData(false)
    private var indexingJob: Job? = null
    private var indexingRunId: Int = 0

    init {
        val imageEmbeddingDao = ImageEmbeddingDatabase.getDatabase(application).imageEmbeddingDao()
        repository = ImageEmbeddingRepository(imageEmbeddingDao)
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

            val modelID = R.raw.visual_quant
            val resources = getApplication<Application>().resources
            val model = resources.openRawResource(modelID).readBytes()
            val session = ortEnv.createSession(model)

            val newIdxList = ArrayList<Long>()
            val newEmbeddingsList = ArrayList<FloatArray>()

            val uri: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
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
                    while (it.moveToNext()) {
                        if (!isActive || !isLatest()) return@use

                        val id: Long = it.getLong(idColumn)
                        val date: Long = it.getLong(dateColumn)
                        val bucket: String = it.getString(bucketColumn)
                        // Don't add screenshots to image index
                        if (bucket == "Screenshots") continue
                        desiredIds.add(id)
                        val record = repository.getRecord(id)
                        if (record != null) {
                            newIdxList.add(record.id)
                            newEmbeddingsList.add(record.embedding)
                            postIndexedCount(newIdxList.size)
                        } else {
                            val imageUri: Uri = Uri.withAppendedPath(uri, id.toString())
                            val inputStream = contentResolver.openInputStream(imageUri)
                            val bytes = inputStream?.readBytes()
                            inputStream?.close()

                            // Can fail to create the image decoder if its not implemented for the image type
                            val bitmap: Bitmap? =
                                BitmapFactory.decodeByteArray(bytes, 0, bytes?.size ?: 0)
                            bitmap?.let {
                                val rawBitmap = centerCrop(bitmap, 224)
                                val inputShape = longArrayOf(1, 3, 224, 224)
                                val inputName = "pixel_values"
                                val imgData = preProcess(rawBitmap)
                                val inputTensor = OnnxTensor.createTensor(ortEnv, imgData, inputShape)

                                inputTensor.use {
                                    val output =
                                        session.run(Collections.singletonMap(inputName, inputTensor))
                                    output.use {
                                        @Suppress("UNCHECKED_CAST") var rawOutput =
                                            ((output[0].value) as Array<FloatArray>)[0]
                                        rawOutput = normalizeL2(rawOutput)
                                        repository.addImageEmbedding(
                                            ImageEmbedding(
                                                id, date, rawOutput
                                            )
                                        )
                                        newIdxList.add(id)
                                        newEmbeddingsList.add(rawOutput)
                                        postIndexedCount(newIdxList.size)
                                    }
                                }
                            }
                        }
                        // Record created/loaded, update progress
                        if (totalImages > 0) {
                            postProgress(it.position.toDouble() / totalImages.toDouble())
                        }
                    }
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
                session.close()
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



