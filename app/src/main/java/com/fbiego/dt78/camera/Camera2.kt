/*
 *
 * MIT License
 *
 * Copyright (c) 2021 Felix Biego
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.fbiego.dt78.camera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.*
import android.graphics.Matrix.ScaleToFit
import android.hardware.camera2.*
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.util.SparseIntArray
import android.view.Surface
import android.view.TextureView
import androidx.core.content.ContextCompat
import timber.log.Timber
import java.util.*
import java.util.Collections.singletonList
import kotlin.Comparator


class Camera2(private val activity: Activity, private val textureView: AutoFitTextureView) {

    private var onBitmapReady: (Bitmap) -> Unit = {}
    private val cameraManager: CameraManager =
        textureView.context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraFacing = CameraCharacteristics.LENS_FACING_BACK
    private var previewSize: Size? = null
    //Current Camera id
    private var cameraId = "-1"
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null
    private var cameraDevice: CameraDevice? = null
    //
    private var cameraCaptureSession: CameraCaptureSession? = null
    // capture request builder for camera.
    private var captureRequestBuilder: CaptureRequest.Builder? = null
    // capture request generated by above builder.
    private var captureRequest: CaptureRequest? = null
    private var flash = FLASH.AUTO

    private var cameraState = STATE_PREVIEW


    private var surface: Surface? = null


    /**
     * Whether the current camera device supports Flash or not.
     */
    private var isFlashSupported = true

    /**
     * Orientation of the camera sensor
     */
    private var mSensorOrientation = 0


    private val cameraCaptureCallBack = object : CameraCaptureSession.CaptureCallback() {

        private fun process(captureResult: CaptureResult) {
            when (cameraState) {
// We have nothing to do when the camera preview is working normally.
                STATE_PREVIEW -> {

                }
                STATE_WAITING_LOCK -> {
                    val afState = captureResult[CaptureResult.CONTROL_AF_STATE]

                    if (afState == null) {
                        captureStillPicture()
                    } else if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
// CONTROL_AE_STATE can be null on some devices
                        val aeState = captureResult[CaptureResult.CONTROL_AE_STATE]
                        if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                            cameraState = STATE_PICTURE_TAKEN
                            captureStillPicture()

                        } else runPrecaptureSequence()
                    }
                }

                STATE_WAITING_PRECAPTURE -> {
// CONTROL_AE_STATE can be null on some devices
                    val aeState = captureResult[CaptureResult.CONTROL_AE_STATE]
                    if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE || aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED) {
                        cameraState = STATE_WAITING_NON_PRECAPTURE
                    }


                }

                STATE_WAITING_NON_PRECAPTURE -> {
// CONTROL_AE_STATE can be null on some devices
                    val aeState = captureResult[CaptureResult.CONTROL_AE_STATE]
                    if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                        cameraState = STATE_PICTURE_TAKEN
                        captureStillPicture()
                    }
                }
            }
        }

        override fun onCaptureProgressed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            partialResult: CaptureResult
        ) {
            process(partialResult)
        }

        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            process(result)
        }
    }


    private companion object {
// These values represent Camera states.

        // Showing Camera Preview.
        private const val STATE_PREVIEW = 0
        // Waiting for the focus to be locked.
        private const val STATE_WAITING_LOCK = 1
        // Waiting for the exposure to be in pre-capture state.
        private const val STATE_WAITING_PRECAPTURE = 2
        // Waiting for the exposure to be in any other state except pre-capture state.
        private const val STATE_WAITING_NON_PRECAPTURE = 3
        // Picture was taken
        private const val STATE_PICTURE_TAKEN = 4


        private val ORIENTATIONS = SparseIntArray()

        init {
            ORIENTATIONS.append(Surface.ROTATION_0, 90)
            ORIENTATIONS.append(Surface.ROTATION_90, 0)
            ORIENTATIONS.append(Surface.ROTATION_180, 270)
            ORIENTATIONS.append(Surface.ROTATION_270, 180)
        }

        /**
         * Max preview width that is guaranteed by Camera2 API
         */
        private const val MAX_PREVIEW_WIDTH = 1920

        /**
         * Max preview height that is guaranteed by Camera2 API
         */
        private const val MAX_PREVIEW_HEIGHT = 1080


        // Flag to check if camera capture sessions is closed.

        //  private var cameraSessionClosed = false


        /**
         * Given {@code choices} of {@code Size}s supported by a camera, choose the smallest one that
         * is at least as large as the respective texture view size, and that is at most as large as the
         * respective max size, and whose aspect ratio matches with the specified value. If such size
         * doesn't exist, choose the largest one that is at most as large as the respective max size,
         * and whose aspect ratio matches with the specified value.
         *
         * @param choices           The list of sizes that the camera supports for the intended output
         *                          class
         * @param textureViewWidth  The width of the texture view relative to sensor coordinate
         * @param textureViewHeight The height of the texture view relative to sensor coordinate
         * @param maxWidth          The maximum width that can be chosen
         * @param maxHeight         The maximum height that can be chosen
         * @param aspectRatio       The aspect ratio
         * @return The optimal {@code Size}, or an arbitrary one if none were big enough
         */
        private fun chooseOptimalSize(
            choices: Array<Size>, textureViewWidth: Int,
            textureViewHeight: Int, maxWidth: Int, maxHeight: Int, aspectRatio: Size
        ): Size {

            // Collect the supported resolutions that are at least as big as the preview Surface
            val bigEnough = arrayListOf<Size>()
            // Collect the supported resolutions that are smaller than the preview Surface
            val notBigEnough = arrayListOf<Size>()
            val w = aspectRatio.width
            val h = aspectRatio.height
            for (option in choices) {
                if (option.width <= maxWidth && option.height <= maxHeight &&
                    option.height == option.width * h / w
                ) {
                    if (option.width >= textureViewWidth && option.height >= textureViewHeight) {
                        bigEnough.add(option)
                    } else {
                        notBigEnough.add(option)
                    }
                }
            }

            // Pick the smallest of those big enough. If there is no one big enough, pick the
            // largest of those not big enough.

            return when {
                bigEnough.isNotEmpty() -> Collections.min(bigEnough, compareSizesByArea)
                notBigEnough.isNotEmpty() -> Collections.max(notBigEnough, compareSizesByArea)
                else -> {
                    Timber.e("Couldn't find any suitable preview size")
                    choices[0]
                }
            }
        }

        private val compareSizesByArea = Comparator<Size> { lhs, rhs ->
            // We cast here to ensure the multiplications won't overflow
            java.lang.Long.signum(lhs.width.toLong() * lhs.height - rhs.width.toLong() * rhs.height)
        }

    }


