package pl.polidea.asl.demo;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import pl.polidea.asl.IScreenshotProvider;
import pl.polidea.asl.ScreenshotService;

public class ScreenshotDemo extends Activity {
  /*
   * The ImageView used to display taken screenshots.
   */
  private ImageView imgScreen;

  private IScreenshotProvider aslProvider = null;

  private View.OnClickListener btnTakeScreenshot_onclick = new View.OnClickListener() {
    @Override
    public void onClick(View view) {
      try {
        if (aslProvider == null) {
          Toast.makeText(ScreenshotDemo.this, R.string.n_a, Toast.LENGTH_SHORT).show();
        } else if (!aslProvider.isAvailable()) {
          Toast.makeText(ScreenshotDemo.this, R.string.native_n_a, Toast.LENGTH_SHORT).show();
        } else {
          String file = aslProvider.takeScreenshot();
          if (file == null) {
            Toast.makeText(ScreenshotDemo.this, R.string.screenshot_error, Toast.LENGTH_SHORT).show();
          } else {
            Toast.makeText(ScreenshotDemo.this, R.string.screenshot_ok, Toast.LENGTH_SHORT).show();
            Bitmap screen = BitmapFactory.decodeFile(file);
            imgScreen.setImageBitmap(screen);
          }
        }
      } catch (Resources.NotFoundException e) {
        e.printStackTrace();
      } catch (RemoteException e) {
      }
    }
  };

  private ServiceConnection aslServiceConn = new ServiceConnection() {
    @Override
    public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
      aslProvider = IScreenshotProvider.Stub.asInterface(iBinder);
    }

    @Override
    public void onServiceDisconnected(ComponentName componentName) {
    }
  };

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.main);

    imgScreen = (ImageView) findViewById(R.id.imageScreen);
    Button btn = (Button) findViewById(R.id.btnTakeScreenshot);
    btn.setOnClickListener(btnTakeScreenshot_onclick);

    // connect to ASL service
    Intent intent = new Intent();
    intent.setClass(this, ScreenshotService.class);
    bindService(intent, aslServiceConn, Context.BIND_AUTO_CREATE);
  }

  @Override
  protected void onDestroy() {
    unbindService(aslServiceConn);
    super.onDestroy();
  }
}
