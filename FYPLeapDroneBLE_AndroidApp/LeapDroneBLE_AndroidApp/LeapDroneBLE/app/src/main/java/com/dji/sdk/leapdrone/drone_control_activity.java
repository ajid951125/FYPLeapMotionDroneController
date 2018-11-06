package com.dji.sdk.leapdrone;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.dji.sdk.leapdrone.ui.joystick.OnScreenJoystick;
import com.dji.sdk.leapdrone.ui.joystick.OnScreenJoystickListener;
import com.dji.sdk.leapdrone.utils.DialogUtils;
import com.dji.sdk.leapdrone.utils.ModuleVerificationUtils;

import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;

import dji.common.error.DJIError;
import dji.common.flightcontroller.FlightControllerState;
import dji.common.flightcontroller.virtualstick.FlightControlData;
import dji.common.flightcontroller.virtualstick.FlightCoordinateSystem;
import dji.common.flightcontroller.virtualstick.RollPitchControlMode;
import dji.common.flightcontroller.virtualstick.VerticalControlMode;
import dji.common.flightcontroller.virtualstick.YawControlMode;
import dji.common.util.CommonCallbacks;
import dji.sdk.flightcontroller.FlightController;
import dji.sdk.products.Aircraft;

import static java.lang.System.nanoTime;

public class drone_control_activity extends Activity implements View.OnClickListener{
    private static final String TAG = drone_control_activity.class.getName();

    /**
     * UI related variables
     */
    private TextView ble_device_address_tv;
    private TextView ble_connection_state_tv;
    private TextView ble_received_data_tv;
    private Button drone_takeoff_btn;
    private Button drone_land_btn;
    private OnScreenJoystick mScreenJoystickRight;
    private OnScreenJoystick mScreenJoystickLeft;
    private boolean backPressedToExitOnce = false;

    /**
     * BLE related variables
     */
    private ble_backend_service mBluetoothLeService;
    public static final String EXTRAS_DEVICE_NAME = "DEVICE_NAME";
    public static final String EXTRAS_DEVICE_ADDRESS = "DEVICE_ADDRESS";
    private String current_BLEdevice_name;
    private String current_BLEdevice_address;
    private boolean is_BLEdevice_connected = false;