//    private fun configureTransform() {
//
//    }

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            TODO("Not yet implemented")
            openCamera(width, height)
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            TODO("Not yet implemented")
            configureTransform(width, height)
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            TODO("Not yet implemented")
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
            TODO("Not yet implemented")
        }
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            this@Camera2.cameraDevice = camera
            createPreviewSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            this@Camera2.cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
        }
    }

    fun onResume() {
        openBackgroundThread()
        if (textureView.isAvailable) {
            openCamera(textureView.width, textureView.height)
        } else textureView.surfaceTextureListener = surfaceTextureListener


    }

    fun close() {
        closeCamera()
        closeBackgroundThread()

    }


    private fun closeCamera() {
        if (cameraCaptureSession != null) {
            cameraCaptureSession!!.close()
            cameraCaptureSession = null
            //   cameraSessionClosed = true
        }

        if (cameraDevice != null) {
            cameraDevice!!.close()
            cameraDevice = null
        }
    }

    private fun closeBackgroundThread() {
        if (backgroundHandler != null) {
            backgroundThread!!.quitSafely()
            backgroundThread = null
            backgroundHandler = null
        }
    }


    private fun openCamera(width: Int, height: Int) {
        if (ContextCompat.checkSelfPermission(
                textureView.context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
//setup camera called here
            setUpCameraOutputs(width, height)
            configureTransform(width, height)

            cameraManager.openCamera(cameraId, cameraStateCallback, backgroundHandler)


        } else Timber.e("Requires Camera Permission")
    }

    /**
     * Sets up member variables related to camera.
     *
     * @param width  The width of available size for camera preview
     * @param height The height of available size for camera preview
     */
    private fun setUpCameraOutputs(width: Int, height: Int) {
        try {
            for (cameraId in cameraManager.cameraIdList) {
                val cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
                val cameraFacing = cameraCharacteristics.get(CameraCharacteristics.LENS_FACING)

                if (cameraFacing == this.cameraFacing) {
                    val streamConfigurationMap = cameraCharacteristics.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
                    )
// For still image captures, we use the largest available size.
                    val largest = Collections.max(
                        streamConfigurationMap?.getOutputSizes(ImageFormat.JPEG)?.toList(),
                        compareSizesByArea
                    )
// image reader could go here

// Find out if we need to swap dimension to get the preview size relative to sensor
// coordinate.
                    val displayRotation = activity.windowManager.defaultDisplay.rotation

                    //noinspection ConstantConditions
                    mSensorOrientation = cameraCharacteristics[CameraCharacteristics.SENSOR_ORIENTATION] ?: 0

                    var swappedDimensions = false

                    when (displayRotation) {
                        Surface.ROTATION_0 -> {
                        }
                        Surface.ROTATION_90 -> {
                        }
                        Surface.ROTATION_180 -> {
                            swappedDimensions = mSensorOrientation == 90 || mSensorOrientation == 270
                        }
                        Surface.ROTATION_270 -> {
                            swappedDimensions = mSensorOrientation == 0 || mSensorOrientation == 180
                        }
                        else -> Timber.e("Display rotation is invalid: $displayRotation")

                    }

                    val displaySize = Point()

                    activity.windowManager.defaultDisplay.getSize(displaySize)

                    var rotatedPreviewWidth = width
                    var rotatedPreviewHeight = height
                    var maxPreviewWidth = displaySize.x
                    var maxPreviewHeight = displaySize.y

                    if (swappedDimensions) {
                        rotatedPreviewWidth = height
                        rotatedPreviewHeight = width
                        maxPreviewWidth = displaySize.y
                        maxPreviewHeight = displaySize.x
                    }

                    if (maxPreviewWidth > MAX_PREVIEW_WIDTH) {
                        maxPreviewWidth = MAX_PREVIEW_WIDTH
                    }

                    if (maxPreviewHeight > MAX_PREVIEW_HEIGHT) {
                        maxPreviewHeight = MAX_PREVIEW_HEIGHT
                    }


                    // Danger, W.R.! Attempting to use too large a preview size could  exceed the camera
                    // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
                    // garbage capture data.
                    previewSize = chooseOptimalSize(
                        streamConfigurationMap!!.getOutputSizes(SurfaceTexture::class.java),
                        rotatedPreviewWidth, rotatedPreviewHeight, maxPreviewWidth,
                        maxPreviewHeight, largest
                    )

                    // We fit the aspect ratio of TextureView to the size of preview we picked.
                    val orientation = activity.resources.configuration.orientation

                    if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                        textureView.setAspectRatio(
                            previewSize!!.width, previewSize!!.height
                        )
                    } else {
                        textureView.setAspectRatio(
                            previewSize!!.height, previewSize!!.width
                        )
                    }
                    // check flash support
                    val flashSupported = cameraCharacteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)
                    isFlashSupported = flashSupported == null ?: false

                    this.cameraId = cameraId

                    return
                }
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    /**
     * Configures the necessary [android.graphics.Matrix] transformation to `mTextureView`.
     * This method should be called after the camera preview size is determined in
     * setUpCameraOutputs and also the size of `mTextureView` is fixed.
     *
     * @param viewWidth  The width of `mTextureView`
     * @param viewHeight The height of `mTextureView`
     */
    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val rotation = activity.windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, previewSize!!.height.toFloat(), previewSize!!.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, ScaleToFit.FILL)
            val scale = Math.max(
                viewHeight.toFloat() / previewSize!!.height,
                viewWidth.toFloat() / previewSize!!.width
            )
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180f, centerX, centerY)
        }
        textureView.setTransform(matrix)
    }

    /**
     * Retrieves the JPEG orientation from the specified screen rotation.
     *
     * @param rotation The screen rotation.
     * @return The JPEG orientation (one of 0, 90, 270, and 360)
     */
    private fun getOrientation(rotation: Int) =

    // Sensor orientation is 90 for most devices, or 270 for some devices (eg. Nexus 5X)
    // We have to take that into account and rotate JPEG properly.
    // For devices with orientation of 90, we simply return our mapping from ORIENTATIONS.
        // For devices with orientation of 270, we need to rotate the JPEG 180 degrees.
        (ORIENTATIONS.get(rotation) + mSensorOrientation + 270) % 360


    private fun openBackgroundThread() {
        backgroundThread = HandlerThread("camera_background_thread")
        backgroundThread!!.start()
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    // Creates a new camera preview session
    private fun createPreviewSession() {

        try {

            val surfaceTexture = textureView.surfaceTexture
// We configure the size of default buffer to be the size of camera preview we want.
            surfaceTexture?.setDefaultBufferSize(previewSize!!.width, previewSize!!.height)

// This is the output Surface we need to start preview.
            if (surface == null)
                surface = Surface(surfaceTexture)

            val previewSurface = surface
// We set up a CaptureRequest.Builder with the output Surface.

            captureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder!!.addTarget(previewSurface!!)


// Here, we create a CameraCaptureSession for camera preview.

            cameraDevice!!.createCaptureSession(
                singletonList(previewSurface),
                object : CameraCaptureSession.StateCallback() {

                    override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                        if (cameraDevice == null) {
                            return
                        }

                        try {
// When session is ready we start displaying preview.
                            this@Camera2.cameraCaptureSession = cameraCaptureSession
                            //     cameraSessionClosed = false

// Auto focus should be continuous for camera preview.

                            captureRequestBuilder!!.set(
                                CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                            )

// Finally, we start displaying the camera preview.
                            captureRequest = captureRequestBuilder!!.build()

                            this@Camera2.cameraCaptureSession!!.setRepeatingRequest(
                                captureRequest!!,
                                cameraCaptureCallBack,
                                backgroundHandler
                            )


/* Initially flash is automatically enabled when necessary. But In case activity is resumed and flash is set to fire
we set flash after the preview request is processed to ensure flash fires only during a still capture. */
                            setFlashMode(captureRequestBuilder!!, true)


                        } catch (e: CameraAccessException) {
                            e.printStackTrace()
                        }

                    }

                    override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {

                    }
                }, backgroundHandler
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }

    }

/* For some reason, The code for firing flash in both methods below which is prescribed doesn't work on API level below PIE it maybe a device-specific issue as very common with Camera API
   so I had to build my own code if the else block works well for your devices even below PIE I would recommend using it because that's
   the official way and code is available for all levels >=21 as mentioned.
*/

    private fun flashOn(captureRequestBuilder: CaptureRequest.Builder) {
        //  cameraManager.setTorchMode()
        if (Build.VERSION.SDK_INT > 28) {
            captureRequestBuilder.set(
                CaptureRequest.FLASH_MODE,
                CaptureRequest.FLASH_MODE_TORCH
            )
        } else {
            captureRequestBuilder.set(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
            )
        }

    }

    // sets flash mode for a capture request builder
    private fun setFlashMode(
        captureRequestBuilder: CaptureRequest.Builder,
        trigger: Boolean
    ) {
        if (trigger) {
            // This is how to tell the camera to trigger.
            captureRequestBuilder.set(
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START
            )
        }
        when (flash) {
            FLASH.ON -> flashOn(captureRequestBuilder)
            FLASH.AUTO -> {
                captureRequestBuilder.set(
                    CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                )
            }
            else -> captureRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
        }
    }

    enum class FLASH {
        ON, OFF, AUTO
    }

    // Locks the preview focus.
    private fun lockPreview() {
        try {

            captureRequestBuilder!!.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
// Tell #cameraCaptureCallback to wait for the lock.
            cameraState = STATE_WAITING_LOCK
            cameraCaptureSession!!.capture(
                captureRequestBuilder!!.build(), cameraCaptureCallBack, backgroundHandler
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }

    }


    private fun unlockPreview() {
        try {
// Reset the auto-focus trigger
            captureRequestBuilder!!.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)

            setFlashMode(captureRequestBuilder!!, false)


            cameraCaptureSession!!.capture(
                captureRequestBuilder!!.build(), cameraCaptureCallBack, backgroundHandler
            )
// After this, the camera will go back to the normal state of preview.
            cameraState = STATE_PREVIEW



            cameraCaptureSession!!.setRepeatingRequest(
                captureRequest!!, cameraCaptureCallBack,
                backgroundHandler
            )


        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }

    }

    // This method switches Camera Lens Front or Back then restarts camera.
    fun switchCamera() {
        close()
        cameraFacing = if (cameraFacing == CameraCharacteristics.LENS_FACING_BACK)
            CameraCharacteristics.LENS_FACING_FRONT
        else CameraCharacteristics.LENS_FACING_BACK
        onResume()


    }


    fun setFlash(flash: FLASH) {

        this.flash = flash

        if (textureView.context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            when (cameraFacing) {
//     CameraCharacteristics.LENS_FACING_BACK -> setFlashMode()
                CameraCharacteristics.LENS_FACING_FRONT -> Timber.e("Front Camera Flash isn't supported yet.")
            }
        }

    }

    /**
     * Run the precapture sequence for capturing a still image. This method should be called when
     * we get a response in {@link #mCaptureCallback} from {@link #lockPreview()}.
     */

    private fun runPrecaptureSequence() {
        try {


// Tell #cameraCaptureCallback to wait for the precapture sequence to be set.
            cameraState = STATE_WAITING_PRECAPTURE

            setFlashMode(captureRequestBuilder!!, true)

            cameraCaptureSession!!.capture(captureRequestBuilder!!.build(), cameraCaptureCallBack, backgroundHandler)


        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }


    }


    private fun captureBitmap() {
        if (textureView.isAvailable) {
            textureView.bitmap?.let { onBitmapReady(it) }
        }
    }

    /**
     * Capture a still picture. This method should be called when we get a response in
     * {@link #cameraCaptureCallback} from both {@link #lockPreview()}.
     */
    private fun captureStillPicture() {
        try {
// This is the CaptureRequest.Builder that we use to take a picture.
            val captureBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)

//            val surfaceTexture = textureView.surfaceTexture
//            surfaceTexture.setDefaultBufferSize(previewSize!!.width, previewSize!!.height)
            captureBuilder.addTarget(surface!!)
            // Use the same AE and AF modes as the preview.
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)


            setFlashMode(captureBuilder, true)

            // Orientation
            val rotation = activity.windowManager.defaultDisplay.rotation

            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getOrientation(rotation))

            cameraCaptureSession!!.stopRepeating()
            cameraCaptureSession!!.abortCaptures()
            cameraCaptureSession!!.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {

                    captureBitmap()
                    unlockPreview()


                }
            }, null)


        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }


    }
// uncomment to use

    fun isFlashAuto() =
        isFlashSupported && flash == FLASH.AUTO && cameraFacing == CameraCharacteristics.LENS_FACING_BACK


    fun isFlashON() =
        isFlashSupported && (flash == FLASH.ON) && cameraFacing == CameraCharacteristics.LENS_FACING_BACK


    fun takePhoto(onBitmapReady: (Bitmap) -> Unit) {

        this.onBitmapReady = onBitmapReady
        lockPreview()
    }

}






