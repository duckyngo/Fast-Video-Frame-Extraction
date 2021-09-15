package com.ducky.fastvideoframeextraction

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.DisplayMetrics
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer


/**
 * Created by Duc Ky Ngo on 9/13/2021.
 * duckyngo1705@gmail.com
 */
object Utils {


    /**
     * Get bitmap from ByteBuffer
     */
    fun fromBufferToBitmap(buffer: ByteBuffer, width: Int, height: Int): Bitmap? {
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        result.copyPixelsFromBuffer(buffer)
        val transformMatrix = Matrix()
        val outputBitmap = Bitmap.createBitmap(result, 0, 0, result.width, result.height, transformMatrix, false)
        outputBitmap.density = DisplayMetrics.DENSITY_DEFAULT
        return outputBitmap
    }


    fun saveImageToFile(bmp: Bitmap?, file: File, isPNG: Boolean=false, quality: Int=90): File? {
        if (bmp == null) {
            return null
        }
        try {
            val fos = FileOutputStream(file)
            if (isPNG) {
                bmp.compress(Bitmap.CompressFormat.PNG, quality, fos)
            } else {
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, fos)
            }
            fos.flush()
            fos.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
        return file
    }


    fun deleteFile(f: File) {
        if (f.isDirectory) {
            val files = f.listFiles()
            if (files != null && files.size > 0) {
                for (i in files.indices) {
                    deleteFile(files[i])
                }
            }
        }
        f.delete()
    }


    fun saveMediaToStorage(context: Context?, bitmap: Bitmap, filename: String) {
        //Generating a file name

        //Output stream
        var fos: OutputStream? = null

        //For devices running android >= Q
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            //getting the contentResolver
            context?.contentResolver?.also { resolver ->

                //Content resolver will process the contentvalues
                val contentValues = ContentValues().apply {

                    //putting file information in content values
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                }

                //Inserting the contentValues to contentResolver and getting the Uri
                val imageUri: Uri? =
                    resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

                //Opening an outputstream with the Uri that we got
                fos = imageUri?.let { resolver.openOutputStream(it) }
            }
        } else {
            //These for devices running on android < Q
            //So I don't think an explanation is needed here
            val imagesDir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val image = File(imagesDir, filename)
            fos = FileOutputStream(image)
        }

        fos?.use {
            //Finally writing the bitmap to the output stream that we opened
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)
        }
    }


}