    /**
     * Flight(Drone) control related variables
     */
    private boolean isTakeOff = false;
    private boolean isLanding = false;
    private FlightController mFlightController;
    private Timer mSendVirtualStickDataTimer;
    private SendFlightControlDataTask mSendVirtualStickDataTask;
    private int drone_action = 0;
    private float drone_action_speed = 0;
    private float mPitch = 0;
    private float mRoll = 0;
    private float mYaw = 0;
    private float mThrottle = 0;
        // 0 - no_action
        // 1 - takeoff
        // 2 - land
        // 3 - roll
        // 4 - throttle
        // 5 - pitch
        // 6 - yaw
    private String[] actionMap = {"no_action","takeoff","land","roll","throttle","pitch","yaw"};

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.drone_control_activity_layout);

        // define ble device name and address
        final Intent intent = getIntent();
        current_BLEdevice_name = intent.getStringExtra(EXTRAS_DEVICE_NAME);
        current_BLEdevice_address = intent.getStringExtra(EXTRAS_DEVICE_ADDRESS);

        // initialize this layout view
        initUI();

        // bind this activity with ble_backend_service
        Intent gattServiceIntent = new Intent(this, ble_backend_service.class);
        bindService(gattServiceIntent, mServiceConnection, BIND_AUTO_CREATE);

        // for logging logcat to file in external memory of android device
        if ( isExternalStorageWritable() ) {

            File appDirectory = new File( Environment.getExternalStorageDirectory() + "/MyPersonalAppFolder" );
            File logDirectory = new File( appDirectory + "/log" );
            File logFile = new File( logDirectory, "logcat" + System.currentTimeMillis() + ".txt" );

            // create app folder
            if ( !appDirectory.exists() ) {
                appDirectory.mkdir();
            }

            // create log folder
            if ( !logDirectory.exists() ) {
                logDirectory.mkdir();
            }

            // clear the previous logcat and then write the new one to the file
            try {
                Process process = Runtime.getRuntime().exec("logcat -c");
                process = Runtime.getRuntime().exec("logcat -f " + logFile + " *:S latency_calculation:D");
            } catch ( IOException e ) {
                e.printStackTrace();
            }
        }
    }

    @Override
    protected void onResume() {
        Log.e(TAG, "onResume");
        super.onResume();

        // for BLE device
        ble_received_data_tv.setText(R.string.ble_no_data);
        registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());
        if (mBluetoothLeService != null) {
            final boolean result = mBluetoothLeService.connect(current_BLEdevice_address);
            Log.d(TAG, "Connect request result = " + result);
        }

        // for flight controller
        resetDroneFlightControlData(); // reset [roll,pitch,throttle,yaw] = 0
        Aircraft aircraft = app_backend.getAircraftInstance();
        if (aircraft == null || !aircraft.isConnected()) {
            finishAffinity();
        }
        initFlightController();
    }

    @Override
    protected void onPause() {
        super.onPause();

        // for BLE device
        unregisterReceiver(mGattUpdateReceiver);

        // for flight controller
        resetDroneFlightControlData();
    }

    @Override
    public void onBackPressed() {
        if (backPressedToExitOnce) {
            finishAffinity();
        } else {
            this.backPressedToExitOnce = true;
            Toast.makeText(drone_control_activity.this, "Press again to exit", Toast.LENGTH_SHORT).show();
            new Handler().postDelayed(new Runnable() {

                @Override
                public void run() {
                    backPressedToExitOnce = false;
                }
            }, 2000);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // for BLE device
        ble_received_data_tv.setText(R.string.ble_no_data);
        unbindService(mServiceConnection);
        mBluetoothLeService = null;

        // for flight controller
        //mScreenJoystickLeft.setJoystickListener(null);
        //mScreenJoystickRight.setJoystickListener(null);
        resetDroneFlightControlData(); // reset [roll,pitch,throttle,yaw] = 0
        if(ModuleVerificationUtils.isFlightControllerAvailable()) {
            ((Aircraft) app_backend.getProductInstance()).getFlightController().setStateCallback(null);
        }
    }

    @Override
    public void onClick(View v) { // handled on_click buttons on this view
        switch (v.getId()) {
            case R.id.btn_take_off:
                performTakeoff(mFlightController);
                break;

            case R.id.btn_land:
                performLanding(mFlightController);
                break;

            default:
                break;
        }
    }

    private void initUI() { // Sets up UI references.
        // BLE related
        ble_device_address_tv = findViewById(R.id.device_address);
        ble_device_address_tv.setText(current_BLEdevice_address);
        ble_connection_state_tv = findViewById(R.id.connection_state);
        ble_received_data_tv = findViewById(R.id.data_value);
        ble_received_data_tv.setText(R.string.ble_no_data);

        // Drone related
        drone_takeoff_btn = findViewById(R.id.btn_take_off);
        drone_land_btn = findViewById(R.id.btn_land);
        mScreenJoystickLeft = findViewById(R.id.directionJoystickLeft);
        mScreenJoystickRight = findViewById(R.id.directionJoystickRight);

        drone_takeoff_btn.setOnClickListener(this);
        drone_land_btn.setOnClickListener(this);
        /*
        mScreenJoystickLeft.setJoystickListener(new OnScreenJoystickListener() {
            @Override
            public void onTouch(OnScreenJoystick joystick, float pX, float pY) {
                if(Math.abs(pX) < 0.02 ){
                    pX = 0;
                }
                if(Math.abs(pY) < 0.02 ){
                    pY = 0;
                }
                float verticalJoyControlMaxSpeed = 2;
                float yawJoyControlMaxSpeed = 30;
                mYaw = yawJoyControlMaxSpeed * pX;
                mThrottle = verticalJoyControlMaxSpeed * pY;
                if (null == mSendVirtualStickDataTimer) {
                    mSendVirtualStickDataTask = new SendFlightControlDataTask();
                    mSendVirtualStickDataTimer = new Timer();
                    mSendVirtualStickDataTimer.schedule(mSendVirtualStickDataTask, 0, 200);
                }
            }
        });
        mScreenJoystickRight.setJoystickListener(new OnScreenJoystickListener(){
            @Override
            public void onTouch(OnScreenJoystick joystick, float pX, float pY) {
                if(Math.abs(pX) < 0.02 ){
                    pX = 0;
                }
                if(Math.abs(pY) < 0.02 ){
                    pY = 0;
                }
                float pitchJoyControlMaxSpeed = 10;
                float rollJoyControlMaxSpeed = 10;
                mRoll = rollJoyControlMaxSpeed * pY;
                mPitch = pitchJoyControlMaxSpeed * pX;
                if (null == mSendVirtualStickDataTimer) {
                    mSendVirtualStickDataTask = new SendFlightControlDataTask();
                    mSendVirtualStickDataTimer = new Timer();
                    mSendVirtualStickDataTimer.schedule(mSendVirtualStickDataTask, 100, 200);
                }
            }
        });
        */
    }

    /**
     * (UI) Options menu codes.....
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.drone_control_activity_menu, menu);
        if (is_BLEdevice_connected) {
            menu.findItem(R.id.menu_connect).setVisible(false);
            menu.findItem(R.id.menu_disconnect).setVisible(true);
        } else {
            menu.findItem(R.id.menu_connect).setVisible(true);
            menu.findItem(R.id.menu_disconnect).setVisible(false);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch(item.getItemId()) {
            case R.id.menu_connect:
                mBluetoothLeService.connect(current_BLEdevice_address);
                return true;
            case R.id.menu_disconnect:
                mBluetoothLeService.disconnect();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * BLE codes.....
     */
    private static IntentFilter makeGattUpdateIntentFilter() { // code to keep the update on action from ble_backend_service.
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ble_backend_service.ACTION_GATT_CONNECTED);
        intentFilter.addAction(ble_backend_service.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(ble_backend_service.ACTION_DATA_AVAILABLE);
        return intentFilter;
    }

    private final ServiceConnection mServiceConnection = new ServiceConnection() { // code to manage Service lifecycle.
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            mBluetoothLeService = ((ble_backend_service.LocalBinder) service).getService();
            if (!mBluetoothLeService.initialize()) {
                Log.e(TAG, "Unable to initialize Bluetooth");
                finishAffinity();
            }
            // Automatically connects to the device upon successful start-up initialization.
            mBluetoothLeService.connect(current_BLEdevice_address);
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mBluetoothLeService = null;
        }
    };

    // Handles various events fired by the Service.
    // ACTION_GATT_CONNECTED: connected to a GATT server.
    // ACTION_GATT_DISCONNECTED: disconnected from a GATT server.
    // ACTION_DATA_AVAILABLE: received data from the device.  This can be a result of read or notification operations.
    private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, final Intent intent) {
            final String action = intent.getAction();
            if (ble_backend_service.ACTION_GATT_CONNECTED.equals(action)) { // if a device is connected
                is_BLEdevice_connected = true;
                updateConnectionStateTextView(R.string.ble_connected);
                invalidateOptionsMenu();
            }
            else if (ble_backend_service.ACTION_GATT_DISCONNECTED.equals(action)) { // if a device is disconnected
                is_BLEdevice_connected = false;
                updateConnectionStateTextView(R.string.ble_disconnected);
                invalidateOptionsMenu();
                ble_received_data_tv.setText(R.string.ble_no_data);
                update_ble_received_data_tv(String.valueOf(R.string.ble_no_data));
            }
            else if (ble_backend_service.ACTION_DATA_AVAILABLE.equals(action)) { // if a characteristic [notify, read] triggered
                // update drone flight control here
                // intent.getStringExtra(BLE_Service.EXTRA_DATA) return [action,speed] for BLE notification
                drone_action = 0; // reset drone_action
                drone_action_speed = 0; // reset action_speed
                String[] ble_received_data = intent.getStringExtra(ble_backend_service.EXTRA_DATA).split(","); // split the action and speed
                drone_action = Integer.parseInt(ble_received_data[0]); // update drone_action to action
                drone_action_speed = Float.parseFloat(ble_received_data[1]); //update drone_action_speed to speed
                if (mFlightController != null){ // if drone is available
                    if(mFlightController.getState().isFlying()){
                        float verticalJoyControlMaxSpeed = 2;
                        float yawJoyControlMaxSpeed = 30;
                        float pitchJoyControlMaxSpeed = 2;
                        float rollJoyControlMaxSpeed = 2;
                        switch(actionMap[drone_action]){
                            case "no_action": // no_action
                                update_ble_received_data_tv(actionMap[drone_action]);
                                resetDroneFlightControlData();
                                if (null == mSendVirtualStickDataTimer) {
                                    mSendVirtualStickDataTask = new SendFlightControlDataTask();
                                    mSendVirtualStickDataTimer = new Timer();
                                    mSendVirtualStickDataTimer.schedule(mSendVirtualStickDataTask, 0, 200); // delay and period in millisec
                                }
                                break;

                            case "land": // perform landing
                                if(!isLanding){
                                    isLanding = true;
                                    update_ble_received_data_tv(actionMap[drone_action]);
                                    resetDroneFlightControlData();
                                    if (null == mSendVirtualStickDataTimer) {
                                        mSendVirtualStickDataTask = new SendFlightControlDataTask();
                                        mSendVirtualStickDataTimer = new Timer();
                                        mSendVirtualStickDataTimer.schedule(mSendVirtualStickDataTask, 0, 200);
                                    }
                                    performLanding(mFlightController);
                                }
                                break;

                            case "roll": // leap: roll, drone: pitch
                                // roll from leap motion gave Px
                                update_ble_received_data_tv(actionMap[drone_action]);
                                mPitch = pitchJoyControlMaxSpeed * drone_action_speed;
                                if (null == mSendVirtualStickDataTimer) {
                                    mSendVirtualStickDataTask = new SendFlightControlDataTask();
                                    mSendVirtualStickDataTimer = new Timer();
                                    mSendVirtualStickDataTimer.schedule(mSendVirtualStickDataTask, 0, 200);
                                }
                                break;

                            case "throttle": // throttle
                                update_ble_received_data_tv(actionMap[drone_action]);
                                mThrottle = verticalJoyControlMaxSpeed * drone_action_speed;
                                if (null == mSendVirtualStickDataTimer) {
                                    mSendVirtualStickDataTask = new SendFlightControlDataTask();
                                    mSendVirtualStickDataTimer = new Timer();
                                    mSendVirtualStickDataTimer.schedule(mSendVirtualStickDataTask, 100, 200);
                                }
                                break;

                            case "pitch": // leap: pitch, drone: roll
                                // pitch from leap motion gave Py
                                update_ble_received_data_tv(actionMap[drone_action]);
                                mRoll = rollJoyControlMaxSpeed * drone_action_speed;
                                if (null == mSendVirtualStickDataTimer) {
                                    mSendVirtualStickDataTask = new SendFlightControlDataTask();
                                    mSendVirtualStickDataTimer = new Timer();
                                    mSendVirtualStickDataTimer.schedule(mSendVirtualStickDataTask, 0, 200);
                                }
                                break;

                            case "yaw": // yaw
                                update_ble_received_data_tv(actionMap[drone_action]);
                                mYaw = yawJoyControlMaxSpeed * drone_action_speed;
                                if (null == mSendVirtualStickDataTimer) {
                                    mSendVirtualStickDataTask = new SendFlightControlDataTask();
                                    mSendVirtualStickDataTimer = new Timer();
                                    mSendVirtualStickDataTimer.schedule(mSendVirtualStickDataTask, 100, 200);
                                }
                                break;

                            default:
                                break;
                        }
                    }
                    else if (actionMap[drone_action].equals("takeoff") && !isTakeOff) {
                        isTakeOff = true;
                        update_ble_received_data_tv(actionMap[drone_action]);
                        resetDroneFlightControlData();
                        if (null == mSendVirtualStickDataTimer) {
                            mSendVirtualStickDataTask = new SendFlightControlDataTask();
                            mSendVirtualStickDataTimer = new Timer();
                            mSendVirtualStickDataTimer.schedule(mSendVirtualStickDataTask, 0, 200);
                        }
                        performTakeoff(mFlightController);
                    }
                }
                else { // if there is no drone connected
                    ble_received_data_tv.setText(R.string.ble_no_data);
                }
            }
        }
    };

    private void updateConnectionStateTextView(final int resourceId) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                ble_connection_state_tv.setText(resourceId);
            }
        });
    }

    private void update_ble_received_data_tv(String data) {
        if (data != null) {
            ble_received_data_tv.setText(data);
        }
    }

    /**
     * Flight controller codes...
     */
    private void initFlightController() {
        Aircraft aircraft = app_backend.getAircraftInstance();
        if (aircraft == null || !aircraft.isConnected()) {
            mFlightController = null;
        }
        else {
            mFlightController = aircraft.getFlightController();
            mFlightController.setRollPitchControlMode(RollPitchControlMode.VELOCITY); // (MIN:-4,MAX:4)meters/s
            mFlightController.setVerticalControlMode(VerticalControlMode.VELOCITY); // (MIN:-15,MAX:15) meters/s
            mFlightController.setYawControlMode(YawControlMode.ANGULAR_VELOCITY); // (MIN:-100,MAX:100) degrees/s
            mFlightController.setRollPitchCoordinateSystem(FlightCoordinateSystem.BODY);
            mFlightController.setVirtualStickModeEnabled(true, new CommonCallbacks.CompletionCallback() {
                @Override
                public void onResult(DJIError djiError) {
                    if (djiError != null) {
                        DialogUtils.showDialogBasedOnError(drone_control_activity.this, djiError);
                    }
                    else {
                        Log.d(TAG, "DRONE: virtual stick enabled");
                    }
                }
            });
            mFlightController.setStateCallback(new FlightControllerState.Callback() {
                @Override
                public void onUpdate(@NonNull FlightControllerState flightControllerState) {
                    if (flightControllerState.isLandingConfirmationNeeded()) {
                        landingConfirmation(mFlightController);
                    }
                }
            });

        }
    }

    private void performTakeoff(FlightController mFlightController){
        if (mFlightController == null) {
            return;
        }
        if(!mFlightController.getState().isFlying() && !mFlightController.getState().areMotorsOn()) {
            mFlightController.startTakeoff(new CommonCallbacks.CompletionCallback() {
                @Override
                public void onResult(DJIError djiError) {
                    if (djiError != null) {
                        DialogUtils.showDialogBasedOnError(drone_control_activity.this, djiError);
                        isTakeOff = false;
                        logTimeInMicroSecond("Takeoff_failed");
                    } else {
                        DialogUtils.showDialog(drone_control_activity.this, "takeoff!!");
                        isTakeOff = false;
                        logTimeInMicroSecond("Takeoff_succeed");
                    }
                }
            });
        }
    }

    private void performLanding(FlightController mFlightController){
        if (mFlightController == null) {
            return;
        }
        if(mFlightController.getState().isFlying()){
            mFlightController.startLanding(new CommonCallbacks.CompletionCallback() {
                @Override
                public void onResult(DJIError djiError) {
                    if(djiError != null) {
                        DialogUtils.showDialogBasedOnError(drone_control_activity.this, djiError);
                        isLanding = false;
                        logTimeInMicroSecond("Begin_Landing_failed");
                    }
                    else {
                        DialogUtils.showDialog(drone_control_activity.this, "Begin landing!!");
                        isLanding = true;
                        logTimeInMicroSecond("Begin_Landing_succeed");
                    }
                }
            });
        }
    }

    private void landingConfirmation(FlightController mFlightController){
        mFlightController.confirmLanding(new CommonCallbacks.CompletionCallback() {
            @Override
            public void onResult(DJIError djiError) {
                if(djiError != null) {
                    DialogUtils.showDialogBasedOnError(drone_control_activity.this, djiError);
                    isLanding = false;
                    logTimeInMicroSecond("Confirm_Landing_failed");
                }
                else {
                    DialogUtils.showDialog(drone_control_activity.this, "Landing confirmed!!");
                    isLanding = false;
                    logTimeInMicroSecond("Confirm_Landing_succeed");
                }
            }
        });
    }

    private void resetDroneFlightControlData(){
        mRoll = 0;
        mPitch = 0;
        mYaw = 0;
        mThrottle = 0;
    }

    class SendFlightControlDataTask extends TimerTask {
        @Override
        public void run() {
            if (mFlightController != null) {
                mFlightController.sendVirtualStickFlightControlData(new FlightControlData(mPitch, mRoll, mYaw, mThrottle), new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onResult(DJIError djiError) {
                        if(djiError != null) {
                            Log.d(TAG, djiError.toString());
                            logTimeInMicroSecond("FlightControlDataUpdate failed");
                        }
                        else{
                            logTimeInMicroSecond("FlightControlDataUpdate success");
                        }
                    }
                });
            }
        }
    }

    // This function is solely to log time in microsecond to calculate latency of this system
    private void logTimeInMicroSecond(String current_action_str){
        if(mFlightController != null){
            long time = nanoTime() / 1000;
            @SuppressLint("SimpleDateFormat") DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
            Date date = new Date();
            Log.d("latency_calculation",String.valueOf(dateFormat.format(date)) + "stop action->" + current_action_str + ", time(ms):" + String.valueOf(time) + ", altitude:" + String.valueOf(mFlightController.getState().getAircraftLocation().getAltitude()));
        }
    }

    /* Checks if external storage is available for read and write */
    public boolean isExternalStorageWritable() {
        String state = Environment.getExternalStorageState();
        if ( Environment.MEDIA_MOUNTED.equals( state ) ) {
            return true;
        }
        return false;
    }
}
