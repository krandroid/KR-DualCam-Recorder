package com.example

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.ExecutionException

class CameraManager(private val context: Context) {
    
    private val cameraProviderFuture: ListenableFuture<ProcessCameraProvider> = ProcessCameraProvider.getInstance(context)

    fun bindConcurrentCameras(lifecycleOwner: LifecycleOwner, backPreview: androidx.camera.core.Preview, frontPreview: androidx.camera.core.Preview) {
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                // Unbind all previous use cases
                cameraProvider.unbindAll()

                val backCameraSelector = CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                    .build()

                val frontCameraSelector = CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                    .build()

                // To support true concurrent camera recording (Android 11+ with supported hardware),
                // we would query cameraProvider.availableConcurrentCameraConfigs 
                // and bind them using cameraProvider.bindToLifecycle(lifecycleOwner, it.cameraSelector, useCases)
                
                // Fallback for demonstration: bind independently
                // Note: On unsupported hardware, binding a second camera will displace the first.
                try {
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        backCameraSelector,
                        backPreview
                    )
                    
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        frontCameraSelector,
                        frontPreview
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }

            } catch (e: ExecutionException) {
                e.printStackTrace()
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(context))
    }
}
