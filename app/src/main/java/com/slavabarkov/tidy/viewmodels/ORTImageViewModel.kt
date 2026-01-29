/**
 * Copyright 2023 Viacheslav Barkov
 */

package com.slavabarkov.tidy.viewmodels

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.Application
import android.content.ContentValues
import android.content.ContentUris
import android.content.ContentResolver
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.os.SystemClock
import android.util.Log
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
import java.io.File
import java.util.*

class ORTImageViewModel(application: Application) : AndroidViewModel(application) {
    private var ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var repository: ImageEmbeddingRepository
    private val visualModelBytes: ByteArray = run {
        val resources = getApplication<Application>().resources
        resources.openRawResource(R.raw.visual_quant).readBytes()
    }
    private val cpuSession: OrtSession = ortEnv.createSession(visualModelBytes)
    @Volatile private var qnnSession: OrtSession? = null
    @Volatile private var qnnAttempted: Boolean = false
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
        private const val PERF_LOG_TAG = "TidyPerf"
        private const val PERF_LOG_EVERY_INDEXED = 1000
    }

    private enum class VisualExecution {
        CPU,
        QNN,
    }

    private data class SessionSelection(
        val effective: VisualExecution,
        val note: String? = null,
    )

    private class IndexPerf {
        var runId: Int = 0
        var startNs: Long = 0L
        var endNs: Long = 0L

        var cursorQueryNs: Long = 0L
        var cursorIterNs: Long = 0L

        var dbGetRecordsNs: Long = 0L
        var dbInsertNs: Long = 0L

        var decodeNs: Long = 0L
        var cropDrawNs: Long = 0L
        var preprocessNs: Long = 0L
        var tensorCreateNs: Long = 0L
        var inferenceNs: Long = 0L
        var normalizeNs: Long = 0L

        var totalImagesInCursor: Int = 0
        var cursorRowsSeen: Int = 0
        var screenshotsSkipped: Int = 0
        var desiredIdsCount: Int = 0

        var dbHits: Int = 0
        var embedded: Int = 0
        var decodeFailed: Int = 0

        var lastLoggedIndexed: Int = 0

        fun nowNs(): Long = SystemClock.elapsedRealtimeNanos()

        fun format(label: String, indexedCount: Int, session: SessionSelection): String {
            val end = endNs.takeIf { it != 0L } ?: nowNs()
            val wallNs = (if (startNs != 0L) end - startNs else 0L).coerceAtLeast(0L)
            val measuredNs = (
                dbGetRecordsNs + dbInsertNs +
                    decodeNs + cropDrawNs + preprocessNs +
                    tensorCreateNs + inferenceNs + normalizeNs
                ).coerceAtLeast(1L)

            fun ms(ns: Long): String = String.format(Locale.US, "%.1f", ns / 1_000_000.0)
            fun sec(ns: Long): String = String.format(Locale.US, "%.2f", ns / 1_000_000_000.0)
            fun pct(ns: Long): String = String.format(Locale.US, "%.1f", (ns * 100.0) / measuredNs)
            fun avgMs(ns: Long, n: Int): String =
                if (n <= 0) "n/a" else String.format(Locale.US, "%.3f", (ns / 1_000_000.0) / n)

            return buildString {
                append("index_perf ")
                append(label)
                append(" runId=").append(runId)
                append(" model=").append(android.os.Build.MODEL)
                append(" ep=").append(session.effective.name)
                if (!session.note.isNullOrBlank()) append(" epNote=").append(session.note)
                append(" indexed=").append(indexedCount)
                append(" embedded=").append(embedded)
                append(" dbHits=").append(dbHits)
                append(" decodeFailed=").append(decodeFailed)
                append(" cursorRowsSeen=").append(cursorRowsSeen)
                append(" screenshotsSkipped=").append(screenshotsSkipped)
                append(" desiredIds=").append(desiredIdsCount)
                append(" wallSec=").append(sec(wallNs))
                append(" measuredMs=").append(ms(measuredNs))
                append(" | decodeMs=").append(ms(decodeNs)).append(" (").append(pct(decodeNs)).append("%)")
                append(" cropMs=").append(ms(cropDrawNs)).append(" (").append(pct(cropDrawNs)).append("%)")
                append(" preprocessMs=").append(ms(preprocessNs)).append(" (").append(pct(preprocessNs)).append("%)")
                append(" tensorMs=").append(ms(tensorCreateNs)).append(" (").append(pct(tensorCreateNs)).append("%)")
                append(" inferMs=").append(ms(inferenceNs)).append(" (").append(pct(inferenceNs)).append("%)")
                append(" normMs=").append(ms(normalizeNs)).append(" (").append(pct(normalizeNs)).append("%)")
                append(" dbGetMs=").append(ms(dbGetRecordsNs)).append(" (").append(pct(dbGetRecordsNs)).append("%)")
                append(" dbInsMs=").append(ms(dbInsertNs)).append(" (").append(pct(dbInsertNs)).append("%)")
                append(" | avgNewImageMs:")
                append(" decode=").append(avgMs(decodeNs, embedded))
                append(" preprocess=").append(avgMs(preprocessNs, embedded))
                append(" infer=").append(avgMs(inferenceNs, embedded))
                append(" totalMeasured=").append(avgMs(measuredNs, embedded))
            }
        }

        fun log(label: String, indexedCount: Int, session: SessionSelection) {
            Log.i(PERF_LOG_TAG, format(label, indexedCount, session))
        }
    }

    private fun buildPerfFilename(perf: IndexPerf, label: String, session: SessionSelection): String {
        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "tidy_index_perf_${android.os.Build.MODEL}_${session.effective.name}_${label}_run${perf.runId}_$ts.txt"
    }

    private suspend fun writePerfToDownloads(
        perf: IndexPerf,
        label: String,
        session: SessionSelection,
        text: String,
    ): Uri? {
        val ctx = getApplication<Application>()
        val resolver = ctx.contentResolver
        val filename = buildPerfFilename(perf, label, session)

        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
                resolver.openOutputStream(uri)?.use { os ->
                    os.write(text.toByteArray(Charsets.UTF_8))
                    os.flush()
                }
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
                uri
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = File(dir, filename)
                file.writeText(text, Charsets.UTF_8)
                Uri.fromFile(file)
            }
        } catch (e: Exception) {
            Log.w(PERF_LOG_TAG, "Failed to write perf report to Downloads", e)
            null
        }
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

    @Synchronized
    private fun getOrCreateQnnSessionOrNull(): OrtSession? {
        if (qnnAttempted) return qnnSession
        qnnAttempted = true

        // We intentionally use reflection so the app continues to work with the default
        // onnxruntime-android artifact. If built with onnxruntime-android-qnn, SessionOptions
        // exposes addQnn(Map<String,String>) and this will create a QNN session.
        return try {
            val opts = OrtSession.SessionOptions()
            val addQnn = opts.javaClass.methods.firstOrNull { m ->
                m.name == "addQnn" &&
                    m.parameterTypes.size == 1 &&
                    Map::class.java.isAssignableFrom(m.parameterTypes[0])
            } ?: run {
                opts.close()
                null
            }

            if (addQnn == null) return null

            val qnnOptions = HashMap<String, String>()
            // Target Hexagon HTP backend when available.
            qnnOptions["backend_type"] = "htp"
            addQnn.invoke(opts, qnnOptions)

            val session = ortEnv.createSession(visualModelBytes, opts)
            opts.close()
            qnnSession = session
            Log.i(PERF_LOG_TAG, "QNN session created")
            session
        } catch (t: Throwable) {
            Log.w(PERF_LOG_TAG, "QNN session unavailable; using CPU", t)
            null
        }
    }

    private fun selectSession(): Pair<OrtSession, SessionSelection> {
        val qnn = getOrCreateQnnSessionOrNull()
        if (qnn != null) return qnn to SessionSelection(effective = VisualExecution.QNN)
        val note = if (qnnAttempted) "qnn_unavailable" else "qnn_not_attempted"
        return cpuSession to SessionSelection(effective = VisualExecution.CPU, note = note)
    }

    fun generateIndex() {
        indexingJob?.cancel()
        indexingRunId += 1
        val runId = indexingRunId

        indexingJob = viewModelScope.launch(Dispatchers.Default) {
            val perf = IndexPerf().apply {
                this.runId = runId
                this.startNs = nowNs()
            }
            val (session, sessionSelection) = selectSession()

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

            val queryStart = perf.nowNs()
            val cursor: Cursor? =
                contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
            perf.cursorQueryNs += (perf.nowNs() - queryStart)
            val totalImages = cursor?.count ?: 0
            perf.totalImagesInCursor = totalImages
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
                        val dbGetStart = perf.nowNs()
                        val records = withContext(Dispatchers.IO) { repository.getRecordsByIds(ids) }
                        perf.dbGetRecordsNs += (perf.nowNs() - dbGetStart)
                        val recordById = HashMap<Long, ImageEmbedding>(records.size)
                        for (r in records) recordById[r.id] = r

                        val toInsert = ArrayList<ImageEmbedding>(INSERT_BATCH_SIZE)
                        suspend fun flushInserts() {
                            if (toInsert.isEmpty()) return
                            val insStart = perf.nowNs()
                            withContext(Dispatchers.IO) { repository.addImageEmbeddings(toInsert) }
                            perf.dbInsertNs += (perf.nowNs() - insStart)
                            toInsert.clear()
                        }

                        for (row in batch) {
                            if (!isActive || !isLatest()) return

                            val record = recordById[row.id]
                            if (record != null) {
                                perf.dbHits += 1
                                newIdxList.add(record.id)
                                newEmbeddingsList.add(record.embedding)
                                postIndexedCount(newIdxList.size)
                                continue
                            }

                            val imageUri: Uri = ContentUris.withAppendedId(uri, row.id)
                            val decodeStart = perf.nowNs()
                            val decoded = decodeBitmapForEmbedding(contentResolver, imageUri)
                            perf.decodeNs += (perf.nowNs() - decodeStart)
                            if (decoded == null) {
                                perf.decodeFailed += 1
                                continue
                            }
                            try {
                                val cropSize = minOf(decoded.width, decoded.height)
                                val cropX = (decoded.width - cropSize) / 2
                                val cropY = (decoded.height - cropSize) / 2
                                srcRect.set(cropX, cropY, cropX + cropSize, cropY + cropSize)
                                val cropStart = perf.nowNs()
                                workCanvas.drawBitmap(decoded, srcRect, dstRect, paint)
                                perf.cropDrawNs += (perf.nowNs() - cropStart)

                                val preStart = perf.nowNs()
                                preProcessInto(workBitmap, bmpData, floats, imgData)
                                perf.preprocessNs += (perf.nowNs() - preStart)

                                val tensorStart = perf.nowNs()
                                val inputTensor = OnnxTensor.createTensor(ortEnv, imgData, inputShape)
                                perf.tensorCreateNs += (perf.nowNs() - tensorStart)

                                inputTensor.use {
                                    inputMap[inputName] = inputTensor
                                    val inferStart = perf.nowNs()
                                    val output = session.run(inputMap)
                                    perf.inferenceNs += (perf.nowNs() - inferStart)
                                    output.use {
                                        @Suppress("UNCHECKED_CAST") val rawOutput =
                                            ((output[0].value) as Array<FloatArray>)[0]
                                        val normStart = perf.nowNs()
                                        normalizeL2(rawOutput)
                                        perf.normalizeNs += (perf.nowNs() - normStart)

                                        toInsert.add(ImageEmbedding(row.id, row.date, rawOutput))
                                        if (toInsert.size >= INSERT_BATCH_SIZE) flushInserts()

                                        perf.embedded += 1
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

                            val indexed = newIdxList.size
                            if (indexed - perf.lastLoggedIndexed >= PERF_LOG_EVERY_INDEXED) {
                                perf.lastLoggedIndexed = indexed
                                perf.log(label = "progress", indexedCount = indexed, session = sessionSelection)
                            }
                        }

                        flushInserts()
                    }

                    val pending = ArrayList<MediaRow>(DB_ID_BATCH_SIZE)
                    val iterStart = perf.nowNs()
                    while (it.moveToNext()) {
                        if (!isActive || !isLatest()) return@use
                        perf.cursorRowsSeen += 1

                        val id: Long = it.getLong(idColumn)
                        val date: Long = it.getLong(dateColumn)
                        val bucket: String = it.getString(bucketColumn)
                        // Don't add screenshots to image index
                        if (bucket == "Screenshots") {
                            perf.screenshotsSkipped += 1
                            if (totalImages > 0) {
                                postProgress(it.position.toDouble() / totalImages.toDouble())
                            }
                            continue
                        }

                        desiredIds.add(id)
                        perf.desiredIdsCount += 1
                        pending.add(MediaRow(id = id, date = date))
                        if (pending.size >= DB_ID_BATCH_SIZE) {
                            processBatch(pending)
                            pending.clear()
                        }

                        if (totalImages > 0) {
                            postProgress(it.position.toDouble() / totalImages.toDouble())
                        }
                    }
                    perf.cursorIterNs += (perf.nowNs() - iterStart)

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
                    perf.endNs = perf.nowNs()
                    val label = if (completedNormally) "done" else "cancelled_or_partial"
                    val summary = perf.format(label = label, indexedCount = newIdxList.size, session = sessionSelection)
                    perf.log(label = label, indexedCount = newIdxList.size, session = sessionSelection)
                    withContext(NonCancellable + Dispatchers.IO) {
                        val reportUri = writePerfToDownloads(
                            perf,
                            label = label,
                            session = sessionSelection,
                            text = summary + "\n"
                        )
                        if (reportUri != null) {
                            Log.i(PERF_LOG_TAG, "Wrote perf report to Downloads: $reportUri")
                        }
                    }
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
            cpuSession.close()
        } catch (_: Exception) {
        }
        try {
            qnnSession?.close()
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



