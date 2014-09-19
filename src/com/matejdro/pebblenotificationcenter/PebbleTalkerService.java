package com.matejdro.pebblenotificationcenter;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.media.AudioManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.util.SparseArray;
import com.getpebble.android.kit.PebbleKit;
import com.getpebble.android.kit.util.PebbleDictionary;
import com.luckycatlabs.sunrisesunset.SunriseSunsetCalculator;
import com.matejdro.pebblenotificationcenter.appsetting.AppSetting;
import com.matejdro.pebblenotificationcenter.appsetting.AppSettingStorage;
import com.matejdro.pebblenotificationcenter.appsetting.DefaultAppSettingsStorage;
import com.matejdro.pebblenotificationcenter.appsetting.SharedPreferencesAppStorage;
import com.matejdro.pebblenotificationcenter.location.LocationLookup;
import com.matejdro.pebblenotificationcenter.notifications.JellybeanNotificationListener;
import com.matejdro.pebblenotificationcenter.notifications.NotificationHandler;
import com.matejdro.pebblenotificationcenter.util.PebbleDeveloperConnection;
import com.matejdro.pebblenotificationcenter.util.PreferencesUtil;
import com.matejdro.pebblenotificationcenter.util.TextUtil;
import com.matejdro.pebblenotificationcenter.util.WatchappHandler;
import timber.log.Timber;

import java.net.URISyntaxException;
import java.util.*;

public class PebbleTalkerService extends Service
{
    //This used to be UUID for all system apps, but now they all get their separate UUID it seems
    public static final UUID invalidUUID = UUID.fromString("0a7575eb-e5b9-456b-8701-3eacb62d74f1");

    private static PebbleTalkerService instance;

    private SharedPreferences settings;
    private DefaultAppSettingsStorage defaultSettingsStorage;
    private NotificationHistoryStorage historyDb;
    private Handler handler;

    private PebbleDeveloperConnection devConn;
    private static UUID previousUUID;

    private NotificationListAdapter listHandler;

    private boolean commBusy = false;
    private Queue<Integer> notificationRemovalQueue = new LinkedList<Integer>();

    PendingNotification curSendingNotification;
    private Queue<PendingNotification> sendingQueue = new LinkedList<PendingNotification>();
    private SparseArray<PendingNotification> sentNotifications = new SparseArray<PendingNotification>();

    private LocationLookup locationLookup;

    @Override
    public IBinder onBind(Intent intent)
    {
        return null;
    }

    @Override
    public void onDestroy()
    {
        instance = null;
        if (devConn != null)
        {
            devConn.close();
        }
        historyDb.close();
        handler.removeCallbacksAndMessages(null);
        locationLookup.close();
    }

    @Override
    public void onCreate()
    {
        handler = new Handler();
        instance = this;
        settings = PreferenceManager.getDefaultSharedPreferences(this);
        defaultSettingsStorage = new DefaultAppSettingsStorage(settings, settings.edit());
        historyDb = new NotificationHistoryStorage(this);

        try
        {
            devConn = new PebbleDeveloperConnection();
            devConn.connectBlocking();
        } catch (InterruptedException e)
        {
        } catch (URISyntaxException e)
        {
        }

        locationLookup = new LocationLookup(this.getApplicationContext());
        locationLookup.lookup();
        super.onCreate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId)
    {

        if (intent != null && intent.hasExtra("id"))
        {
            int id = intent.getIntExtra("id", -1);
            String title = intent.getStringExtra("title");
            String pkg = intent.getStringExtra("pkg");

            String tag = intent.getStringExtra("tag");
            String subtitle = intent.getStringExtra("subtitle");
            String text = intent.getStringExtra("text");
            boolean dismissable = intent.getBooleanExtra("dismissable", false);
            boolean noHistory = intent.getBooleanExtra("noHistory", false);
            boolean isListNotification = intent.getBooleanExtra("isListNotification", false);

            notifyInternal(id, pkg, tag, title, subtitle, text, dismissable, noHistory, isListNotification);
        } else
            appOpened();

        return super.onStartCommand(intent, flags, startId);
    }

