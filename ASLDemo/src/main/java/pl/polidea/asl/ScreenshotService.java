package pl.polidea.asl;

import android.app.Service;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.AsyncTask;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.security.InvalidParameterException;
import java.util.UUID;

public class ScreenshotService extends Service {
  /*
   * Action name for intent used to bind to service.
   */
  public static final String BIND = "pl.polidea.asl.ScreenshotService.BIND";

  /*
   * Name of the native process.
   */
  private static final String NATIVE_PROCESS_NAME = "asl-native";

  /*
   * Port number used to communicate with native process.
   */
  private static final int PORT = 42380;

  /*
   * Timeout allowed in communication with native process.
   */
  private static final int TIMEOUT = 1000;

  /*
   * Directory where screenshots are being saved.
   */
  private static String SCREENSHOT_FOLDER = "/sdcard/screens/";

  /*
   * An implementation of interface used by clients to take screenshots.
   */
  private final IScreenshotProvider.Stub mBinder = new IScreenshotProvider.Stub() {
    @Override
    public boolean isAvailable() throws RemoteException {
      new SocketTask().execute();
      return connected;
    }

    @Override
    public String takeScreenshot() throws RemoteException {
      new ScreenshotTask().execute();
      return file;
    }
  };

  private Boolean connected = false;
  private String file;

  /*
   * Takes screenshot and saves to a file.
   */
  private String takeScreenshot() throws IOException {
    // make sure the path to save screens exists
    File screensPath = new File(SCREENSHOT_FOLDER);
    screensPath.mkdirs();

    // construct screenshot file name
    StringBuilder sb = new StringBuilder();
    sb.append(SCREENSHOT_FOLDER);
    sb.append(Math.abs(UUID.randomUUID().hashCode())); // hash code of UUID should be quite random yet short
    sb.append(".png");
    String file = sb.toString();

    // fetch the screen and save it
    Screenshot ss = null;
    try {
      ss = retrieveRawScreenshot();
    } catch (Exception e) {
      e.printStackTrace();
    }
    writeImageFile(ss, file);

    return file;
  }

