package com.draborneagle.draborntranslate;

import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.ViewParent;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class MissionFlow {
    private MissionFlow() {}

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

    // The release helper calls this from the mission detail's conversation pane.
    static GtaMissionDialogue select(GtaMissionDetailsActivity owner, LinearLayout container,
            GtaMissionDialogue archive, GtaMissionSearchLanguage language, GtaMissionDialogue recording) {
        GtaMissionDialogue story = story(archive);
        if (story != null) return story;
        return recording != null && !recording.getReadableLines().isEmpty() ? recording : null;
    }

    static void addBackToTop(LinearLayout body) {
        ViewParent parent = body.getParent();
        while (parent != null && !(parent instanceof ScrollView)) parent = parent.getParent();
        if (!(parent instanceof ScrollView)) return;
        ScrollView scroll = (ScrollView) parent;
        float density = body.getResources().getDisplayMetrics().density;
        int size = Math.round(48 * density);
        int margin = Math.round(12 * density);

        GradientDrawable shape = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[] {0xffacebe7, 0xffbcd0ff});
        shape.setCornerRadius(Math.round(15 * density));
        ImageButton arrow = new ImageButton(body.getContext());
        arrow.setImageResource(android.R.drawable.arrow_up_float);
        arrow.setImageTintList(ColorStateList.valueOf(0xff171929));
        arrow.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33171929), shape, null));
        arrow.setContentDescription("Sayfanın başına dön");
        arrow.setTooltipText("Sayfanın başına dön");
        arrow.setOnClickListener(view -> scroll.smoothScrollTo(0, 0));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        params.gravity = Gravity.END;
        params.topMargin = margin;
        params.bottomMargin = margin;
        body.addView(arrow, params);
    }
}
