package com.gyoche.check;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

// 매일 정한 시각에, 확인할 물건이 있으면 한 번 묶어서 알린다
public class Reminder {
    static final String PREF = "gyoche", CHANNEL = "daily";
    static final String ACTION = "com.gyoche.check.DAILY";
    static final String ACT_DONE = "com.gyoche.check.DONE", ACT_SHOP = "com.gyoche.check.SHOP", ACT_SNOOZE = "com.gyoche.check.SNOOZE", ACT_AGAIN = "com.gyoche.check.AGAIN", ACT_USE = "com.gyoche.check.USE";

    // 알림·위젯 버튼에서 누른 일을 쌓아 두었다가 앱을 열 때 화면에 반영한다
    static synchronized void addAction(Context c, JSONObject a) {
        android.content.SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        try {
            JSONArray arr = new JSONArray(p.getString("acts", "[]"));
            if (arr.length() < 200) arr.put(a);
            p.edit().putString("acts", arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    static synchronized String takeActions(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String s = p.getString("acts", "[]");
        p.edit().putString("acts", "[]").apply();
        return s;
    }

    // 다른 앱이 버튼 신호를 흉내 내지 못하게 하는 비밀 값 (이 폰 안에서만 씀)
    static String token(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String t = p.getString("tok", null);
        if (t == null) { t = java.util.UUID.randomUUID().toString(); p.edit().putString("tok", t).apply(); }
        return t;
    }

    static PendingIntent actionIntent(Context c, String action, int code, String id) {
        Intent i = new Intent(c, ReminderReceiver.class).setAction(action).putExtra("tok", token(c));
        if (id != null) i.putExtra("id", id);
        return PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // 버튼으로 처리한 물건은 바로 목록에서 빼서 알림·위젯이 즉시 맞게 보이게
    static void removeItem(Context c, String id) {
        try {
            JSONObject d = data(c);
            JSONArray items = d.optJSONArray("items"), keep = new JSONArray();
            if (items == null) return;
            for (int k = 0; k < items.length(); k++) {
                JSONObject it = items.optJSONObject(k);
                if (it != null && !id.equals(it.optString("id"))) keep.put(it);
            }
            d.put("items", keep);
            save(c, d.toString());
        } catch (Exception ignored) {}
    }

    static void handleAction(Context c, Intent i) {
        String a = i.getAction();
        if (!token(c).equals(i.getStringExtra("tok"))) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        try {
            if (ACT_DONE.equals(a)) {
                String id = i.getStringExtra("id");
                if (id == null) return;
                JSONObject o = new JSONObject().put("type", "done").put("id", id).put("date", ymd(new Date()));
                addAction(c, o);
                removeItem(c, id);
                if (summary(c).count > 0) notifyNow(c, false, true); else if (nm != null) nm.cancel(7);
                android.widget.Toast.makeText(c, "처리했어요. 앱을 열면 기록에 반영돼요", android.widget.Toast.LENGTH_SHORT).show();
            } else if (ACT_SHOP.equals(a)) {
                Summary r = summary(c);
                JSONArray ids = new JSONArray();
                for (String x : r.shopIds) ids.put(x);
                addAction(c, new JSONObject().put("type", "shop").put("ids", ids));
                android.widget.Toast.makeText(c, "장보기 목록에 담았어요", android.widget.Toast.LENGTH_SHORT).show();
            } else if (ACT_SNOOZE.equals(a)) {
                if (nm != null) nm.cancel(7);
                AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
                if (am != null) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 3 * 3600000L, actionIntent(c, ACT_AGAIN, 13, null));
                android.widget.Toast.makeText(c, "3시간 뒤에 다시 알려드릴게요", android.widget.Toast.LENGTH_SHORT).show();
            } else if (ACT_AGAIN.equals(a)) {
                notifyNow(c, false, false);
            } else if (ACT_USE.equals(a)) {
                String id = i.getStringExtra("id"), ym = i.getStringExtra("ym");
                if (id == null || ym == null) return;
                boolean used = i.getBooleanExtra("used", true);
                addAction(c, new JSONObject().put("type", "use").put("id", id).put("ym", ym).put("used", used));
                if (nm != null) nm.cancel(i.getIntExtra("nid", 0));
                android.widget.Toast.makeText(c, used ? "썼다고 기록했어요" : "안 쓴 달로 기록했어요. 2달 연속이면 해지를 추천해 드려요", android.widget.Toast.LENGTH_SHORT).show();
            }
        } catch (Exception ignored) {}
        ChaenggimWidget.updateAll(c);
    }

    static void save(Context c, String json) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("data", json).apply();
    }

    static JSONObject data(Context c) {
        try {
            return new JSONObject(c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("data", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static PendingIntent alarmIntent(Context c) {
        Intent i = new Intent(c, ReminderReceiver.class).setAction(ACTION);
        return PendingIntent.getBroadcast(c, 1, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent p = alarmIntent(c);
        am.cancel(p);
        JSONObject d = data(c);
        if (!d.optBoolean("notify", true)) return;
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, d.optInt("hour", 9));
        cal.set(Calendar.MINUTE, d.optInt("minute", 0));
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        String today = ymd(new Date());
        String sent = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("sentDay", "");
        // 오늘 시각이 이미 지났거나 오늘 이미 보냈으면 내일
        if (cal.getTimeInMillis() <= System.currentTimeMillis() || today.equals(sent)) cal.add(Calendar.DAY_OF_YEAR, 1);
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), p);
    }

    static boolean canNotify(Context c) {
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) return false;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        return nm != null && nm.areNotificationsEnabled();
    }

    private static String ymd(Date d) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(d);
    }

    private static int daysBetween(String from, String to) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.KOREA);
            long a = f.parse(from).getTime(), b = f.parse(to).getTime();
            return (int) Math.round((b - a) / 86400000.0);
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }

