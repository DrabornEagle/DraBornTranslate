package com.draborneagle.draborntranslate;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

final class CheckMissionFlow {
    public static void main(String[] args) {
        File reference = new File(args[0]);
        File personal = new File(args[1]);
        int expectedMissions = Integer.parseInt(args[2]);
        int expectedAdditions = Integer.parseInt(args[3]);
        GtaMissionCatalog catalog = GtaMissionPack.INSTANCE.validate(reference);
        GtaMissionCatalog archiveCatalog = GtaMissionPack.INSTANCE.validate(personal);
        check("2026.11.28.11".equals(catalog.getVersion()), "reference version");
        check(catalog.getVersion().equals(archiveCatalog.getVersion()), "dialogue version");
        List<GtaMission> cards = catalog.search("", GtaMissionSearchLanguage.TURKISH);
        check(cards.size() == 197, "search results");
        check(!catalog.search("Watson Bay Buluşması", GtaMissionSearchLanguage.TURKISH).isEmpty(),
            "Turkish mission name");
        check(!catalog.search("The Missing Link", GtaMissionSearchLanguage.ENGLISH).isEmpty(),
            "English mission name");

        int archives = 0;
        int updated = 0;
        int lines = 0;
        for (GtaMission mission : cards) {
            GtaMissionDialogue all = GtaMissionPack.INSTANCE.dialogue(personal, mission.getId());
            if (all == null) continue;
            archives++;
            lines += all.getReadableLines().size();
            if (!MissionFlow.hasStory(all)) {
                check(MissionFlow.project(all, MissionFlow.View.STORY) == all, "legacy fallback");
                continue;
            }
            updated++;
            GtaMissionDialogue story = MissionFlow.project(all, MissionFlow.View.STORY);
            GtaMissionDialogue extra = MissionFlow.project(all, MissionFlow.View.EXTRA);
            check(!story.getReadableLines().isEmpty(), mission.getId() + " empty timeline");
            check(story.inReadingOrder().equals(story.inSceneOrder()), mission.getId() + " reading modes disagree");
            check(story.getReadableLines().size() + extra.getReadableLines().size() == all.getReadableLines().size(),
                mission.getId() + " dialogue missing from both views");
        }
        check(archives == 164 && updated == expectedMissions && lines == 71659 + expectedAdditions, "archive counts");

        GtaMissionDialogue missingLink = story(personal, "ban-carte");
        ordered(missingLink, "cinematic-ban-carte-int", "flow-sloppy-mollys", "flow-photoshoot",
            "cinematic-ban-carte-mcs2-gang-contact-start-fight", "flow-warehouse-exit", "flow-to-raul",
            "flow-questioning", "flow-monty-answers", "flow-watson-bay-finish");
        GtaMissionDialogue bricked = story(personal, "bricked-up");
        ordered(bricked, "cinematic-clb-tower-int", "flow-boobie-assignment", "flow-venture-drive",
            "flow-venture-lobby", "cinematic-clb-tower-mcs1-deal", "flow-venture-raid",
            "cinematic-clb-tower-mcs2-elevator", "flow-apartment-escape", "flow-window-exit",
            "flow-jack-of-hearts-drive", "cinematic-clb-tower-ext", "bricked-up-after-the-raid");
        line(bricked, "flow-boobie-assignment", 0xa055d6879ad44736L, "Boobie Ike");
        line(bricked, "cinematic-clb-tower-mcs2-elevator", 0xc2ee0fa8291bd685L, "Lucia Caminos");

        GtaMissionDialogue credit = story(personal, "rob-bank");
        ordered(credit, "cinematic-rob-bank-int", "flow-bank-gear", "washington-beach-fleeca-yolculugu",
            "flow-washington-beach-bank", "flow-second-fleeca", "flow-thompson-fleeca", "flow-return-home",
            "cinematic-rob-bank-ext", "flow-following-morning");
        line(credit, "washington-beach-fleeca-yolculugu", 0x153f3af39f9faa4eL, "Jason Duval");
        GtaMissionScene firstBank = credit.getScenes().stream()
            .filter(scene -> scene.getId().equals("washington-beach-fleeca-yolculugu"))
            .findFirst().orElseThrow(AssertionError::new);
        check(firstBank.getGallery().size() == 3, "first bank gallery");

        GtaMissionDialogue fishing = story(personal, "gone-fishing");
        ordered(fishing, "cinematic-int-chase-int", "flow-follow-plane", "cinematic-int-chase-mcs7-disembark",
            "flow-donnie-rescue", "flow-escape-in-plane", "cinematic-int-chase-mcs8-midflight-banter",
            "cinematic-int-chase-mcs1-plane-land", "cinematic-int-chase-mcs2-friend-drop-off");
        check("Cal Hampton".equals(MissionV078.visibleSpeaker("Cal Hampton", GtaMissionSearchLanguage.ENGLISH)),
            "character name without a role suffix");

        System.out.printf("%d cards, %d missions with dialogue, %d timelines, %d original and %d restored subtitles%n",
            cards.size(), archives, updated, lines - expectedAdditions, expectedAdditions);
    }

    private static GtaMissionDialogue story(File pack, String missionId) {
        return MissionFlow.project(GtaMissionPack.INSTANCE.dialogue(pack, missionId), MissionFlow.View.STORY);
    }

    private static void ordered(GtaMissionDialogue dialogue, String... sceneIds) {
        List<String> viewed = dialogue.inSceneOrder().stream().map(GtaMissionText::getSceneId).distinct()
            .collect(Collectors.toList());
        List<String> relevant = new ArrayList<>(viewed);
        relevant.retainAll(Arrays.asList(sceneIds));
        check(relevant.equals(Arrays.asList(sceneIds)), "scene order " + relevant);
    }

    private static void line(GtaMissionDialogue dialogue, String scene, long key, String speaker) {
        check(dialogue.getReadableLines().stream().anyMatch(line ->
            scene.equals(line.getSceneId()) && Objects.equals(key, line.getSubtitleKey()) &&
            speaker.equals(line.getSpeaker())), "missing confirmed speaker or subtitle key in " + scene);
    }

    private static void check(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
}
