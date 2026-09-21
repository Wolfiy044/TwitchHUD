package com.wolfiy.twitchhud.render;

import com.wolfiy.twitchhud.core.TwitchHudConfig;
import com.wolfiy.twitchhud.core.TwitchLiveState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class OverlayWidgetModel {
    public enum Kind {
        VIEWERS,
        TITLE,
        HYPE_TRAIN,
        FOLLOWERS,
        CREATOR_GOAL
    }

    public record Card(
            String key,
            Kind kind,
            int width,
            int height,
            String heading,
            String primary,
            String secondary,
            double progress
    ) {
    }

    private OverlayWidgetModel() {
    }

    public static List<Card> cards(
            TwitchHudConfig config,
            TwitchLiveState.Snapshot snapshot,
            boolean preview
    ) {
        List<Card> cards = new ArrayList<>();

        if (config.showViewerWidget()) {
            cards.add(card(
                    config,
                    "viewer",
                    Kind.VIEWERS,
                    140,
                    24,
                    "VIEWERS: "
                            + (snapshot.live()
                                    ? formatNumber(
                                            snapshot.viewerCount()
                                    )
                                    : "OFFLINE"),
                    "",
                    ""
            ));
        }

        if (config.showTitleWidget()) {
            String title = snapshot.title();
            cards.add(card(
                    config,
                    "title",
                    Kind.TITLE,
                    230,
                    36,
                    snapshot.live() ? "LIVE" : "TITLE",
                    title.isBlank()
                            ? "No stream title"
                            : title,
                    ""
            ));
        }

        if (config.showHypeTrainWidget()) {
            TwitchLiveState.HypeTrain hype =
                    snapshot.hypeTrain();
            cards.add(card(
                    config,
                    "hype",
                    Kind.HYPE_TRAIN,
                    220,
                    36,
                    hype.active()
                            ? "HYPE TRAIN · LEVEL "
                                    + hype.level()
                            : "HYPE TRAIN",
                    hype.active()
                            ? formatNumber(hype.progress())
                                    + " / "
                                    + formatNumber(hype.goal())
                            : "INACTIVE",
                    ""
            ));
        }

        if (config.showFollowerWidget()) {
            int followers = snapshot.followerCount();
            cards.add(card(
                    config,
                    "follower",
                    Kind.FOLLOWERS,
                    184,
                    24,
                    "FOLLOWERS: "
                            + (followers < 0
                                    ? "—"
                                    : formatNumber(followers)),
                    "",
                    ""
            ));
        }

        if (config.showCreatorGoals()) {
            if (snapshot.goals().isEmpty()) {
                cards.add(card(
                        config,
                        "goal:*",
                        Kind.CREATOR_GOAL,
                        236,
                        36,
                        "CREATOR GOALS",
                        "No active goals",
                        ""
                ));
            } else {
                for (TwitchLiveState.CreatorGoal goal
                        : snapshot.goals()) {
                    String goalKey = goal.id().isBlank()
                            ? goal.type()
                            : goal.id();
                    String heading =
                            goal.description().isBlank()
                                    ? friendlyGoalType(
                                            goal.type()
                                    )
                                    : goal.description();
                    cards.add(card(
                            config,
                            "goal:" + goalKey,
                            Kind.CREATOR_GOAL,
                            236,
                            36,
                            heading,
                            formatNumber(goal.current())
                                    + " / "
                                    + formatNumber(goal.target()),
                            ""
                    ));
                }
            }
        }

        return cards;
    }

    private static Card card(
            TwitchHudConfig config,
            String key,
            Kind kind,
            int defaultWidth,
            int defaultHeight,
            String heading,
            String primary,
            String secondary
    ) {
        TwitchHudConfig.WidgetSize size =
                config.widgetSize(
                        key,
                        defaultWidth,
                        defaultHeight
                );
        return new Card(
                key,
                kind,
                (int) Math.round(size.width()),
                (int) Math.round(size.height()),
                heading,
                primary,
                secondary,
                -1
        );
    }

    private static String friendlyGoalType(String type) {
        if (type == null || type.isBlank()) {
            return "Goal";
        }
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "follow", "follower" -> "Follower goal";
            case "subscription" -> "Subscription goal";
            case "subscription_count" ->
                    "Subscriber goal";
            case "new_subscription" ->
                    "New subscriber goal";
            case "new_subscription_count" ->
                    "New subscriber goal";
            case "new_bit" -> "Bits goal";
            case "new_cheerer" -> "Cheerer goal";
            default -> type.replace('_', ' ');
        };
    }

    private static String formatNumber(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }
}
