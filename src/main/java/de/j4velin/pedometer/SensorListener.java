/*
 * Copyright 2013 Thomas Hoffmann
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.j4velin.pedometer;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.IBinder;
import android.util.Log;

import java.io.Serializable;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;

import de.j4velin.pedometer.ui.Activity_Main;
import de.j4velin.pedometer.util.Logger;
import de.j4velin.pedometer.util.Util;
import de.j4velin.pedometer.widget.WidgetUpdateService;

/**
 * Background service which keeps the step-sensor listener alive to always get
 * the number of steps since boot.
 * <p/>
 * This service won't be needed any more if there is a way to read the
 * step-value without waiting for a sensor event
 */
public class SensorListener extends Service implements SensorEventListener {

    private final static int NOTIFICATION_ID = 1;
    private final static long MICROSECONDS_IN_ONE_MINUTE = 60000000;
    private final static long SAVE_OFFSET_TIME = AlarmManager.INTERVAL_HOUR;
    private final static int SAVE_OFFSET_STEPS = 50;

    public final static String ACTION_PAUSE = "pause";

    private static int steps;
    private static int lastSaveSteps;
    private static long lastSaveTime;

    Sensor countsensor;
    Sensor as;

    public final static String ACTION_UPDATE_NOTIFICATION = "updateNotificationState";

    @Override
    public void onAccuracyChanged(final Sensor sensor, int accuracy) {
        // nobody knows what happens here: step value might magically decrease
        // when this method is called...
        if (BuildConfig.DEBUG) Logger.log(sensor.getName() + " accuracy changed: " + accuracy);
    }

    private static double count=0.0;
    double last=0;
    long now,prev=0;


    public static final int MAX_BUFFER_SIZE = 5;

    private static final int Y_DATA_COUNT = 4;
    private static final double MIN_GRAVITY = 1.4;
    private static final double MAX_GRAVITY = 1200;

    private ArrayList<float[]> mAccelDataBuffer = new ArrayList<float[]>();
    private ArrayList<Long> mMagneticFireData = new ArrayList<Long>();
    private Long mLastStepTime = null;
    private ArrayList<Pair> mAccelFireData = new ArrayList<Pair>();

    private void accelDetector(float[] detectedValues, long timeStamp)
    {
        float[] currentValues = new float[3];
        for (int i = 0; i < currentValues.length; ++i)
        {
            currentValues[i] = detectedValues[i];
        }
        mAccelDataBuffer.add(currentValues);
        if (mAccelDataBuffer.size() > MAX_BUFFER_SIZE)
        {
            double avgGravity = 0;
            for (float[] values : mAccelDataBuffer)
            {
                avgGravity += Math.abs(Math.sqrt(
                        values[0] * values[0] + values[1] * values[1] + values[2] * values[2]) -    SensorManager.STANDARD_GRAVITY);
            }
            avgGravity /= mAccelDataBuffer.size();

            if (avgGravity >= MIN_GRAVITY && avgGravity < MAX_GRAVITY)
            {
                mAccelFireData.add(new Pair(timeStamp, true));
            }
            else
            {
                mAccelFireData.add(new Pair(timeStamp, false));
            }

            if (mAccelFireData.size() >= Y_DATA_COUNT)
            {
                checkData(mAccelFireData, timeStamp);

                mAccelFireData.remove(0);
            }

            mAccelDataBuffer.clear();
        }
    }

