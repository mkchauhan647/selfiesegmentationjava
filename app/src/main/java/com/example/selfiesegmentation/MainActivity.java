package com.example.selfiesegmentation;//package com.example.selfiesegmentation;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.common.MlKitException;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.segmentation.Segmentation;
import com.google.mlkit.vision.segmentation.SegmentationMask;
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private ExecutorService cameraExecutor;
    private ImageView outputImageView;
    private ImageAnalysis imageAnalysis;
    private int selectedSegmentationStyle;
    private OverlayView overlayView;
    private boolean showInImageView = false; // Track view state
    private Button toggleButton;

    private static final String TAG = "CameraXBasic";
    private static final int REQUEST_CODE_PERMISSIONS = 10;
    private static final String[] REQUIRED_PERMISSIONS = new String[] {
            Manifest.permission.CAMERA
    };
    private CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        outputImageView = findViewById(R.id.outputImageView);
        overlayView = findViewById(R.id.overlayView);
        toggleButton = findViewById(R.id.toggleButton);

        // Set up the toggle button
        toggleButton.setOnClickListener(view -> {
            showInImageView = !showInImageView; // Toggle between the views
            if (showInImageView) {
                // Show ImageView, hide OverlayView
                outputImageView.setVisibility(View.VISIBLE);
                overlayView.setVisibility(View.GONE);
                toggleButton.setText("Switch to OverlayView");
            } else {
                // Show OverlayView, hide ImageView
                outputImageView.setVisibility(View.GONE);
                overlayView.setVisibility(View.VISIBLE);
                toggleButton.setText("Switch to ImageView");
            }
        });

        // Set up segmentation options (e.g., grayscale, colored overlay)
        Spinner segmentationOptions = findViewById(R.id.segmentationOptions);
        segmentationOptions.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedSegmentationStyle = position;
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        Button switchCameraButton = findViewById(R.id.switchCameraButton);
        switchCameraButton.setOnClickListener(view -> {
            if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
                cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;
            } else {
                cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
            }
//            startCamera(); // Restart the camera with the new selector
            // Request camera permissions
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                ActivityCompat.requestPermissions(
                        this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
                );
            }
        });





        cameraExecutor = Executors.newSingleThreadExecutor();
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                // Preview
                Preview preview = new Preview.Builder().build();
                PreviewView previewView = findViewById(R.id.viewFinder);
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                // Real-time Image Analysis
                imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, imageProxy -> {
                    processImageProxy(imageProxy);
                });

                // Select back camera as a default
                CameraSelector cameraSelector = this.cameraSelector;

                // Bind use cases to camera
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(
                        this, cameraSelector, preview, imageAnalysis
                );

            } catch (Exception exc) {
                Log.e(TAG, "Use case binding failed", exc);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(
                    getBaseContext(), permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private void processImageProxy(ImageProxy imageProxy) {
        // Ensure the image is not null before processing
        if (imageProxy.getImage() == null) {
            imageProxy.close();
            return;
        }

        // Convert ImageProxy to Bitmap

//        Bitmap bitmap = imageProxyToBitmap(imageProxy);

        Bitmap bitmap = yuvToRgb(imageProxy);


        if (bitmap == null) {
            Log.e(TAG, "Failed to convert ImageProxy to Bitmap");
            imageProxy.close();
            return;
        }

        // Convert ImageProxy to InputImage for ML Kit segmentation
        InputImage inputImage = InputImage.fromMediaImage(
                Objects.requireNonNull(imageProxy.getImage()),
                imageProxy.getImageInfo().getRotationDegrees()
        );

        // Perform segmentation and close the imageProxy after completion
        segmentImage(inputImage, bitmap, imageProxy);
    }

    private void segmentImage(InputImage image, Bitmap bitmap, ImageProxy imageProxy) {
        SelfieSegmenterOptions options = new SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)  // Real-time segmentation mode
                .enableRawSizeMask()
                .build();

        Segmentation.getClient(options).process(image)
                .addOnSuccessListener(mask -> {
                    // Process the segmentation mask using the converted Bitmap
                    processSegmentationMask(mask, bitmap);

                    // Close the imageProxy after processing
                    imageProxy.close();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Segmentation failed: " + e.getMessage(), e);
                    imageProxy.close();
                });
    }

    @SuppressLint("UnsafeOptInUsageError")
    private void processSegmentationMask(SegmentationMask mask, Bitmap bitmap) {
        if (bitmap == null) {
            Log.e(TAG, "Bitmap is null. Cannot process segmentation.");
            return;
        }

        // Resize the bitmap to match the mask dimensions
        Bitmap resizedBitmap = Bitmap.createScaledBitmap(bitmap, mask.getWidth(), mask.getHeight(), true);

        // Create a mutable bitmap to draw on
        Bitmap mutableBitmap = resizedBitmap.copy(Bitmap.Config.ARGB_8888, true);

        // Get the buffer for the segmentation mask
        ByteBuffer buffer = mask.getBuffer();
        buffer.rewind(); // Ensure the buffer is at the start

        // Get the width and height of the mask
        int width = mask.getWidth();
        int height = mask.getHeight();

        // Create a pixel array to hold the bitmap pixels
        int[] pixels = new int[width * height];
        mutableBitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        // Iterate over the mask pixels and modify the bitmap pixels based on the selected style
        for (int i = 0; i < width * height; i++) {
            float confidence = buffer.getFloat();

            int x = i % width;  // x-coordinate (column number)
            int y = i / width;  // y-coordinate (row number)


            if (confidence < 0.5f) { // Background pixel
                int color = pixels[i];
                int alpha = Color.alpha(color);
                int red = Color.red(color);
                int green = Color.green(color);
                int blue = Color.blue(color);

                switch (selectedSegmentationStyle) {
                    case 0: // Grayscale background
                        int gray = (red + green + blue) / 3;
                        pixels[i] = Color.argb(alpha, gray, gray, gray);
                        break;

                    case 1: // Red overlay
                        pixels[i] = Color.argb(128, 255, 0, 0); // Semi-transparent red
                        break;

                    case 2: // Blue overlay
                        pixels[i] = Color.argb(128, 0, 0, 255); // Semi-transparent blue
                        break;

                    case 3: // Green overlay
                        pixels[i] = Color.argb(128, 0, 255, 0); // Semi-transparent green
                        break;

                    case 4: // Inverted colors
                        int invertedRed = 255 - red;
                        int invertedGreen = 255 - green;
                        int invertedBlue = 255 - blue;
                        pixels[i] = Color.argb(alpha, invertedRed, invertedGreen, invertedBlue);
                        break;

                    case 5: // Sepia tone
                        int sepiaRed = (int)(red * 0.393 + green * 0.769 + blue * 0.189);
                        int sepiaGreen = (int)(red * 0.349 + green * 0.686 + blue * 0.168);
                        int sepiaBlue = (int)(red * 0.272 + green * 0.534 + blue * 0.131);
                        sepiaRed = Math.min(255, sepiaRed);
                        sepiaGreen = Math.min(255, sepiaGreen);
                        sepiaBlue = Math.min(255, sepiaBlue);
                        pixels[i] = Color.argb(alpha, sepiaRed, sepiaGreen, sepiaBlue);
                        break;

                    case 6: // Pixelation effect
                        if (i % 10 == 0) {
                            int blockGray = (red + green + blue) / 3;
                            pixels[i] = Color.argb(alpha, blockGray, blockGray, blockGray);
                        }
                        break;

                    case 7: // Vignette effect
                        int centerX = width / 2;
                        int centerY = height / 2;
                        double radius = Math.sqrt(Math.pow(x - centerX, 2) + Math.pow(y - centerY, 2));
                        double maxRadius = Math.sqrt(Math.pow(centerX, 2) + Math.pow(centerY, 2));
                        double vignetteFactor = radius / maxRadius;
                        int vignetteGray = (int)(255 - vignetteFactor * 255);
                        pixels[i] = Color.argb(alpha, vignetteGray, vignetteGray, vignetteGray);
                        break;

                    case 8: // Black and White
                        int bw = (red + green + blue) / 3;
                        pixels[i] = Color.argb(alpha, bw, bw, bw);
                        break;

                    case 9: // Brighten
                        int brightRed = Math.min(255, red + 50);
                        int brightGreen = Math.min(255, green + 50);
                        int brightBlue = Math.min(255, blue + 50);
                        pixels[i] = Color.argb(alpha, brightRed, brightGreen, brightBlue);
                        break;

                    case 10: // Darken
                        int darkRed = Math.max(0, red - 50);
                        int darkGreen = Math.max(0, green - 50);
                        int darkBlue = Math.max(0, blue - 50);
                        pixels[i] = Color.argb(alpha, darkRed, darkGreen, darkBlue);
                        break;

                    case 11: // Red Tint
                        pixels[i] = Color.argb(128, Math.min(255, red + 100), green, blue);
                        break;

                    case 12: // Green Tint
                        pixels[i] = Color.argb(128, red, Math.min(255, green + 100), blue);
                        break;

                    case 13: // Blue Tint
                        pixels[i] = Color.argb(128, red, green, Math.min(255, blue + 100));
                        break;

                    case 14: // Warm Tone
                        int warmRed = Math.min(255, red + 50);
                        int warmYellow = Math.min(255, green + 30);
                        pixels[i] = Color.argb(alpha, warmRed, warmYellow, blue);
                        break;

                    case 15: // Cold Tone
                        int coldBlue = Math.min(255, blue + 50);
                        pixels[i] = Color.argb(alpha, red, green, coldBlue);
                        break;

                    case 16: // Increase Contrast
                        int contrastRed = (int) ((red - 128) * 1.5 + 128);
                        int contrastGreen = (int) ((green - 128) * 1.5 + 128);
                        int contrastBlue = (int) ((blue - 128) * 1.5 + 128);
                        contrastRed = Math.min(255, Math.max(0, contrastRed));
                        contrastGreen = Math.min(255, Math.max(0, contrastGreen));
                        contrastBlue = Math.min(255, Math.max(0, contrastBlue));
                        pixels[i] = Color.argb(alpha, contrastRed, contrastGreen, contrastBlue);
                        break;

                    case 17: // Blur effect (Approximation: Reduce sharpness)
                        if (i > 1 && i < width * height - 1) {
                            int avgRed = (red + Color.red(pixels[i - 1]) + Color.red(pixels[i + 1])) / 3;
                            int avgGreen = (green + Color.green(pixels[i - 1]) + Color.green(pixels[i + 1])) / 3;
                            int avgBlue = (blue + Color.blue(pixels[i - 1]) + Color.blue(pixels[i + 1])) / 3;
                            pixels[i] = Color.argb(alpha, avgRed, avgGreen, avgBlue);
                        }
                        break;

                    default:
//                        gray = (red + green + blue) / 3;
//                        pixels[i] = Color.argb(alpha, gray, gray, gray);
                        break;
                }

            }
        }

        // Set the modified pixels back to the bitmap
        mutableBitmap.setPixels(pixels, 0, width, 0, 0, width, height);

        // Update the ImageView with the modified bitmap or overlay it
        runOnUiThread(() -> {
            if (!showInImageView) {
                overlayView.setOverlayBitmap(mutableBitmap); // If you are using an overlay
            } else {
                outputImageView.setImageBitmap(mutableBitmap); // If showing in ImageView
            }
        });
    }


    private Bitmap yuvToRgb(ImageProxy imageProxy) {
    ImageProxy.PlaneProxy[] planes = imageProxy.getPlanes();
    ByteBuffer yBuffer = planes[0].getBuffer(); // Y
    ByteBuffer uBuffer = planes[1].getBuffer(); // U
    ByteBuffer vBuffer = planes[2].getBuffer(); // V

    int ySize = yBuffer.remaining();
    int uSize = uBuffer.remaining();
    int vSize = vBuffer.remaining();

    byte[] nv21 = new byte[ySize + uSize + vSize];

    // U and V are swapped
    yBuffer.get(nv21, 0, ySize);
    vBuffer.get(nv21, ySize, vSize);
    uBuffer.get(nv21, ySize + vSize, uSize);

    YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, imageProxy.getWidth(), imageProxy.getHeight(), null);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    yuvImage.compressToJpeg(new Rect(0, 0, imageProxy.getWidth(), imageProxy.getHeight()), 100, out);
    byte[] imageBytes = out.toByteArray();
    return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
}


    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
    }
}

