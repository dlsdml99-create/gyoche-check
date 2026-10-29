package com.gyoche.check;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

// 알림 시각이 되면 알림을 보내고 다음 날 알림을 예약. 폰을 껐다 켜거나 앱을 업데이트해도 다시 예약.
public class ReminderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String a = i == null ? null : i.getAction();
        if (a != null && a.startsWith("com.gyoche.check.") && !Reminder.ACTION.equals(a)) { Reminder.handleAction(c, i); return; }
        if (Reminder.ACTION.equals(a)) {
            try { Reminder.notifyNow(c, false); } catch (Exception ignored) {}
        }
        Reminder.schedule(c);
        ChaenggimWidget.updateAll(c);
    }
}
