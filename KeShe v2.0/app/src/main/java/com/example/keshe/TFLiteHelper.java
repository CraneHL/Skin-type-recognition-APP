package com.example.keshe;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.util.Log;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.InterpreterApi;
import org.tensorflow.lite.support.common.FileUtil;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 一个相对稳健的 TFLite 帮助类：
 * - 构造时只映射模型文件（mapped buffer）
 * - 每次 classify 时创建一个 Interpreter，run 完即 close（避免长期 delegate/Interpreter 生命周期问题）
 * - 从 assets 加载 labels.txt（默认文件名 labels.txt）
 */
public class TFLiteHelper {
    private static final String TAG = "TFLiteHelper";

    private final Context ctx;
    private final String modelAssetName;
    private final String labelAssetName = "labels.txt"; // 默认
    private final int inputSize; // e.g. 224
    private final MappedByteBuffer modelBuffer;
    private final List<String> labels;

    // 默认线程数（你可以在构造后调整）
    private int numThreads = 4;
    private boolean useCpuOnly = true; // 当前保守为 CPU only (避免 GPU delegate 在模拟器上缺类)

    public static class Prediction {
        public final String label;
        public final float prob;
        public Prediction(String label, float prob) {
            this.label = label;
            this.prob = prob;
        }
    }

    /**
     * 构造：ctx、模型资产文件名（assets 内），输入 size（宽=高）
     * 默认会读取 assets/labels.txt
     */
    public TFLiteHelper(Context ctx, String modelAssetName, int inputSize) throws IOException {
        this.ctx = ctx.getApplicationContext();
        this.modelAssetName = modelAssetName;
        this.inputSize = inputSize;
        this.modelBuffer = loadModelFile(this.modelAssetName);
        this.labels = loadLabelsFromAssets(this.labelAssetName);
        Log.i(TAG, "Mapped model file: " + modelAssetName + " labels=" + this.labels.size());
    }

    public void setNumThreads(int n) {
        this.numThreads = Math.max(1, n);
    }

    public void setUseCpuOnly(boolean cpuOnly) {
        this.useCpuOnly = cpuOnly;
    }

