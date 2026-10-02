package com.v2ray.ang.ui

import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.viewfinder.compose.MutableCoordinateTransformer
import androidx.camera.viewfinder.core.ImplementationMode
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import android.util.Size as TargetSize

private val qrReader = MultiFormatReader()

@Composable
fun CameraXPreview(
    onScanResult: (String) -> Unit,
    onCameraReady: (CameraControl, CameraInfo) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val foundResult = remember { AtomicBoolean(false) }
    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }

    DisposableEffect(lifecycleOwner) {
        val analysisExecutor = Executors.newSingleThreadExecutor()
        var cameraProvider: ProcessCameraProvider? = null
        var camera: Camera? = null
        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(ResolutionStrategy(TargetSize(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .build()
        val imageAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .apply {
                setAnalyzer(analysisExecutor) { imageProxy -> processImageProxy(imageProxy, foundResult, onScanResult) }
            }
        val preview = Preview.Builder().build()
        preview.setSurfaceProvider { request -> surfaceRequest = request }
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                cameraProvider?.unbindAll()
                camera = cameraProvider?.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
                camera?.let { onCameraReady(it.cameraControl, it.cameraInfo) }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "CameraX bind failed", e)
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            cameraProvider?.unbindAll()
            analysisExecutor.shutdownNow()
        }
    }

    surfaceRequest?.let { request ->
        val coordinateTransformer = remember { MutableCoordinateTransformer() }
        CameraXViewfinder(
            surfaceRequest = request,
            implementationMode = ImplementationMode.EXTERNAL,
            coordinateTransformer = coordinateTransformer,
            modifier = Modifier.fillMaxSize()
        )
    }
}

private fun processImageProxy(imageProxy: ImageProxy, foundResult: AtomicBoolean, onResult: (String) -> Unit) {
    if (foundResult.get()) {
        imageProxy.close()
        return
    }
    try {
        val yPlane = imageProxy.planes[0]
        val source = createYPlaneLuminanceSource(yPlane.buffer, imageProxy.width, imageProxy.height, yPlane.rowStride)
        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
        val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true, DecodeHintType.CHARACTER_SET to "UTF-8")
        val result = qrReader.decode(binaryBitmap, hints)
        val text = result.text
        if (!text.isNullOrEmpty() && foundResult.compareAndSet(false, true)) onResult(text)
    } catch (_: Exception) {
    } finally {
        imageProxy.close()
    }
}

internal fun createYPlaneLuminanceSource(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int): PlanarYUVLuminanceSource {
    val bufferCopy = buffer.duplicate()
    val bytes = ByteArray(bufferCopy.remaining())
    bufferCopy.get(bytes)
    return PlanarYUVLuminanceSource(bytes, rowStride, height, 0, 0, width, height, false)
}
