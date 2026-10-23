package biz.cunning.cunning_document_scanner.fallback.utils

import android.app.Activity
import android.content.Intent
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

/**
 * This class contains a helper function for opening the camera.
 *
 * @param activity current activity
 * @param onPhotoCaptureSuccess gets called with photo file path when photo is ready
 * @param onCancelPhoto gets called when user cancels out of camera
 * @constructor creates camera util
 */
class CameraUtil(
    private val activity: ComponentActivity,
    private val onPhotoCaptureSuccess: (photoFilePath: String) -> Unit,
    private val onCancelPhoto: () -> Unit,
    private val onPhotoFileCreated: (File) -> Unit,
    private val onPhotoCaptureError: (String) -> Unit
) {
    /**
     * @property photoFilePath the photo file path
     */
    private var photoFilePath: String? = null

    fun restorePendingPhoto(path: String?) {
        photoFilePath = path
    }

    /**
     * @property startForResult used to launch camera
     */
    private val startForResult = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        val path = photoFilePath
        photoFilePath = null
        if (path == null) {
            onPhotoCaptureError("Camera returned without a photo file")
        } else if (result.resultCode == Activity.RESULT_OK) {
            onPhotoCaptureSuccess(path)
        } else {
            File(path).delete()
            if (result.resultCode == Activity.RESULT_CANCELED) {
                onCancelPhoto()
            } else {
                onPhotoCaptureError("Unexpected camera result: ${result.resultCode}")
            }
        }
    }

    /**
     * open the camera by launching an image capture intent
     *
     * @param pageNumber the current document page number
     */
    @Throws(IOException::class)
    fun openCamera(pageNumber: Int, scanID: String) {
        // create intent to launch camera
        val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)

        // create new file for photo
        val photoFile: File = FileUtil().createImageFile(activity, pageNumber, scanID)

        // store the photo file path, and send it back once the photo is saved
        photoFilePath = photoFile.absolutePath
        onPhotoFileCreated(photoFile)

        // photo gets saved to this file path
        val photoURI = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.DocumentScannerFileProvider",
            photoFile
        )
        takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI)

        // open camera
        startForResult.launch(takePictureIntent)
    }
}
