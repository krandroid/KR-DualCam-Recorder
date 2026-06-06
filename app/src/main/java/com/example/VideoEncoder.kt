package com.example

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * VideoEncoder is responsible for taking two camera streams, compositing them side-by-side using OpenGL, and encoding via MediaCodec.
 * Implementing a full OpenGL hardware compositor takes thousands of lines of C++/Java using EGL and Surface textures.
 * This file serves as the architectural skeleton requested.
 */
class VideoEncoder {
    
    private var mediaMuxer: MediaMuxer? = null
    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var isMuxerStarted = false
    
    fun prepareEncoder(outputFile: File) {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            format.setInteger(MediaFormat.KEY_BIT_RATE, 10000000)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

            videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            videoCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            
            // In a real application, we would create an InputSurface here to render our dual-camera OpenGL composition.
            // val inputSurface = videoCodec?.createInputSurface()

            mediaMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            
            videoCodec?.start()
            
            Log.d("VideoEncoder", "Encoder prepared with file: ${outputFile.name}")
        } catch (e: IOException) {
            e.printStackTrace()
            Log.e("VideoEncoder", "Failed to prepare encoder")
        }
    }
    
    fun startMuxer() {
        if (!isMuxerStarted && videoTrackIndex >= 0) {
            mediaMuxer?.start()
            isMuxerStarted = true
        }
    }
    
    fun stopAndRelease() {
        if (isMuxerStarted) {
            mediaMuxer?.stop()
            isMuxerStarted = false
        }
        
        videoCodec?.stop()
        videoCodec?.release()
        videoCodec = null
        
        mediaMuxer?.release()
        mediaMuxer = null
    }

    companion object {
        fun getOutputDirectory(): File {
            val dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            val appDir = File(dcimDir, "KR_DualCam")
            if (!appDir.exists()) {
                appDir.mkdirs()
            }
            return appDir
        }
    }
}
