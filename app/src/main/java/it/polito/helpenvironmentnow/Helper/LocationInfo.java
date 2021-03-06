package it.polito.helpenvironmentnow.Helper;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.util.Log;

import com.fonfon.geohash.GeoHash;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.OnSuccessListener;

public class LocationInfo {

    private static final double defaultAltitude = 0.0;
    private static final double defaultLatitude = 0.0;
    private static final double defaultLongitude = 0.0;

    public static boolean getCurrentLocation(Context context, final MyLocationListener myLocationListener) {
        FusedLocationProviderClient locationClient = LocationServices.getFusedLocationProviderClient(context);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
                    PackageManager.PERMISSION_GRANTED &&
                    context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        locationClient.getLastLocation().addOnSuccessListener(new OnSuccessListener<Location>() {
            @Override
            public void onSuccess(Location location) {
                // Got last known location. In some rare situations this can be null.
                if (location != null) {
                    if(!location.hasAltitude())
                        location.setAltitude(defaultAltitude);
                    myLocationListener.locationCompleted(location); // fires the callback in StaticService
                } else {
                    Location defaultLocation = new Location(LocationManager.GPS_PROVIDER);
                    Log.d("LocationInfo", "lat" + defaultLocation.getLatitude()+"long"+defaultLocation.getLongitude()+"alt"+defaultLocation.getAltitude());
                    defaultLocation.setLatitude(defaultLatitude);
                    defaultLocation.setLongitude(defaultLongitude);
                    defaultLocation.setAltitude(defaultAltitude);
                    myLocationListener.locationCompleted(defaultLocation); // fires the callback in StaticService
                }
            }
        });

        return true;
    }

    public static String encodeLocation(Location location) {
        final int numberOfChars = 12; // the same size as the corresponding remote database field - varchar(12)
        GeoHash hash = GeoHash.fromLocation(location, numberOfChars);
        return hash.toString();
    }

    public static String encodeLocation(double latitude, double longitude) {
        final int numberOfChars = 12; // the same size as the corresponding remote database field - varchar(12)

        Location location = new Location("geohash");
        location.setLatitude(latitude);
        location.setLongitude(longitude);
        GeoHash hash = GeoHash.fromLocation(location, numberOfChars);
        return hash.toString();
    }
}