    private static String label(int left) {
        if (left == 0) return "오늘";
        if (left > 0) return "D-" + left;
        return (-left) + "일 지남";
    }

    // 오늘 챙길 것 요약 (알림과 홈 화면 위젯이 같이 씀)
    static class Summary {
        int count, over;
        String title = "", text = "", big = "", widget = "";
        String dish, season;
        String firstId, firstName, firstCat;          // 버튼으로 바로 처리할 첫 물건
        List<String> shopIds = new ArrayList<>();      // 장보기에 담을 물건
    }

    static Summary summary(Context c) {
        JSONObject d = data(c);
        JSONArray items = d.optJSONArray("items");
        String today = ymd(new Date());
        List<long[]> idx = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        List<String> dishes = new ArrayList<>();
        List<String> ids = new ArrayList<>(), cats = new ArrayList<>();
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null) continue;
                int left = daysBetween(today, it.optString("due"));
                if (it.optBoolean("repeat", false) && left < 0) continue;   // 반복 일정(구독 결제·기념일)은 지난 회차를 건너뜀
                boolean byKm = it.optBoolean("force", false);   // 자동차: 주행거리로 이미 가까워진 부품
                if (byKm || (left != Integer.MAX_VALUE && left <= it.optInt("alert", 3))) {
                    idx.add(new long[]{byKm ? Math.min(left, 0) : left, names.size()});
                    names.add(it.optString("name"));
                    notes.add(byKm ? it.optString("note", "") : null);
                    dishes.add(it.optString("dish", ""));
                    ids.add(it.optString("id", ""));
                    cats.add(it.optString("cat", ""));
                }
            }
        }
        // 자동차 주행거리를 한 달 넘게 안 넣었으면 함께
        // 냉장고 1분 정리를 일주일 넘게 안 했으면 함께
        String chk = d.optString("chk", "");
        if (!chk.isEmpty() && !"null".equals(chk)) {
            int ago = daysBetween(chk, today);
            if (ago != Integer.MAX_VALUE && ago >= 7) {
                idx.add(new long[]{0, names.size()});
                names.add("냉장고 1분 정리");
                notes.add(ago + "일 전 정리");
                dishes.add("");
                ids.add("");
                cats.add("");
            }
        }
        String kmDate = d.optString("kmDate", "");
        if (!kmDate.isEmpty() && !"null".equals(kmDate)) {
            int ago = daysBetween(kmDate, today);
            if (ago != Integer.MAX_VALUE && ago >= 30) {
                idx.add(new long[]{0, names.size()});
                names.add("주행거리 입력");
                notes.add(ago + "일 전 기록");
                dishes.add("");
                ids.add("");
                cats.add("");
            }
        }
        Collections.sort(idx, (a, b) -> Long.compare(a[0], b[0]));
        Summary r = new Summary();
        r.count = idx.size();
        StringBuilder sb = new StringBuilder(), big = new StringBuilder(), w = new StringBuilder();
        for (int k = 0; k < idx.size(); k++) {
            long[] x = idx.get(k);
            int j = (int) x[1];
            if (x[0] < 0) r.over++;
            String note = notes.get(j);
            String lab = note != null && !note.isEmpty() ? note : label((int) x[0]);
            String one = names.get(j) + "(" + lab + ")";
            if (k < 4) { if (sb.length() > 0) sb.append(", "); sb.append(one); }
            if (k < 8) { if (big.length() > 0) big.append('\n'); big.append("• ").append(one); }
            if (k < 5) { if (w.length() > 0) w.append('\n'); w.append(lab).append("  ").append(names.get(j)); }
            String cat = cats.get(j);
            boolean handy = !ids.get(j).isEmpty() && ("food".equals(cat) || "clothes".equals(cat) || "daily".equals(cat));
            if (handy && r.firstId == null) { r.firstId = ids.get(j); r.firstName = names.get(j); r.firstCat = cat; }
            if (handy && !"clothes".equals(cat)) r.shopIds.add(ids.get(j));
            if (r.dish == null && !dishes.get(j).isEmpty()) r.dish = "오늘 " + dishes.get(j) + " 어때요? (" + names.get(j) + " " + lab + ")";
        }
        if (idx.size() > 4) sb.append(" 외 ").append(idx.size() - 4).append("개");
        if (idx.size() > 8) big.append("\n외 ").append(idx.size() - 8).append("개");
        if (idx.size() > 5) w.append("\n외 ").append(idx.size() - 5).append("개");
        // 계절 알림: 시작하는 날 하루
        JSONArray ss = d.optJSONArray("seasons");
        if (ss != null) {
            String md = today.substring(5);
            for (int i = 0; i < ss.length(); i++) {
                JSONObject o = ss.optJSONObject(i);
                if (o != null && md.equals(o.optString("from"))) { r.season = o.optString("text"); break; }
            }
        }
        r.title = r.count > 0 ? "오늘 챙길 것 " + r.count + "개" + (r.over > 0 ? " (지난 것 " + r.over + "개)" : "") : "오늘은 챙길 것이 없어요";
        r.text = sb.toString();
        if (r.dish != null) big.append("\n\n🍳 ").append(r.dish);
        if (r.season != null) big.append("\n\n🍃 ").append(r.season);
        r.big = big.toString();
        r.widget = w.length() > 0 ? w.toString() : "기한이 다가오면 여기에 보여드려요";
        return r;
    }

    static void notifyNow(Context c, boolean force) { notifyNow(c, force, false); }

    // 구독 결제 3일 전: "이번 달 썼어요?" 따로 한 번 묻기 (버튼으로 바로 답)
    static void askSubs(Context c) {
        if (!canNotify(c)) return;
        JSONObject d = data(c);
        JSONArray items = d.optJSONArray("items");
        if (items == null) return;
        android.content.SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String today = ymd(new Date());
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        ensureChannel(nm);
        int shown = 0;
        for (int k = 0; k < items.length() && shown < 2; k++) {
            JSONObject it = items.optJSONObject(k);
            if (it == null || !it.optBoolean("sub", false) || it.optBoolean("asked", false)) continue;
            String sid = it.optString("sid", ""), ym = it.optString("ym", "");
            int left = daysBetween(today, it.optString("due"));
            if (sid.isEmpty() || ym.isEmpty() || left == Integer.MAX_VALUE || left < 0 || left > 3) continue;
            String key = "q_" + sid + "_" + ym;
            if (p.getBoolean(key, false)) continue;
            p.edit().putBoolean(key, true).apply();
            int nid = 100 + Math.abs(sid.hashCode() % 800);
            String name = it.optString("sname", "구독");
            Intent open = new Intent(c, MainActivity.class).putExtra("fromNotify", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
            android.graphics.drawable.Icon ic = android.graphics.drawable.Icon.createWithResource(c, R.drawable.ic_notify);
            b.setSmallIcon(R.drawable.ic_notify).setColor(0xFF2E7D5B)
                .setContentTitle(name + " 이번 달 썼어요?")
                .setContentText((left == 0 ? "오늘 결제" : "결제 " + left + "일 전") + " · 안 쓰는 구독을 찾아 드려요")
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(c, 2, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .addAction(new Notification.Action.Builder(ic, "썼어요", useIntent(c, nid * 2, sid, ym, true, nid)).build())
                .addAction(new Notification.Action.Builder(ic, "안 썼어요", useIntent(c, nid * 2 + 1, sid, ym, false, nid)).build());
            nm.notify(nid, b.build());
            shown++;
        }
    }

    static void ensureChannel(NotificationManager nm) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "챙김이 아침 알림", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("확인할 물건이 있는 날 아침에 한 번 알려요");
            nm.createNotificationChannel(ch);
        }
    }

    static PendingIntent useIntent(Context c, int code, String sid, String ym, boolean used, int nid) {
        Intent i = new Intent(c, ReminderReceiver.class).setAction(ACT_USE).putExtra("tok", token(c))
            .putExtra("id", sid).putExtra("ym", ym).putExtra("used", used).putExtra("nid", nid);
        return PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void notifyNow(Context c, boolean force, boolean quiet) {
        if (!quiet) { try { askSubs(c); } catch (Exception ignored) {} }
        Summary r = summary(c);
        String today = ymd(new Date());
        if (r.count == 0 && r.season == null && !force) return;
        if (!canNotify(c)) return;

        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        ensureChannel(nm);
        String title = r.title, text = r.text, big = r.big;
        if (r.count == 0) {
            if (r.season != null) { title = "챙김이 계절 알림"; text = r.season; big = r.season; }
            else { text = "기한이 다가오면 이렇게 알려드려요."; big = text; }
        }

        Intent open = new Intent(c, MainActivity.class).putExtra("fromNotify", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 2, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_notify)
            .setColor(0xFF2E7D5B)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(big))
            .setAutoCancel(true)
            .setOnlyAlertOnce(quiet)
            .setContentIntent(pi);
        // 알림에서 바로 처리하는 버튼 (앱을 안 열어도 됨)
        android.graphics.drawable.Icon ic = android.graphics.drawable.Icon.createWithResource(c, R.drawable.ic_notify);
        if (r.firstId != null) {
            String nm0 = r.firstName.length() > 8 ? r.firstName.substring(0, 8) + "…" : r.firstName;
            String lab = "food".equals(r.firstCat) ? nm0 + " 다 먹음" : nm0 + " 바꿈";
            b.addAction(new Notification.Action.Builder(ic, lab, actionIntent(c, ACT_DONE, 10, r.firstId)).build());
        }
        if (!r.shopIds.isEmpty()) b.addAction(new Notification.Action.Builder(ic, "장보기에 담기", actionIntent(c, ACT_SHOP, 11, null)).build());
        if (r.count > 0) b.addAction(new Notification.Action.Builder(ic, "3시간 뒤 다시", actionIntent(c, ACT_SNOOZE, 12, null)).build());
        nm.notify(7, b.build());
        if (!force) c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("sentDay", today).apply();
    }
}