    private void send(PendingNotification notification)
    {
        Timber.d("Send " + notification.id);

        curSendingNotification = notification;
        sentNotifications.put(notification.id, notification);

        int periodicVibrationInterval = 0;
        try
        {
            periodicVibrationInterval = Math.min(Integer.parseInt(notification.settingStorage.getString(AppSetting.PERIODIC_VIBRATION)), 128);
        } catch (NumberFormatException e)
        {
        }

        PebbleDictionary data = new PebbleDictionary();
        List<Byte> vibrationPattern = AppSetting.parseVibrationPattern(notification.settingStorage);

        byte[] configBytes = new byte[4 + vibrationPattern.size()];

        byte flags = 0;
        flags |= (byte) (notification.dismissable ? 0x01 : 0);
        flags |= (byte) (notification.isListNotification ? 0x2 : 0);
        flags |= (byte) (notification.settingStorage.getBoolean(AppSetting.SWITCH_TO_MOST_RECENT_NOTIFICATION) ? 0x4 : 0);

        configBytes[0] = Byte.parseByte(settings.getString("textSize", "0"));
        configBytes[1] = flags;
        configBytes[2] = (byte) periodicVibrationInterval;
        configBytes[3] = (byte) vibrationPattern.size();
        for (int i = 0; i < vibrationPattern.size(); i++)
            configBytes[4 + i] = vibrationPattern.get(i);

        int timeout = 0;
        try
        {
            timeout = Math.min(30000, Integer.parseInt(settings.getString("watchappTimeout", "0")));
        } catch (NumberFormatException e)
        {
        }

        data.addUint8(0, (byte) 0);
        data.addInt32(1, notification.id);
        data.addBytes(2, configBytes);
        data.addUint16(3, (short) timeout);
        data.addUint8(4, (byte) notification.textChunks.size());
        data.addString(5, notification.title);
        data.addString(6, notification.subtitle);

        PebbleKit.sendDataToPebble(this, DataReceiver.pebbleAppUUID, data);

        commStarted();
    }

    private void dismissOnPebble(Integer id, boolean dontClose)
    {
        Timber.d("Dismissing upwards...");

        PebbleDictionary data = new PebbleDictionary();

        data.addUint8(0, (byte) 4);
        data.addInt32(1, id);
        if (dontClose)
            data.addUint8(2, (byte) 1);

        PebbleKit.sendDataToPebble(this, DataReceiver.pebbleAppUUID, data);
        commStarted();
    }

    private void dismissOnPebbleInternal(Integer androidId, String pkg, String tag, boolean dontClose)
    {
        Timber.d("got dismiss: " + pkg + " " + androidId + " " + tag);

        AppSettingStorage settingsStorage;
        if (pkg == null)
            settingsStorage = defaultSettingsStorage;
        else
            settingsStorage = new SharedPreferencesAppStorage(this, pkg, defaultSettingsStorage, true);

        boolean syncDismissUp = settingsStorage.getBoolean(AppSetting.DISMISS_UPRWADS);
        if (!syncDismissUp)
            return;

        for (int i = 0; i < sentNotifications.size(); i++)
        {
            PendingNotification notification = sentNotifications.valueAt(i);

            if (!notification.isListNotification && notification.androidID != null && notification.androidID.intValue() == androidId.intValue() && notification.pkg != null && notification.pkg.equals(pkg) && (notification.tag == null || notification.tag.equals(tag)))
            {
                Timber.tag("NC Upwards debug");
                Timber.d("	rem notifications check: %b %d", commBusy, sendingQueue.size());
                if (commBusy)
                {
                    notificationRemovalQueue.add(notification.id);
                    continue;
                }

                dismissOnPebble(notification.id, dontClose);
            }
        }

        if (!dontClose)
        {
            Iterator<PendingNotification> iterator = sendingQueue.iterator();
            while (iterator.hasNext())
            {
                PendingNotification notification = iterator.next();

                if (!notification.isListNotification && notification.androidID != null && notification.androidID.intValue() == androidId.intValue() && notification.pkg != null && notification.pkg.equals(pkg) && (notification.tag == null || notification.tag.equals(tag)))
                {
                    iterator.remove();
                }
            }

        }
    }

