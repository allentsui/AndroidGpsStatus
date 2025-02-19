package com.adam.gpsstatus;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.GnssStatus;
import android.location.GpsSatellite;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;

import androidx.core.app.ActivityCompat;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Created by wuzongheng on 2017/4/2.
 */

public class GpsStatusProxy {
    private static volatile GpsStatusProxy proxy;
    private Context context;
    private LocationManager locationManager;
    private List<WeakReference<GpsStatusListener>> listenerList;
    private List<Satellite> satelliteList;
    private boolean isGpsLocated = false;
    private Object listenerCompat = null;
    private Handler handler = new Handler();

    public static GpsStatusProxy getInstance(Context context) {
        if (proxy == null) {
            synchronized (GpsStatusProxy.class) {
                if (proxy == null) {
                    proxy = new GpsStatusProxy(context);
                }
            }
        }
        return proxy;
    }

    public GpsStatusProxy(Context context) {
        this.context = context;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            listenerCompat = new GnssStatusCallback();
        } else {
            listenerCompat = new GpsStatusCallback();
        }
    }

    /**
     * Register GPS Status Listener, must check for permission of ACCESS_FINE_LOCATION first!
     */
    public void register() {
        unRegister();
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            locationManager.registerGnssStatusCallback((GnssStatusCallback) listenerCompat, handler);
        } else {
            locationManager.addGpsStatusListener((GpsStatusCallback) listenerCompat);
        }
    }

    /**
     * Release GPS Status Listener
     */
    public synchronized void unRegister() {
        if (locationManager == null)
            return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            locationManager.unregisterGnssStatusCallback((GnssStatusCallback) listenerCompat);
        } else {
            locationManager.removeGpsStatusListener((GpsStatusCallback) listenerCompat);
        }
        locationManager = null;
    }

    /**
     * Call this after location has changed to refresh status
     * @param location new location
     */
    public void notifyLocation(Location location) {
        isGpsLocated = location.getProvider().equals(LocationManager.GPS_PROVIDER);

        refreshStatus();
    }

    /**
     * @return List of {@link Satellite} found
     */
    public List<Satellite> getSatelliteList(){
        return satelliteList;
    }

    /**
     * 用于GPS信号断断续续时GPS字体颜色的改变。不同于gpsStatusListener用于GPS开关时GPS字体颜色的改变
     */
    void refreshStatus(){
        if (isGpsLocated){
            // 之前gps未定位，突然gps定位了
            for (WeakReference<GpsStatusListener> listenerWeakReference : listenerList) {
                if (listenerWeakReference.get() != null) {
                    listenerWeakReference.get().onFixed();
                }
            }
        }else {
            // 之前是gps定位，突然不是了
            if (checkOpenGps(context)){
                // 进房间或者隧道了，重新搜索卫星
                for (WeakReference<GpsStatusListener> listenerWeakReference : listenerList) {
                    if (listenerWeakReference.get() != null) {
                        listenerWeakReference.get().onUnFixed();
                    }
                }
            }else {
                // 手动关闭GPS了
                for (WeakReference<GpsStatusListener> listenerWeakReference : listenerList) {
                    if (listenerWeakReference.get() != null) {
                        listenerWeakReference.get().onStop();
                    }
                }
            }
        }
    }

    void addListener(GpsStatusListener listener) {
        if (listenerList == null) {
            listenerList = new ArrayList<>();
        } else {
            Iterator<WeakReference<GpsStatusListener>> iterator = listenerList.iterator();
            while (iterator.hasNext()) {
                WeakReference<GpsStatusListener> listenerWeakReference = iterator.next();
                if (listenerWeakReference.get() == null) {
                    iterator.remove();
                } else if (listenerWeakReference.get() == listener) {
                    return;
                }
            }
        }

        listenerList.add(new WeakReference<GpsStatusListener>(listener));
    }

    void removeListener(GpsStatusListener listener) {
        if (listenerList == null)
            return;
        Iterator<WeakReference<GpsStatusListener>> iterator = listenerList.iterator();
        while (iterator.hasNext()) {
            WeakReference<GpsStatusListener> listenerWeakReference = iterator.next();
            if (listenerWeakReference.get() == null || listenerWeakReference.get() == listener) {
                iterator.remove();
            }
        }
    }

    private boolean checkOpenGps(final Context context) {
        LocationManager alm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (alm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
            return true;
        } else {
            return false;
        }
    }


    class GpsStatusCallback implements GpsStatus.Listener {

        @Override
        public void onGpsStatusChanged(int event) {
            if (listenerList == null || listenerList.size() == 0)
                return;

            switch (event) {//第一次定位
                case GpsStatus.GPS_EVENT_FIRST_FIX:
                    for (WeakReference<GpsStatusListener> listenerWeakReference : listenerList) {
                        if (listenerWeakReference.get() != null) {
                            listenerWeakReference.get().onFixed();
                        }
                    }
                    break;
                //卫星状态改变
                case GpsStatus.GPS_EVENT_SATELLITE_STATUS:
                    // 获取当前状态
                    // 包括 卫星的高度角、方位角、信噪比、和伪随机号（及卫星编号）
                    /*
                     * satellite.getElevation(); //卫星仰角
                     * satellite.getAzimuth();   //卫星方位角
                     * satellite.getSnr();       //信噪比
                     * satellite.getPrn();       //伪随机数，可以认为他就是卫星的编号
                     * satellite.hasAlmanac();   //卫星历书
                     * satellite.hasEphemeris();
                     * satellite.usedInFix();
                     */
                    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                        return;
                    }
                    GpsStatus gpsStatus = null;
                    synchronized (this){
                        if (locationManager != null){
                            gpsStatus = locationManager.getGpsStatus(null);
                        }
                    }
                    if (gpsStatus != null) {
                        //                        GpsStatus gpsStatus=mAMapLocationManager.getGpsStatus(null);
                        //获取卫星颗数的默认最大值
                        int maxSatellites = gpsStatus.getMaxSatellites();
                        //创建一个迭代器保存所有卫星
                        Iterator<GpsSatellite> iters = gpsStatus.getSatellites().iterator();
                        int count = 0;
                        int inUse = 0;
                        satelliteList = new ArrayList<>();
                        while (iters.hasNext() && count <= maxSatellites) {
                            GpsSatellite s = iters.next();
                            count++;
                            if (s.usedInFix()) {
                                inUse++;
                            }
                            if (s.getSnr() > 0) {
                                satelliteList.add(new Satellite(s));
                            }
                        }
                        Collections.sort(satelliteList);

                        for (WeakReference<GpsStatusListener> listenerWeakReference : listenerList) {
                            if (listenerWeakReference.get() != null) {
                                listenerWeakReference.get().onSignalStrength(inUse, count);
                            }
                        }
                    }

                    break;
                //定位启动
                case GpsStatus.GPS_EVENT_STARTED:
                    for (WeakReference<GpsStatusListener> listenerWeakReference : listenerList) {
                        if (listenerWeakReference.get() != null) {
                            listenerWeakReference.get().onStart();
                        }
                    }
                    break;
                //定位结束
                case GpsStatus.GPS_EVENT_STOPPED:
                    for (WeakReference<GpsStatusListener> listenerWeakReference : listenerList) {
                        if (listenerWeakReference.get() != null) {
                            listenerWeakReference.get().onStop();
                        }
                    }
                    break;
            }
        }
    }

    @SuppressLint("NewApi")
    class GnssStatusCallback extends GnssStatus.Callback {

        @Override
        public void onStarted() {
            super.onStarted();
            //定位启动
            for (WeakReference<GpsStatusListener> listenerWeakReference : listenerList) {
                if (listenerWeakReference.get() != null) {
                    listenerWeakReference.get().onStart();
                }
            }
        }

        @Override
        public void onStopped() {
            super.onStopped();
            //定位结束
            for (WeakReference<GpsStatusListener> listenerWeakReference : listenerList) {
                if (listenerWeakReference.get() != null) {
                    listenerWeakReference.get().onStop();
                }
            }
        }

        @Override
        public void onFirstFix(int ttffMillis) {
            super.onFirstFix(ttffMillis);
            //第一次定位
            for (WeakReference<GpsStatusListener> listenerWeakReference : listenerList) {
                if (listenerWeakReference.get() != null) {
                    listenerWeakReference.get().onFixed();
                }
            }
        }

        @Override
        public void onSatelliteStatusChanged(GnssStatus status) {
            super.onSatelliteStatusChanged(status);
            //卫星状态改变
            // 获取当前状态
            // 包括 卫星的高度角、方位角、信噪比、和伪随机号（及卫星编号）
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            if (status != null) {
                //                        GpsStatus gpsStatus=mAMapLocationManager.getGpsStatus(null);
                //获取卫星颗数的默认最大值
                int count = status.getSatelliteCount();
                //创建一个迭代器保存所有卫星
                int inUse = 0;
                satelliteList = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    if (status.usedInFix(i)) {
                        inUse++;
                    }
                    if (status.getCn0DbHz(i) > 0) {
                        satelliteList.add(new Satellite(status.getSvid(i), status.getCn0DbHz(i), status.usedInFix(i)));
                    }
                }
                Collections.sort(satelliteList);

                for (WeakReference<GpsStatusListener> listenerWeakReference : listenerList) {
                    if (listenerWeakReference.get() != null) {
                        listenerWeakReference.get().onSignalStrength(inUse, count);
                    }
                }
            }
        }
    }

}
