package com.gyoche.check;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

// 홈 화면 위젯: 앱을 열지 않아도 오늘 챙길 물건이 보인다 (알림과 같은 내용)
public class ChaenggimWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        draw(c, m, ids);
    }

    static void updateAll(Context c) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            int[] ids = m.getAppWidgetIds(new ComponentName(c, ChaenggimWidget.class));
            if (ids != null && ids.length > 0) draw(c, m, ids);
        } catch (Exception ignored) {}
    }

    private static void draw(Context c, AppWidgetManager m, int[] ids) {
        Reminder.Summary s = Reminder.summary(c);
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
        v.setTextViewText(R.id.w_title, s.title);
        v.setTextViewText(R.id.w_body, s.widget);
        v.setTextViewText(R.id.w_foot, s.dish != null ? "🍳 " + s.dish : "챙김이 · 눌러서 열기");
        Intent open = new Intent(c, MainActivity.class).putExtra("fromNotify", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(c, 3, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        // 위젯에서 바로 처리: 맨 위 물건 처리 / 장보기에 담기
        if (s.firstId != null) {
            String nm0 = s.firstName.length() > 7 ? s.firstName.substring(0, 7) + "…" : s.firstName;
            v.setTextViewText(R.id.w_done, "✓ " + nm0 + ("food".equals(s.firstCat) ? " 다 먹음" : " 바꿈"));
            v.setViewVisibility(R.id.w_done, android.view.View.VISIBLE);
            v.setOnClickPendingIntent(R.id.w_done, Reminder.actionIntent(c, Reminder.ACT_DONE, 20, s.firstId));
        } else v.setViewVisibility(R.id.w_done, android.view.View.GONE);
        if (!s.shopIds.isEmpty()) {
            v.setViewVisibility(R.id.w_shop, android.view.View.VISIBLE);
            v.setOnClickPendingIntent(R.id.w_shop, Reminder.actionIntent(c, Reminder.ACT_SHOP, 21, null));
        } else v.setViewVisibility(R.id.w_shop, android.view.View.GONE);
        m.updateAppWidget(ids, v);
    }
}
