package com.example.keshe;

import android.Manifest;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.content.ContextCompat;
import androidx.core.app.ActivityCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity2 extends AppCompatActivity {
    ImageView iv;
    Button btn3;
    Button btn4;
    TextView tv2;

    private static final int REQ_WRITE_EXTERNAL = 1001;
    private Bitmap lastGeneratedBitmap = null;
    private String lastGeneratedFilename = null;

    // TFLite
    private TFLiteHelper tfliteHelper = null;
    private ExecutorService executor;
    private Handler mainHandler;

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

        executor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());

        // 初始化 TFLiteHelper（在后台线程加载模型）
        executor.submit(() -> {
            try {
                tfliteHelper = new TFLiteHelper(MainActivity2.this, "skin_model.tflite", "labels.txt", 224);
            } catch (Exception e) {
                e.printStackTrace();
                mainHandler.post(() -> Toast.makeText(MainActivity2.this, "模型加载失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });

        // 获取 Intent 里的图片 Uri 并显示
        Intent intent = getIntent();
        String uriStr = intent.getStringExtra("image_uri");
        if (uriStr != null) {
            Uri imageUri = Uri.parse(uriStr);
            iv.setImageURI(imageUri);
            tv2.setText("正在准备检测... 图片来源：" + imageUri.toString());

            // 在后台线程进行推理（先把 Bitmap 读出来）
            executor.submit(() -> {
                try {
                    Bitmap bmp = loadBitmapFromUri(imageUri);
                    if (bmp == null) {
                        mainHandler.post(() -> tv2.setText("无法读取图片"));
                        return;
                    }

                    // 显示缩略（在 UI 线程）
                    mainHandler.post(() -> iv.setImageBitmap(bmp));

                    // 等待模型加载（如果还没加载完成）
                    while (tfliteHelper == null) {
                        Thread.sleep(100);
                    }

                    List<TFLiteHelper.Prediction> preds = tfliteHelper.classify(bmp, 3);
                    StringBuilder sb = new StringBuilder();
                    sb.append("检测报告：\n");
                    if (preds.isEmpty()) {
                        sb.append("未能产生结果");
                    } else {
                        for (TFLiteHelper.Prediction p : preds) {
                            sb.append(String.format("%s：%.3f\n", p.label, p.prob));
                        }
                    }
                    final String out = sb.toString();
                    mainHandler.post(() -> tv2.setText(out));
                } catch (Exception e) {
                    e.printStackTrace();
                    mainHandler.post(() -> tv2.setText("推理失败: " + e.getMessage()));
                }
            });
        } else {
            tv2.setText("未收到图片数据");
        }

        // 生成并保存图片按钮（保持原有功能）
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

    private Bitmap loadBitmapFromUri(Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bmp = BitmapFactory.decodeStream(is, null, opts);
            return bmp;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
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
        if (executor != null) {
            executor.shutdownNow();
        }
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
}
