package biz.cunning.cunning_document_scanner.fallback.utils

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date

/**
 * This class contains a helper function creating temporary files
 *
 * @constructor creates file util
 */
class FileUtil {
    /**
     * create a temporary file
     *
     * @param context the application or activity context
     * @param pageNumber the current document page number
     * @param scanID the owning scan session
     */
    @Throws(IOException::class)
    fun createImageFile(context: Context, pageNumber: Int, scanID: String): File {
        // use current time to make file name more unique
        val dateTime: String = SimpleDateFormat(
            "yyyyMMdd_HHmmss",
            Locale.US
        ).format(Date())

        // create file in pictures directory
        val storageDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: throw IOException("Scan pictures directory is unavailable")
        return File.createTempFile(
            "DOCUMENT_SCAN_${scanID}_${pageNumber}_${dateTime}",
            ".jpg",
            storageDir
        )
    }

    fun getScanFiles(context: Context, scanID: String): List<File> {
        val storageDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return storageDir?.listFiles()?.filter {
            it.name.startsWith("DOCUMENT_SCAN_${scanID}_") && it.isFile
        }.orEmpty()
    }
}
