package com.wolfiy.twitchhud.render;

public sealed interface MessageToken {
    record Text(String text, boolean link, String url) implements MessageToken {
    }

    record Emote(String emoteId, String code, String imageUrl) implements MessageToken {
    }
}
