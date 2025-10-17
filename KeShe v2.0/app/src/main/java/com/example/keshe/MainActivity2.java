package com.example.keshe;

import android.Manifest;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.content.ContextCompat;
import androidx.core.app.ActivityCompat;
import androidx.exifinterface.media.ExifInterface;

import android.util.Log;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.io.InputStream;

import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class MainActivity2 extends AppCompatActivity {
    private static final String TAG = "MainActivity2";

    ImageView iv;
    Button btn3;
    Button btn4;
    TextView tv2;
    TextView tvRecommendation;

    private static final int REQ_WRITE_EXTERNAL = 1001;

    private Bitmap lastGeneratedBitmap = null;
    private String lastGeneratedFilename = null;

    private final ExecutorService initExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService inferExecutor = Executors.newFixedThreadPool(2);

    private TFLiteHelper tfliteHelper = null;

    private static final String MODEL_NAME = "skin_model.tflite";
    private static final int MODEL_INPUT_SIZE = 224; // 请按实际模型调整
    private static final int TOP_K = 3;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main2);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        iv = findViewById(R.id.iv);
        btn3 = findViewById(R.id.btn3);
        btn4 = findViewById(R.id.btn4);
        tv2 = findViewById(R.id.tv2);
        tvRecommendation = findViewById(R.id.tvRecommendation);

        tv2.setText("准备中...");
        tvRecommendation.setText("");

        // 模型初始化（放到 initExecutor）
        initExecutor.submit(() -> {
            try {
                Log.i(TAG, "开始创建 TFLiteHelper（CPU-only, safe mode）...");
                tfliteHelper = new TFLiteHelper(MainActivity2.this, MODEL_NAME, MODEL_INPUT_SIZE);
                tfliteHelper.setNumThreads(4);
                boolean ok = tfliteHelper.selfTest();
                Log.i(TAG, "selfTest result: " + ok);
                runOnUiThread(() -> {
                    if (ok) tv2.setText("模型加载完成，等待图片...");
                    else tv2.setText("模型加载完成，但 selfTest 失败（请查看 Logcat）");
                });
            } catch (Exception e) {
                Log.e(TAG, "模型初始化异常: ", e);
                runOnUiThread(() -> tv2.setText("模型初始化失败: " + e.getMessage()));
            }
        });

        // 处理传入图片
        Intent intent = getIntent();
        String uriStr = intent.getStringExtra("image_uri");
        if (uriStr != null) {
            Uri imageUri = Uri.parse(uriStr);
            // 先尝试快速设置 uri（ImageView 会在后续设置 bitmap）
            iv.setImageURI(imageUri);
            tv2.setText("正在准备检测... 图片来源: " + imageUri.toString());

            // 执行读取 + 推理
            inferExecutor.submit(() -> {
                long overallStart = System.currentTimeMillis();
                try {
                    Log.i(TAG, "开始从 Uri 读取 Bitmap...");
                    long t0 = System.currentTimeMillis();
                    Bitmap bmp = loadBitmapFromUri(imageUri);
                    long t1 = System.currentTimeMillis();
                    if (bmp == null) {
                        Log.e(TAG, "从 Uri 读取 Bitmap 为空");
                        runOnUiThread(() -> tv2.setText("读取图片失败"));
                        return;
                    }
                    Log.i(TAG, "Bitmap 读取成功，宽高: " + bmp.getWidth() + "x" + bmp.getHeight() + ", decode耗时(ms): " + (t1 - t0));

                    // 在主线程设置图片
                    runOnUiThread(() -> {
                        iv.setImageBitmap(bmp);
                        Log.i(TAG, "ImageView bitmap 已设置");
                    });

                    // 等待模型就绪（最多 25s）
                    long waitStart = System.currentTimeMillis();
                    while (tfliteHelper == null && System.currentTimeMillis() - waitStart < 25000) {
                        Log.i(TAG, "等待 TFLiteHelper 初始化...");
                        Thread.sleep(200);
                    }
                    if (tfliteHelper == null) {
                        Log.e(TAG, "模型未能在 25s 内初始化");
                        runOnUiThread(() -> tv2.setText("模型初始化超时"));
                        return;
                    }

                    // 推理任务（主推理超时：60s）
                    final long INFER_TIMEOUT_MS = 60000L;
                    Callable<List<TFLiteHelper.Prediction>> task = () -> {
                        Log.i(TAG, "推理任务开始 (timestamp=" + System.currentTimeMillis() + ")");
                        long pre0 = System.currentTimeMillis();
                        Bitmap small = Bitmap.createScaledBitmap(bmp, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE, true);
                        long pre1 = System.currentTimeMillis();
                        Log.i(TAG, "Preprocess: scaled bitmap to " + MODEL_INPUT_SIZE + "x" + MODEL_INPUT_SIZE +
                                ", preprocess耗时(ms): " + (pre1 - pre0));
                        List<TFLiteHelper.Prediction> preds = tfliteHelper.classify(small, TOP_K);
                        Log.i(TAG, "推理任务结束 (timestamp=" + System.currentTimeMillis() + ")");
                        return preds;
                    };

                    Future<List<TFLiteHelper.Prediction>> future = inferExecutor.submit(task);
                    List<TFLiteHelper.Prediction> preds = null;
                    try {
                        preds = future.get(INFER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    } catch (java.util.concurrent.TimeoutException tex) {
                        future.cancel(true);
                        Log.e(TAG, "推理超时: " + tex.toString(), tex);
                        runOnUiThread(() -> tv2.setText("推理超时: " + tex.toString() + "\n（已取消任务）\n请查看 Logcat 获取详细堆栈"));
                        // 尝试降级
                        try {
                            Log.i(TAG, "尝试降级推理：将图片缩小到128并重试");
                            Bitmap tiny = Bitmap.createScaledBitmap(bmp, 128, 128, true);
                            Callable<List<TFLiteHelper.Prediction>> quick = () -> tfliteHelper.classify(Bitmap.createScaledBitmap(tiny, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE, true), TOP_K);
                            Future<List<TFLiteHelper.Prediction>> f2 = inferExecutor.submit(quick);
                            List<TFLiteHelper.Prediction> r2 = f2.get(20000, TimeUnit.MILLISECONDS);
                            if (r2 != null && !r2.isEmpty()) {
                                StringBuilder sb = new StringBuilder("降级推理（小图）成功：\n");
                                for (TFLiteHelper.Prediction p : r2) sb.append(String.format("%s：%.3f\n", p.label, p.prob));
                                final String out = sb.toString();
                                runOnUiThread(() -> tv2.setText(out + "\n（原始推理超时，已降级成功）"));
                                // 同时显示建议
                                runOnUiThread(() -> {
                                    tvRecommendation.setText(getRecommendationForLabel(r2.get(0).label));
                                });
                                return;
                            }
                        } catch (Exception ex2) {
                            Log.e(TAG, "降级推理也失败: ", ex2);
                            String st = Log.getStackTraceString(ex2);
                            runOnUiThread(() -> tv2.setText("降级推理失败:\n" + st));
                            return;
                        }
                        return;
                    } catch (Exception ex) {
                        future.cancel(true);
                        Log.e(TAG, "推理异常: ", ex);
                        String st = Log.getStackTraceString(ex);
                        runOnUiThread(() -> tv2.setText("推理失败:\n" + st));
                        return;
                    }

                    if (preds == null || preds.isEmpty()) {
                        Log.w(TAG, "推理返回为空");
                        runOnUiThread(() -> {
                            tv2.setText("未能产生预测结果");
                            tvRecommendation.setText("");
                        });
                    } else {
                        StringBuilder sb = new StringBuilder();
                        sb.append("检测报告：\n");
                        for (TFLiteHelper.Prediction p : preds) {
                            sb.append(String.format("%s：%.3f\n", p.label, p.prob));
                        }
                        TFLiteHelper.Prediction top = preds.get(0);
                        sb.append(String.format("\n最可能：%s (置信度 %.3f)", top.label, top.prob));
                        final String out = sb.toString();
                        Log.i(TAG, "推理结果: " + out.replace("\n", " | "));
                        runOnUiThread(() -> {
                            tv2.setText(out);
                            tvRecommendation.setText(getRecommendationForLabel(top.label));
                        });
                    }

                    long overallEnd = System.currentTimeMillis();
                    Log.i(TAG, "总耗时(ms): " + (overallEnd - overallStart));
                } catch (Exception e) {
                    Log.e(TAG, "图片读取或推理时发生异常: ", e);
                    String stack = Log.getStackTraceString(e);
                    runOnUiThread(() -> tv2.setText("推理失败:\n" + stack));
                }
            });
        } else {
            tv2.setText("未收到图片数据");
        }

        btn3.setOnClickListener(v -> {
            btn3.setVisibility(View.GONE);
            btn4.setVisibility(View.GONE);

            View root = findViewById(R.id.main);
            Bitmap bitmap = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
            root.draw(canvas);

            btn3.setVisibility(View.VISIBLE);
            btn4.setVisibility(View.VISIBLE);

            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            String filename = "report_" + timeStamp + ".jpg";

            try {
                String savedPath = saveBitmapToAppPictures(bitmap, filename);
                Toast.makeText(MainActivity2.this, "已保存到应用目录：" + savedPath, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                e.printStackTrace();
                Toast.makeText(MainActivity2.this, "保存到应用目录失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
            }

            lastGeneratedBitmap = bitmap;
            lastGeneratedFilename = filename;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    saveBitmapToGallery(bitmap, filename);
                    Toast.makeText(MainActivity2.this, "已保存到相册（系统）", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    e.printStackTrace();
                    Toast.makeText(MainActivity2.this, "保存到相册失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            } else {
                if (ContextCompat.checkSelfPermission(MainActivity2.this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(MainActivity2.this,
                            new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                            REQ_WRITE_EXTERNAL);
                } else {
                    try {
                        saveBitmapToGallery(bitmap, filename);
                        Toast.makeText(MainActivity2.this, "已保存到相册（系统）", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        e.printStackTrace();
                        Toast.makeText(MainActivity2.this, "保存到相册失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });

        btn4.setOnClickListener(v -> finish());
    }

    // 读取 Uri 并正确处理 EXIF 方向
    private Bitmap loadBitmapFromUri(Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(is, null, opts);
        } catch (Exception e) {
            Log.w(TAG, "第一次 decodeBounds 失败，尝试简单 decode: " + e.getMessage(), e);
        }

        try (InputStream is2 = getContentResolver().openInputStream(uri)) {
            BitmapFactory.Options opts2 = new BitmapFactory.Options();
            opts2.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bmp = BitmapFactory.decodeStream(is2, null, opts2);

            // 读取 EXIF 并旋转（如果需要）
            try (InputStream is3 = getContentResolver().openInputStream(uri)) {
                ExifInterface exif = new ExifInterface(is3);
                int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                bmp = rotateBitmapIfRequired(bmp, orientation);
            } catch (Exception ex) {
                Log.w(TAG, "读取 EXIF 失败: " + ex.getMessage(), ex);
            }

            return bmp;
        } catch (Exception e) {
            Log.e(TAG, "loadBitmapFromUri 异常: " + e.getMessage(), e);
            return null;
        }
    }

    private Bitmap rotateBitmapIfRequired(Bitmap img, int orientation) {
        if (img == null) return null;
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.postRotate(90);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.postRotate(180);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.postRotate(270);
                break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.postScale(1, -1);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.postRotate(90);
                matrix.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.postRotate(270);
                matrix.postScale(-1, 1);
                break;
            default:
                return img;
        }
        try {
            Bitmap rotated = Bitmap.createBitmap(img, 0, 0, img.getWidth(), img.getHeight(), matrix, true);
            if (rotated != img) {
                img.recycle();
            }
            return rotated;
        } catch (Exception e) {
            Log.w(TAG, "rotateBitmapIfRequired 失败: " + e.getMessage(), e);
            return img;
        }
    }

    private String saveBitmapToAppPictures(Bitmap bmp, String filename) throws Exception {
        File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (storageDir != null && !storageDir.exists()) storageDir.mkdirs();
        File outFile = new File(storageDir, filename);
        try (FileOutputStream out = new FileOutputStream(outFile)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, out);
            out.flush();
        }
        return outFile.getAbsolutePath();
    }

    private String saveBitmapToGallery(Bitmap bmp, String filename) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/KesheReports");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);

            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new Exception("无法在 MediaStore 中创建条目");

            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new Exception("打开 MediaStore 输出流失败");
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, out);
            }

            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            getContentResolver().update(uri, values, null, null);

            return uri.toString();
        } else {
            File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
            File appDir = new File(picturesDir, "KesheReports");
            if (!appDir.exists()) appDir.mkdirs();
            File outFile = new File(appDir, filename);
            try (FileOutputStream out = new FileOutputStream(outFile)) {
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, out);
                out.flush();
            }
            MediaScannerConnection.scanFile(this, new String[]{outFile.getAbsolutePath()}, new String[]{"image/jpeg"}, null);
            return outFile.getAbsolutePath();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tfliteHelper != null) {
            tfliteHelper.close();
            tfliteHelper = null;
        }
        try { initExecutor.shutdownNow(); } catch (Exception ignored) {}
        try { inferExecutor.shutdownNow(); } catch (Exception ignored) {}
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_WRITE_EXTERNAL) {
            if (grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                if (lastGeneratedBitmap != null && lastGeneratedFilename != null) {
                    try {
                        saveBitmapToGallery(lastGeneratedBitmap, lastGeneratedFilename);
                        Toast.makeText(this, "已保存到相册（系统）", Toast.LENGTH_LONG).show();
                    } catch (Exception e) {
                        e.printStackTrace();
                        Toast.makeText(this, "保存到相册失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }
            } else {
                Toast.makeText(this, "需要存储权限才能保存到相册（已保存到应用目录）", Toast.LENGTH_LONG).show();
            }
        }
    }

    // 根据 label 提供建议文本（示例，请按实际 label 调整）
    private String getRecommendationForLabel(String label) {
        if (label == null) return "";
        label = label.toLowerCase();
        switch (label) {
            case "oily":
            case "油性":
            case "油性皮肤":
                return "你的皮肤类型为：油性皮肤。\n建议：\n• 使用温和洁面，避免过度清洁导致皮脂失衡。\n• 白天使用控油、清爽型保湿产品（含水杨酸或茶树成分可帮助控油）。\n• 每周使用1-2次吸附性面膜（如泥膜），但避免频繁强力去油。\n• 注意防晒，选择清爽型防晒产品。";
            case "dry":
            case "干性":
            case "干性皮肤":
                return "你的皮肤类型为：干性皮肤。\n建议：\n• 使用温和保湿的洁面产品，避免含酒精的化妆品。\n• 加强保湿：使用含透明质酸、甘油、神经酰胺的产品。\n• 夜间使用滋润型面霜，必要时使用面油锁水。\n• 避免长期热水洗脸，注意补水与防晒。";
            case "normal":
            case "中性":
            case "中性皮肤":
                return "你的皮肤类型为：中性皮肤。\n建议：\n• 保持日常温和清洁与补水即可。\n• 选择适合肤质的轻薄保湿和防晒产品。\n• 根据季节调整护肤品（冬季加强保湿）。";
            case "combination":
            case "混合":
            case "混合性":
                return "你的皮肤类型为：混合性皮肤。\n建议：\n• T区可使用控油产品，U区（两颊）注重保湿。\n• 使用温和、平衡型护肤步骤，避免全脸一刀切的强力控油产品。\n• 定期去角质（注意频率）以保持毛孔通畅。";
            case "sensitive":
            case "敏感":
            case "敏感性":
                return "你的皮肤类型为：敏感皮肤。\n建议：\n• 使用简洁、低刺激的护肤配方，避免含酒精、香精的产品。\n• 新产品先在耳后或下颌试用，观察24-48小时再全脸使用。\n• 如果皮肤明显不适，建议就医并遵循医生建议。";
            default:
                return "未识别到明确的皮肤类型（label=" + label + "）。\n建议：\n• 保持温和清洁与基础保湿；\n• 如需更准确诊断，请前往皮肤科或专业机构。";
        }
    }
}
