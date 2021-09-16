package com.ducky.fastvideoframeextraction

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.MainThread
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ducky.fastvideoframeextraction.decoder.Frame
import com.ducky.fastvideoframeextraction.decoder.FrameExtractor
import com.ducky.fastvideoframeextraction.decoder.IVideoFrameExtractor
import java.io.File
import java.util.ArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), IVideoFrameExtractor {

    private val executorService: ExecutorService = Executors.newSingleThreadExecutor()

    private var resultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data: Intent? = result.data
            val dataUri = data?.data
            if (dataUri != null) {
                val uriPathHelper = URIPathHelper()
                val videoInputPath = uriPathHelper.getPath(this, dataUri).toString()
                val videoInputFile = File(videoInputPath)
                try {
                    val frameExtractor = FrameExtractor(this)
                    executorService.execute {
                        frameExtractor.extractFrames(videoInputFile.absolutePath)
                    }
                }  catch (throwable: Throwable) {
                    throwable.printStackTrace()
                    Toast.makeText(this, "Failed to extract frames", Toast.LENGTH_SHORT).show()
                }

            } else {
                Toast.makeText(this, "Video input error!", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val videoSelectBt: Button = this.findViewById(R.id.select_bt)
        videoSelectBt.setOnClickListener {
            openGalleryForVideo()
        }


        if (!allPermissionsGranted()) {
            getRuntimePermissions()
        }
    }

    private fun openGalleryForVideo() {
        val intent = Intent()
        intent.type = "video/*"
        intent.action = Intent.ACTION_PICK
        resultLauncher.launch(intent)
    }

    private fun getRequiredPermissions(): Array<String?> {
        return try {
            val info = this.packageManager
                .getPackageInfo(this.packageName, PackageManager.GET_PERMISSIONS)
            val ps = info.requestedPermissions
            if (ps != null && ps.isNotEmpty()) {
                ps
            } else {
                arrayOfNulls(0)
            }
        } catch (e: Exception) {
            arrayOfNulls(0)
        }
    }

    private fun allPermissionsGranted(): Boolean {
        for (permission in getRequiredPermissions()) {
            permission?.let {
                if (!isPermissionGranted(this, it)) {
                    return false
                }
            }
        }
        return true
    }

    private fun getRuntimePermissions() {
        val allNeededPermissions = ArrayList<String>()
        for (permission in getRequiredPermissions()) {
            permission?.let {
                if (!isPermissionGranted(this, it)) {
                    allNeededPermissions.add(permission)
                }
            }
        }

        if (allNeededPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this, allNeededPermissions.toTypedArray(), PERMISSION_REQUESTS
            )
        }
    }

    private fun isPermissionGranted(context: Context, permission: String): Boolean {
        if (ContextCompat.checkSelfPermission(context, permission)
            == PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "Permission granted: $permission")
            return true
        }
        Log.i(TAG, "Permission NOT granted: $permission")
        return false
    }

    companion object{
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUESTS = 1
    }

    override fun onCurrentFrameExtracted(currentFrame: Frame) {
        // 1. Convert frame byte buffer to bitmap
        val imageBitmap = Utils.fromBufferToBitmap(currentFrame.byteBuffer, currentFrame.width, currentFrame.height)

        // 2. Get the frame file in app external file directory
        val allFrameFileFolder = File(this.getExternalFilesDir(null), "all-frames")
        if (!allFrameFileFolder.isDirectory) {
            allFrameFileFolder.mkdirs()
        }
        val frameFile = File(allFrameFileFolder, "frame_num_${currentFrame.timestamp.toString().padStart(10, '0')}.jpeg")

        // 3. Save current frame to storage
        imageBitmap?.let { Utils.saveImageToFile(it, frameFile) }
    }

    override fun onAllFrameExtracted(processedFrameCount: Int, processedTime: Long) {
//        Toast.makeText(this, "Save: $processedFrameCount frames took: $processedTime ms.", Toast.LENGTH_LONG).show()
    }
}