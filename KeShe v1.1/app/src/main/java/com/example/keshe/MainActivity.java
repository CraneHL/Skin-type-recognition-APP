package com.example.keshe;

import android.content.Intent;
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
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class MainActivity extends AppCompatActivity {
    TextView tv;
    Button btn1;
    Button btn2;

    private Uri cameraImageUri = null;

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

        // 点击拍照：先创建一个文件，然后用 FileProvider 获取 uri，再调用 takePictureLauncher
        btn1.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

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

    // 启动 MainActivity2，并把图片 Uri 通过 Intent 传过去（用字符串）
    private void goToPreviewActivity(Uri imageUri) {
        Intent it = new Intent(MainActivity.this, MainActivity2.class);
        it.putExtra("image_uri", imageUri.toString());
        startActivity(it);
    }
}
