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
        // 아침 알림 신호도 이 앱이 만든 것만 (다른 앱이 알림을 마구 띄우지 못하게)
        if (Reminder.ACTION.equals(a) && Reminder.token(c).equals(i.getStringExtra("tok"))) {
            try { Reminder.notifyNow(c, false); } catch (Exception ignored) {}
        }
        Reminder.schedule(c);
        ChaenggimWidget.updateAll(c);
    }
}
