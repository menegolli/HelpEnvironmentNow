package it.polito.helpenvironmentnow;

import android.Manifest;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import it.polito.helpenvironmentnow.Helper.BtDevice;
import it.polito.helpenvironmentnow.Helper.DynamicModeStatus;
import it.polito.helpenvironmentnow.Helper.ServiceNotification;
import it.polito.helpenvironmentnow.MyWorker.MyWorkerManager;
import it.polito.helpenvironmentnow.Storage.MyDb;
import it.polito.helpenvironmentnow.Storage.Position;

// This SERVICE is enabled when the user activates the DYNAMIC MODE and it is used to get
// continuous location updates. The positions are saved into a local database; in this way
// we can use the positions later and match with the measures received from the Raspberry Pi device
// using the timestamp as matching criteria.
// The SERVICE runs indefinitely until the user stops it by disabling the DYNAMIC MODE or until the
// connection is lost.
public class DynamicService extends Service {

    private final int SERVICE_ID = 2;
    private String TAG = "DynamicService"; // String used for debug in Log.d() method

    private Looper serviceLooper;
    private ServiceHandler serviceHandler;

    private FusedLocationProviderClient locationClient;
    private LocationCallback locationCallback;

    private MyDb myDb;
    private AtomicBoolean userStop = new AtomicBoolean(true);
    private DynamicRaspberryPi pi;

    // Handler that receives messages from the thread
    private final class ServiceHandler extends Handler {

        public ServiceHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {

            // Check for permissions. If not granted, stop the service.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (getApplicationContext().checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
                    stopSelf();
                }
            }

            // Get info from the activity that started this service
            String name = msg.getData().getString(getString(R.string.DEVICE_NM));
            String address = msg.getData().getString(getString(R.string.DEVICE_ADDR));
            BtDevice btDevice = new BtDevice(name, address);
            LocationRequest locationRequest = (LocationRequest) msg.getData().get(getString(R.string.LOCATION_REQ));

            // Save into Shared Preferences the status(CONNECTING) of the DynamicService
            saveDynamicModeStatus(DynamicModeStatus.CONNECTING, btDevice);

            // Request location updates and open db
            locationClient.requestLocationUpdates(locationRequest, locationCallback, serviceLooper);

            /*for(int i = 0; i < 20; i++ ) {
                Log.d(TAG, "sleep:" + (i+1));
                SystemClock.sleep(1000);
            }
            Log.d(TAG, "sleep finished");*/

            /*while (true) {
                try {
                    if(test) {
                        throw new IOException("");
                    }
                } catch (IOException e) {
                    SystemClock.sleep(3000);
                    endService();
                    break;
                }
            }*/
            // Connect to Raspberry Pi
            pi = new DynamicRaspberryPi(getApplicationContext());
            if(pi.connect(address)) {
                saveDynamicModeStatus(DynamicModeStatus.CONNECTED, btDevice);
                broadcastDynamicModeStatus(DynamicModeStatus.CONNECTED, btDevice);
                try {
                    pi.read(); // it is a BLOCKING call. It is stopped on user request(stop Dynamic mode) or IOException
                    pi.sendLocation();
                    boolean ackReceived = pi.waitLocationAck();
                    pi.closeConnection();
                    if (!ackReceived)
                        endService();
                } catch (IOException e) {
                    endService();
                }
            } else {
                endService();
            }

        }
    }

    @Override
    public void onCreate() {
        // A foreground service in order to work in Android has to show a notification, as quoted by
        // the official guide: "Foreground services must display a Notification."
        ServiceNotification.notifyForeground(this, SERVICE_ID, "Dynamic Service is ON",
                "Keep Android and Pi close together");

        locationClient = LocationServices.getFusedLocationProviderClient(getApplicationContext());
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult == null) {
                    return;
                }

                List<Position> positions = new ArrayList<>();
                for (Location location : locationResult.getLocations()) {
                    int timestamp = ((Long)TimeUnit.MILLISECONDS.toSeconds(location.getTime())).intValue();
                    Position currentPosition = new Position();
                    currentPosition.timestamp = timestamp;
                    currentPosition.latitude = location.getLatitude();
                    currentPosition.longitude = location.getLongitude();
                    currentPosition.altitude = location.getAltitude();
                    positions.add(currentPosition);
                }
                Log.d(TAG, "Positions: " + positions.size());
                myDb.insertPositions(positions);
            }
        };

        myDb = new MyDb(getApplicationContext());
        // Start up the thread running the service. Note that we create a
        // separate thread because the service normally runs in the process's
        // main thread, which we don't want to block.
        HandlerThread thread = new HandlerThread("LocationServiceHT",
                Process.THREAD_PRIORITY_BACKGROUND); // HandlerThread: Helper class that builds a
        // secondary thread that incorporates a Looper and a MessageQueue
        thread.start();

        // Get the HandlerThread's Looper and use it for our Handler
        serviceLooper = thread.getLooper();
        serviceHandler = new ServiceHandler(serviceLooper);
    }

    // Called by the system every time a client explicitly starts the service by calling
    // startService(Intent), providing the arguments it supplied and a unique integer token
    // representing the start request. Do not call this method directly.
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // For each start request, send a message to start a job and deliver the
        // start ID so we know which request we're stopping when we finish the job
        Message msg = serviceHandler.obtainMessage();
        msg.arg1 = startId;
        msg.setData(intent.getExtras());
        serviceHandler.sendMessage(msg);

        // If we get killed, after returning from here, restart
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy(), init");
        if (userStop.get() && pi != null) {
            pi.stopReading();
        }
        saveDynamicModeStatus(DynamicModeStatus.OFF, null);
        if (locationClient != null)
            locationClient.removeLocationUpdates(locationCallback);
        if (myDb != null)
            myDb.closeDb();
        serviceLooper.quitSafely();
        serviceLooper.getThread().interrupt();
        /* I enqueue a work that the Worker Manager will execute when network became available */
        MyWorkerManager.enqueueNetworkWorker(getApplicationContext());
        Log.d(TAG, "onDestroy(), exit");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void saveDynamicModeStatus(int dynamicModeStatus, BtDevice btDevice) {
        SharedPreferences sharedPref = getSharedPreferences(getString(R.string.config_file), Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPref.edit();
        editor.putInt(getString(R.string.MODE), dynamicModeStatus);
        if (btDevice != null) {
            editor.putString(getString(R.string.DEVICE_NM), btDevice.getName());
            editor.putString(getString(R.string.DEVICE_ADDR), btDevice.getAddress());
        }
        editor.commit();
    }

    private void broadcastDynamicModeStatus(int dynamicModeStatus, BtDevice btDevice) {
        Intent localIntent = new Intent(DynamicModeStatus.BROADCAST_ACTION)
                .putExtra(getString(R.string.MODE), dynamicModeStatus);
        if (btDevice != null) {
            localIntent.putExtra(getString(R.string.DEVICE_NM), btDevice.getName())
                    .putExtra(getString(R.string.DEVICE_ADDR), btDevice.getAddress());
        }
        // Broadcasts the Intent to receivers in this app( to the MainActivity )
        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(localIntent);
    }

    private void endService() {
        broadcastDynamicModeStatus(DynamicModeStatus.OFF, null);
        userStop.set(false);
        ServiceNotification.showDisconnect(DynamicService.this);
        stopSelf();
    }
}
