package com.example.shrey.phonemouse;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.graphics.ColorUtils;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.highgui.Highgui;
import org.opencv.imgproc.Imgproc;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import static org.opencv.core.CvType.CV_32F;
import static org.opencv.highgui.Highgui.IMREAD_COLOR;
import static org.opencv.highgui.Highgui.IMREAD_GRAYSCALE;

public class MouseActivity extends AppCompatActivity {


    private Button leftMouseButton;
    private Button rightMouseButton;

    private double[] mVelocity;
    private Queue<Actions> mActionQueue;

    private SocketTask mSocketTask;
    private BluetoothDevice mBluetoothDevice;

    private CameraDevice mCameraDevice;
    private CameraCaptureSession mCaptureSession;
    private ImageReader mImageReader;


    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;

    private CaptureRequest.Builder mPreviewRequestBuilder;
    private CaptureRequest mPreviewRequest;

    private ImageView imageView;

    private Mat lastImageMat;

    private long lastTime;

    private static boolean isOpenCVLoaded;

    static {
        if (!OpenCVLoader.initDebug()) {
            Log.d("OPENCV", "FAILURE");
        } else {
            Log.d("OPENCV", "SUCCESS");
            isOpenCVLoaded = true;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mouse);

        Intent intent = getIntent();

        mBluetoothDevice = (BluetoothDevice) intent.getParcelableExtra("BLUETOOTH_DEVICE");

        mVelocity = new double[2];

        leftMouseButton = (Button) findViewById(R.id.left_mouse_button);
        rightMouseButton = (Button) findViewById(R.id.right_mouse_button);

        imageView = (ImageView) findViewById(R.id.imageView);

        leftMouseButton.setOnTouchListener(leftMouseButtonTouch);
        rightMouseButton.setOnTouchListener(rightMouseButtonTouch);

        mActionQueue = new LinkedList<>();
        openCamera();
    }

    @Override
    protected void onResume() {
        super.onResume();

        Arrays.fill(mVelocity, 0.0);

        mActionQueue.clear();
        //setup socket
        mSocketTask = new SocketTask(mVelocity, mActionQueue, mBluetoothDevice);
        mSocketTask.execute();
        startBackgroundThread();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mSocketTask.cancel(true);
        stopBackgroundThread();
    }

    //https://github.com/googlesamples/android-Camera2Basic
    private void openCamera() {
        CameraManager cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            for (String cameraId : cameraManager.getCameraIdList()) {
                CameraCharacteristics cameraCharacteristics = cameraManager
                        .getCameraCharacteristics(cameraId);
                Integer facing = cameraCharacteristics.get(CameraCharacteristics.LENS_FACING);

                //camera is facing backwards
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    StreamConfigurationMap map = cameraCharacteristics
                            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

                    //get smallest size
                    Size minSize = Collections.min(
                            Arrays.asList(map.getOutputSizes(ImageReader.class)),
                            new Comparator<Size>() {
                                @Override
                                public int compare(Size o1, Size o2) {
                                    return Long.signum((long) o1.getWidth() * o1.getHeight() -
                                            (long) o2.getWidth() * o2.getHeight());
                                }
                            });

                    //make sure you have permissions
                    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
                            PackageManager.PERMISSION_GRANTED) {
                        ActivityCompat.requestPermissions(
                                this,
                                new String[]{Manifest.permission.CAMERA},
                                200);
                        return;
                    }

                    mImageReader = ImageReader.newInstance(minSize.getWidth(), minSize.getHeight(),
                            ImageFormat.JPEG, 2);

                    mImageReader.setOnImageAvailableListener(imageAvailable, mBackgroundHandler);


                    cameraManager.openCamera(cameraId, cameraStateCallback, mBackgroundHandler);
                    break;
                }
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void createCameraSession() {
        try {
            mPreviewRequestBuilder
                    = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

            Surface surface = mImageReader.getSurface();
            mPreviewRequestBuilder.addTarget(surface);

            mCameraDevice.createCaptureSession(Arrays.asList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            mCaptureSession = session;
                            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                    CaptureRequest.CONTROL_MODE_OFF);
                            mPreviewRequestBuilder.set(CaptureRequest.FLASH_MODE,
                                    CaptureRequest.FLASH_MODE_TORCH);

                            // Finally, we start displaying the camera preview.
                            mPreviewRequest = mPreviewRequestBuilder.build();
                            try {
                                mCaptureSession.setRepeatingRequest(mPreviewRequest,
                                        null, mBackgroundHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {

                        }
                    }, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    ImageReader.OnImageAvailableListener imageAvailable = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Image image = reader.acquireLatestImage();
            if (image == null) {
                return;
            }

            long time = System.nanoTime();
            //Log.d("FPS", 1000000000.0 / (time - lastTime) + "");

            Mat buf = new Mat(image.getHeight(), image.getWidth(), CvType.CV_8UC1);

            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            buf.put(0, 0, bytes);


            Mat mat = Highgui.imdecode(buf, IMREAD_GRAYSCALE);
            Imgproc.threshold(mat,mat,100,255,Imgproc.THRESH_BINARY);
            Mat floatMat = new Mat(mat.rows(), mat.cols(), CvType.CV_32FC1);
            mat.convertTo(floatMat, CvType.CV_32FC1);


            if (lastImageMat != null) {
                Point point = Imgproc.phaseCorrelate(floatMat, lastImageMat);
                //switching vals because matricies are y then x
                mVelocity[0] = point.y;
                mVelocity[1] = point.x;
            }

            Bitmap bmp = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(mat, bmp);

            imageView.setImageBitmap(bmp);


            lastImageMat = floatMat;

            image.close();
            lastTime = time;
        }
    };

    private CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            mCameraDevice = camera;
            createCameraSession();
            Log.d("Camera Opened", "Opened");
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            mCameraDevice.close();
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {

        }
    };

    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    /**
     * Stops the background thread and its {@link Handler}.
     */
    private void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private View.OnTouchListener leftMouseButtonTouch = new View.OnTouchListener() {

        //listen for button up and down
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                mActionQueue.add(Actions.LEFT_PRESS);
            } else if (event.getAction() == MotionEvent.ACTION_UP) {
                mActionQueue.add(Actions.LEFT_RELEASE);
            }
            return true;
        }
    };

    private View.OnTouchListener rightMouseButtonTouch = new View.OnTouchListener() {

        //listen for button up and down
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                mActionQueue.add(Actions.RIGHT_PRESS);
            } else if (event.getAction() == MotionEvent.ACTION_UP) {
                mActionQueue.add(Actions.RIGHT_RELEASE);
            }
            return true;
        }
    };

}
