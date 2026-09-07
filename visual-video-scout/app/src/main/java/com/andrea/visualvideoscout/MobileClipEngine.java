package com.andrea.visualvideoscout;

import android.content.Context;
import android.graphics.Bitmap;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

final class MobileClipEngine implements AutoCloseable {
    private static final String VISION_ASSET = "models/vision_model_uint8.onnx";
    private static final String TEXT_ASSET = "models/text_model_int8.onnx";
    private static final String TOKENIZER_ASSET = "models/tokenizer.json";
    private static final int IMAGE_SIZE = 256;

    private final OrtEnvironment env;
    private final OrtSession visionSession;
    private final OrtSession textSession;
    private final OrtSession.SessionOptions sessionOptions;
    private final ClipTokenizer tokenizer;

    MobileClipEngine(Context context) throws Exception {
        env = OrtEnvironment.getEnvironment();
        sessionOptions = new OrtSession.SessionOptions();
        sessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        sessionOptions.setIntraOpNumThreads(Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors())));
        sessionOptions.setInterOpNumThreads(1);

        File visionFile = materializeAsset(context, VISION_ASSET, "mobileclip-vision-int8-fcbd153d.onnx");
        File textFile = materializeAsset(context, TEXT_ASSET, "mobileclip-text-int8-fc8d8797.onnx");
        visionSession = env.createSession(visionFile.getAbsolutePath(), sessionOptions);
        textSession = env.createSession(textFile.getAbsolutePath(), sessionOptions);
        tokenizer = new ClipTokenizer(readTextAsset(context, TOKENIZER_ASSET));
    }

    float[] embedImage(Bitmap source) throws Exception {
        float[] data = preprocess(source);
        Map<String, OnnxTensor> inputs = new HashMap<>();
        try {
            for (Map.Entry<String, NodeInfo> entry : visionSession.getInputInfo().entrySet()) {
                String name = entry.getKey();
                TensorInfo info = (TensorInfo) entry.getValue().getInfo();
                if (name.toLowerCase().contains("pixel")) {
                    inputs.put(name, OnnxTensor.createTensor(env, FloatBuffer.wrap(data), new long[]{1, 3, IMAGE_SIZE, IMAGE_SIZE}));
                } else {
                    throw new IllegalStateException("Input vision non supportato: " + name + " " + info);
                }
            }
            try (OrtSession.Result result = visionSession.run(inputs)) {
                return normalize(extractEmbedding(visionSession, result));
            }
        } finally {
            for (OnnxTensor tensor : inputs.values()) tensor.close();
        }
    }

    float[] embedText(String query) throws Exception {
        String prompt = normalizeItalianVisualPrompt(query);
        ClipTokenizer.Encoding encoding = tokenizer.encode(prompt);
        Map<String, OnnxTensor> inputs = new HashMap<>();
        try {
            for (Map.Entry<String, NodeInfo> entry : textSession.getInputInfo().entrySet()) {
                String name = entry.getKey();
                String lower = name.toLowerCase();
                TensorInfo info = (TensorInfo) entry.getValue().getInfo();
                if (lower.contains("input_ids") || lower.equals("input_ids")) {
                    inputs.put(name, OnnxTensor.createTensor(env, LongBuffer.wrap(encoding.inputIds), new long[]{1, ClipTokenizer.CONTEXT_LENGTH}));
                } else if (lower.contains("attention_mask")) {
                    inputs.put(name, OnnxTensor.createTensor(env, LongBuffer.wrap(encoding.attentionMask), new long[]{1, ClipTokenizer.CONTEXT_LENGTH}));
                } else if (lower.contains("token_type") || lower.contains("position")) {
                    long[] zeros = new long[ClipTokenizer.CONTEXT_LENGTH];
                    inputs.put(name, OnnxTensor.createTensor(env, LongBuffer.wrap(zeros), new long[]{1, ClipTokenizer.CONTEXT_LENGTH}));
                } else {
                    throw new IllegalStateException("Input text non supportato: " + name + " " + info);
                }
            }
            try (OrtSession.Result result = textSession.run(inputs)) {
                return normalize(extractEmbedding(textSession, result));
            }
        } finally {
            for (OnnxTensor tensor : inputs.values()) tensor.close();
        }
    }

    private float[] extractEmbedding(OrtSession session, OrtSession.Result result) throws Exception {
        String[] preferred = {"image_embeds", "text_embeds", "embeds", "pooler_output"};
        Set<String> outputNames = session.getOutputNames();
        for (String preferredName : preferred) {
            for (String name : outputNames) {
                if (name.toLowerCase().contains(preferredName)) {
                    float[] vector = toVector(result.get(name).orElse(null));
                    if (vector != null) return vector;
                }
            }
        }
        for (int i = 0; i < result.size(); i++) {
            float[] vector = toVector(result.get(i));
            if (vector != null && vector.length >= 128 && vector.length <= 2048) return vector;
        }
        throw new IllegalStateException("Nessun embedding vettoriale trovato nell'output ONNX");
    }

    private float[] toVector(OnnxValue value) throws Exception {
        if (!(value instanceof OnnxTensor)) return null;
        Object raw = value.getValue();
        if (raw instanceof float[][]) {
            float[][] v = (float[][]) raw;
            return v.length == 1 ? v[0] : null;
        }
        if (raw instanceof float[]) return (float[]) raw;
        return null;
    }

    private float[] preprocess(Bitmap source) {
        int w = source.getWidth();
        int h = source.getHeight();
        float scale = IMAGE_SIZE / (float) Math.min(w, h);
        int rw = Math.max(IMAGE_SIZE, Math.round(w * scale));
        int rh = Math.max(IMAGE_SIZE, Math.round(h * scale));
        Bitmap resized = Bitmap.createScaledBitmap(source, rw, rh, true);
        int left = Math.max(0, (rw - IMAGE_SIZE) / 2);
        int top = Math.max(0, (rh - IMAGE_SIZE) / 2);
        Bitmap crop = Bitmap.createBitmap(resized, left, top, IMAGE_SIZE, IMAGE_SIZE);
        int[] pixels = new int[IMAGE_SIZE * IMAGE_SIZE];
        crop.getPixels(pixels, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE);
        float[] chw = new float[3 * IMAGE_SIZE * IMAGE_SIZE];
        int plane = IMAGE_SIZE * IMAGE_SIZE;
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            chw[i] = ((p >> 16) & 0xff) / 255f;
            chw[plane + i] = ((p >> 8) & 0xff) / 255f;
            chw[2 * plane + i] = (p & 0xff) / 255f;
        }
        if (crop != resized) crop.recycle();
        if (resized != source) resized.recycle();
        return chw;
    }

    private float[] normalize(float[] vector) {
        double sum = 0;
        for (float v : vector) sum += v * (double) v;
        float inv = (float) (1.0 / Math.max(1e-12, Math.sqrt(sum)));
        float[] out = new float[vector.length];
        for (int i = 0; i < vector.length; i++) out[i] = vector[i] * inv;
        return out;
    }

    static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return -1;
        double dot = 0;
        for (int i = 0; i < a.length; i++) dot += a[i] * (double) b[i];
        return dot;
    }

    private String normalizeItalianVisualPrompt(String input) {
        String q = (input == null ? "" : input).toLowerCase().trim();
        String[][] replacements = {
            {"tizio", "man"}, {"tipo", "man"}, {"uomo", "man"}, {"ragazzo", "young man"},
            {"donna", "woman"}, {"ragazza", "young woman"}, {"persona", "person"},
            {"maglietta", "shirt"}, {"t-shirt", "shirt"}, {"camicia", "shirt"}, {"felpa", "hoodie"},
            {"giacca", "jacket"}, {"cappello", "hat"}, {"pantaloni", "pants"}, {"scarpe", "shoes"},
            {"rosso", "red"}, {"rossa", "red"}, {"rossi", "red"}, {"rosse", "red"},
            {"blu", "blue"}, {"azzurro", "blue"}, {"azzurra", "blue"}, {"verde", "green"},
            {"giallo", "yellow"}, {"gialla", "yellow"}, {"nero", "black"}, {"nera", "black"},
            {"bianco", "white"}, {"bianca", "white"}, {"viola", "purple"}, {"arancione", "orange"},
            {"capelli", "hair"}, {"biondi", "blond"}, {"bionde", "blond"}, {"scuri", "dark"},
            {"all'aperto", "outdoors"}, {"aperto", "outdoors"}, {"piscina", "swimming pool"},
            {"macchina", "car"}, {"auto", "car"}, {"divano", "sofa"}, {"seduto", "sitting"},
            {"seduta", "sitting"}, {"in piedi", "standing"}, {"indossa", "wearing"}
        };
        for (String[] pair : replacements) q = q.replace(pair[0], pair[1]);
        q = q.replaceAll("\\b(con|una|un|uno|il|lo|la|i|gli|le|che|di|del|della|delle|dei)\\b", " ")
             .replaceAll("\\s+", " ").trim();
        if (!q.startsWith("a photo")) q = "a photo of " + q;
        return q;
    }

    private File materializeAsset(Context context, String assetPath, String fileName) throws Exception {
        File target = new File(context.getFilesDir(), fileName);
        if (target.isFile() && target.length() > 1024 * 1024) return target;
        File tmp = new File(context.getFilesDir(), fileName + ".tmp");
        try (InputStream in = context.getAssets().open(assetPath); FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buffer = new byte[1024 * 256];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        }
        if (target.exists() && !target.delete()) throw new IllegalStateException("Impossibile aggiornare " + target);
        if (!tmp.renameTo(target)) throw new IllegalStateException("Impossibile materializzare " + target);
        return target;
    }

    private String readTextAsset(Context context, String path) throws Exception {
        try (InputStream in = context.getAssets().open(path); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    @Override public void close() throws Exception {
        visionSession.close();
        textSession.close();
        sessionOptions.close();
    }
}
