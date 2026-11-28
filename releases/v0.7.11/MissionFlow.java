package com.draborneagle.draborntranslate;

import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

final class MissionFlow {
    enum View { STORY, EXTRA, RECORDING }

    private static final Map<GtaMissionDetailsActivity, View> views = new WeakHashMap<>();

    private MissionFlow() {}

    private static boolean extraScene(String id) {
        return id != null && (id.equals("unplaced-dialogue") || id.startsWith("flow-extra-"));
    }

    static boolean hasStory(GtaMissionDialogue dialogue) {
        return dialogue != null && dialogue.getScenes().stream().anyMatch(scene ->
            scene.getId().startsWith("flow-") && !extraScene(scene.getId()));
    }

    static GtaMissionDialogue project(GtaMissionDialogue archive, View view) {
        if (!hasStory(archive) || view == View.RECORDING) return archive;

        boolean extra = view == View.EXTRA;
        List<GtaMissionScene> scenes = new ArrayList<>();
        for (GtaMissionScene scene : archive.getScenes()) {
            if (extraScene(scene.getId()) == extra) scenes.add(scene);
        }

        List<GtaMissionText> rows = new ArrayList<>();
        for (GtaMissionText line : archive.getReadableLines()) {
            if (extraScene(line.getSceneId()) == extra) rows.add(line);
        }
        return archive.copy(scenes, rows, true);
    }

    static GtaMissionDialogue select(GtaMissionDetailsActivity owner, LinearLayout container,
            GtaMissionDialogue archive, GtaMissionSearchLanguage language, GtaMissionDialogue recording) {
        boolean english = language == GtaMissionSearchLanguage.ENGLISH;
        boolean hasArchive = containsLines(archive);
        boolean hasRecording = containsLines(recording);
        View view = views.getOrDefault(owner, View.STORY);
        if (hasRecording && !hasArchive) view = View.RECORDING;
        if (view == View.RECORDING && !hasRecording) view = View.STORY;

        GtaMissionDialogue selected = view == View.RECORDING ? recording : project(archive, view);
        if (!containsLines(selected)) return selected;

        String caption;
        if (view == View.RECORDING) {
            caption = english ? "Recorded gameplay" : "Oynanış kaydı";
        } else if (view == View.EXTRA) {
            caption = english ? "Additional dialogue" : "Ek replikler";
        } else if (hasStory(archive)) {
            caption = english ? "Mission flow" : "Görev akışı";
        } else {
            caption = english ? "Archive order" : "Arşiv sırası";
        }
        TextView note = new TextView(owner);
        note.setText(caption);
        note.setTextColor(0xffb8bfd9);
        note.setTextSize(13);
        MissionV078.add(container, note, 8);

        if (view != View.STORY && hasArchive) {
            option(owner, container, View.STORY, english ? "Back to mission dialogue" : "Görev metinlerine dön");
        }
        if (view == View.STORY && hasStory(archive) && containsLines(project(archive, View.EXTRA))) {
            option(owner, container, View.EXTRA, english ? "Show additional dialogue" : "Ek replikleri göster");
        }
        if (view != View.RECORDING && hasRecording) {
            option(owner, container, View.RECORDING, english ? "Show my recording" : "Oynanış kaydımı göster");
        }
        return selected;
    }

    private static boolean containsLines(GtaMissionDialogue dialogue) {
        return dialogue != null && !dialogue.getReadableLines().isEmpty();
    }

    private static void option(GtaMissionDetailsActivity owner, LinearLayout container, View view, String label) {
        TextView button = MissionV078.chip(owner, label, false);
        button.setOnClickListener(ignored -> {
            views.put(owner, view);
            owner.v078RefreshDialogue();
        });
        MissionV078.add(container, button, 8);
    }
}
