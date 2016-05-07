/*

pushd ~/b/android-sdk/extras/android/support/v7/appcompat
android update lib-project -p . --target android-23
ant release
popd

*/


// Headunit app Main Activity
/*

Start with USB plugged
transport_start
  usb_attach_handler
    usb_connect

1st Permission granted
usb_receiver
  usb_attach_handler
    usb_connect
      usb_open
    -
      acc_mode_switch
      usb_disconnect

Disconnect
usb_receiver
  usb_detach_handler

Attached in ACC mode
usb_receiver
  usb_attach_handler
    usb_connect

2nd Permission granted
usb_receiver
  usb_attach_handler
    usb_connect
      usb_open
    -
      acc_mode_endpoints_set
  -
    jni_aap_start
*/

/* How to implement Android Open Accessory mode as a service:

Copy the intent that you received when starting your activity that you use to launch the service, because the intent contains the details of the accessory that the ADK implementation needs.
Then, in the service proceed to implement the rest of ADK exactly as before.

if (intent.getAction().equals(USB_OAP_ATTACHED)) {
    Intent i = new Intent(this, YourServiceName.class);
    i.putExtras(intent);
    startService(i);
}


*/
package ca.yyx.hu;

import android.app.Activity;
import android.app.UiModeManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.PowerManager;
import android.support.v4.widget.DrawerLayout;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import java.nio.ByteBuffer;

import ca.yyx.hu.decoder.AudioDecoder;
import ca.yyx.hu.decoder.VideoDecoder;



public class HeadUnitActivity extends Activity implements SurfaceHolder.Callback {

    private HeadUnitTransport mTransport;        // Transport API
    private SurfaceView mSurfaceView;
    private DrawerLayout mDrawerLayout;
    private ListView mDrawerListView;
    private View mContentView;

    private static final double m_virt_vid_wid = 800f;
    private static final double m_virt_vid_hei = 480f;

    // Presets:

    private static final int PRESET_LEN_FIX = 4;
    public static final int PRESET_LEN_USB = 5;  // Room left over for USB entries

    private static final String DATA_DATA = "/data/data/ca.yyx.hu";
    private static final String SDCARD = "/sdcard/";

    private String[] mDrawerSections;
    private boolean isVideoStarted;
    private AudioDecoder mAudioDecoder;
    private VideoDecoder mVideoDecoder;

    private UiModeManager mUiModeManager = null;
    private PowerManager.WakeLock mWakelock = null;