  /*
   * Saves given array of bytes into image file in the PNG format.
   */
  private void writeImageFile(Screenshot ss, String file) {
    if (ss == null || !ss.isValid()) throw new IllegalArgumentException();
    if (file == null || file.length() == 0)
      throw new IllegalArgumentException();

    // resolve screenshot's BPP to actual bitmap pixel format
    Bitmap.Config pf;
    switch (ss.bpp) {
      case 16:
        pf = Bitmap.Config.RGB_565;
        break;
      case 32:
        pf = Bitmap.Config.ARGB_8888;
        break;
      default:
        pf = Bitmap.Config.ARGB_8888;
        break;
    }

    // create appropriate bitmap and fill it with data
    Bitmap bmp = Bitmap.createBitmap(ss.width, ss.height, pf);
    bmp.copyPixelsFromBuffer(ss.pixels);

    // handle the screen rotation
    int rot = getScreenRotation();
    if (rot != 0) {
      Matrix matrix = new Matrix();
      matrix.postRotate(-rot);
      bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), matrix, true);
    }

    // save it in PNG format
    FileOutputStream fos;
    try {
      fos = new FileOutputStream(file);
    } catch (FileNotFoundException e) {
      throw new InvalidParameterException();
    }
    bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
  }

  @Override
  public void onCreate() {
    Log.i("service", "Service created.");
  }

  /*
     * Determines whether the phone's screen is rotated.
     */
  private int getScreenRotation() {
    WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
    Display disp = wm.getDefaultDisplay();

    // check whether we operate under Android 2.2 or later
    try {
      Class<?> displayClass = disp.getClass();
      Method getRotation = displayClass.getMethod("getRotation");
      int rot = ((Integer) getRotation.invoke(disp)).intValue();

      switch (rot) {
        case Surface.ROTATION_0:
          return 0;
        case Surface.ROTATION_90:
          return 90;
        case Surface.ROTATION_180:
          return 180;
        case Surface.ROTATION_270:
          return 270;
        default:
          return 0;
      }
    } catch (NoSuchMethodException e) {
      // no getRotation() method -- fall back to dispation()
      int orientation = disp.getOrientation();

      // Sometimes you may get undefined orientation value is 0
      // simple logic solves the problem compare the screen
      // x, y coordinates and determine the orientation in such cases
      if (orientation == Configuration.ORIENTATION_UNDEFINED) {
        Configuration config = getResources().getConfiguration();
        orientation = config.orientation;

        if (orientation == Configuration.ORIENTATION_UNDEFINED) {
          // if height and width of screen are equal then
          // it is square orientation
          if (disp.getWidth() == disp.getHeight()) {
            orientation = Configuration.ORIENTATION_SQUARE;
          } else { // if width is less than height than it is portrait
            if (disp.getWidth() < disp.getHeight()) {
              orientation = Configuration.ORIENTATION_PORTRAIT;
            } else { // if it is not any of the above it will definitely be landscape
              orientation = Configuration.ORIENTATION_LANDSCAPE;
            }
          }
        }
      }

      return orientation == 1 ? 0 : 90; // 1 for portrait, 2 for landscape
    } catch (Exception e) {
      return 0;
    }
  }

  /*
   * Communicates with the native service and retrieves a screenshot from it
   * as a 2D array of bytes.
   */
  private Screenshot retrieveRawScreenshot() throws Exception {
    try {
      // connect to native application
      Socket s = new Socket();
      s.connect(new InetSocketAddress("localhost", PORT), TIMEOUT);

      // send command to take screenshot
      OutputStream os = s.getOutputStream();
      os.write("SCREEN".getBytes("ASCII"));

      // retrieve response -- first the size and BPP of the screenshot
      InputStream is = s.getInputStream();
      StringBuilder sb = new StringBuilder();
      int c;
      while ((c = is.read()) != -1) {
        if (c == 0) break;
        sb.append((char) c);
      }

      // parse it
      String[] screenData = sb.toString().split(" ");
      if (screenData.length >= 3) {
        Screenshot ss = new Screenshot();
        ss.width = Integer.parseInt(screenData[0]);
        ss.height = Integer.parseInt(screenData[1]);
        ss.bpp = Integer.parseInt(screenData[2]);

        // retrieve the screenshot
        // (this method - via ByteBuffer - seems to be the fastest)
        ByteBuffer bytes = ByteBuffer.allocate(ss.width * ss.height * ss.bpp / 8);
        is = new BufferedInputStream(is); // buffering is very important apparently
        is.read(bytes.array()); // reading all at once for speed
        bytes.position(0); // reset position to the beginning of ByteBuffer
        ss.pixels = bytes;

        return ss;
      }
    } catch (Exception e) {
      throw new Exception(e);
    } finally {
    }

    return null;
  }

  /*
   * Checks whether the internal native application is running.
   */
  private boolean isNativeRunning() {
    try {
      Socket sock = new Socket();
      sock.connect(new InetSocketAddress("localhost", PORT), 10); // short timeout
    } catch (Exception e) {
      return false;
    }
    return true;
  }

  class ScreenshotTask extends AsyncTask<Void, Void, String> {
    @Override
    protected String doInBackground(Void... params) {
      try {
        return takeScreenshot();
      } catch (IOException e) {
        return null;
      }
    }

    @Override
    protected void onPostExecute(String result) {
      file = result;
    }
  }

  class SocketTask extends AsyncTask<Void, Void, Boolean> {
    @Override
    protected Boolean doInBackground(Void... params) {
      return isNativeRunning();
    }

    @Override
    protected void onPostExecute(Boolean result) {
      connected = result;
    }
  }

  @Override
  public IBinder onBind(Intent intent) {
    return mBinder;
  }

  /*
   * Internal class describing a screenshot.
   */
  class Screenshot {
    public Buffer pixels;
    public int width;
    public int height;
    public int bpp;

    public boolean isValid() {
      if (pixels == null || pixels.capacity() == 0 || pixels.limit() == 0)
        return false;
      if (width <= 0 || height <= 0) return false;
      return true;
    }
  }
}