    private void dismissOnPebbleSucceeded(PebbleDictionary data)
    {
        Timber.tag("NC Upwards debug");
        Timber.d("	dismiss success: %b %d", data.contains(2), notificationRemovalQueue.size());

        if (data.contains(2))
        {
            closeApp();
            return;
        }

        commWentIdle();
    }

    private void notifyInternal(Integer androidID, String pkg, String tag, String title, String subtitle, String text, boolean dismissable, boolean noHistory, boolean isListNotification)
    {
        Timber.d("notify internal");

        text = TextUtil.prepareString(text, 900);

        PendingNotification notification = new PendingNotification();
        notification.androidID = androidID;
        notification.pkg = pkg;
        notification.tag = tag;
        notification.title = TextUtil.prepareString(title, 30);
        notification.subtitle = TextUtil.prepareString(subtitle, 30);
        notification.text = text;
        notification.dismissable = dismissable;
        notification.isListNotification = isListNotification;
        if (pkg == null)
            notification.settingStorage = defaultSettingsStorage;
        else
            notification.settingStorage = new SharedPreferencesAppStorage(this, pkg, defaultSettingsStorage, true);

        Timber.d("got notify: " + pkg + " " + androidID + " " + tag);

        if (!noHistory && notification.settingStorage.getBoolean(AppSetting.SAVE_TO_HISTORY))
            historyDb.storeNotification(System.currentTimeMillis(), title, subtitle, text);

        if (!isListNotification)
        {
            if (notification.androidID != null)
            {
                //Preventing spamming of equal notifications
                for (int i = 0; i < sentNotifications.size(); i++)
                {
                    PendingNotification comparing = sentNotifications.valueAt(i);
                    if (!notification.isListNotification && notification.androidID == comparing.androidID && comparing.text.equals(notification.text) && comparing.title.equals(notification.title) && comparing.subtitle.equals(notification.subtitle))
                    {
                        return;
                    }
                }

                dismissOnPebbleInternal(notification.androidID, notification.pkg, notification.tag, true);
            }

            if (settings.getBoolean(PebbleNotificationCenter.NOTIFICATIONS_DISABLED, false))
                return;

            if (settings.getBoolean("noNotificationsScreenOn", false))
            {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm.isScreenOn())
                    return;
            }

            if (settings.getBoolean(PebbleNotificationCenter.NO_NOTIFY_VIBRATE, false))
            {
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                if (am.getRingerMode() != AudioManager.RINGER_MODE_NORMAL)
                    return;

            }

            if (settings.getBoolean("enableQuietTime", false) && !notification.settingStorage.getBoolean(AppSetting.IGNORE_QUIET_HOURS))
            {
                int startHour = settings.getInt("quiteTimeStartHour", 0);
                int startMinute = settings.getInt("quiteTimeStartMinute", 0);
                int startTime = startHour * 60 + startMinute;

                int endHour = settings.getInt("quiteTimeEndHour", 23);
                int endMinute = settings.getInt("quiteTimeEndMinute", 59);
                int endTime = endHour * 60 + endMinute;

                Calendar calendar = Calendar.getInstance();
                int curHour = calendar.get(Calendar.HOUR_OF_DAY);
                int curMinute = calendar.get(Calendar.MINUTE);
                int curTime = curHour * 60 + curMinute;


                if ((endTime > startTime && curTime <= endTime && curTime >= startTime) || (endTime < startTime && (curTime <= endTime || curTime >= startTime)))
                {
                    return;
                }
            }

            if (settings.getBoolean("noNotificationsNoPebble", false) && !isWatchConnected())
                return;

            updateCurrentlyRunningApp();
            int pebbleAppMode = 0;
            if (previousUUID != null)
                pebbleAppMode = PreferencesUtil.getPebbleAppNotificationMode(settings, previousUUID);
            else
                pebbleAppMode = PreferencesUtil.getPebbleAppNotificationMode(settings, invalidUUID);

            if (pebbleAppMode == 1) //Pebble native notification
            {
                String nativeTitle = notification.title;
                String nativeText = notification.subtitle + "\n\n" + notification.text;

                devConn.sendNotification(nativeTitle, nativeText);
                return;
            } else if (pebbleAppMode == 2) //No notification
            {
                return;
            }
        }

        Random rnd = new Random();
        do
        {
            notification.id = rnd.nextInt();
        }
        while (sentNotifications.get(notification.id) != null);