    private boolean m_tcp_connected = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);   // !! Keep Screen on !!
        setContentView(R.layout.layout);

        Utils.logd("Headunit for Android Auto (tm) - Copyright 2011-2015 Michael A. Reid. All Rights Reserved...");

        Utils.logd("--- savedInstanceState: " + savedInstanceState);
        Utils.logd("--- m_tcp_connected: " + m_tcp_connected);

        mDrawerSections = getResources().getStringArray(R.array.drawer_items);

        mDrawerLayout = (DrawerLayout) findViewById(R.id.drawer_layout);
        mDrawerListView = (ListView) findViewById(R.id.left_drawer);
        mDrawerListView.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, mDrawerSections));
        mDrawerListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                mDrawerLayout.closeDrawer(Gravity.LEFT);
                drawerSelect(position);
                SystemUI.hide(mContentView, mDrawerLayout);
            }
        });

        mContentView = findViewById(android.R.id.content);

        SystemUI.hide(mContentView, null);

        mSurfaceView = (SurfaceView) findViewById(R.id.surface);
        mSurfaceView.getHolder().addCallback(this);
        mSurfaceView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                touch_send(event);
                return (true);
            }
        });

        PowerManager m_pwr_mgr = (PowerManager) getSystemService(POWER_SERVICE);
        mWakelock = m_pwr_mgr.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HeadUnitWakeLockTag");
        mWakelock.acquire();                                          // Android M exception for WAKE_LOCK

        mAudioDecoder = new AudioDecoder(this);
        mVideoDecoder = new VideoDecoder(this);

        mTransport = new HeadUnitTransport(this, mAudioDecoder);                                       // Start USB/SSL/AAP Transport
        Intent intent = getIntent();                                     // Get launch Intent

        int ret = mTransport.transport_start(intent);
        if (ret <= 0) {                                                   // If no USB devices...
            if (!Utils.file_get(SDCARD + "/hu_nocarm") && !starting_car_mode) {  // Else if have at least 1 USB device and we are not starting yet car mode...
                Utils.logd("Before car_mode_start()");
                starting_car_mode = true;
                car_mode_start();
                Utils.logd("After  car_mode_start()");
            } else {
                Utils.logd("Starting car mode or disabled so don't call car_mode_start()");
                starting_car_mode = false;
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        mTransport.unregisterUsbReceiver();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mTransport.registerUsbReceiver();
        mDrawerLayout.openDrawer(Gravity.LEFT);
    }


    private static boolean starting_car_mode = false;

    @Override
    protected void onNewIntent(Intent intent) {
        // am start -n ca.yyx.hu/.HeadUnitActivity -a "send" -e data 040b000000130801       #AUDIO_FOCUS_STATE_GAIN
        // am start -n ca.yyx.hu/.HeadUnitActivity -a "send" -e data 000b0000000f0800       Byebye request
        // am start -n ca.yyx.hu/.HeadUnitActivity -a "send" -e data 020b0000800808021001   VideoFocus lost focusState=0 unsolicited=true
        super.onNewIntent(intent);
        Utils.logd("--- intent: " + intent);

        String action = intent.getAction();
        if (action == null) {
            Utils.loge("action == null");
            return;
        }
        // --- intent: Intent { act=android.hardware.usb.action.USB_DEVICE_ATTACHED flg=0x10000000 cmp=ca.yyx.hu/.HeadUnitActivity (has extras) }
        if (!action.equals("send")) {                                     // If this is NOT our "fm.a2d.s2.send" Intent...
            //Utils.logd ("action: " + action);                              // action: android.hardware.usb.action.USB_DEVICE_ATTACHED
            return;
        }

        Bundle extras = intent.getExtras();
        if (extras == null) {
            Utils.loge("extras == null");
            return;
        }

        String val = extras.getString("data", "def");
        if (val == null) {
            Utils.loge("val == null");
            return;
        }
        byte[] send_buf = Utils.hexstr_to_ba(val);
        String val2 = Utils.ba_to_hexstr(send_buf);
        Utils.logd("val: " + val + "  val2: " + val2);

        mTransport.test_send(send_buf, send_buf.length);

    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        Utils.logd("--- m_tcp_connected: " + m_tcp_connected);

        all_stop();

        if (!Utils.file_get(SDCARD + "/hu_usbr_disable"))
            Utils.sys_run(DATA_DATA + "/lib/libusb_reset.so /dev/bus/usb/*/* 1>/dev/null 2>/dev/null", true);

        if (!starting_car_mode)
            android.os.Process.killProcess(android.os.Process.myPid());       // Kill this process completely for "super cleanup"
    }

    // Drawer:

    private void drawerSelect(int idx) {
        if (idx == 0) {                                                     // If Exit...
            car_mode_stop();
            finish();                                                        // Hangs with TCP
        } else if (idx == 1) {                                               // If Test...
            startActivity(new Intent(this, VideoTestActivity.class));
        } else if (idx == 2) {
            mTransport.usb_force();
        } else if (idx == 3) {
            Utils.sys_run(DATA_DATA + "/lib/libusb_reset.so /dev/bus/usb/*/* 1>/dev/null 2>/dev/null", true);
        } else if (idx >= PRESET_LEN_FIX) {
            mTransport.presets_select(idx - PRESET_LEN_FIX);
        }
    }

    private void car_mode_start() {
        try {
            if (mUiModeManager == null) {
                mUiModeManager = (UiModeManager) getSystemService(UI_MODE_SERVICE);
            }
            mUiModeManager.enableCarMode(0);
        } catch (Throwable t) {
            Utils.loge(t);
        }
    }

    private void car_mode_stop() {
        try {
            if (mUiModeManager != null) {                                          // If was used to enable...
                mUiModeManager.disableCarMode(0);
                Utils.logd("OK disableCarMode");
            }
        } catch (Throwable t) {
            Utils.loge(t);
        }
        Utils.logd("After disableCarMode");
    }


    public void ui_video_started_set(boolean started) {                  // Called directly from HeadUnitTransport:jni_aap_start() because runOnUiThread() won't work
        if (isVideoStarted == started) {
            return;
        }

        Utils.logd("Started: "+started);

        if (started) {
            SystemUI.hide(mContentView, mDrawerLayout);
            mSurfaceView.setVisibility(View.VISIBLE);                     // Enable  video
        } else {
            mSurfaceView.setVisibility(View.GONE);                    // Disable video
        }

        isVideoStarted = started;
    }


    private void all_stop() {

        if (!starting_car_mode) {
            SystemUI.show(mContentView);

            car_mode_stop();
        }

        mAudioDecoder.stop();
        mVideoDecoder.stop();
        isVideoStarted = false;

        if (mTransport != null) {
            mTransport.transport_stop();
        }

        try {
            if (mWakelock != null)
                mWakelock.release();
        } catch (Throwable t) {
            Utils.loge("Throwable: " + t);
        }

    }

    // Touch:

    private void touch_send(MotionEvent event) {

        int x = (int) (event.getX(0) / (mSurfaceView.getWidth() / m_virt_vid_wid));
        int y = (int) (event.getY(0) / (mSurfaceView.getHeight() / m_virt_vid_hei));

        if (x < 0 || y < 0 || x >= 65535 || y >= 65535) {   // Infinity if vid_wid_get() or vid_hei_get() return 0
            Utils.loge("Invalid x: " + x + "  y: " + y);
            return;
        }

        byte aa_action;
        int me_action = event.getActionMasked();
        switch (me_action) {
            case MotionEvent.ACTION_DOWN:
                Utils.logd("event: " + event + " (ACTION_DOWN)    x: " + x + "  y: " + y);
                aa_action = MotionEvent.ACTION_DOWN;
                break;
            case MotionEvent.ACTION_MOVE:
                Utils.logd("event: " + event + " (ACTION_MOVE)    x: " + x + "  y: " + y);
                aa_action = MotionEvent.ACTION_MOVE;
                break;
            case MotionEvent.ACTION_CANCEL:
                Utils.logd("event: " + event + " (ACTION_CANCEL)  x: " + x + "  y: " + y);
                aa_action = MotionEvent.ACTION_UP;
                break;
            case MotionEvent.ACTION_UP:
                Utils.logd("event: " + event + " (ACTION_UP)      x: " + x + "  y: " + y);
                aa_action = MotionEvent.ACTION_UP;
                break;
            default:
                Utils.loge("event: " + event + " (Unknown: " + me_action + ")  x: " + x + "  y: " + y);
                return;
        }
        if (mTransport != null) {
            mTransport.touch_send(aa_action, x, y);
        }
    }

    public void sys_ui_hide() {
        SystemUI.hide(mContentView, mDrawerLayout);
    }

    public void handleMedia(byte[] buffer, int size) {
        ByteBuffer bb = ByteBuffer.wrap(buffer);
        bb.limit(size);
        bb.position(0);

        if (VideoDecoder.isH246Video(buffer)) {
            mVideoDecoder.decode(bb);
        } else {
            mAudioDecoder.decode(bb);
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Utils.logd("holder" + holder);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Utils.logd("holder %s, format: %d, width: %d, height: %d", holder, format, width, height);
        mVideoDecoder.onSurfaceHolderAvailable(holder, width, height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {

    }

    public void presets_update(String[] usb_list_name) {                // Update Presets. Called only by HeadUnitActivity:usb_add() & HeadUnitActivity:usb_del()
        for (int idx = 0; idx < PRESET_LEN_USB; idx++) {
            Utils.logd("idx: " + idx + "  name: " + usb_list_name[idx]);
            if (usb_list_name[idx] != null) {
                mDrawerSections[idx + PRESET_LEN_FIX] = usb_list_name[idx];
            }
        }
        mDrawerListView.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, mDrawerSections));
    }

}