package com.example.keshe;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.util.Log;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

/**
 * 更保守的 TFLite helper：
 *  - 对每次 classify 动作都新建 Interpreter（可避免长期 native 锁/delegate 导致卡住）
 *  - 在 setTensor / invoke 前后增加详细日志和耗时打印
 *  - 默认使用 float32 输入 (等你模型是 FP32)
 */
public class TFLiteHelper {
    private static final String TAG = "TFLiteHelper";
    private final Context ctx;
    private final String modelAssetName;
    private final int inputSize; // e.g. 224
    private final int numChannels = 3;
    private final int numThreads = 4; // 可调

    private MappedByteBuffer modelBufferCached = null;

    public static class Prediction {
        public final String label;
        public final float prob;
        public Prediction(String l, float p) { label = l; prob = p; }
    }

    public TFLiteHelper(Context ctx, String modelAssetName, int inputSize) {
        this.ctx = ctx.getApplicationContext();
        this.modelAssetName = modelAssetName;
        this.inputSize = inputSize;
    }

    // 载入 model 到内存映射（缓存一次）
    private synchronized MappedByteBuffer loadModelFile() throws IOException {
        if (modelBufferCached != null) return modelBufferCached;
        AssetFileDescriptor fileDescriptor = ctx.getAssets().openFd(modelAssetName);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        modelBufferCached = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
        Log.i(TAG, "Mapped model file: " + modelAssetName + " size=" + declaredLength);
        return modelBufferCached;
    }

    // 创建 Interpreter 的 Options（每次都新建 Interpreter 但复用 model buffer）
    private Interpreter createInterpreterFromBuffer(MappedByteBuffer modelBuffer, boolean useCpuOnly) {
        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(numThreads);
        // 禁用 NNAPI（eg. 不要自动切换到 NNAPI）
        options.setUseNNAPI(false);
        // 你也可以尝试降低线程数以避免某些设备问题
        // options.setNumThreads(2);

        // 不主动添加 GPU delegate（如果需要GPU，应确保 GPU AAR 可用）
        Interpreter interpreter = new Interpreter(modelBuffer, options);
        Log.i(TAG, "Interpreter created (useCpuOnly=" + useCpuOnly + ", threads=" + numThreads + ")");
        return interpreter;
    }

    // 简单的 selfTest（用随机输入做一次推理）
    public boolean selfTest() {
        try {
            MappedByteBuffer buf = loadModelFile();
            Interpreter it = createInterpreterFromBuffer(buf, true);
            int[] inputShape = it.getInputTensor(0).shape(); // e.g. [1,224,224,3]
            float[][] out = new float[1][it.getOutputTensor(0).shape()[1]];
            // random
            ByteBuffer rnd = ByteBuffer.allocateDirect(4 * inputSize * inputSize * numChannels).order(ByteOrder.nativeOrder());
            for (int i=0;i<inputSize*inputSize*numChannels;i++) rnd.putFloat(0f);
            rnd.rewind();
            try {
                long t0 = System.currentTimeMillis();
                it.run(rnd, out);
                long t1 = System.currentTimeMillis();
                Log.i(TAG, "selfTest run succeeded, time(ms)=" + (t1 - t0));
                it.close();
                return true;
            } catch (Exception e) {
                Log.e(TAG, "selfTest run failed: " + e.getMessage(), e);
                it.close();
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "selfTest overall failed: " + e.getMessage(), e);
            return false;
        }
    }

    // 将 Bitmap 预处理为 float buffer (NHWC float32)
    private ByteBuffer bitmapToFloatBuffer(Bitmap bmp) {
        ByteBuffer bb = ByteBuffer.allocateDirect(4 * inputSize * inputSize * numChannels);
        bb.order(ByteOrder.nativeOrder());
        int[] intValues = new int[inputSize * inputSize];
        bmp.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize);
        // Normalization: 0..255 -> 0..1
        int idx = 0;
        for (int i = 0; i < inputSize; ++i) {
            for (int j = 0; j < inputSize; ++j) {
                final int val = intValues[idx++];
                float r = ((val >> 16) & 0xFF) / 255.0f;
                float g = ((val >> 8) & 0xFF) / 255.0f;
                float b = (val & 0xFF) / 255.0f;
                bb.putFloat(r);
                bb.putFloat(g);
                bb.putFloat(b);
            }
        }
        bb.rewind();
        return bb;
    }

    /**
     * classify：对单张 Bitmap 做预测
     * 注意：该方法会新建 Interpreter、运行并关闭（更稳妥但慢）
     */
    public List<Prediction> classify(Bitmap inputBitmap, int topK) throws Exception {
        long tStart = System.currentTimeMillis();
        // 缩放到 model 输入尺寸（确保已是 model size）
        Bitmap bmp = Bitmap.createScaledBitmap(inputBitmap, inputSize, inputSize, true);
        long tScaled = System.currentTimeMillis();
        Log.i(TAG, "classify: scaled bmp to " + inputSize + "x" + inputSize + ", cost(ms)=" + (tScaled - tStart));

        ByteBuffer inputBuffer = bitmapToFloatBuffer(bmp);
        long tPrep = System.currentTimeMillis();
        Log.i(TAG, "classify: prepared input buffer, cost(ms)=" + (tPrep - tScaled));

        MappedByteBuffer modelBuf = loadModelFile();

        Interpreter interpreter = null;
        try {
            interpreter = createInterpreterFromBuffer(modelBuf, true);
            // 输出数组：假设模型输出为 [1, N]
            int outDim = interpreter.getOutputTensor(0).shape()[1];
            float[][] out = new float[1][outDim];

            Log.i(TAG, "classify: about to set input and invoke (timestamp=" + System.currentTimeMillis() + ")");
            long tBeforeInvoke = System.currentTimeMillis();

            // Set input and run
            // NOTE: using run(...) because we have a simple single input and single output
            interpreter.run(inputBuffer, out);

            long tAfterInvoke = System.currentTimeMillis();
            Log.i(TAG, "classify: invoke finished, invokeCost(ms)=" + (tAfterInvoke - tBeforeInvoke));

            // Build result
            List<Prediction> preds = new ArrayList<>();
            // naive top-k: just return all with probs
            for (int i = 0; i < out[0].length; ++i) {
                preds.add(new Prediction(String.valueOf(i), out[0][i]));
            }
            long tEnd = System.currentTimeMillis();
            Log.i(TAG, "classify: totalCost(ms)=" + (tEnd - tStart));
            return preds;
        } catch (Throwable e) {
            Log.e(TAG, "classify: exception during run: " + e.getMessage(), e);
            throw new Exception(e);
        } finally {
            if (interpreter != null) {
                try {
                    interpreter.close();
                    Log.i(TAG, "Interpreter closed after classify.");
                } catch (Exception ex) {
                    Log.w(TAG, "Error closing interpreter: " + ex.getMessage(), ex);
                }
            }
        }
    }

    // 可显式清理（释放映射）
    public synchronized void close() {
        // modelBufferCached 不需要显式 unmap
        modelBufferCached = null;
    }
}
