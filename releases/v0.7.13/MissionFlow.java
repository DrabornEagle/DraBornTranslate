package com.draborneagle.draborntranslate;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.ViewParent;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class MissionFlow {
    private static final String ARCHIVE = "gta-6-personal-conversations-2026.11.28.13.zip";
    private static final String BACK_TO_TOP = "mission-back-to-top";
    private static volatile Boolean bundledArchive;

    private MissionFlow() {}

    static boolean hasBundledArchive(Context context) {
        Boolean cached = bundledArchive;
        if (cached != null) return cached;
        try {
            String[] assets = context.getAssets().list("");
            if (assets != null) {
                for (String name : assets) {
                    if (ARCHIVE.equals(name)) {
                        bundledArchive = true;
                        return true;
                    }
                }
            }
        } catch (IOException ignored) {
            return false;
        }
        bundledArchive = false;
        return false;
    }

    static GtaMissionDialogue story(GtaMissionDialogue archive) {
        if (archive == null) return null;

        List<GtaMissionScene> scenes = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (GtaMissionScene scene : archive.getScenes()) {
            if (scene.getId().startsWith("timeline-")) {
                scenes.add(scene);
                ids.add(scene.getId());
            }
        }

        if (scenes.isEmpty()) return null;
        List<GtaMissionText> rows = new ArrayList<>();
        for (GtaMissionText line : archive.getReadableLines()) {
            if (ids.contains(line.getSceneId())) rows.add(line);
        }
        return rows.isEmpty() ? null : archive.copy(scenes, rows, true);
    }

    static GtaMissionDialogue select(GtaMissionDetailsActivity owner, LinearLayout container,
            GtaMissionDialogue archive, GtaMissionSearchLanguage language, GtaMissionDialogue recording) {
        GtaMissionDialogue verified = story(archive);
        if (verified != null) return verified;
        return recording != null && !recording.getReadableLines().isEmpty() ? recording : null;
    }

    static String emptyMessage(GtaMission mission) {
        if (mission != null && "get-the-chop".equals(mission.getId())) {
            return "Barnacle Beauties’te saç veya sakal değiştirerek tamamlanır. Bu adıma ait doğrulanmış replik yok.";
        }
        return "Bu göreve ait doğrulanmış konuşma bulunamadı.";
    }

    static void addBackToTop(LinearLayout body) {
        ViewParent parent = body.getParent();
        while (parent != null && !(parent instanceof ScrollView)) parent = parent.getParent();
        if (!(parent instanceof ScrollView)) return;
        ScrollView scroll = (ScrollView) parent;
        if (!(scroll.getParent() instanceof LinearLayout)) return;

        LinearLayout page = (LinearLayout) scroll.getParent();
        if (!(page.getChildAt(0) instanceof LinearLayout)) return;
        LinearLayout header = (LinearLayout) page.getChildAt(0);
        if (!(header.getChildAt(1) instanceof TextView) || header.findViewWithTag(BACK_TO_TOP) != null) return;

        float density = body.getResources().getDisplayMetrics().density;
        int size = Math.round(48 * density);
        GradientDrawable shape = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[] {0xffacebe7, 0xffbcd0ff});
        shape.setCornerRadius(Math.round(15 * density));

        ImageButton arrow = new ImageButton(body.getContext());
        arrow.setTag(BACK_TO_TOP);
        arrow.setImageResource(android.R.drawable.arrow_up_float);
        arrow.setImageTintList(ColorStateList.valueOf(0xff171929));
        arrow.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33171929), shape, null));
        arrow.setContentDescription("Sayfanın başına dön");
        arrow.setTooltipText("Sayfanın başına dön");
        arrow.setOnClickListener(view -> scroll.smoothScrollTo(0, 0));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        params.setMarginStart(Math.round(10 * density));
        header.addView(arrow, params);
    }
}