        while (text.length() > 0)
        {
            String chunk = TextUtil.trimString(text, 80, false);
            notification.textChunks.add(chunk);
            text = text.substring(chunk.length());
        }

        openApp();

        if (commBusy)
        {
            sendingQueue.add(notification);
        } else
            send(notification);
    }

    private void updateCurrentlyRunningApp()
    {
        UUID currentApp = devConn.getCurrentRunningApp();

        if (currentApp != null && !(currentApp.getLeastSignificantBits() == 0 && currentApp.getMostSignificantBits() == 0) && !currentApp.equals(DataReceiver.pebbleAppUUID) && !currentApp.equals(invalidUUID))
        {
            previousUUID = currentApp;
        }
    }

    private void openApp()
    {
        PebbleKit.startAppOnPebble(this, DataReceiver.pebbleAppUUID);
    }

    private void closeApp()
    {
        Timber.d("CloseApp " + previousUUID);
        commBusy = false;

        if (settings.getBoolean(PebbleNotificationCenter.CLOSE_TO_LAST_APP, false) && previousUUID != null)
            PebbleKit.startAppOnPebble(this, previousUUID);
        else
            PebbleKit.closeAppOnPebble(this, DataReceiver.pebbleAppUUID);

        Editor editor = settings.edit();
        editor.putLong("lastClose", System.currentTimeMillis());
        editor.apply();

            stopSelf();
    }

    private void appOpened()
    {
        sendConfig(sendingQueue.size() > 0 || curSendingNotification != null);
    }

    private void configDelivered()
    {
        if (commWentIdle())
            return;

        Timber.i("Sending notification list");

        if (NotificationHandler.isNotificationListenerSupported())
        {
            PebbleDictionary data = new PebbleDictionary();
            data.addUint8(0, (byte) 3);

            PebbleKit.sendDataToPebble(this, DataReceiver.pebbleAppUUID, data);
        } else
        {
            listHandler = new RecentNotificationsAdapter(this, historyDb);
            listHandler.sendNotification(0);
        }

    }

    /**
     * Called when communication becomes idle and something else can be sent
     *
     * @return true if that function did anything, false if communication is still idle after calling.
     */
    private boolean commWentIdle()
    {
        Timber.i("Went idle");

        if (curSendingNotification != null)
        {
            send(curSendingNotification);
            return true;
        }

        PendingNotification next = sendingQueue.poll();
        if (next != null)
        {
            send(next);
            return true;
        }

        if (notificationRemovalQueue.size() > 0)
        {
            Integer nextRemovalNotifiaction = notificationRemovalQueue.poll();
            dismissOnPebble(nextRemovalNotifiaction, false);
            return true;
        }

        //Clean up excess history entries every day
        long lastDbCleanup = settings.getLong("lastCleanup", 0);
        if (System.currentTimeMillis() - lastDbCleanup > 1000 * 3600 * 24)
        {
            historyDb.cleanDatabase();
        }

        commBusy = false;

        return false;
    }

    /**
     * Starts timer that will mark communication as idle, if nothing happened in 10 seconds.
     */
    private void commStarted()
    {
        Timber.i("Not Idle");

        commBusy = true;
    }

    private void menuPicked(PebbleDictionary data)
    {
        int index = data.getUnsignedInteger(1).intValue();
        if (index == 1 || !NotificationHandler.isNotificationListenerSupported())
        {
            listHandler = new RecentNotificationsAdapter(this, historyDb);
            listHandler.sendNotification(0);
        } else
        {
            listHandler = new ActiveNotificationsAdapter(this);
            listHandler.sendNotification(0);
        }

    }

    private void moreTextRequested(PebbleDictionary data)
    {
        Timber.d("More text requested...");

        int id = data.getInteger(1).intValue();

        PendingNotification notification = sentNotifications.get(id);
        if (notification == null)
        {
            Timber.d("Unknown ID!");

            notificationTransferCompleted();
            return;
        }

        int chunk = data.getUnsignedInteger(2).intValue();

        if (notification.textChunks.size() <= chunk)
        {
            Timber.d("Too much chunks!");

            notificationTransferCompleted();
            return;
        }

        data = new PebbleDictionary();

        data.addUint8(0, (byte) 1);
        data.addInt32(1, id);
        data.addUint8(2, (byte) chunk);
        data.addString(3, notification.textChunks.get(chunk));

        Timber.d("Sending more text...");

        PebbleKit.sendDataToPebble(this, DataReceiver.pebbleAppUUID, data);
        commStarted();
    }

    private void notificationTransferCompleted()
    {
        Timber.d("Transfer completed...");

        curSendingNotification = null;

        Timber.d("csn null: " + (curSendingNotification == null));
        Timber.d("queue size: " + sendingQueue.size());

        if (commWentIdle())
            return;
    }

    private void dismissRequested(PebbleDictionary data)
    {
        int id = data.getInteger(1).intValue();

        Timber.d("Dismiss requested. Close: " + data.contains(2));

        PendingNotification notification = sentNotifications.get(id);
        if (notification != null)
        {
            JellybeanNotificationListener.dismissNotification(notification.pkg, notification.tag, notification.androidID);
        }

        if (data.contains(2))
            closeApp();
    }

    private void sendConfig(boolean notificationWaiting)
    {
        PebbleDictionary data = new PebbleDictionary();

        data.addUint8(0, (byte) 3);

        byte[] configBytes = new byte[11];

        int timeout = 0;
        try
        {
            timeout = Math.min(30000, Integer.parseInt(settings.getString("watchappTimeout", "0")));
        } catch (NumberFormatException e)
        {
        }

        boolean backlight = false;
        int backlightSetting = Integer.parseInt(settings.getString(PebbleNotificationCenter.LIGHT_SCREEN_ON_NOTIFICATIONS, "2"));
        switch (backlightSetting)
        {
            case 1:
                break;
            case 2:
                backlight = true;
                break;
            case 3:
                locationLookup.lookup();
                backlight = SunriseSunsetCalculator.isSunDown(settings);
                break;
        }

        configBytes[0] = (byte) Integer.parseInt(settings.getString(PebbleNotificationCenter.FONT_TITLE, "6"));
        configBytes[1] = (byte) Integer.parseInt(settings.getString(PebbleNotificationCenter.FONT_SUBTITLE, "5"));
        configBytes[2] = (byte) Integer.parseInt(settings.getString(PebbleNotificationCenter.FONT_BODY, "4"));
        configBytes[3] = (byte) (timeout >>> 0x08);
        configBytes[4] = (byte) timeout;

        byte flags = 0;
        flags |= (byte) (settings.getBoolean(PebbleNotificationCenter.CLOSE_TO_LAST_APP, false) ? 0x02 : 0);
        flags |= (byte) (NotificationHandler.isNotificationListenerSupported() ? 0x04 : 0);
        flags |= (byte) (notificationWaiting ? 0x08 : 0);
        flags |= (byte) (backlight ? 0x10 : 0);
        flags |= (byte) (settings.getBoolean(PebbleNotificationCenter.DONT_VIBRATE_WHEN_CHARGING, true) ? 0x20 : 0);
        flags |= (byte) (settings.getBoolean(PebbleNotificationCenter.INVERT_COLORS, false) ? 0x40 : 0);
        flags |= (byte) (settings.getBoolean(PebbleNotificationCenter.NOTIFICATIONS_DISABLED, false) ? 0x80 : 0);


        configBytes[7] = flags;

        configBytes[8] = (byte) (WatchappHandler.SUPPORTED_PROTOCOL >>> 0x08);
        configBytes[9] = (byte) WatchappHandler.SUPPORTED_PROTOCOL;
        configBytes[10] = (byte) Integer.parseInt(settings.getString(PebbleNotificationCenter.SHAKE_ACTION, "1"));

        data.addBytes(1, configBytes);

        Timber.d("Sending config...");

        PebbleKit.sendDataToPebble(this, DataReceiver.pebbleAppUUID, data);

        commBusy = false;
    }

    private void receivedConfigChange(PebbleDictionary data)
    {
        Editor editor = settings.edit();

        int id = data.getUnsignedInteger(1).intValue();
        switch (id)
        {
            case 0: //Notifications disabled
                boolean value = data.getUnsignedInteger(2) != 0;
                editor.putBoolean(PebbleNotificationCenter.NOTIFICATIONS_DISABLED, value);
                break;
        }

        editor.apply();
    }

	private void packetInternal(int id, PebbleDictionary data)
	{
		switch (id)
		{
		case 0:
			appOpened();
			break;
		case 1:
			moreTextRequested(data);
			break;
		case 2:
			notificationTransferCompleted();
			break;
		case 3:
			dismissRequested(data);
			break;
		case 4:
			if (listHandler != null) listHandler.gotRequest(data);
			break;
		case 5:
			if (listHandler != null) listHandler.entrySelected(data);
			break;
		case 6:
			menuPicked(data);
			break;
		case 7:
			closeApp();
			break;
		case 8:
			if (listHandler != null) listHandler.sendRelativeNotification(data);
			break;
		case 9:
			dismissOnPebbleSucceeded(data);
			break;
		case 10:
			configDelivered();
			break;
        case 11:
            receivedConfigChange(data);
            break;
		}
	}

    private boolean isWatchConnected()
    {
        try
        {
            return PebbleKit.isWatchConnected(this);
        }
        catch (Exception e)
        {
            return false;
        }
    }

	public static void notify(Context context, String pkg, String title, String text)
	{
		notify(context, pkg, title, text, false);
	}

	public static void notify(Context context, String pkg, String title, String text, boolean noHistory)
	{
		//Attempt to figure out subtitle
		String subtitle = "";

		if (text.contains("\n"))
		{
			int firstLineBreak = text.indexOf('\n');
			if (firstLineBreak < 40 && firstLineBreak < text.length() * 0.8)
			{
				subtitle = text.substring(0, firstLineBreak).trim();
				text = text.substring(firstLineBreak).trim();
			}
		}

		notify(context, pkg, title, subtitle, text, noHistory, false);
	}

	public static void notify(Context context, String pkg, String title, String subtitle, String text)
	{
		notify(context, pkg, title, subtitle, text, false, false);
	}

	public static void notify(Context context, String pkg, String title, String subtitle, String text, boolean noHistory, boolean isListNotification)
	{
		notify(context, null, pkg, null, title, subtitle, text, false, noHistory, isListNotification);
	}

	public static void notify(Context context, Integer id, String pkg, String tag, String title, String subtitle, String text, boolean dismissable)
	{
		notify(context, id, pkg, tag, title, subtitle, text, dismissable, false, false);
	}

	public static void notify(Context context, Integer id, String pkg, String tag, String title, String subtitle, String text, boolean dismissable, boolean noHistory, boolean isListNotification)
	{
		if (title == null)
			title = "";
		if (subtitle == null)
			subtitle = "";
		if (subtitle.trim().equalsIgnoreCase(title.trim()))
			subtitle = "";
		if (text == null)
			text = "";

        Timber.d("notify");
		PebbleTalkerService service = PebbleTalkerService.instance;

		if (service == null)
		{
			Intent startIntent = new Intent(context, PebbleTalkerService.class);

			startIntent.putExtra("id", id);
			startIntent.putExtra("pkg", pkg);
			startIntent.putExtra("tag", tag);
			startIntent.putExtra("title", title);
			startIntent.putExtra("subtitle", subtitle);
			startIntent.putExtra("text", text);
			startIntent.putExtra("dismissable", dismissable);
			startIntent.putExtra("noHistory", noHistory);
			startIntent.putExtra("isListNotification", isListNotification);

			context.startService(startIntent);
		}
		else
		{
			service.notifyInternal(id, pkg, tag, title, subtitle, text, dismissable, noHistory, isListNotification);
		}
	}

	public static void dismissOnPebble(Integer id, String pkg, String tag)
	{
		PebbleTalkerService service = PebbleTalkerService.instance;

		if (service != null)
		{
			service.dismissOnPebbleInternal(id, pkg, tag, false);
		}
	}

	public static void gotPacket(final Context context, final int packetId, final PebbleDictionary data)
	{
		PebbleTalkerService service = PebbleTalkerService.instance;

		if (service == null)
		{
			Intent startIntent = new Intent(context, PebbleTalkerService.class);
			context.startService(startIntent);
		}
		else
		{
			service.packetInternal(packetId, data);
		}
	}
}
