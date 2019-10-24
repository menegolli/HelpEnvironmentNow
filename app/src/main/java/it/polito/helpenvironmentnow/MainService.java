package it.polito.helpenvironmentnow;

import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.work.Constraints;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import org.json.JSONObject;

import it.polito.helpenvironmentnow.Helper.JsonBuilder;
import it.polito.helpenvironmentnow.Helper.LocationInfo;
import it.polito.helpenvironmentnow.Helper.MyLocationListener;
import it.polito.helpenvironmentnow.Helper.UploadWorker;

public class MainService extends IntentService implements MyLocationListener {

    private final int WAIT_LOCATION_SLEEP_MSEC = 1000;
    private Location curLocation;
    private boolean curLocationReady = false;

    public MainService() {
        super("RaspberryToServerService");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // A foreground service in order to work in Android has to show a notification, as quoted by
        // the official guide: "Foreground services must display a Notification."
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) // check if Android version is 8 or higher
            startMyOwnForeground(); // put the service in a foreground state - for Android 8+
        else
            startForeground(1, new Notification()); // put the service in a foreground state - for Android 7 or below
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void startMyOwnForeground(){
        String NOTIFICATION_CHANNEL_ID = "it.polito.helpenvironmentnow";
        String channelName = "Background HelpEnvironmentNow Service";
        NotificationChannel chan = new NotificationChannel(NOTIFICATION_CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_NONE);
        chan.setLightColor(Color.BLUE);
        chan.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        assert manager != null;
        manager.createNotificationChannel(chan);

        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID);
        Notification notification = notificationBuilder.setOngoing(true)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("HelpEnvironmentNow is running in background")
                .setPriority(NotificationManager.IMPORTANCE_MIN)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
        startForeground(1, notification);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        /* the remote device is the the Raspberry Pi */
        String remoteDeviceMacAddress = intent.getStringExtra("remoteMacAddress");
        RaspberryPi raspberryPi = new RaspberryPi();
        boolean readResult = raspberryPi.connectAndRead(remoteDeviceMacAddress);
        if(readResult) {
            /* readResult=true -> Data has been read correctly from Raspberry Pi */
            LocationInfo.getCurrentLocation(this, this);
            /* Wait until the device current location is returned. When location is ready, locationCompleted(...)
            * is called and sets curLocationReady to true and so the while cycle will be interrupted
            * and the field curLocation will contain latitude, longitude, altitude */
            while(!curLocationReady) {
                Log.d("MainService", "Inside wait while...");
                SystemClock.sleep(WAIT_LOCATION_SLEEP_MSEC);
            }
            JsonBuilder jsonBuilder = new JsonBuilder();
            JSONObject dataBlock = jsonBuilder.parseAndBuildJson(curLocation, raspberryPi.getTempHumMetaData(),
                    raspberryPi.getFixedSensorsData(), raspberryPi.getVariableSensorsData());
            if(isNetworkAvailable()) {
                Log.d("MainService", "Network available!");
                HeRestClient heRestClient = new HeRestClient();
                heRestClient.sendToServer(this, dataBlock);
            } else {
                Log.d("MainService", "Network NOT available!");
                // Create a Constraints object that defines when the task should run
                Constraints constraints = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
                OneTimeWorkRequest uploadWorkRequest = new OneTimeWorkRequest.Builder(UploadWorker.class)
                        .setConstraints(constraints).build();
                WorkManager.getInstance(this).enqueue(uploadWorkRequest);
            }
            Log.d("MainService", "All executed!");
        }
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
        boolean isConnected;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network activeNetwork = cm.getActiveNetwork();
            NetworkCapabilities networkCapabilities = cm.getNetworkCapabilities(activeNetwork);
            isConnected = networkCapabilities != null && networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } else {
            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            isConnected = activeNetwork != null && activeNetwork.isConnected();
        }

        return isConnected;
    }

    @Override
    public void locationCompleted(Location location) {
        curLocation = location;
        curLocationReady = true;
        Log.d("MainService", "lat" + location.getLatitude()+"long"+location.getLongitude()+"alt"+location.getAltitude());
    }
}
