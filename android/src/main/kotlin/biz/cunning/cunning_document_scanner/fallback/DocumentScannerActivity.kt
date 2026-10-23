package biz.cunning.cunning_document_scanner.fallback

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import biz.cunning.cunning_document_scanner.R
import biz.cunning.cunning_document_scanner.fallback.constants.DefaultSetting
import biz.cunning.cunning_document_scanner.fallback.constants.DocumentScannerExtra
import biz.cunning.cunning_document_scanner.fallback.extensions.onClick
import biz.cunning.cunning_document_scanner.fallback.extensions.saveToFile
import biz.cunning.cunning_document_scanner.fallback.extensions.screenHeight
import biz.cunning.cunning_document_scanner.fallback.extensions.screenWidth
import biz.cunning.cunning_document_scanner.fallback.models.Document
import biz.cunning.cunning_document_scanner.fallback.models.Point
import biz.cunning.cunning_document_scanner.fallback.models.Quad
import biz.cunning.cunning_document_scanner.fallback.ui.ImageCropView
import biz.cunning.cunning_document_scanner.fallback.utils.CameraUtil
import biz.cunning.cunning_document_scanner.fallback.utils.FileUtil
import biz.cunning.cunning_document_scanner.fallback.utils.ImageUtil
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.min

/** Camera fallback with a bounded preview and serial background image processing. */
class DocumentScannerActivity : AppCompatActivity() {
    private var maxNumDocuments = DefaultSetting.MAX_NUM_DOCUMENTS
    private var croppedImageQuality = DefaultSetting.CROPPED_IMAGE_QUALITY
    private lateinit var session: ScanSession
    private var displayedDocument: Document? = null
    private var displayedBitmap: Bitmap? = null
    private var busy = true
    private var resultDelivered = false

    private lateinit var imageView: ImageCropView
    private lateinit var newPhotoButton: ImageButton
    private lateinit var completeDocumentScanButton: ImageButton
    private lateinit var retakePhotoButton: ImageButton
    private lateinit var loadingIndicator: ProgressBar

