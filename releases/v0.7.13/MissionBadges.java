package com.draborneagle.draborntranslate;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class MissionBadges {
    // The in-game Story Index uses a pink pin with a black letter for story missions.
    private static final int PINK = 0xffed85ad;
    private static final int BLACK = 0xff191519;
    private static final int ACTIVITY = 0xffb69bf3;

    private static volatile JSONObject missionIcons;
    private static final Map<String, Bitmap> glyphs = new ConcurrentHashMap<>();

    private MissionBadges() {}

    static int foreground(GtaMissionType type) {
        return type == GtaMissionType.STORY ? BLACK : type == GtaMissionType.SIDE ? PINK : ACTIVITY;
    }

    static int background(GtaMissionType type) {
        return type == GtaMissionType.STORY ? PINK : BLACK;
    }

    static String initials(String contact) {
        if (contact == null) return "";
        String name = contact.split("/", 2)[0].trim();
        if (name.isEmpty() || !Character.isLetter(name.charAt(0))) return "";
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.startsWith("kenton")) return "KR";
        if (lower.startsWith("dr. merle") || lower.equals("merle")) return "DM";
        return name.substring(0, 1).toUpperCase(Locale.ROOT);
    }

    static void attach(FrameLayout portrait, GtaMission mission, boolean english) {
        if (!(portrait.getParent() instanceof ViewGroup)) return;
        Context context = portrait.getContext();
        String contact = mission.getGiver();
        if ((contact == null || contact.trim().isEmpty()) && !mission.getCharacters().isEmpty()) {
            contact = mission.getCharacters().get(0);
        }
        Bitmap icon = loadGlyph(context, mission.getId());
        String letter = initials(contact);
        if (icon == null && letter.isEmpty()) return;
        GtaMissionType type = mission.getType();
        float density = context.getResources().getDisplayMetrics().density;
        int size = Math.round(22 * density);

        ViewGroup parent = (ViewGroup) portrait.getParent();
        int index = parent.indexOfChild(portrait);
        ViewGroup.LayoutParams original = portrait.getLayoutParams();
        FrameLayout frame = new FrameLayout(context);
        parent.removeViewAt(index);
        frame.addView(portrait, new FrameLayout.LayoutParams(-1, -1));
        parent.addView(frame, index, original);

        View badge;
        if (icon == null) {
            TextView label = new TextView(context);
            label.setGravity(Gravity.CENTER);
            label.setText(letter);
            label.setTextSize(11);
            label.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            label.setTextColor(foreground(type));
            label.setIncludeFontPadding(false);
            badge = label;
        } else {
            ImageView marker = new ImageView(context);
            marker.setImageBitmap(icon);
            marker.setColorFilter(foreground(type));
            marker.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int padding = Math.round(1 * density);
            marker.setPadding(padding, padding, padding, padding);
            badge = marker;
        }
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(background(type));
        shape.setCornerRadius(Math.round(6 * density));
        if (type != GtaMissionType.STORY) shape.setStroke(Math.round(density), foreground(type));
        badge.setBackground(shape);
        badge.setContentDescription(english ? "Mission marker" : "Görev haritası simgesi");
        frame.addView(badge, new FrameLayout.LayoutParams(size, size, Gravity.BOTTOM | Gravity.END));
    }

    private static Bitmap loadGlyph(Context context, String id) {
        try {
            JSONObject table = missionIcons;
            if (table == null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        context.getAssets().open("mission-icons.json"), StandardCharsets.UTF_8))) {
                    table = new JSONObject(reader.readLine());
                    missionIcons = table;
                }
            }
            String file = table.optString(id);
            if (!file.matches("blip_mission_[a-z0-9_]+\\.webp")) return null;
            Bitmap found = glyphs.get(file);
            if (found != null) return found;
            try (InputStream stream = context.getAssets().open("gta-6-mission-icons/" + file)) {
                Bitmap decoded = BitmapFactory.decodeStream(stream);
                if (decoded != null) glyphs.put(file, decoded);
                return decoded;
            }
        } catch (IOException | org.json.JSONException invalid) {
            return null;
        }
    }
}
