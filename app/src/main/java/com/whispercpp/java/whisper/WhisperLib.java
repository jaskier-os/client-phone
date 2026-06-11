package com.whispercpp.java.whisper;

import android.content.res.AssetManager;
import android.util.Log;

public class WhisperLib {
    private static boolean loaded = false;

    static {
        try {
            System.loadLibrary("ggml-base");
            System.loadLibrary("ggml-cpu");
            System.loadLibrary("ggml");
            System.loadLibrary("whisper");
            loaded = true;
        } catch (Throwable e) {
            Log.e("WhisperLib", "Failed to load whisper native libs", e);
        }
    }

    public static boolean isLoaded() { return loaded; }

    public static native long initContextFromAsset(AssetManager assetManager, String modelPath);
    public static native long initContext(String modelPath);
    public static native void freeContext(long context);
    public static native void fullTranscribe(long context, int numThreads, float[] audioData);
    public static native int getTextSegmentCount(long context);
    public static native String getTextSegment(long context, int index);
    public static native long getTextSegmentT0(long context, int index);
    public static native long getTextSegmentT1(long context, int index);
    public static native String getSystemInfo();
}