    // 加载 assets 下的 tflite 到 MappedByteBuffer（可复用）
    private MappedByteBuffer loadModelFile(String assetName) throws IOException {
        AssetFileDescriptor fileDescriptor = ctx.getAssets().openFd(assetName);
        try (FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor())) {
            FileChannel fileChannel = inputStream.getChannel();
            long startOffset = fileDescriptor.getStartOffset();
            long declaredLength = fileDescriptor.getDeclaredLength();
            MappedByteBuffer mbb = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
            return mbb;
        }
    }

    // 从 assets 加载 labels 文本
    private List<String> loadLabelsFromAssets(String assetFilename) {
        List<String> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(ctx.getAssets().open(assetFilename)))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) out.add(line);
            }
        } catch (Exception e) {
            Log.w(TAG, "无法加载 labels (assets/" + assetFilename + "): " + e.getMessage() + "，后续将以index作为label");
        }
        return out;
    }

    // 简单 selfTest：建立 Interpreter，运行一次零输入
    public boolean selfTest() {
        Interpreter interpreter = null;
        try {
            Interpreter.Options options = new Interpreter.Options();
            options.setNumThreads(numThreads);
            // 使用 CPU-only（避免 GPU delegate 在某些环境报错）
            interpreter = new Interpreter(modelBuffer, options);
            // 获取输出 shape (1, N)
            int[] inputShape = interpreter.getInputTensor(0).shape();
            int[] outShape = interpreter.getOutputTensor(0).shape();
            float[][] sampleOut = new float[1][outShape[1]];
            // 输入零张量
            float[][][][] in = new float[1][inputShape[1]][inputShape[2]][inputShape[3]];
            interpreter.run(in, sampleOut);
            Log.i(TAG, "selfTest run succeeded, time(ms)=0 (quick)");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "selfTest failed: " + e.getMessage(), e);
            return false;
        } finally {
            if (interpreter != null) {
                try { interpreter.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * classify：输入 bitmap（应已缩放到 inputSize x inputSize 或在此进行缩放），返回 Top-K 的 Predictions（label + prob）
     * - 会自动判断输出是否已经是 probability（sum≈1），否则对输出做 softmax 再返回。
     */
    public List<Prediction> classify(Bitmap srcBitmap, int topK) {
        Interpreter interpreter = null;
        try {
            // 准备 Interpreter options
            Interpreter.Options options = new Interpreter.Options();
            options.setNumThreads(numThreads);
            // 保守：使用 CPU only（避免模拟器或包里缺 GPU delegate 的 native 类）
            // 如果你要启用 GPU，需在构建时正确引入 tensorflow-lite-gpu 并在真机上测试。
            // options.addDelegate(...)

            // 创建 Interpreter（每次新建 -> 运行 -> 关闭）
            interpreter = new Interpreter(modelBuffer, options);
            Log.i(TAG, "Interpreter created (useCpuOnly=" + useCpuOnly + ", threads=" + numThreads + ")");

            // 获取输入输出信息
            int[] inShape = interpreter.getInputTensor(0).shape(); // e.g. [1, 224, 224, 3] 或 [-1,224,224,3]
            int height = inShape[1];
            int width = inShape[2];
            int channels = inShape[3];

            // 获取输出维度 (1, N)
            int[] outShape = interpreter.getOutputTensor(0).shape();
            int numClasses = outShape[outShape.length - 1];

            // 预处理：将 Bitmap 缩放到 model 要求的宽高（width x height）
            Bitmap bmp = Bitmap.createScaledBitmap(srcBitmap, width, height, true);

            // 将 bitmap 转为 float32 数组 [1, H, W, C]，归一化到 [0,1]
            ByteBuffer inputBuffer = ByteBuffer.allocateDirect(4 * 1 * height * width * channels);
            inputBuffer.order(ByteOrder.nativeOrder());
            inputBuffer.rewind();

            int[] intValues = new int[width * height];
            bmp.getPixels(intValues, 0, width, 0, 0, width, height);
            // 这里假设训练时使用的是 [0,1] 的 float 输入；如果是 [-1,1] 或归一化方式不同，请修改
            int pixel = 0;
            for (int y = 0; y < height; ++y) {
                for (int x = 0; x < width; ++x) {
                    final int val = intValues[pixel++];
                    float r = ((val >> 16) & 0xFF) / 255.0f;
                    float g = ((val >> 8) & 0xFF) / 255.0f;
                    float b = (val & 0xFF) / 255.0f;
                    // order: NHWC
                    inputBuffer.putFloat(r);
                    inputBuffer.putFloat(g);
                    inputBuffer.putFloat(b);
                }
            }
            inputBuffer.rewind();
            Log.i(TAG, "classify: prepared input buffer, cost(ms)=0");

            // 输出 buffer
            float[][] output = new float[1][numClasses];

            long tstart = System.currentTimeMillis();
            interpreter.run(inputBuffer, output);
            long tend = System.currentTimeMillis();
            Log.i(TAG, "classify: invoke finished, invokeCost(ms)=" + (tend - tstart));

            // 分析输出：如果 sum≈1 则认为是概率，否则做 softmax
            float[] raw = output[0];
            float sum = 0f;
            for (float v : raw) sum += v;
            float EPS = 1e-3f;
            float[] probs;
            if (Math.abs(sum - 1.0f) < 0.1f) {
                probs = raw.clone();
            } else {
                probs = softmax(raw);
            }

            // 构建 Prediction 列表（带 label 名）
            List<Prediction> all = new ArrayList<>();
            for (int i = 0; i < probs.length; ++i) {
                String lab;
                if (i < labels.size()) lab = labels.get(i);
                else lab = "class_" + i;
                all.add(new Prediction(lab, probs[i]));
            }

            // 排序并返回 topK
            Collections.sort(all, new Comparator<Prediction>() {
                @Override
                public int compare(Prediction o1, Prediction o2) {
                    return Float.compare(o2.prob, o1.prob);
                }
            });

            int k = Math.min(topK, all.size());
            List<Prediction> top = new ArrayList<>();
            for (int i = 0; i < k; ++i) top.add(all.get(i));

            Log.i(TAG, "classify: totalCost(ms)=" + (System.currentTimeMillis() - tstart));
            return top;
        } catch (Exception e) {
            Log.e(TAG, "classify failed: " + e.getMessage(), e);
            return new ArrayList<>();
        } finally {
            if (interpreter != null) {
                try {
                    interpreter.close();
                    Log.i(TAG, "Interpreter closed after classify.");
                } catch (Exception ignored) {}
            }
        }
    }

    // softmax 实现
    private float[] softmax(float[] logits) {
        double max = Double.NEGATIVE_INFINITY;
        for (float v : logits) if (v > max) max = v;
        double sum = 0.0;
        double[] exps = new double[logits.length];
        for (int i = 0; i < logits.length; ++i) {
            exps[i] = Math.exp(logits[i] - max);
            sum += exps[i];
        }
        float[] out = new float[logits.length];
        for (int i = 0; i < logits.length; ++i) out[i] = (float)(exps[i] / sum);
        return out;
    }

    // 释放（如果有需要）。注意：this.modelBuffer 无需手动释放。
    public void close() {
        // nothing to close here other than potential resources; MappedByteBuffer will be GC'd
        Log.i(TAG, "TFLiteHelper closed.");
    }
}
