package com.wolfiy.twitchhud.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TwitchLiveState {
    public record HypeTrain(
            boolean active,
            int level,
            long progress,
            long goal,
            long expiresAt
    ) {
    }

    public record CreatorGoal(
            String id,
            String type,
            String description,
            long current,
            long target
    ) {
    }

    public record Snapshot(
            boolean live,
            int viewerCount,
            String title,
            HypeTrain hypeTrain,
            int followerCount,
            List<CreatorGoal> goals
    ) {
    }

    private volatile boolean live;
    private volatile int viewerCount;
    private volatile String title = "";
    private volatile HypeTrain hypeTrain =
            new HypeTrain(false, 0, 0, 0, 0);
    private volatile int followerCount = -1;
    private final Map<String, CreatorGoal> goals =
            new LinkedHashMap<>();

    public Snapshot snapshot() {
        synchronized (goals) {
            return new Snapshot(
                    live,
                    viewerCount,
                    title,
                    hypeTrain,
                    followerCount,
                    List.copyOf(goals.values())
            );
        }
    }

    public void stream(boolean live, int viewers) {
        this.live = live;
        viewerCount = Math.max(0, viewers);
    }

    public void title(String title) {
        this.title = title == null ? "" : title;
    }

    public void hypeTrain(HypeTrain hypeTrain) {
        this.hypeTrain = hypeTrain == null
                ? new HypeTrain(false, 0, 0, 0, 0)
                : hypeTrain;
    }

    public void followerCount(int followerCount) {
        this.followerCount = Math.max(0, followerCount);
    }

    public void replaceGoals(List<CreatorGoal> nextGoals) {
        synchronized (goals) {
            goals.clear();
            for (CreatorGoal goal : nextGoals) {
                if (goal != null && !goal.id().isBlank()) {
                    goals.put(goal.id(), goal);
                }
            }
        }
    }

    public void upsertGoal(CreatorGoal goal) {
        if (goal == null || goal.id().isBlank()) {
            return;
        }
        synchronized (goals) {
            goals.put(goal.id(), goal);
        }
    }

    public void removeGoal(String id) {
        synchronized (goals) {
            goals.remove(id);
        }
    }

    public void clearChannelData() {
        live = false;
        viewerCount = 0;
        title = "";
        hypeTrain = new HypeTrain(false, 0, 0, 0, 0);
        followerCount = -1;
        synchronized (goals) {
            goals.clear();
        }
    }
}