    private val cameraUtil = CameraUtil(
        this,
        onPhotoCaptureSuccess = { path ->
            session.pendingCameraPath = null
            session.loadPhoto(path)
        },
        onCancelPhoto = {
            session.pendingCameraPath = null
            if (session.documents.isEmpty()) {
                onClickCancel()
            } else {
                val previous = session.documents.removeAt(session.documents.lastIndex)
                session.loadPhoto(previous.originalPhotoFilePath, previous)
            }
        },
        onPhotoFileCreated = { file ->
            session.ownedFiles.add(file)
            session.pendingCameraPath = file.absolutePath
        },
        onPhotoCaptureError = { message ->
            session.pendingCameraPath = null
            session.fail(message)
        }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_crop)
        imageView = findViewById(R.id.image_view)
        newPhotoButton = findViewById(R.id.new_photo_button)
        completeDocumentScanButton = findViewById(R.id.complete_document_scan_button)
        retakePhotoButton = findViewById(R.id.retake_photo_button)
        loadingIndicator = findViewById(android.R.id.progress)
        session = ViewModelProvider(this)[ScanSession::class.java]
        newPhotoButton.onClick { onClickNew() }
        completeDocumentScanButton.onClick { onClickDone() }
        retakePhotoButton.onClick { onClickRetake() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { onClickCancel() }
        })
        session.updates.observe(this) { renderSession() }
        setBusy(true)

        try {
            intent.extras?.get(DocumentScannerExtra.EXTRA_MAX_NUM_DOCUMENTS)?.let {
                require(it is Int && it > 0) { "Maximum number of documents must be positive" }
                maxNumDocuments = it
            }
            intent.extras?.get(DocumentScannerExtra.EXTRA_CROPPED_IMAGE_QUALITY)?.let {
                require(it is Int && it in 0..100) { "Image quality must be between 0 and 100" }
                croppedImageQuality = it
            }
            val needsInitialization = !session.initialized
            if (needsInitialization) {
                session.initialize(savedInstanceState?.getBundle("scanner_session"), savedInstanceState != null)
            }
            // Restore before ON_START lets the Activity Result registry deliver a pending capture
            // to this new instance using its original file, without launching a second camera.
            cameraUtil.restorePendingPhoto(session.pendingCameraPath)
            if (needsInitialization) {
                if (savedInstanceState == null) openCamera() else session.resumeRestoredWork(croppedImageQuality)
            }
            renderSession()
        } catch (exception: Exception) {
            session.fail("Unable to restore scanner: ${exception.localizedMessage}")
        }
    }

    private fun renderSession() {
        if (resultDelivered || isFinishing || isDestroyed) return
        if (session.cancelled) {
            resultDelivered = true
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        session.error?.let { message ->
            resultDelivered = true
            setResult(Activity.RESULT_OK, Intent().putExtra("error", message))
            finish()
            return
        }
        session.outputFiles?.let { files ->
            if (files.isEmpty() || files.any { !it.isFile || it.length() == 0L }) {
                session.fail("Completed scan files are unavailable")
                return
            }
            val paths = ArrayList(files.map { Uri.fromFile(it).toString() })
            resultDelivered = true
            setResult(Activity.RESULT_OK, Intent().putStringArrayListExtra("croppedImageResults", paths))
            session.keepOutputs(files)
            finish()
            return
        }
        val photo = session.previewBitmap
        val current = session.document
        try {
            if (photo != null && current != null) {
                if (displayedBitmap !== photo || displayedDocument !== current) showPreview(photo, current)
            } else {
                imageView.clearImage()
                displayedBitmap = null
                displayedDocument = null
            }
            setBusy(session.pendingCameraPath != null || session.loadingPath != null || session.exporting || current == null)
        } catch (exception: Exception) {
            session.fail("Unable to show photo: ${exception.localizedMessage}")
        }
    }

    private fun showPreview(photo: Bitmap, current: Document) {
        imageView.setImagePreviewBounds(photo, screenWidth, screenHeight)
        imageView.setImage(photo)
        imageView.setCropper(current.corners.mapOriginalToPreviewImageCoordinates(
            imageView.imagePreviewBounds, imageView.imagePreviewBounds.height() / current.originalPhotoHeight
        ))
        displayedBitmap = photo
        displayedDocument = current
    }

    private fun rememberCurrentCorners() {
        val current = session.document ?: return
        if (displayedDocument !== current) return
        current.corners = imageView.corners.mapPreviewToOriginalImageCoordinates(
            imageView.imagePreviewBounds, imageView.imagePreviewBounds.height() / current.originalPhotoHeight
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        rememberCurrentCorners()
        outState.putBundle("scanner_session", session.saveState())
        super.onSaveInstanceState(outState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        rememberCurrentCorners()
        super.onConfigurationChanged(newConfig)
        val photo = session.previewBitmap ?: return
        val current = session.document ?: return
        showPreview(photo, current)
        imageView.invalidate()
    }

    private fun setBusy(value: Boolean) {
        busy = value
        val canEdit = !value && session.document != null
        imageView.isEnabled = canEdit
        completeDocumentScanButton.isEnabled = canEdit
        retakePhotoButton.isEnabled = canEdit
        newPhotoButton.isEnabled = canEdit && session.documents.size < maxNumDocuments - 1
        newPhotoButton.visibility = if (session.documents.size < maxNumDocuments - 1) View.VISIBLE else View.INVISIBLE
        loadingIndicator.visibility = if (value) View.VISIBLE else View.GONE
    }

    private fun openCamera() {
        session.document = null
        setBusy(true)
        try {
            cameraUtil.openCamera(session.documents.size, session.scanID)
        } catch (exception: Exception) {
            session.fail("Error opening camera: ${exception.localizedMessage}")
        }
    }

    private fun addCurrentDocument() {
        rememberCurrentCorners()
        session.document?.let { session.documents.add(it) }
        session.document = null
    }

    private fun onClickNew() {
        if (busy || resultDelivered || session.documents.size >= maxNumDocuments - 1) return
        addCurrentDocument()
        openCamera()
    }

    private fun onClickDone() {
        if (busy || resultDelivered) return
        try {
            addCurrentDocument()
            require(session.documents.isNotEmpty()) { "No document available to crop" }
            session.exportImages(croppedImageQuality)
        } catch (exception: Exception) {
            session.fail("Unable to prepare crop: ${exception.localizedMessage}")
        }
    }

    private fun onClickRetake() {
        if (busy || resultDelivered) return
        session.discardCurrentPhoto()
        openCamera()
    }

    private fun onClickCancel() {
        if (resultDelivered || isDestroyed) return
        session.cancel()
        renderSession()
    }

    override fun onDestroy() {
        imageView.clearImage()
        displayedBitmap = null
        displayedDocument = null
        if (isFinishing || resultDelivered) session.finishRequest()
        // Configuration changes retain the session. Other nonterminal destruction stops its
        // worker but leaves files available for the saved-state session that replaces it.
        super.onDestroy()
    }

    class ScanSession(application: Application) : AndroidViewModel(application) {
        val updates = MutableLiveData<Unit>()
        val documents = mutableListOf<Document>()
        val ownedFiles = Collections.synchronizedSet(mutableSetOf<File>())
        var document: Document? = null
        var pendingCameraPath: String? = null
        var previewBitmap: Bitmap? = null
            private set
        var loadingPath: String? = null
            private set
        var exporting = false
            private set
        var outputFiles: List<File>? = null
            private set
        var error: String? = null
            private set
        var cancelled = false
            private set
        var initialized = false
            private set
        var scanID = UUID.randomUUID().toString()
            private set
        private var loadingDocument: Document? = null
        private var transferredPaths = emptySet<String>()
        private val imageExecutor = Executors.newSingleThreadExecutor()
        private val mainHandler = Handler(Looper.getMainLooper())
        @Volatile private var closed = false

        fun initialize(state: Bundle?, restoring: Boolean) {
            initialized = true
            if (!restoring) return
            requireNotNull(state) { "Saved scan state is unavailable. Please restart scanning" }
            scanID = UUID.fromString(requireNotNull(state.getString("id"))).toString()
            val pages = state.getBundle("pages") ?: Bundle()
            for (i in 0 until pages.getInt("count")) {
                documents.add(Document.fromBundle(requireNotNull(pages.getBundle(i.toString()))))
            }
            document = state.getBundle("current")?.let { Document.fromBundle(it) }
            pendingCameraPath = state.getString("camera")
            loadingPath = state.getString("loading_path")
            loadingDocument = state.getBundle("loading_document")?.let { Document.fromBundle(it) }
            exporting = state.getBoolean("exporting")
            outputFiles = state.getStringArrayList("outputs")?.map { File(it) }
            error = state.getString("error")
            cancelled = state.getBoolean("cancelled")
        }

        fun saveState(): Bundle = Bundle().apply {
            putString("id", scanID)
            putBundle("pages", Bundle().apply {
                putInt("count", documents.size)
                documents.forEachIndexed { i, page -> putBundle(i.toString(), page.toBundle()) }
            })
            putBundle("current", document?.toBundle())
            putString("camera", pendingCameraPath)
            putString("loading_path", loadingPath)
            putBundle("loading_document", loadingDocument?.toBundle())
            putBoolean("exporting", exporting)
            outputFiles?.let { putStringArrayList("outputs", ArrayList(it.map { file -> file.absolutePath })) }
            putString("error", error)
            putBoolean("cancelled", cancelled)
        }

        fun resumeRestoredWork(quality: Int) {
            // A process can die after saving state while the worker continues writing. The
            // session prefix finds those later files without touching another scan's output.
            val protectedPaths = (documents.map { it.originalPhotoFilePath } + listOfNotNull(
                document?.originalPhotoFilePath, pendingCameraPath, loadingPath
            ) + outputFiles.orEmpty().map { it.absolutePath }).toSet()
            imageExecutor.execute {
                if (closed) return@execute
                FileUtil().getScanFiles(getApplication<Application>(), scanID)
                    .filter { it.absolutePath !in protectedPaths }
                    .forEach { if (!closed) it.delete() }
            }
            when {
                error != null || cancelled || outputFiles != null -> updates.value = Unit
                exporting -> {
                    exporting = false
                    exportImages(quality)
                }
                pendingCameraPath != null -> Unit
                loadingPath != null -> loadPhoto(requireNotNull(loadingPath), loadingDocument)
                document != null -> {
                    val current = requireNotNull(document)
                    loadPhoto(current.originalPhotoFilePath, current)
                }
                else -> fail("Saved scan is incomplete. Please restart scanning")
            }
        }

        fun loadPhoto(path: String, savedDocument: Document? = null) {
            if (closed) return
            loadingPath = path
            loadingDocument = savedDocument
            document = null
            previewBitmap = null
            updates.value = Unit
            imageExecutor.execute {
                if (closed) return@execute
                val photo = try {
                    ImageUtil().getImageFromFilePath(path) ?: throw Exception("Document bitmap is null")
                } catch (exception: Exception) {
                    mainHandler.post { fail("Unable to load photo: ${exception.localizedMessage}") }
                    return@execute
                }
                mainHandler.post {
                    if (closed) {
                        photo.recycle()
                    } else {
                        val inset = min(100.0, min(photo.width, photo.height) / 10.0)
                        val corners = savedDocument?.corners ?: Quad(
                            Point(inset, inset), Point(photo.width - inset, inset),
                            Point(photo.width - inset, photo.height - inset), Point(inset, photo.height - inset)
                        )
                        document = Document(path, photo.width, photo.height, corners)
                        previewBitmap = photo
                        loadingPath = null
                        loadingDocument = null
                        updates.value = Unit
                    }
                }
            }
        }

        fun discardCurrentPhoto() {
            val current = document ?: return
            imageExecutor.execute { if (!closed) File(current.originalPhotoFilePath).delete() }
            document = null
        }

        fun exportImages(quality: Int) {
            if (closed || exporting || outputFiles != null) return
            val pages = documents.toList()
            exporting = true
            previewBitmap = null
            updates.value = Unit
            imageExecutor.execute {
                val outputs = mutableListOf<File>()
                try {
                    for ((pageNumber, page) in pages.withIndex()) {
                        if (closed) return@execute
                        val image = ImageUtil().crop(
                            page.originalPhotoFilePath, page.corners,
                            page.originalPhotoWidth, page.originalPhotoHeight
                        ) ?: throw Exception("Result of cropping is null")
                        var pendingOutput: File? = null
                        try {
                            if (closed) return@execute
                            val output = FileUtil().createImageFile(getApplication<Application>(), pageNumber, scanID)
                            pendingOutput = output
                            ownedFiles.add(output)
                            image.saveToFile(output, quality)
                            if (closed) {
                                return@execute
                            }
                            outputs.add(output)
                        } finally {
                            image.recycle()
                            // A replaced nonterminal session must also remove its own late or
                            // failed write, even if the restoring session already listed files.
                            if (closed) pendingOutput?.delete()
                        }
                    }
                    // Originals stay until delivery, so export can restart after process death.
                    mainHandler.post {
                        if (!closed) {
                            outputFiles = outputs.toList()
                            exporting = false
                            updates.value = Unit
                        }
                    }
                } catch (exception: Exception) {
                    mainHandler.post { fail("Unable to export scan: ${exception.localizedMessage}") }
                }
            }
        }

        fun keepOutputs(files: List<File>) {
            transferredPaths = files.map { it.absolutePath }.toSet()
        }

        fun fail(message: String) {
            if (closed) return
            error = message
            updates.value = Unit
        }

        fun cancel() {
            cancelled = true
            close(cleanup = true)
            updates.value = Unit
        }

        fun finishRequest() {
            close(cleanup = true)
        }

        private fun close(cleanup: Boolean) {
            if (closed) return
            closed = true
            if (cleanup) {
                imageExecutor.execute {
                    val files = synchronized(ownedFiles) { ownedFiles.toList() } +
                        FileUtil().getScanFiles(getApplication<Application>(), scanID)
                    files.distinct().filter { it.absolutePath !in transferredPaths }.forEach { it.delete() }
                }
            }
            imageExecutor.shutdown()
        }

        override fun onCleared() {
            close(cleanup = false)
            previewBitmap = null
            super.onCleared()
        }
    }
}
