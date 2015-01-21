package com.example.streamingapp;

import android.app.Activity;
import android.app.ProgressDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.media.MediaScannerConnection.MediaScannerConnectionClient;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.util.SparseArray;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.Window;
import android.view.WindowManager;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Calendar;
import java.util.UUID;



/**
 * Created by Dave Smith
 * Double Encore, Inc.
 * MainActivity
 */
public class MainActivity extends Activity implements BluetoothAdapter.LeScanCallback {
    private static final String TAG = "BluetoothGattActivity";

    //private static final String DEVICE_NAME = "PMD R1-44C9";

    /* Humidity Service */
    private static final UUID MAIN_SERVICE = UUID.fromString("0000FFB0-0000-1000-8000-00805f9b34fb");
    private static final UUID DATA_CHAR = UUID.fromString("0000FFB1-0000-1000-8000-00805f9b34fb");
    private static final UUID CONFIG_CHAR = UUID.fromString("0000FFB2-0000-1000-8000-00805f9b34fb");
    /* Barometric Pressure Service */
   
    /* Client Configuration Descriptor */
    private static final UUID CONFIG_DESCRIPTOR = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");


    private BluetoothAdapter mBluetoothAdapter;
    private SparseArray<BluetoothDevice> mDevices;

    private BluetoothGatt mConnectedGatt;

    private TextView xVal, yVal, zVal, vRefVal, p1Val, p2Val, streamText;

    private ProgressDialog mProgress;
    
    private MediaScannerConnection MSC;
    
    public static final String DIRECTORY_PATH = Environment.getExternalStorageDirectory().getAbsolutePath() + "/pmd-respirasense-stream-logs/";
    
    int counter = 0;
    
    private RelativeLayout mainLayout;
    
    private Handler timoutHandler;
    private MainActivity mainAct = this;
    
    private BluetoothDevice last;
    
    private static boolean firstRun = true;
    
    /**
	 * Runnable responsible for closing the ResultActivity when the timeout is reached.
	 */
	private final Runnable timeout = new Runnable() {
		public void run() {
			Intent it = new Intent("intent.my.action");
			it.setComponent(new ComponentName(mainAct.getPackageName(), MainActivity.class.getName()));
			it.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			mainAct.getApplicationContext().startActivity(it);
			reconnect();
		}
	};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        setContentView(R.layout.activity_main);
        setProgressBarIndeterminate(true);
        
        
        
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);


        /*
         * We are going to display the results in some text fields
         */
