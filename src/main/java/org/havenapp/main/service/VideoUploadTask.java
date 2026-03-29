package org.havenapp.main.service;

import android.os.AsyncTask;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class VideoUploadTask extends AsyncTask<Void, Void, Boolean> {

    private static final String TAG = "VideoUploadTask";
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final MediaType MEDIA_TYPE_MP4 = MediaType.parse("video/mp4");

    private final String filePath;
    private final String uploadUrl;
    private final String authToken;
    private final int maxRetries;

    public VideoUploadTask(String filePath, String uploadUrl, String authToken, int maxRetries) {
        this.filePath = filePath;
        this.uploadUrl = uploadUrl;
        this.authToken = authToken;
        this.maxRetries = maxRetries;
    }

    @Override
    protected Boolean doInBackground(Void... voids) {
        File file = new File(filePath);
        if (!file.exists() || !file.canRead()) {
            Log.e(TAG, "File not found or not readable: " + filePath);
            return false;
        }

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();

        RequestBody fileBody = RequestBody.create(MEDIA_TYPE_MP4, file);
        MultipartBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("video", file.getName(), fileBody)
                .build();

        Request.Builder reqBuilder = new Request.Builder()
                .url(uploadUrl)
                .post(body);

        if (authToken != null && !authToken.isEmpty()) {
            reqBuilder.addHeader("Authorization", "Bearer " + authToken);
        }

        Request request = reqBuilder.build();

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                Response response = client.newCall(request).execute();
                int code = response.code();
                response.close();

                if (code >= 200 && code < 300) {
                    Log.i(TAG, "Upload successful: " + file.getName());
                    return true;
                } else if (code >= 400 && code < 500) {
                    Log.e(TAG, "Upload rejected (HTTP " + code + "): " + file.getName());
                    return false;
                } else {
                    Log.w(TAG, "Server error (HTTP " + code + "), attempt " + (attempt + 1));
                }
            } catch (IOException e) {
                Log.w(TAG, "Upload failed (attempt " + (attempt + 1) + "): " + e.getMessage());
            }

            if (attempt < maxRetries) {
                try {
                    Thread.sleep((long) Math.pow(2, attempt) * 1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }

        Log.e(TAG, "Upload failed after " + (maxRetries + 1) + " attempts: " + file.getName());
        return false;
    }

    @Override
    protected void onPostExecute(Boolean success) {
        if (success) {
            Log.i(TAG, "Video upload completed: " + filePath);
        } else {
            Log.e(TAG, "Video upload failed: " + filePath);
        }
    }

    public static void upload(String filePath, String url, String token) {
        new VideoUploadTask(filePath, url, token, DEFAULT_MAX_RETRIES)
                .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }
}
