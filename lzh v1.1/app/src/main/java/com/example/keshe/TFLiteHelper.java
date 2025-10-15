package com.example.keshe;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.tensorflow.lite.Interpreter;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

public class TFLiteHelper {
    private static final String TAG = "TFLiteHelper";
    private Interpreter interpreter = null;
    private List<String> labels = null;
    private final int inputSize;
    private final int pixelSize = 3; // RGB
    private final int bytePerChannel = 4; // float32

    public static class Prediction {
        public final String label;
        public final float prob;
        public Prediction(String label, float prob) { this.label = label; this.prob = prob; }
    }

    public TFLiteHelper(Context ctx, String modelAssetPath, String labelsAssetPath, int inputSize) throws Exception {
        this.inputSize = inputSize;
        MappedByteBuffer model = loadModelFile(ctx, modelAssetPath);
        Interpreter.Options options = new Interpreter.Options();
        // options.setNumThreads(4); // 可调整
        // 若想启用 GPU delegate，需在此添加 delegate（并添加依赖）；建议先用 CPU 测试
        interpreter = new Interpreter(model, options);
        labels = loadLabels(ctx, labelsAssetPath);
        Log.i(TAG, "Model and labels loaded. numLabels=" + labels.size());
    }

    public TFLiteHelper(Context ctx) throws Exception {
        // 默认路径 & 输入尺寸 (和你训练时的一致)
        this(ctx, "skin_model.tflite", "labels.txt", 224);
    }

    private MappedByteBuffer loadModelFile(Context ctx, String assetPath) throws Exception {
        AssetFileDescriptor fileDescriptor = ctx.getAssets().openFd(assetPath);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    private List<String> loadLabels(Context ctx, String labelsPath) throws Exception {
        List<String> out = new ArrayList<>();
        InputStream is = ctx.getAssets().open(labelsPath);
        BufferedReader br = new BufferedReader(new InputStreamReader(is));
        String line;
        while ((line = br.readLine()) != null) {
            line = line.trim();
            if (!line.isEmpty()) out.add(line);
        }
        br.close();
        return out;
    }

    private ByteBuffer bitmapToInputBuffer(Bitmap bitmap) {
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true);
        ByteBuffer buffer = ByteBuffer.allocateDirect(bytePerChannel * inputSize * inputSize * pixelSize);
        buffer.order(ByteOrder.nativeOrder());
        int[] intValues = new int[inputSize * inputSize];
        scaled.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize);
        int pixel = 0;
        for (int i = 0; i < inputSize; ++i) {
            for (int j = 0; j < inputSize; ++j) {
                final int val = intValues[pixel++];
                float r = ((val >> 16) & 0xFF);
                float g = ((val >> 8) & 0xFF);
                float b = (val & 0xFF);
                // MobileNetV2 preprocess: (x / 127.5) - 1 -> range [-1,1]
                buffer.putFloat((r / 127.5f) - 1f);
                buffer.putFloat((g / 127.5f) - 1f);
                buffer.putFloat((b / 127.5f) - 1f);
            }
        }
        buffer.rewind();
        return buffer;
    }

    public List<Prediction> classify(Bitmap bitmap, int topK) {
        if (interpreter == null) return new ArrayList<>();
        ByteBuffer inp = bitmapToInputBuffer(bitmap);

        // 输出大小用 labels 数
        float[][] output = new float[1][labels.size()];
        interpreter.run(inp, output);
        float[] probs = output[0];

        // 找 topK 索引
        List<Integer> idxs = new ArrayList<>();
        for (int i = 0; i < probs.length; ++i) idxs.add(i);
        // sort by prob desc, simple selection for small num classes
        idxs.sort((a, b) -> Float.compare(probs[b], probs[a]));
        List<Prediction> results = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, idxs.size()); ++i) {
            int id = idxs.get(i);
            results.add(new Prediction(labels.get(id), probs[id]));
        }
        return results;
    }

    public void close() {
        if (interpreter != null) {
            interpreter.close();
            interpreter = null;
        }
    }
}
