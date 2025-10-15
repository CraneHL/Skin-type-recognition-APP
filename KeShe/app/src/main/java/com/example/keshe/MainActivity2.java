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
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;

public class MainActivity2 extends AppCompatActivity {
    ImageView iv;
    Button btn3;
    Button btn4;
    TextView tv2;

    private static final int REQ_WRITE_EXTERNAL = 1001;

    // 保存刚生成的 bitmap 与文件名，以便在请求权限后继续保存到相册
    private Bitmap lastGeneratedBitmap = null;
    private String lastGeneratedFilename = null;

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

        // 从 Intent 取 image uri 并显示
        Intent intent = getIntent();
        String uriStr = intent.getStringExtra("image_uri");
        if (uriStr != null) {
            Uri imageUri = Uri.parse(uriStr);
            iv.setImageURI(imageUri);
            tv2.setText("检测报告：未实现（预测部分由你实现）。\n图片来源：" + imageUri.toString());
        } else {
            tv2.setText("未收到图片数据");
        }

        btn3.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                // 隐藏按钮，使生成的图片不包含按钮
                btn3.setVisibility(View.GONE);
                btn4.setVisibility(View.GONE);

                // 把整个 root 布局绘制成 bitmap
                View root = findViewById(R.id.main);
                Bitmap bitmap = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
                android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
                root.draw(canvas);

                // 恢复按钮显示
                btn3.setVisibility(View.VISIBLE);
                btn4.setVisibility(View.VISIBLE);

                // 生成文件名
                String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
                String filename = "report_" + timeStamp + ".jpg";

                // 先保存到 app 专属目录（原来的路径）
                try {
                    String savedPath = saveBitmapToAppPictures(bitmap, filename);
                    Toast.makeText(MainActivity2.this, "已保存到应用目录：" + savedPath, Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    e.printStackTrace();
                    Toast.makeText(MainActivity2.this, "保存到应用目录失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                }

                // 再保存到系统相册（MediaStore），但如果设备 < Q 需要申请写权限
                lastGeneratedBitmap = bitmap;
                lastGeneratedFilename = filename;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 10 及以上：直接写入 MediaStore，无需额外权限
                    try {
                        String uriStrSaved = saveBitmapToGallery(bitmap, filename);
                        Toast.makeText(MainActivity2.this, "已保存到相册（系统）", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        e.printStackTrace();
                        Toast.makeText(MainActivity2.this, "保存到相册失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                } else {
                    // Android 9 及以下：需要 WRITE_EXTERNAL_STORAGE 权限
                    if (ContextCompat.checkSelfPermission(MainActivity2.this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        // 请求权限；保存操作会在 onRequestPermissionsResult 中继续
                        ActivityCompat.requestPermissions(MainActivity2.this,
                                new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                                REQ_WRITE_EXTERNAL);
                    } else {
                        // 已有权限，直接保存
                        try {
                            String uriStrSaved = saveBitmapToGallery(bitmap, filename);
                            Toast.makeText(MainActivity2.this, "已保存到相册（系统）", Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            e.printStackTrace();
                            Toast.makeText(MainActivity2.this, "保存到相册失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            }
        });

        btn4.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                // 返回上一页
                finish();
            }
        });
    }

    /**
     * 保存到 app 专属外部目录（getExternalFilesDir(Environment.DIRECTORY_PICTURES)）
     * 返回保存的绝对路径
     */
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

    /**
     * 保存 bitmap 到系统相册（兼容 Android Q+ 与 旧版）
     * 返回写入后的 Uri 字符串（Q+）或文件绝对路径（<Q）
     */
    private String saveBitmapToGallery(Bitmap bmp, String filename) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            // 放到 Pictures/YourAppFolder 文件夹下（相册中会显示 YourAppFolder）
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
            // Android 9 及以下：写入公共 Pictures 目录，并扫入媒体库
            File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
            File appDir = new File(picturesDir, "KesheReports");
            if (!appDir.exists()) appDir.mkdirs();
            File outFile = new File(appDir, filename);
            try (FileOutputStream out = new FileOutputStream(outFile)) {
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, out);
                out.flush();
            }
            // 通知媒体库刷新
            MediaScannerConnection.scanFile(this, new String[]{outFile.getAbsolutePath()}, new String[]{"image/jpeg"}, null);
            return outFile.getAbsolutePath();
        }
    }

    // 处理权限请求结果（针对低于 Q 的设备）
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_WRITE_EXTERNAL) {
            if (grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                // 权限允许：继续把之前生成的 bitmap 保存到相册
                if (lastGeneratedBitmap != null && lastGeneratedFilename != null) {
                    try {
                        saveBitmapToGallery(lastGeneratedBitmap, lastGeneratedFilename);
                        Toast.makeText(this, "已保存到相册（系统）", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        e.printStackTrace();
                        Toast.makeText(this, "保存到相册失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                }
            } else {
                Toast.makeText(this, "需要存储权限才能保存到相册（已保存到应用目录）", Toast.LENGTH_LONG).show();
            }
        }
    }
}
