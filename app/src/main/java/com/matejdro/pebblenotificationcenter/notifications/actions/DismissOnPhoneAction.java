package com.matejdro.pebblenotificationcenter.notifications.actions;

import android.content.Context;
import android.os.Parcel;

import com.matejdro.pebblecommons.pebble.PebbleTalkerService;
import com.matejdro.pebblenotificationcenter.NCTalkerService;
import com.matejdro.pebblenotificationcenter.PebbleNotification;
import com.matejdro.pebblenotificationcenter.ProcessedNotification;
import com.matejdro.pebblenotificationcenter.R;
import com.matejdro.pebblenotificationcenter.notifications.JellybeanNotificationListener;
import com.matejdro.pebblenotificationcenter.pebble.modules.DismissUpwardsModule;

/**
 * Created by Matej on 22.9.2014.
 */
public class DismissOnPhoneAction extends NotificationAction
{
    private DismissOnPhoneAction(String text)
    {
        super(text);
    }

    public DismissOnPhoneAction(Context context)
    {
        super(context.getString(R.string.dismissOnPhone));
    }

    @Override
    public boolean executeAction(NCTalkerService service, ProcessedNotification notification)
    {
        dismissOnPhone(notification, service);
        return true;
    }

    public static void dismissOnPhone(ProcessedNotification notification, NCTalkerService service)
    {
        DismissUpwardsModule.dismissPebbleID(service, notification.id);

        if (!notification.source.isDismissable() || notification.source.getKey().getAndroidId() == null)
            return;

        //After all group messages are dismissed (only current is left), we should dismiss summary (some apps do not do this automatically)
        if (notification.source.getWearGroupType() == PebbleNotification.WEAR_GROUP_TYPE_GROUP_MESSAGE &&
            getNumberOfMessagesInSameGroup(notification.source.getWearGroupKey(), service) == 1)
        {
            dismissSummary(notification.source.getWearGroupKey(), service);
        }

        JellybeanNotificationListener.dismissNotification(notification.source.getKey());
    }


    @Override
    public int describeContents()
    {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int i)
    {
        parcel.writeValue(actionText);
    }

    public static final Creator<DismissOnPhoneAction> CREATOR = new Creator<DismissOnPhoneAction>()
    {
        @Override
        public DismissOnPhoneAction createFromParcel(Parcel parcel)
        {
            String text = (String) parcel.readValue(String.class.getClassLoader());

            return new DismissOnPhoneAction(text);
        }

        @Override
        public DismissOnPhoneAction[] newArray(int i)
        {
            return new DismissOnPhoneAction[0];
        }
    };

    private static int getNumberOfMessagesInSameGroup(String group, NCTalkerService service)
    {
        int amount = 0;

        for (int i = 0; i < service.sentNotifications.size(); i++)
        {
            ProcessedNotification notification = service.sentNotifications.valueAt(i);

            if (notification.source.getWearGroupType() == PebbleNotification.WEAR_GROUP_TYPE_GROUP_MESSAGE && notification.source.getWearGroupKey().equalsIgnoreCase(group))
            {
                amount++;
            }
        }

        return amount;
    }

    private static void dismissSummary(String group, NCTalkerService service)
    {
        for (int i = 0; i < service.sentNotifications.size(); i++)
        {
            ProcessedNotification notification = service.sentNotifications.valueAt(i);

            if (notification.source.getWearGroupType() == PebbleNotification.WEAR_GROUP_TYPE_GROUP_SUMMARY && notification.source.getWearGroupKey().equalsIgnoreCase(group))
            {
                JellybeanNotificationListener.dismissNotification(notification.source.getKey());
                break;
            }
        }
    }

}
