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
        int expectedShown = Integer.parseInt(args[4]);
        int expectedArchives = Integer.parseInt(args[5]);
        GtaMissionCatalog catalog = GtaMissionPack.INSTANCE.validate(reference);
        GtaMissionCatalog archive = GtaMissionPack.INSTANCE.validate(personal);
        check("2026.11.28.13".equals(catalog.getVersion()) &&
            catalog.getVersion().equals(archive.getVersion()), "pack versions");
        List<GtaMission> cards = catalog.search("", GtaMissionSearchLanguage.TURKISH);
        check(cards.size() == 197, "search results");
        check(!catalog.search("Watson Bay Buluşması", GtaMissionSearchLanguage.TURKISH).isEmpty() &&
            !catalog.search("The Missing Link", GtaMissionSearchLanguage.ENGLISH).isEmpty(), "mission search names");

        int archives = 0, timelines = 0, total = 0, shown = 0;
        for (GtaMission card : cards) {
            GtaMissionDialogue original = GtaMissionPack.INSTANCE.dialogue(personal, card.getId());
            if (original == null) continue;
            archives++;
            total += original.getReadableLines().size();
            GtaMissionDialogue story = MissionFlow.story(original);
            if (story == null) continue;
            timelines++;
            shown += story.getReadableLines().size();
            check(story.inReadingOrder().equals(story.inSceneOrder()), "reading order for " + card.getId());
            check(story.getScenes().stream().allMatch(scene -> scene.getId().startsWith("timeline-")),
                "archived sections rendered");
            check(story.getReadableLines().stream().allMatch(line ->
                    line.getSceneId() != null && line.getSceneId().startsWith("timeline-")),
                "an unplaced quote reached the mission view");
        }
        check(archives == expectedArchives && timelines == expectedMissions && total == 72770 + expectedAdditions &&
            shown == expectedShown, String.format("mission archive counts (%d, %d, %d, %d)", archives, timelines, total, shown));

        GtaMissionDialogue missingLink = read(personal, "ban-carte");
        ordered(missingLink, "cinematic-ban-carte-int", "flow-sloppy-mollys", "flow-photoshoot",
            "cinematic-ban-carte-mcs2-gang-contact-start-fight", "flow-warehouse-exit", "flow-to-raul",
            "flow-questioning", "flow-monty-answers", "flow-watson-bay-finish");
        verifyStage(missingLink, "flow-to-raul", GtaSceneCategory.GAMEPLAY, "Araç içi konuşma", 2);
        verifyStage(missingLink, "flow-warehouse-exit", GtaSceneCategory.GAMEPLAY, "Kaçış", 2);
        GtaMissionDialogue bricked = read(personal, "bricked-up");
        ordered(bricked, "cinematic-clb-tower-int", "flow-boobie-assignment", "flow-venture-drive",
            "flow-venture-lobby", "cinematic-clb-tower-mcs1-deal", "flow-venture-raid",
            "cinematic-clb-tower-mcs2-elevator", "flow-apartment-escape", "flow-window-exit",
            "flow-jack-of-hearts-drive", "cinematic-clb-tower-ext", "bricked-up-after-the-raid");
        verifyStage(bricked, "flow-boobie-assignment", GtaSceneCategory.CINEMATIC, "Sinematik ara sahne", 2);
        verifyStage(bricked, "flow-venture-drive", GtaSceneCategory.GAMEPLAY, "Araç içi konuşma", 2);
        line(bricked, "flow-boobie-assignment", 0xa055d6879ad44736L, "Boobie Ike");
        line(bricked, "cinematic-clb-tower-mcs2-elevator", 0xc2ee0fa8291bd685L, "Lucia Caminos");

        GtaMissionDialogue credit = read(personal, "rob-bank");
        ordered(credit, "cinematic-rob-bank-int", "flow-bank-gear", "washington-beach-fleeca-yolculugu",
            "flow-washington-beach-bank", "flow-second-fleeca", "flow-thompson-fleeca", "flow-return-home",
            "cinematic-rob-bank-ext", "flow-following-morning");
        verifyStage(credit, "washington-beach-fleeca-yolculugu", GtaSceneCategory.GAMEPLAY, "Araç içi konuşma ve telsiz", 3);
        line(credit, "washington-beach-fleeca-yolculugu", 0x153f3af39f9faa4eL, "Jason Duval");

        GtaMissionDialogue fishing = read(personal, "gone-fishing");
        ordered(fishing, "cinematic-int-chase-int", "flow-follow-plane", "cinematic-int-chase-mcs7-disembark",
            "flow-donnie-rescue", "flow-escape-in-plane", "cinematic-int-chase-mcs8-midflight-banter",
            "cinematic-int-chase-mcs1-plane-land");
        check("Cal Hampton".equals(MissionV078.visibleSpeaker("Cal Hampton", GtaMissionSearchLanguage.ENGLISH)),
            "character name");
        GtaMissionDialogue building = read(personal, "building-a-life");
        ordered(building, "evde-sehpa", "sehpa-vidalari", "sehpa-yeniden", "sehpa-cam", "sehpa-kapanis");
        verifyStage(building, "evde-sehpa", GtaSceneCategory.GAMEPLAY, "Evin salonunda", 2);
        ordered(read(personal, "steal-the-scooter"), "shirley-istek", "peggy-scooter", "shirley-teslim");
        ordered(read(personal, "find-the-lost-drugs"), "kulube-yakininda", "paketi-inceleme");
        GtaMissionDialogue donnie = read(personal, "cti-pil-rd-bc-parent");
        ordered(donnie, "cal-arama", "hamlet-balikci", "hamlet-kulube", "donnie-geri-donus");
        check(donnie.inReadingOrder().equals(read(personal, "clean-up-after-donnie").inReadingOrder()) &&
            catalog.mission("cti-pil-rd-bc-parent").getMapLabels().contains("Find Donnie’s Package"),
            "Donnie route alias");
        check(read(personal, "get-the-chop") == null &&
            MissionFlow.emptyMessage(catalog.mission("get-the-chop")).contains("Barnacle Beauties"),
            "salon objective must not render unrelated conversations");
        GtaMissionDialogue zorrusso = read(personal, "clonin-keys-makin-gs");
        ordered(zorrusso, "randy-kopyalayici", "hathaway-garaj", "zorrusso-cikis");
        line(zorrusso, "randy-kopyalayici", 0xa7cddd877513c156L, "Randy");
        check("B".equals(MissionBadges.initials("Brian Heder")) &&
            "KR".equals(MissionBadges.initials("Kenton Rees")), "contact initials");
        check(MissionBadges.background(GtaMissionType.STORY) == 0xffed85ad &&
            MissionBadges.background(GtaMissionType.SIDE) == MissionBadges.foreground(GtaMissionType.STORY) &&
            MissionBadges.foreground(GtaMissionType.SIDE) == MissionBadges.background(GtaMissionType.STORY),
            "story and optional marker palette");
        System.out.printf("%d cards, %d chronological missions, %d placed lines, %d archived lines total%n",
                          cards.size(), timelines, shown, total);
    }

    private static GtaMissionDialogue read(File pack, String mission) {
        return MissionFlow.story(GtaMissionPack.INSTANCE.dialogue(pack, mission));
    }

    private static void ordered(GtaMissionDialogue dialogue, String... sceneIds) {
        List<String> viewed = dialogue.inSceneOrder().stream().map(GtaMissionText::getSceneId)
            .map(id -> id.substring("timeline-".length())).distinct().collect(Collectors.toList());
        List<String> relevant = new ArrayList<>(viewed);
        relevant.retainAll(Arrays.asList(sceneIds));
        check(relevant.equals(Arrays.asList(sceneIds)), "scene order " + relevant);
    }

    private static void verifyStage(GtaMissionDialogue archive, String id, GtaSceneCategory category,
            String environment, int imageCount) {
        GtaMissionScene scene = archive.getScenes().stream().filter(item -> item.getId().equals("timeline-" + id))
            .findFirst().orElseThrow(AssertionError::new);
        check(scene.getCategory() == category && environment.equals(scene.getEnvironment()) &&
            scene.getGallery().size() >= imageCount, "scene type or frames in " + id);
    }

    private static void line(GtaMissionDialogue archive, String scene, long key, String speaker) {
        check(archive.getReadableLines().stream().anyMatch(line ->
            ("timeline-" + scene).equals(line.getSceneId()) && Objects.equals(key, line.getSubtitleKey()) &&
            speaker.equals(line.getSpeaker())), "speaker or line placement for " + scene);
    }

    private static void check(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
}
