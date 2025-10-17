package com.example.keshe;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.app.ActivityCompat;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class MainActivity extends AppCompatActivity {
    TextView tv;
    Button btn1;
    Button btn2;

    private Uri cameraImageUri = null;

    private static final int REQ_CAMERA = 2001;

    // 拍照 launcher：使用 ACTION_IMAGE_CAPTURE + EXTRA_OUTPUT
    private ActivityResultLauncher<Uri> takePictureLauncher;

    // 选图 launcher：使用 ACTION_PICK（图片）
    private ActivityResultLauncher<String> pickImageLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 先设置布局
        setContentView(R.layout.activity_main);

        // EdgeToEdge padding
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // findViewById
        tv = findViewById(R.id.tv1);
        btn1 = findViewById(R.id.btn1);
        btn2 = findViewById(R.id.btn2);

        // 显示启动免责声明对话框（必须同意才继续）
        showStartupConsentDialog();

        // 初始化 launcher：拍照（传入 Uri）
        takePictureLauncher = registerForActivityResult(
                new ActivityResultContracts.TakePicture(),
                new ActivityResultCallback<Boolean>() {
                    @Override
                    public void onActivityResult(Boolean result) {
                        if (result != null && result) {
                            // 拍照成功：cameraImageUri 已经是图片的 Uri
                            goToPreviewActivity(cameraImageUri);
                        } else {
                            Toast.makeText(MainActivity.this, "拍照失败或取消", Toast.LENGTH_SHORT).show();
                        }
                    }
                }
        );

        // 初始化选图 launcher：返回图片的 content Uri 字符串
        pickImageLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                new ActivityResultCallback<Uri>() {
                    @Override
                    public void onActivityResult(Uri uri) {
                        if (uri != null) {
                            // 选图成功，直接进入预览 Activity 并传 uri
                            goToPreviewActivity(uri);
                        } else {
                            Toast.makeText(MainActivity.this, "未选择图片", Toast.LENGTH_SHORT).show();
                        }
                    }
                }
        );

        // 点击拍照：先检查权限，再创建一个文件，然后用 FileProvider 获取 uri，再调用 takePictureLauncher
        btn1.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA)
                        != PackageManager.PERMISSION_GRANTED) {
                    // 请求权限
                    ActivityCompat.requestPermissions(MainActivity.this,
                            new String[]{Manifest.permission.CAMERA},
                            REQ_CAMERA);
                } else {
                    // 有权限 -> 打开相机
                    openCamera();
                }
            }
        });

        // 选取图片：使用 GetContent，mime type = "image/*"
        btn2.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickImageLauncher.launch("image/*");
            }
        });
    }

    // 创建一个用于保存照片的文件（放在 getExternalFilesDir(Environment.DIRECTORY_PICTURES)）
    private File createImageFile() throws IOException {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String imageFileName = "JPEG_" + timeStamp + "_";
        File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (storageDir != null && !storageDir.exists()) storageDir.mkdirs();
        File image = File.createTempFile(imageFileName, ".jpg", storageDir);
        return image;
    }

    // 打开相机（假设已有 CAMERA 权限）
    private void openCamera() {
        try {
            File photoFile = createImageFile();
            cameraImageUri = FileProvider.getUriForFile(
                    MainActivity.this,
                    getPackageName() + ".fileprovider",
                    photoFile
            );
            // 启动系统相机并把图片写入我们创建的文件
            takePictureLauncher.launch(cameraImageUri);
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(MainActivity.this, "无法创建图片文件：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // 启动 MainActivity2，并把图片 Uri 通过 Intent 传过去（用字符串）
    private void goToPreviewActivity(Uri imageUri) {
        Intent it = new Intent(MainActivity.this, MainActivity2.class);
        it.putExtra("image_uri", imageUri.toString());
        startActivity(it);
    }

    // 权限回调（用于 CAMERA 请求）
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openCamera();
            } else {
                Toast.makeText(this, "需要相机权限才能拍照（可在设置中允许）", Toast.LENGTH_LONG).show();
            }
        }
    }

    // 启动时显示的免责声明弹窗（必须同意才能继续）
    private void showStartupConsentDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("使用须知");
        builder.setMessage("本应用提供的检测与建议仅供参考，不能替代专业医疗诊断。如有皮肤问题，请以医院或皮肤科医生的诊断为准。点击“继续”表示你已阅读并理解本提示。");
        builder.setCancelable(false);
        builder.setNegativeButton("退出app", (dialog, which) -> {
            dialog.dismiss();
            finish();
        });
        builder.setPositiveButton("继续", (dialog, which) -> {
            dialog.dismiss();
        });
        AlertDialog dlg = builder.create();
        dlg.show();

        // 将退出按钮放到左下（AlertDialog 默认样式可能已处理好，保持系统默认即可）
    }
}