//        xVal = (TextView) findViewById(R.id.xVal);
//        yVal = (TextView) findViewById(R.id.yVal);
//        zVal = (TextView) findViewById(R.id.zVal);
//        vRefVal = (TextView) findViewById(R.id.refVal);
//        p1Val = (TextView) findViewById(R.id.p1Val);
//        p2Val = (TextView) findViewById(R.id.p2Val);
        streamText = (TextView) findViewById(R.id.streaming);
        mainLayout = (RelativeLayout) findViewById(R.id.mainLayout);
        
        /*
         * Bluetooth in Android 4.3 is accessed via the BluetoothManager, rather than
         * the old static BluetoothAdapter.getInstance()
         */
        BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        mBluetoothAdapter = manager.getAdapter();

        mDevices = new SparseArray<BluetoothDevice>();

        /*
         * A progress dialog will be needed while the connection process is
         * taking place
         */
        mProgress = new ProgressDialog(this);
        mProgress.setIndeterminate(true);
        mProgress.setCancelable(false);
        if (firstRun){
        	hideUI();
        }
        firstRun = false;
    }
    
    private void hideUI() {
		mainLayout.setVisibility(View.INVISIBLE);
		
	}

	public void reconnect(){
    	
    	 mConnectedGatt = last.connectGatt(this, false, mGattCallback);
         //Display progress UI
         mHandler.sendMessage(Message.obtain(null, MSG_PROGRESS, "Connecting to "+last.getName()+"..."));
         mainLayout.setVisibility(View.VISIBLE);
         
    }

    @Override
    protected void onResume() {
        super.onResume();
        /*
         * We need to enforce that Bluetooth is first enabled, and take the
         * user to settings to enable it if they have not done so.
         */
        if (last != null){
        	mainLayout.setVisibility(View.VISIBLE);
        }
        firstRun = false;
        if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) {
            //Bluetooth is disabled
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivity(enableBtIntent);
            finish();
            return;
        }

        /*
         * Check for Bluetooth LE Support.  In production, our manifest entry will keep this
         * from installing on these devices, but this will allow test devices or other
         * sideloads to report whether or not the feature exists.
         */
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, "No LE Support.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        clearDisplayValues();
    }

    @Override
    protected void onPause() {
        super.onPause();
        //Make sure dialog is hidden
        mProgress.dismiss();
        //Cancel any scans in progress
        mHandler.removeCallbacks(mStopRunnable);
        mHandler.removeCallbacks(mStartRunnable);
        mBluetoothAdapter.stopLeScan(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        //Disconnect from any active tag connection
        if (mConnectedGatt != null) {
            mConnectedGatt.disconnect();
            mConnectedGatt = null;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Add the "scan" option to the menu
        getMenuInflater().inflate(R.menu.main, menu);
        //Add any device elements we've discovered to the overflow menu
        for (int i=0; i < mDevices.size(); i++) {
            BluetoothDevice device = mDevices.valueAt(i);
            menu.add(0, mDevices.keyAt(i), 0, device.getName());
        }

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_scan:
                mDevices.clear();
                startScan();
                return true;
            default:
                //Obtain the discovered device to connect with
                BluetoothDevice device = mDevices.get(item.getItemId());
                last = device;
                Log.i(TAG, "Connecting to "+device.getName());
                /*
                 * Make a connection with the device using the special LE-specific
                 * connectGatt() method, passing in a callback for GATT events
                 */
                mConnectedGatt = device.connectGatt(this, false, mGattCallback);
                //Display progress UI
                mHandler.sendMessage(Message.obtain(null, MSG_PROGRESS, "Connecting to "+device.getName()+"..."));
                return super.onOptionsItemSelected(item);
        }
    }
    
    public void onClick(View v) {
		switch(v.getId()){
		case R.id.pauseButton:
			
			
			break;
		default:
			break;
		}
	}
    
    public void pauseClicked(View v){
    	Log.wtf("Streaming", "Pause Pressed!!");
    	mHandler.removeCallbacks(mStopRunnable);
        mHandler.removeCallbacks(mStartRunnable);
        mBluetoothAdapter.stopLeScan(this);
        if (mConnectedGatt != null) {
            mConnectedGatt.disconnect();
            mConnectedGatt = null;
        }
        //mainLayout.setVisibility(View.INVISIBLE);
        startTimeout();
    	startNewActivity(this, "com.pmdsolutions.respirasense");
    	
    }

    private void startTimeout() {
    	timoutHandler = new Handler();
    	timoutHandler.postDelayed(timeout, 60000);
		
	}

	public void startNewActivity(Context context, String packageName) {
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent != null) {
            /* We found the activity now start the activity */
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } else {
            /* Bring user to the market or let them choose an app? */
            intent = new Intent(Intent.ACTION_VIEW);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.setData(Uri.parse("market://details?id=" + packageName));
            context.startActivity(intent);
        }
    }

	private void clearDisplayValues() {
        
    }


    private Runnable mStopRunnable = new Runnable() {
        @Override
        public void run() {
            stopScan();
        }
    };
    private Runnable mStartRunnable = new Runnable() {
        @Override
        public void run() {
            startScan();
        }
    };

    private void startScan() {
        mBluetoothAdapter.startLeScan(this);
        setProgressBarIndeterminateVisibility(true);

        mHandler.postDelayed(mStopRunnable, 2500);
    }

    private void stopScan() {
        mBluetoothAdapter.stopLeScan(this);
        setProgressBarIndeterminateVisibility(false);
    }

    /* BluetoothAdapter.LeScanCallback */

    @Override
    public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {
        Log.i(TAG, "New LE Device: " + device.getName() + " @ " + rssi);
        /*
         * We are looking for SensorTag devices only, so validate the name
         * that each device reports before adding it to our collection
         */
       
            mDevices.put(device.hashCode(), device);
            //Update the overflow menu
            invalidateOptionsMenu();
        
    }

    /*
     * In this callback, we've created a bit of a state machine to enforce that only
     * one characteristic be read or written at a time until all of our sensors
     * are enabled and we are registered to get notifications.
     */
    private BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {

        /* State Machine Tracking */
        private int mState = 0;

        private void reset() { mState = 0; }

        private void advance() { mState++; }

        /*
         * Send an enable command to each sensor by writing a configuration
         * characteristic.  This is specific to the SensorTag to keep power
         * low by disabling sensors you aren't using.
         */
        private void enableNextSensor(BluetoothGatt gatt) {
            BluetoothGattCharacteristic characteristic;
            switch (mState) {
                case 0:
                    Log.d(TAG, "Enabling pressure cal");
                    characteristic = gatt.getService(MAIN_SERVICE)
                            .getCharacteristic(CONFIG_CHAR);
                    characteristic.setValue(new byte[] {0x02});
                    break;
                default:
                    mHandler.sendEmptyMessage(MSG_DISMISS);
                    Log.i(TAG, "All Sensors Enabled");
                    return;
            }

            gatt.writeCharacteristic(characteristic);
        }

        /*
         * Read the data characteristic's value for each sensor explicitly
         */
//        private void readNextSensor(BluetoothGatt gatt) {
//            BluetoothGattCharacteristic characteristic;
//            switch (mState) {
//                case 0:
//                    Log.d(TAG, "Reading pressure cal");
//                    characteristic = gatt.getService(PRESSURE_SERVICE)
//                            .getCharacteristic(PRESSURE_CAL_CHAR);
//                    break;
//                case 1:
//                    Log.d(TAG, "Reading pressure");
//                    characteristic = gatt.getService(PRESSURE_SERVICE)
//                            .getCharacteristic(PRESSURE_DATA_CHAR);
//                    break;
//                case 2:
//                    Log.d(TAG, "Reading humidity");
//                    characteristic = gatt.getService(HUMIDITY_SERVICE)
//                            .getCharacteristic(HUMIDITY_DATA_CHAR);
//                    break;
//                default:
//                    mHandler.sendEmptyMessage(MSG_DISMISS);
//                    Log.i(TAG, "All Sensors Enabled");
//                    return;
//            }
//
//            gatt.readCharacteristic(characteristic);
//        }

        /*
         * Enable notification of changes on the data characteristic for each sensor
         * by writing the ENABLE_NOTIFICATION_VALUE flag to that characteristic's
         * configuration descriptor.
         */
        private void setNotifyNextSensor(BluetoothGatt gatt) {
            BluetoothGattCharacteristic characteristic;
            switch (mState) {
                case 0:
                    Log.d(TAG, "Set notify pressure cal");
                    characteristic = gatt.getService(MAIN_SERVICE)
                            .getCharacteristic(DATA_CHAR);
                    break;
                
                default:
                    mHandler.sendEmptyMessage(MSG_DISMISS);
                    Log.i(TAG, "All Sensors Enabled");
                    return;
            }

            //Enable local notifications
            gatt.setCharacteristicNotification(characteristic, true);
            //Enabled remote notifications
            BluetoothGattDescriptor desc = characteristic.getDescriptor(CONFIG_DESCRIPTOR);
            desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            gatt.writeDescriptor(desc);
        }

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            Log.d(TAG, "Connection State Change: "+status+" -> "+connectionState(newState));
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                /*
                 * Once successfully connected, we must next discover all the services on the
                 * device before we can read and write their characteristics.
                 */
                gatt.discoverServices();
                mHandler.sendMessage(Message.obtain(null, MSG_PROGRESS, "Discovering Services..."));
            } else if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_DISCONNECTED) {
                /*
                 * If at any point we disconnect, send a message to clear the weather values
                 * out of the UI
                 */
                mHandler.sendEmptyMessage(MSG_CLEAR);
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                /*
                 * If there is a failure at any stage, simply disconnect
                 */
                gatt.disconnect();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            Log.d(TAG, "Services Discovered: "+status);
            mHandler.sendMessage(Message.obtain(null, MSG_PROGRESS, "Enabling Sensors..."));
            /*
             * With services discovered, we are going to reset our state machine and start
             * working through the sensors we need to enable
             */
            reset();
            enableNextSensor(gatt);
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            //For each read, pass the data up to the UI thread to update the display
            if (DATA_CHAR.equals(characteristic.getUuid())) {
                mHandler.sendMessage(Message.obtain(null, MSG_HUMIDITY, characteristic));
            }
            //After reading the initial value, next we enable notifications
            setNotifyNextSensor(gatt);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            //After writing the enable flag, next we read the initial value
        	setNotifyNextSensor(gatt);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            /*
             * After notifications are enabled, all updates from the device on characteristic
             * value changes will be posted here.  Similar to read, we hand these up to the
             * UI thread to update the display.
             */
            if (DATA_CHAR.equals(characteristic.getUuid())) {
                mHandler.sendMessage(Message.obtain(null, MSG_HUMIDITY, characteristic));
            }
            
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            //Once notifications are enabled, we move to the next sensor and start over with enable
            advance();
            enableNextSensor(gatt);
        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
            Log.d(TAG, "Remote RSSI: "+rssi);
        }

        private String connectionState(int status) {
            switch (status) {
                case BluetoothProfile.STATE_CONNECTED:
                    return "Connected";
                case BluetoothProfile.STATE_DISCONNECTED:
                    return "Disconnected";
                case BluetoothProfile.STATE_CONNECTING:
                    return "Connecting";
                case BluetoothProfile.STATE_DISCONNECTING:
                    return "Disconnecting";
                default:
                    return String.valueOf(status);
            }
        }
    };

    /*
     * We have a Handler to process event results on the main thread
     */
    private static final int MSG_HUMIDITY = 101;
    private static final int MSG_PRESSURE = 102;
    private static final int MSG_PRESSURE_CAL = 103;
    private static final int MSG_PROGRESS = 201;
    private static final int MSG_DISMISS = 202;
    private static final int MSG_CLEAR = 301;
    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            BluetoothGattCharacteristic characteristic;
            switch (msg.what) {
                case MSG_HUMIDITY:
                    characteristic = (BluetoothGattCharacteristic) msg.obj;
                    if (characteristic.getValue() == null) {
                        Log.w(TAG, "Error obtaining humidity value");
                        return;
                    }
                    updateHumidityValues(characteristic);
                    updatePressureValue(characteristic);
                    break;
                case MSG_PRESSURE:
                    characteristic = (BluetoothGattCharacteristic) msg.obj;
                    if (characteristic.getValue() == null) {
                        Log.w(TAG, "Error obtaining pressure value");
                        return;
                    }
                    updatePressureValue(characteristic);
                    break;
                case MSG_PRESSURE_CAL:
                    characteristic = (BluetoothGattCharacteristic) msg.obj;
                    if (characteristic.getValue() == null) {
                        Log.w(TAG, "Error obtaining cal value");
                        return;
                    }
                    break;
                case MSG_PROGRESS:
                    mProgress.setMessage((String) msg.obj);
                    if (!mProgress.isShowing()) {
                        mProgress.show();
                    }
                    break;
                case MSG_DISMISS:
                    mProgress.hide();
                    break;
                case MSG_CLEAR:
                    clearDisplayValues();
                    break;
            }
        }
    };

    /* Methods to extract sensor data and update the UI */

    private void updateHumidityValues(BluetoothGattCharacteristic characteristic) {
        byte[] value = characteristic.getValue();
        int humidity = value[0];
       
    }

    private int[] mPressureCals;
   

    private void updatePressureValue(BluetoothGattCharacteristic characteristic) {
    	 mainLayout.setVisibility(View.VISIBLE);
    	byte[] value = characteristic.getValue();
    	 
    	int x = twoBytesToShort(value[0], value[1]);
    	x = x >> 6;
    	int y = twoBytesToShort(value[2], value[3]);
    	y = y >> 6;
    	int z = twoBytesToShort(value[4], value[5]);
    	z = z >> 6;
    	int ref = twoBytesToShort(value[6], value[7]);
    	int p1 = twoBytesToShort(value[8], value[9]);
    	int p2 = twoBytesToShort(value[10], value[11]);
    	  	     
    	counter++;
    	if (counter == 10){
    		streamText.setText("Streaming.");
    		
    	}
    	else if (counter == 20){
    		streamText.setText("Streaming..");
    		
    	}
    	else if (counter == 30){
    		streamText.setText("Streaming...");
    		
    	}
    	else if (counter > 40){
    		streamText.setText("Streaming");
    		counter = 0;
    	}
        Log.wtf(TAG, "updated");
        //mainLayout.setVisibility(View.VISIBLE);
       
        logData(x, y, z, ref, p1, p2);
        
    }
    
    private void logData(int x, int y, int z, int ref, int p1, int p2){
		File outfile = null;
		Log.wtf(TAG, "writing data to log");
		String directoryPath = DIRECTORY_PATH;
		Calendar now = Calendar.getInstance();
		String timestamp = 	now.get(Calendar.DAY_OF_MONTH) + 
				"-" +
				(now.get(Calendar.MONTH) + 1) + 
				"-" + 
				now.get(Calendar.YEAR) + 
				"_" + 
				now.get(Calendar.HOUR_OF_DAY) + 
				"-" + 
				now.get(Calendar.MINUTE) +
				"-" + 
				now.get(Calendar.SECOND) +
				"-" + 
				now.get(Calendar.MILLISECOND);
		final String fileName = "StreamLog.txt";
		//device has already been renamed as such we need to get devices MAC address to get its original name
		//String name = btDevice.getName();

		//"2" prefixed for all error logs, "1" prefixed for maintenance logs
		String contents = timestamp + ", " + x + ", " + y + ", " + z + ", " + ref + ", " + p1 + ", " + p2;
		
		try{
			File directory = new File(directoryPath);
			if(directory.exists() == false){
				directory.mkdirs();
			}
			
			outfile = new File(directoryPath, fileName);

			PrintWriter out = new PrintWriter(new BufferedWriter(new FileWriter(outfile, true)));
			if (outfile.exists() == false){
				outfile.createNewFile();
			}
	    out.print(contents);
	    out.append("\r\n");
	    out.close();
		}catch(Exception e){
			Log.e(TAG, "@CREATE FILE");
		}
//		if(outfile != null){
//			rescanSD(outfile);
//		}
	}
	/**
	 * Method that access the internal storage on the tablet in order to access log files 
	 */
	private void rescanSD(final File toScan){
		MediaScannerConnectionClient MSCC = new MediaScannerConnectionClient(){
			public void onScanCompleted(String path, Uri uri) {
				Log.i(TAG, "Media Scan Completed.");
			}
			public void onMediaScannerConnected() {
				MSC.scanFile(toScan.getAbsolutePath(), null);
				Log.i(TAG, "Media Scanner Connected.");
			}
		};
		MSC = new MediaScannerConnection(getApplicationContext(), MSCC);
		MSC.connect();
	}
    
    public static short twoBytesToShort(byte b1, byte b2) {
        return (short) ((b1 << 8) | (b2 & 0xFF));
}
}
