package com.document.scanner.flutter.document_scanner_flutter

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.annotation.NonNull
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import com.scanlibrary.ScanActivity
import com.scanlibrary.ScanConstants
import java.io.File
import java.io.IOException
import android.view.View
import android.view.ViewGroup

/** DocumentScannerFlutterPlugin */
class DocumentScannerFlutterPlugin :
  FlutterPlugin,
  MethodCallHandler,
  ActivityAware,
  PluginRegistry.ActivityResultListener {

  private lateinit var channel: MethodChannel
  private lateinit var call: MethodCall
  private var activityPluginBinding: ActivityPluginBinding? = null
  private var result: Result? = null

  companion object {
    const val SCAN_REQUEST_CODE: Int = 101
    private const val TAG = "DocScannerFlutter"
  }

  override fun onAttachedToEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    channel = MethodChannel(binding.binaryMessenger, "document_scanner_flutter")
    channel.setMethodCallHandler(this)
  }

  override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    channel.setMethodCallHandler(null)
  }

  override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
    this.call = call
    this.result = result
    when (call.method) {
      "camera" -> launch(ScanConstants.OPEN_CAMERA)
      "gallery" -> launch(ScanConstants.OPEN_MEDIA)
      else -> result.notImplemented()
    }
  }

  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    activityPluginBinding = binding
    binding.addActivityResultListener(this)
  }

  override fun onDetachedFromActivity() {
    activityPluginBinding?.removeActivityResultListener(this)
    activityPluginBinding = null
  }

  override fun onDetachedFromActivityForConfigChanges() {}
  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {}

  private fun composeIntentArguments(intent: Intent) {
    val args = mapOf(
      ScanConstants.SCAN_NEXT_TEXT to "ANDROID_NEXT_BUTTON_LABEL",
      ScanConstants.SCAN_SAVE_TEXT to "ANDROID_SAVE_BUTTON_LABEL",
      ScanConstants.SCAN_ROTATE_LEFT_TEXT to "ANDROID_ROTATE_LEFT_LABEL",
      ScanConstants.SCAN_ROTATE_RIGHT_TEXT to "ANDROID_ROTATE_RIGHT_LABEL",
      ScanConstants.SCAN_ORG_TEXT to "ANDROID_ORIGINAL_LABEL",
      ScanConstants.SCAN_BNW_TEXT to "ANDROID_BMW_LABEL",
      ScanConstants.SCAN_SCANNING_MESSAGE to "ANDROID_SCANNING_MESSAGE",
      ScanConstants.SCAN_LOADING_MESSAGE to "ANDROID_LOADING_MESSAGE",
      ScanConstants.SCAN_APPLYING_FILTER_MESSAGE to "ANDROID_APPLYING_FILTER_MESSAGE",
      ScanConstants.SCAN_CANT_CROP_ERROR_TITLE to "ANDROID_CANT_CROP_ERROR_TITLE",
      ScanConstants.SCAN_CANT_CROP_ERROR_MESSAGE to "ANDROID_CANT_CROP_ERROR_MESSAGE",
      ScanConstants.SCAN_OK_LABEL to "ANDROID_OK_LABEL"
    )
    for ((scanKey, flutterKey) in args) {
      if (call.hasArgument(flutterKey)) {
        call.argument<String>(flutterKey)?.let { intent.putExtra(scanKey, it) }
      }
    }
  }

  private fun launch(openPref: Int) {
    val activity = activityPluginBinding?.activity ?: return
    val intent = Intent(activity, EdgeToEdgeScanActivity::class.java)
    intent.putExtra(ScanConstants.OPEN_INTENT_PREFERENCE, openPref)
    composeIntentArguments(intent)
    activity.startActivityForResult(intent, SCAN_REQUEST_CODE)
  }

  /** Copy a content/file Uri to app cache and return a real path (no READ_MEDIA_* needed). */
  private fun copyToCache(context: Context, uri: Uri): String {
    if ("file".equals(uri.scheme, ignoreCase = true)) {
      return File(requireNotNull(uri.path)).absolutePath
    }
    val outFile: File = File.createTempFile("scan_", ".jpg", context.cacheDir)
    context.contentResolver.openInputStream(uri).use { input ->
      outFile.outputStream().use { output ->
        if (input == null) throw IOException("Unable to open input stream for $uri")
        input.copyTo(output)
      }
    }
    return outFile.absolutePath
  }

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
    if (resultCode != Activity.RESULT_OK || requestCode != SCAN_REQUEST_CODE) return false
    val activity = activityPluginBinding?.activity ?: return false
    return try {
      val scannedUri: Uri? = data?.extras?.getParcelable(ScanConstants.SCANNED_RESULT)
      if (scannedUri == null) {
        result?.error("NO_RESULT", "Scan result URI is null", null)
      } else {
        result?.success(copyToCache(activity, scannedUri))
      }
      true
    } catch (t: Throwable) {
      Log.e(TAG, "Failed handling scan result", t)
      result?.error("SCAN_ERROR", t.message, null)
      true
    }
  }
}

/**
 * Wrapper around ScanLibrary's ScanActivity that:
 *  - Enables edge-to-edge
 *  - Applies WindowInsets padding so toolbar/bottom controls are visible
 * No theme/resources required.
 */
class EdgeToEdgeScanActivity : ScanActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    // Enable edge-to-edge (Android 14/15 default for high targetSdks; this covers earlier versions too)
    WindowCompat.setDecorFitsSystemWindows(window, false)
    super.onCreate(savedInstanceState)

    // Root content view of this activity
    val root: View = findViewById(android.R.id.content) ?: return

    // If the ScanActivity adds its own child, pad that; otherwise pad the content itself
    val padTarget: View = (root as? ViewGroup)?.getChildAt(0) ?: root

    ViewCompat.setOnApplyWindowInsetsListener(padTarget) { v, insets ->
      val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
      v.updatePadding(top = bars.top, bottom = bars.bottom)
      // Don't forcibly consume; let child views participate if they want.
      insets
    }
  }
}