    private void checkData(ArrayList<Pair> accelFireData, long timeStamp)
    {
        boolean stepAlreadyDetected = false;

        Iterator<Pair> iterator = accelFireData.iterator();
        while (iterator.hasNext() && !stepAlreadyDetected)
        {
            stepAlreadyDetected = iterator.next().first.equals(mLastStepTime);
        }
        if (!stepAlreadyDetected)
        {
            int firstPosition = Collections.binarySearch(mMagneticFireData, accelFireData.get(0).first);
            int secondPosition = Collections
                    .binarySearch(mMagneticFireData, accelFireData.get(accelFireData.size() - 1).first - 1);

            if (firstPosition > 0 || secondPosition > 0 || firstPosition != secondPosition)
            {
                if (firstPosition < 0)
                {
                    firstPosition = -firstPosition - 1;
                }
                if (firstPosition < mMagneticFireData.size() && firstPosition > 0)
                {
                    mMagneticFireData = new ArrayList<Long>(
                            mMagneticFireData.subList(firstPosition - 1, mMagneticFireData.size()));
                }

                iterator = accelFireData.iterator();
                while (iterator.hasNext())
                {
                    if (iterator.next().second)
                    {
                        mLastStepTime = timeStamp;
                        accelFireData.remove(accelFireData.size() - 1);
                        accelFireData.add(new Pair(timeStamp, false));
                        steps++;
                        updateIfNecessary();
                        break;
                    }
                }
            }
        }
    }

    private float mLastDirections;
    private float mLastValues;
    private float mLastExtremes[] = new float[2];
    private Integer mLastType;
    private ArrayList<Float> mMagneticDataBuffer = new ArrayList<Float>();

    private void magneticDetector(float[] values, long timeStamp)
    {
        mMagneticDataBuffer.add(values[2]);

        if (mMagneticDataBuffer.size() > MAX_BUFFER_SIZE)
        {
            float avg = 0;

            for (int i = 0; i < mMagneticDataBuffer.size(); ++i)
            {
                avg += mMagneticDataBuffer.get(i);
            }

            avg /= mMagneticDataBuffer.size();

            float direction = (avg > mLastValues ? 1 : (avg < mLastValues ? -1 : 0));
            if (direction == -mLastDirections)
            {
                // Direction changed
                int extType = (direction > 0 ? 0 : 1); // minumum or maximum?
                mLastExtremes[extType] = mLastValues;
                float diff = Math.abs(mLastExtremes[extType] - mLastExtremes[1 - extType]);

                if (diff > 4 && (null == mLastType || mLastType != extType))
                {
                    mLastType = extType;

                    mMagneticFireData.add(timeStamp);
                }
            }
            mLastDirections = direction;
            mLastValues = avg;

            mMagneticDataBuffer.clear();
        }
    }

    public static class Pair implements Serializable
    {
        Long first;
        boolean second;

        public Pair(long first, boolean second)
        {
            this.first = first;
            this.second = second;
        }

        @Override
        public boolean equals(Object o)
        {
            if (o instanceof Pair)
            {
                return first.equals(((Pair) o).first);
            }
            return false;
        }
    }

    @Override
    public void onSensorChanged(final SensorEvent event) {
        if (event.values[0] > Integer.MAX_VALUE) {
            if (BuildConfig.DEBUG) Logger.log("probably not a real value: " + event.values[0]);
            return;
        } else {
            final float[] values = event.values;
            final Sensor sensor = event.sensor;

            if (sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD)
            {
                magneticDetector(values, event.timestamp / (500 * 10 ^ 6l));
            }
            if (sensor.getType() == Sensor.TYPE_ACCELEROMETER)
            {
                accelDetector(values, event.timestamp / (500 * 10 ^ 6l));
            }
            /*if (event.sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION){
                now=System.currentTimeMillis();
                float time=(float)(now-prev);
                //finding the time in seconds
                float time_s=(time/1000);
                //finding the acceleration
                double x=Double.parseDouble(String.valueOf(event.values[0]));
                double y=Double.parseDouble(String.valueOf(event.values[1]));
                double z=Double.parseDouble(String.valueOf(event.values[2]));
                double res=Math.sqrt(x*x+y*y+z*z);
                //finding the distance
                double dist=(res*time_s*time_s*1000);
                if (dist>=1.85)
                {
                    count=count+0.8;
                }
                prev=now;
                steps = Double.valueOf(count).intValue();
            }*/
            if(event.sensor.getType()==Sensor.TYPE_STEP_COUNTER) {
                steps = (int) event.values[0];
            }
            updateIfNecessary();
        }
    }

