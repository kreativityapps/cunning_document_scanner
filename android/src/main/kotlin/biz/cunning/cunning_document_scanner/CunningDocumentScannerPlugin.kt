package biz.cunning.cunning_document_scanner

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.IntentSender
import androidx.core.app.ActivityCompat
import biz.cunning.cunning_document_scanner.fallback.DocumentScannerActivity
import biz.cunning.cunning_document_scanner.fallback.constants.DocumentScannerExtra
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_JPEG
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_BASE
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry

/** CunningDocumentScannerPlugin */
class CunningDocumentScannerPlugin : FlutterPlugin, MethodChannel.MethodCallHandler, ActivityAware {
    private var binding: ActivityPluginBinding? = null
    private var activity: Activity? = null
    private var pendingResult: Result? = null
    private var scanId = 0L
    private var requestCode = 0x3600
    private var pendingRequestCode: Int? = null
    private var usingFallback = false
    private var deferredLaunch: (() -> Unit)? = null
    private lateinit var channel: MethodChannel

    private val resultListener = PluginRegistry.ActivityResultListener { code, resultCode, data ->
        if (code != pendingRequestCode || pendingResult == null) {
            false
        } else {
            try {
                when (resultCode) {
                    Activity.RESULT_CANCELED -> complete(emptyList())
                    Activity.RESULT_OK -> {
                        val error = data?.getStringExtra("error")
                        if (error != null) {
                            fail("ERROR", error)
                        } else {
                            val paths = if (usingFallback) {
                                data?.getStringArrayListExtra("croppedImageResults")
                                    ?.map { it.removePrefix("file://") }
                            } else {
                                GmsDocumentScanningResult.fromActivityResultIntent(data)
                                    ?.pages?.map { it.imageUri.toString().removePrefix("file://") }
                            }
                            if (paths.isNullOrEmpty() || paths.any { it.isBlank() }) {
                                fail("ERROR", "No scanned images returned")
                            } else {
                                complete(paths)
                            }
                        }
                    }
                    else -> fail("ERROR", "Unexpected document scanner result: $resultCode")
                }
            } catch (exception: Exception) {
                fail("ERROR", "Unable to read document scanner result: ${exception.localizedMessage}")
            }
            true
        }
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(binding.binaryMessenger, "cunning_document_scanner")
        channel.setMethodCallHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        if (call.method != "getPictures") {
            result.notImplemented()
            return
        }
        if (pendingResult != null) {
            result.error("ALREADY_ACTIVE", "A document scan is already in progress", null)
            return
        }
        val noOfPages = call.argument<Int>("noOfPages") ?: 50
        if (noOfPages <= 0) {
            result.error("INVALID_ARGUMENT", "noOfPages must be positive", null)
            return
        }
        val currentActivity = activity
        if (currentActivity == null || currentActivity.isFinishing || currentActivity.isDestroyed) {
            result.error("NO_ACTIVITY", "Plugin is not attached to an active Activity", null)
            return
        }

        pendingResult = result
        scanId += 1
        // A late Activity result from an abandoned scan must not finish a newer request.
        requestCode = if (requestCode >= 0x7fff) 0x3600 else requestCode + 1
        pendingRequestCode = requestCode
        usingFallback = false
        startScan(scanId, noOfPages, call.argument<Boolean>("isGalleryImportAllowed") ?: false)
    }

    private fun complete(paths: List<String>? = null, errorCode: String? = null, message: String? = null) {
        val result = pendingResult ?: return
        pendingResult = null
        pendingRequestCode = null
        deferredLaunch = null
        usingFallback = false
        if (errorCode == null) result.success(paths) else result.error(errorCode, message, null)
    }

    private fun fail(code: String, message: String) {
        complete(errorCode = code, message = message)
    }

    private fun isCurrentScan(id: Long): Boolean = pendingResult != null && scanId == id

    private fun whenAttached(id: Long, launch: (Activity) -> Unit) {
        if (!isCurrentScan(id)) return
        val currentActivity = activity
        if (currentActivity == null || currentActivity.isChangingConfigurations) {
            // Configuration changes detach briefly. Resume the prepared launch after reattachment.
            deferredLaunch = { whenAttached(id, launch) }
        } else if (currentActivity.isFinishing || currentActivity.isDestroyed) {
            fail("NO_ACTIVITY", "The document scanner Activity is no longer available")
        } else {
            launch(currentActivity)
        }
    }

    private fun startScan(id: Long, noOfPages: Int, isGalleryImportAllowed: Boolean) {
        whenAttached(id) { currentActivity ->
            try {
                val options = GmsDocumentScannerOptions.Builder()
                    .setGalleryImportAllowed(isGalleryImportAllowed)
                    .setPageLimit(noOfPages)
                    .setResultFormats(RESULT_FORMAT_JPEG)
                    .setScannerMode(SCANNER_MODE_BASE)
                    .build()
                GmsDocumentScanning.getClient(options).getStartScanIntent(currentActivity)
                    .addOnSuccessListener { intentSender ->
                        whenAttached(id) launch@ { launchActivity ->
                            val code = pendingRequestCode ?: return@launch
                            try {
                                launchActivity.startIntentSenderForResult(intentSender, code, null, 0, 0, 0)
                            } catch (_: ActivityNotFoundException) {
                                launchFallbackScanner(id, noOfPages)
                            } catch (_: IntentSender.SendIntentException) {
                                fail("ERROR", "Failed to start document scanner")
                            } catch (exception: Exception) {
                                fail("ERROR", "Failed to launch document scanner: ${exception.localizedMessage}")
                            }
                        }
                    }
                    .addOnFailureListener { error ->
                        if (isCurrentScan(id)) {
                            if (error is MlKitException) {
                                launchFallbackScanner(id, noOfPages)
                            } else {
                                fail("ERROR", "Failed to prepare document scanner: ${error.localizedMessage}")
                            }
                        }
                    }
            } catch (_: ActivityNotFoundException) {
                launchFallbackScanner(id, noOfPages)
            } catch (exception: Exception) {
                fail("ERROR", "Failed to prepare document scanner: ${exception.localizedMessage}")
            }
        }
    }

    private fun launchFallbackScanner(id: Long, noOfPages: Int) {
        whenAttached(id) { currentActivity ->
            val code = pendingRequestCode ?: return@whenAttached
            val intent = Intent(currentActivity, DocumentScannerActivity::class.java)
                .putExtra(DocumentScannerExtra.EXTRA_MAX_NUM_DOCUMENTS, noOfPages)
            usingFallback = true
            try {
                ActivityCompat.startActivityForResult(currentActivity, intent, code, null)
            } catch (exception: Exception) {
                fail("ERROR", "Failed to start fallback scanner: ${exception.localizedMessage}")
            }
        }
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        removeActivityResultListener()
        this.binding = binding
        activity = binding.activity
        binding.addActivityResultListener(resultListener)
        val launch = deferredLaunch
        deferredLaunch = null
        launch?.invoke()
    }

    override fun onDetachedFromActivityForConfigChanges() {
        removeActivityResultListener()
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }

    override fun onDetachedFromActivity() {
        removeActivityResultListener()
        activity = null
        fail("NO_ACTIVITY", "Activity detached during document scan")
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        fail("NO_ACTIVITY", "Flutter engine detached during document scan")
        removeActivityResultListener()
        activity = null
        channel.setMethodCallHandler(null)
    }

    private fun removeActivityResultListener() {
        binding?.removeActivityResultListener(resultListener)
        binding = null
    }
}
