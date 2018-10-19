package com.gnucoop.cordova.plugin;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Locale;

public class ChunksDownloaderPlugin extends CordovaPlugin {
  private static final String CACHE_PREFERENCES_NAME = "chunksDownloaderPluginPrefs";
  private static final int BUFFER_SIZE = 4096;
  private static final String DEBUG_PREFIX = "ChunksDownloaderPlugin";
  private static final String DOWNLOAD_NOTIFICATION_FAIILED = "Unable to send download status";
  private static final String CHUNK_DOWNLOAD_ERROR = "Unable to download chunk";
  private static final String INVALID_URL_ERROR = "Invalid URL";

  @Override
  public boolean execute(String action, JSONArray args, final CallbackContext callbackContext) {
    // Verify that the user sent a 'show' action
    if (!action.equals("download")) {
      callbackContext.error("\"" + action + "\" is not a recognized action.");
      return false;
    }

    cordova.getThreadPool().execute((Runnable) () -> {
      JSONArray chunks;
      String filename;
      try {
        JSONObject options = args.getJSONObject(0);
        chunks = options.getJSONArray("chunks");
        filename = options.getString("filename");
      } catch (JSONException e) {
        callbackContext.error("Error encountered: " + e.getMessage());
        return;
      }

      int chunksNum = chunks.length();
      for (int i = 0; i < chunksNum; i++) {
        try {
          URL url = new URL(chunks.getString(i));
          String chunkFilename = getChunkFilename(filename, i);
          if (!downloadFile(callbackContext, url, "temp", chunkFilename)) {
            notifyDownloadError(callbackContext, CHUNK_DOWNLOAD_ERROR);
            callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR));
            return;
          }
        } catch (MalformedURLException ex) {
          notifyDownloadError(callbackContext, INVALID_URL_ERROR);
          callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR));
          return;
        } catch (JSONException e) {
          callbackContext.error("Error encountered: " + e.getMessage());
          return;
        }
      }

      FileInputStream fis = null;
      FileOutputStream fos = null;
      String outputPath = getFilePath(filename);

      try {

        fos = new FileOutputStream(outputPath);

        for (int i = 0; i < chunksNum; i++) {
          fis = new FileInputStream(getFilePath("temp", getChunkFilename(filename, i)));

          byte[] buffer = new byte[1024];
          int byteRead;
          while ((byteRead = fis.read(buffer)) != -1) {
            fos.write(buffer, 0, byteRead);
          }

          fis.close();
        }
      } catch (IOException ex) {
        notifyDownloadError(callbackContext, ex.getMessage());
        callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR));
        return;
      } finally {
        try {
          if (fos != null) {
            fos.close();
          }
        } catch (IOException e) {
        }
        try {
          if (fis != null) {
            fis.close();
          }
        } catch (IOException e) {
        }
      }
      try {
        // Send a positive result to the callbackContext
        JSONObject data = new JSONObject();
        data.put("downloadedFileUrl", Uri.fromFile(new File(outputPath)));
        PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, data);
        callbackContext.sendPluginResult(pluginResult);
      } catch (JSONException e) {
        callbackContext.error("Error encountered: " + e.getMessage());
      }
    });
    return true;
  }

  private String getChunkFilename(String filename, int chunk) {
    return String.format(Locale.US, "%s.chunk%d", filename, chunk);
  }

  private boolean downloadFile(CallbackContext callbackContext, URL url, String dest, String filename) {
    try {
      if (filename == null) {
        String urlStr = url.toString();
        filename = urlStr.substring(urlStr.lastIndexOf("/") + 1, urlStr.length());
      }

      String filePath = getFilePath(dest, filename);

      HttpURLConnection conn = (HttpURLConnection) url.openConnection();
      long currentTime = System.currentTimeMillis();
      long expires = conn.getHeaderFieldDate("Expires", currentTime);
      long lastModified = conn.getHeaderFieldDate("Last-Modified", currentTime);
      long lastUpdateTime = getLastUpdate(url);
      if (lastModified > lastUpdateTime || expires < lastUpdateTime) {
        int responseCode = conn.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_OK) {
          InputStream inputStream = conn.getInputStream();
          File file = new File(filePath);
          File parent = file.getParentFile();
          if (!parent.exists()) {
            boolean parentCreated = parent.mkdirs();
            if (!parentCreated) {
              return false;
            }
          }
          if (!file.exists()) {
            boolean fileCreated = file.createNewFile();
            if (!fileCreated) {
              return false;
            }
          }

          JSONObject dlProgress = new JSONObject();
          int fileLen = conn.getContentLength();
          int readLen = 0;

          if (fileLen > -1) {
            try {
              dlProgress.put("progress", 0f);
            } catch (JSONException e) {
              Log.d(DEBUG_PREFIX, DOWNLOAD_NOTIFICATION_FAIILED);
            }
          }

          notifyDownloadStatus(callbackContext, ChunksDownloaderDownloadStatus.Downloading, dlProgress);

          FileOutputStream outputStream = new FileOutputStream(file);

          int bytesRead;
          byte[] buffer = new byte[BUFFER_SIZE];
          while ((bytesRead = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, bytesRead);
            readLen += bytesRead;
            if (fileLen > 0) {
              try {
                dlProgress.put("progress", readLen / fileLen);
                notifyDownloadStatus(callbackContext, ChunksDownloaderDownloadStatus.Downloading, dlProgress);
              } catch (JSONException e) {
                Log.d(DEBUG_PREFIX, DOWNLOAD_NOTIFICATION_FAIILED);
              }
            }
          }

          outputStream.close();
          inputStream.close();

          setLastUpdate(url);

          return true;
        }
        return false;
      }
      return true;
    } catch(IOException e) {
      return false;
    }
  }

  private String getFilePath(String filename) {
    return new File(
        this.cordova.getContext().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
        filename
    ).toString();
  }

  private String getFilePath(String dest, String filename) {
    return new File(
        new File(this.cordova.getContext().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), dest),
        filename
    ).toString();
  }

  private long getLastUpdate(URL url) {
    Context context = this.cordova.getContext();
    SharedPreferences sharedPref = context.getSharedPreferences(CACHE_PREFERENCES_NAME, Context.MODE_PRIVATE);
    return sharedPref.getLong("last-update-" + url.toString(), 0);
  }

  private void setLastUpdate(URL url) {
    Context context = this.cordova.getContext();
    SharedPreferences sharedPref = context.getSharedPreferences(CACHE_PREFERENCES_NAME, Context.MODE_PRIVATE);
    sharedPref.edit().putLong("last-update-" + url.toString(), System.currentTimeMillis()).apply();
  }

  private void notifyDownloadStatus(CallbackContext callbackContext, ChunksDownloaderDownloadStatus status) {
    notifyDownloadStatus(callbackContext, status, null);
  }

  private void notifyDownloadStatus(CallbackContext callbackContext, ChunksDownloaderDownloadStatus status, JSONObject data) {
    if (data == null) {
      data = new JSONObject();
    }
    try {
      data.put("status", status);

      PluginResult dataResult = new PluginResult(PluginResult.Status.OK, data);
      dataResult.setKeepCallback(true);
      callbackContext.sendPluginResult(dataResult);
    } catch (JSONException e) {
      Log.d(DEBUG_PREFIX, DOWNLOAD_NOTIFICATION_FAIILED);
    }
  }

  private void notifyDownloadError(CallbackContext callbackContext, String error) {
    JSONObject data = new JSONObject();
    try {
      data.put("status", ChunksDownloaderDownloadStatus.Error);
      if (error != null) {
        data.put("error", error);
      }

      PluginResult dataResult = new PluginResult(PluginResult.Status.ERROR, data);
      dataResult.setKeepCallback(true);
      callbackContext.sendPluginResult(dataResult);
    } catch (JSONException e) {
      Log.d(DEBUG_PREFIX, "Unable to send download status");
    }
  }
}