    private void updateIfNecessary() {
        if (steps > lastSaveSteps + SAVE_OFFSET_STEPS ||
                (steps > 0 && System.currentTimeMillis() > lastSaveTime + SAVE_OFFSET_TIME)) {
            if (BuildConfig.DEBUG) Logger.log(
                    "saving steps: steps=" + steps + " lastSave=" + lastSaveSteps +
                            " lastSaveTime=" + new Date(lastSaveTime));
            Database db = Database.getInstance(this);
            if (db.getSteps(Util.getToday()) == Integer.MIN_VALUE) {
                int pauseDifference = steps -
                        getSharedPreferences("pedometer", Context.MODE_PRIVATE)
                                .getInt("pauseCount", steps);
                db.insertNewDay(Util.getToday(), steps - pauseDifference);
                if (pauseDifference > 0) {
                    // update pauseCount for the new day
                    getSharedPreferences("pedometer", Context.MODE_PRIVATE).edit()
                            .putInt("pauseCount", steps).commit();
                }
            }
            db.saveCurrentSteps(steps);
            db.close();
            lastSaveSteps = steps;
            lastSaveTime = System.currentTimeMillis();
            updateNotificationState();
            startService(new Intent(this, WidgetUpdateService.class));
        }
    }

    @Override
    public IBinder onBind(final Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(final Intent intent, int flags, int startId) {
        if (intent != null && ACTION_PAUSE.equals(intent.getStringExtra("action"))) {
            if (BuildConfig.DEBUG)
                Logger.log("onStartCommand action: " + intent.getStringExtra("action"));
            if (steps == 0) {
                Database db = Database.getInstance(this);
                steps = db.getCurrentSteps();
                db.close();
            }
            SharedPreferences prefs = getSharedPreferences("pedometer", Context.MODE_PRIVATE);
            if (prefs.contains("pauseCount")) { // resume counting
                int difference = steps -
                        prefs.getInt("pauseCount", steps); // number of steps taken during the pause
                Database db = Database.getInstance(this);
                db.addToLastEntry(-difference);
                db.close();
                prefs.edit().remove("pauseCount").commit();
                updateNotificationState();
            } else { // pause counting
                // cancel restart
                ((AlarmManager) getApplicationContext().getSystemService(Context.ALARM_SERVICE))
                        .cancel(PendingIntent.getService(getApplicationContext(), 2,
                                new Intent(this, SensorListener.class),
                                PendingIntent.FLAG_UPDATE_CURRENT));
                prefs.edit().putInt("pauseCount", steps).commit();
                updateNotificationState();
                stopSelf();
                return START_NOT_STICKY;
            }
        }

        if (intent != null && intent.getBooleanExtra(ACTION_UPDATE_NOTIFICATION, false)) {
            updateNotificationState();
        } else {
            updateIfNecessary();
        }

        // restart service every hour to save the current step count
        ((AlarmManager) getApplicationContext().getSystemService(Context.ALARM_SERVICE))
                .set(AlarmManager.RTC, Math.min(Util.getTomorrow(),
                        System.currentTimeMillis() + AlarmManager.INTERVAL_HOUR), PendingIntent
                        .getService(getApplicationContext(), 2,
                                new Intent(this, SensorListener.class),
                                PendingIntent.FLAG_UPDATE_CURRENT));

        return START_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (BuildConfig.DEBUG) Logger.log("SensorListener onCreate");
        reRegisterSensor();
        updateNotificationState();
    }

    @Override
    public void onTaskRemoved(final Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        if (BuildConfig.DEBUG) Logger.log("sensor service task removed");
        // Restart service in 500 ms
        ((AlarmManager) getSystemService(Context.ALARM_SERVICE))
                .set(AlarmManager.RTC, System.currentTimeMillis() + 500, PendingIntent
                        .getService(this, 3, new Intent(this, SensorListener.class), 0));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (BuildConfig.DEBUG) Logger.log("SensorListener onDestroy");
        try {
            SensorManager sm = (SensorManager) getSystemService(SENSOR_SERVICE);
            if(countsensor != null || as != null) {
                sm.unregisterListener(this);
            }
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Logger.log(e);
            e.printStackTrace();
        }
    }

    private void updateNotificationState() {
        if (BuildConfig.DEBUG) Logger.log("SensorListener updateNotificationState");
        SharedPreferences prefs = getSharedPreferences("pedometer", Context.MODE_PRIVATE);
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (prefs.getBoolean("notification", true)) {
            int goal = prefs.getInt("goal", 10000);
            Database db = Database.getInstance(this);
            int today_offset = db.getSteps(Util.getToday());
            if (steps == 0)
                steps = db.getCurrentSteps(); // use saved value if we haven't anything better
            db.close();
            Notification.Builder notificationBuilder = new Notification.Builder(this);
            if (steps > 0) {
                if (today_offset == Integer.MIN_VALUE) today_offset = -steps;
                notificationBuilder.setProgress(goal, today_offset + steps, false).setContentText(
                        today_offset + steps >= goal ? getString(R.string.goal_reached_notification,
                                NumberFormat.getInstance(Locale.getDefault())
                                        .format((today_offset + steps))) :
                                getString(R.string.notification_text,
                                        NumberFormat.getInstance(Locale.getDefault())
                                                .format((goal - today_offset - steps))));
            } else { // still no step value?
                notificationBuilder
                        .setContentText(getString(R.string.your_progress_will_be_shown_here_soon));
            }
            boolean isPaused = prefs.contains("pauseCount");
            notificationBuilder.setPriority(Notification.PRIORITY_MIN).setShowWhen(false)
                    .setContentTitle(isPaused ? getString(R.string.ispaused) :
                            getString(R.string.notification_title)).setContentIntent(PendingIntent
                    .getActivity(this, 0, new Intent(this, Activity_Main.class),
                            PendingIntent.FLAG_UPDATE_CURRENT))
                    .setSmallIcon(R.drawable.app_icon)
                    .addAction(isPaused ? R.drawable.ic_resume : R.drawable.ic_pause,
                            isPaused ? getString(R.string.resume) : getString(R.string.pause),
                            PendingIntent.getService(this, 4, new Intent(this, SensorListener.class)
                                            .putExtra("action", ACTION_PAUSE),
                                    PendingIntent.FLAG_UPDATE_CURRENT)).setOngoing(true);
            nm.notify(NOTIFICATION_ID, notificationBuilder.build());
        } else {
            nm.cancel(NOTIFICATION_ID);
        }
    }

    private void reRegisterSensor() {
        if (BuildConfig.DEBUG) Logger.log("re-register sensor listener");
        SensorManager sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        try {
            sm.unregisterListener(this);
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Logger.log(e);
            e.printStackTrace();
        }

        if (BuildConfig.DEBUG) {
            Logger.log("step sensors: " + sm.getSensorList(Sensor.TYPE_STEP_COUNTER).size());
            if (sm.getSensorList(Sensor.TYPE_STEP_COUNTER).size() < 1) return; // emulator
            Logger.log("default: " + sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER).getName());
        }

        countsensor= sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        if(countsensor!=null)
        {
            sm.registerListener(this, countsensor,  SensorManager.SENSOR_DELAY_NORMAL, (int) (5 * MICROSECONDS_IN_ONE_MINUTE));
        }
        else
        {
            as=sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            Sensor mags=sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
            if(as!=null)
            {
                sm.registerListener(this, as, SensorManager.SENSOR_DELAY_FASTEST);
                sm.registerListener(this, mags, SensorManager.SENSOR_DELAY_FASTEST);
            }
            else
            {
                Log.e("sensor", "unavailable");
            }
        }
    }
}
