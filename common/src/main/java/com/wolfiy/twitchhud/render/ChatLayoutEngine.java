package com.wolfiy.twitchhud.render;

import com.wolfiy.twitchhud.core.ChatMessage;
import com.wolfiy.twitchhud.core.LinkUtils;
import com.wolfiy.twitchhud.core.Platform;
import com.wolfiy.twitchhud.core.ThirdPartyEmoteService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class ChatLayoutEngine {
    private static final float MIN_DYNAMIC_SCALE = 0.4f;
    private static final int LAYOUT_CACHE_LIMIT = 512;
    private static final int TOKEN_CACHE_LIMIT = 512;
    private static final Map<LayoutCacheKey, ScaledLayout>
            LAYOUT_CACHE =
            new LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<
                                LayoutCacheKey,
                                ScaledLayout
                        > eldest
                ) {
                    return size() > LAYOUT_CACHE_LIMIT;
                }
            };

    private static final Map<TokenCacheKey, List<MessageToken>>
            TOKEN_CACHE =
            new LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<
                                TokenCacheKey,
                                List<MessageToken>
                        > eldest
                ) {
                    return size() > TOKEN_CACHE_LIMIT;
                }
            };

    private ChatLayoutEngine() {
    }

    public record PlacedToken(int x, int width, MessageToken token) {
    }

    public record LaidOutLine(List<PlacedToken> tokens, int width) {
    }

    public record ScaledLayout(
            List<LaidOutLine> lines,
            float scale,
            int lineHeight,
            int blockHeight
    ) {
    }

    private record TokenCacheKey(
            String id,
            String text,
            boolean includeEmotes,
            List<ChatMessage.EmoteRef> emotes,
            int thirdPartyVersion
    ) {
    }

    private record LayoutCacheKey(
            List<MessageToken> tokens,
            int maxPhysicalWidth,
            int maxPhysicalHeight,
            int reservedLogicalWidth,
            int emoteSize,
            int baseLineHeight,
            int requestedScaleBits,
            String fontId,
            String platformType
    ) {
    }

    public static List<MessageToken> tokenize(
            ChatMessage message,
            boolean includeEmotes,
            ThirdPartyEmoteService thirdPartyEmotes
    ) {
        int thirdPartyVersion = includeEmotes && thirdPartyEmotes != null
                ? thirdPartyEmotes.metadataVersion()
                : -1;
        TokenCacheKey lookupKey = new TokenCacheKey(
                message.id(),
                message.text(),
                includeEmotes,
                message.emotes(),
                thirdPartyVersion
        );
        synchronized (TOKEN_CACHE) {
            List<MessageToken> cached =
                    TOKEN_CACHE.get(lookupKey);
            if (cached != null) {
                return cached;
            }
        }

        String text = message.text();
        List<MessageToken> tokens = new ArrayList<>();
        List<LinkUtils.LinkSpan> links = LinkUtils.findLinks(text);

        int cursor = 0;
        int nextEmoteIndex = 0;
        List<ChatMessage.EmoteRef> emotes = includeEmotes
                ? new ArrayList<>(message.emotes())
                : new ArrayList<>();
        emotes.sort((a, b) -> Integer.compare(a.start(), b.start()));

        while (cursor < text.length()) {
            ChatMessage.EmoteRef emote = nextEmoteIndex < emotes.size()
                    ? emotes.get(nextEmoteIndex)
                    : null;
            if (emote != null && emote.start() == cursor) {
                String code = text.substring(
                        emote.start(),
                        Math.min(emote.end() + 1, text.length())
                );
                tokens.add(new MessageToken.Emote(emote.emoteId(), code, null));
                cursor = emote.end() + 1;
                nextEmoteIndex++;
                continue;
            }

            int nextBoundary = text.length();
            if (emote != null) {
                nextBoundary = Math.min(nextBoundary, emote.start());
            }

            LinkUtils.LinkSpan activeLink = null;
            for (LinkUtils.LinkSpan link : links) {
                if (link.start() >= cursor && link.start() < nextBoundary) {
                    activeLink = link;
                    break;
                }
            }

            if (activeLink != null && activeLink.start() == cursor) {
                String linkText = text.substring(
                        activeLink.start(),
                        activeLink.end()
                );
                tokens.add(new MessageToken.Text(
                        linkText,
                        true,
                        activeLink.url()
                ));
                cursor = activeLink.end();
                continue;
            }

            int end = activeLink != null
                    ? activeLink.start()
                    : nextBoundary;
            if (end <= cursor) {
                end = nextBoundary;
            }
            appendTextWithThirdPartyEmotes(
                    tokens,
                    text.substring(cursor, end),
                    includeEmotes ? thirdPartyEmotes : null
            );
            cursor = end;
        }
        List<MessageToken> immutable =
                List.copyOf(tokens);
        TokenCacheKey storedKey = new TokenCacheKey(
                message.id(),
                message.text(),
                includeEmotes,
                List.copyOf(message.emotes()),
                thirdPartyVersion
        );
        synchronized (TOKEN_CACHE) {
            TOKEN_CACHE.put(storedKey, immutable);
        }
        return immutable;
    }

    private static void appendTextWithThirdPartyEmotes(
            List<MessageToken> tokens,
            String chunk,
            ThirdPartyEmoteService thirdPartyEmotes
    ) {
        if (thirdPartyEmotes == null || chunk.isEmpty()) {
            if (!chunk.isEmpty()) {
                tokens.add(new MessageToken.Text(chunk, false, null));
            }
            return;
        }

        StringBuilder plain = new StringBuilder();
        int start = 0;
        int length = chunk.length();
        while (start < length) {
            int spaceIndex = chunk.indexOf(' ', start);
            int wordEnd = spaceIndex < 0 ? length : spaceIndex;
            String word = chunk.substring(start, wordEnd);
            Optional<ThirdPartyEmoteService.EmoteInfo> match =
                    word.isEmpty() ? Optional.empty() : thirdPartyEmotes.find(word);

            if (match.isPresent()) {
                if (!plain.isEmpty()) {
                    tokens.add(new MessageToken.Text(plain.toString(), false, null));
                    plain.setLength(0);
                }
                ThirdPartyEmoteService.EmoteInfo info = match.get();
                tokens.add(new MessageToken.Emote(
                        info.provider().toLowerCase(Locale.ROOT) + ":" + info.id(),
                        word,
                        info.url()
                ));
            } else {
                plain.append(word);
            }

            if (spaceIndex < 0) {
                start = wordEnd;
            } else {
                plain.append(' ');
                start = spaceIndex + 1;
            }
        }

        if (!plain.isEmpty()) {
            tokens.add(new MessageToken.Text(plain.toString(), false, null));
        }
    }

    public static List<LaidOutLine> wrap(
            List<MessageToken> tokens,
            int maxWidth,
            int emoteSize,
            Platform platform,
            String fontId
    ) {
        int safeWidth = Math.max(1, maxWidth);
        return wrapWithWidths(
                tokens,
                safeWidth,
                safeWidth,
                emoteSize,
                platform,
                fontId
        );
    }

    private static List<LaidOutLine> wrapWithWidths(
            List<MessageToken> tokens,
            int firstLineWidth,
            int followingLineWidth,
            int emoteSize,
            Platform platform,
            String fontId
    ) {
        int safeFirstWidth = Math.max(1, firstLineWidth);
        int safeFollowingWidth =
                Math.max(1, followingLineWidth);
        List<LaidOutLine> lines = new ArrayList<>();
        List<PlacedToken> current = new ArrayList<>();
        int x = 0;

        for (MessageToken token : tokens) {
            for (String piece : splitIntoWords(token)) {
                int lineLimit = lines.isEmpty()
                        ? safeFirstWidth
                        : safeFollowingWidth;
                int fullWidth = pieceWidth(
                        token,
                        piece,
                        emoteSize,
                        platform,
                        fontId
                );

                if (x > 0
                        && x + fullWidth > lineLimit) {
                    lines.add(new LaidOutLine(current, x));
                    current = new ArrayList<>();
                    x = 0;
                    lineLimit = safeFollowingWidth;
                }

                List<String> fitted = splitToFit(
                        token,
                        piece,
                        lineLimit,
                        emoteSize,
                        platform,
                        fontId
                );

                for (String part : fitted) {
                    lineLimit = lines.isEmpty()
                            ? safeFirstWidth
                            : safeFollowingWidth;
                    int width = pieceWidth(
                            token,
                            part,
                            emoteSize,
                            platform,
                            fontId
                    );
                    if (x > 0
                            && x + width > lineLimit) {
                        lines.add(
                                new LaidOutLine(current, x)
                        );
                        current = new ArrayList<>();
                        x = 0;
                    }

                    current.add(new PlacedToken(
                            x,
                            width,
                            tokenFor(token, part)
                    ));
                    x += width;
                }
            }
        }

        if (!current.isEmpty() || lines.isEmpty()) {
            lines.add(new LaidOutLine(current, x));
        }
        return lines;
    }

    public static ScaledLayout wrapScaled(
            List<MessageToken> tokens,
            int maxPhysicalWidth,
            int maxPhysicalHeight,
            int reservedLogicalWidth,
            int emoteSize,
            int baseLineHeight,
            float requestedScale,
            Platform platform,
            String fontId
    ) {
        LayoutCacheKey lookupKey = layoutCacheKey(
                tokens,
                maxPhysicalWidth,
                maxPhysicalHeight,
                reservedLogicalWidth,
                emoteSize,
                baseLineHeight,
                requestedScale,
                platform,
                fontId
        );
        synchronized (LAYOUT_CACHE) {
            ScaledLayout cached =
                    LAYOUT_CACHE.get(lookupKey);
            if (cached != null) {
                return cached;
            }
        }

        float scale = Math.max(
                MIN_DYNAMIC_SCALE,
                requestedScale
        );
        List<LaidOutLine> lines = wrapAtScale(
                tokens,
                maxPhysicalWidth,
                reservedLogicalWidth,
                emoteSize,
                scale,
                platform,
                fontId
        );

        if (maxPhysicalHeight > 0) {
            float fit = fitScale(
                    lines.size(),
                    baseLineHeight,
                    maxPhysicalHeight
            );
            if (fit < scale) {
                scale = Math.max(
                        MIN_DYNAMIC_SCALE,
                        fit
                );
                lines = wrapAtScale(
                        tokens,
                        maxPhysicalWidth,
                        reservedLogicalWidth,
                        emoteSize,
                        scale,
                        platform,
                        fontId
                );

                fit = fitScale(
                        lines.size(),
                        baseLineHeight,
                        maxPhysicalHeight
                );
                if (fit < scale) {
                    scale = Math.max(
                            MIN_DYNAMIC_SCALE,
                            fit
                    );
                    lines = wrapAtScale(
                            tokens,
                            maxPhysicalWidth,
                            reservedLogicalWidth,
                            emoteSize,
                            scale,
                            platform,
                            fontId
                    );
                }
            }
        }

        int lineHeight = Math.max(
                1,
                Math.round(baseLineHeight * scale)
        );
        int blockHeight = Math.max(
                lineHeight,
                lines.size() * lineHeight
        );
        ScaledLayout result = new ScaledLayout(
                List.copyOf(lines),
                scale,
                lineHeight,
                blockHeight
        );

        LayoutCacheKey storedKey =
                layoutCacheKey(
                        List.copyOf(tokens),
                        maxPhysicalWidth,
                        maxPhysicalHeight,
                        reservedLogicalWidth,
                        emoteSize,
                        baseLineHeight,
                        requestedScale,
                        platform,
                        fontId
                );
        synchronized (LAYOUT_CACHE) {
            LAYOUT_CACHE.put(storedKey, result);
        }
        return result;
    }

    private static LayoutCacheKey layoutCacheKey(
            List<MessageToken> tokens,
            int maxPhysicalWidth,
            int maxPhysicalHeight,
            int reservedLogicalWidth,
            int emoteSize,
            int baseLineHeight,
            float requestedScale,
            Platform platform,
            String fontId
    ) {
        return new LayoutCacheKey(
                tokens,
                maxPhysicalWidth,
                maxPhysicalHeight,
                reservedLogicalWidth,
                emoteSize,
                baseLineHeight,
                Float.floatToIntBits(requestedScale),
                fontId == null ? "" : fontId,
                platform == null
                        ? ""
                        : platform.getClass().getName()
        );
    }

    private static List<LaidOutLine> wrapAtScale(
            List<MessageToken> tokens,
            int maxPhysicalWidth,
            int reservedLogicalWidth,
            int emoteSize,
            float scale,
            Platform platform,
            String fontId
    ) {
        int logicalWidth = Math.max(
                1,
                (int) Math.floor(
                        maxPhysicalWidth / scale
                )
        );
        int firstLineWidth = Math.max(
                1,
                logicalWidth - reservedLogicalWidth
        );
        return wrapWithWidths(
                tokens,
                firstLineWidth,
                logicalWidth,
                emoteSize,
                platform,
                fontId
        );
    }

    private static float fitScale(
            int lineCount,
            int baseLineHeight,
            int maxPhysicalHeight
    ) {
        int logicalHeight = Math.max(
                baseLineHeight,
                lineCount * baseLineHeight
        );
        return (float) maxPhysicalHeight / logicalHeight;
    }

    private static List<String> splitIntoWords(MessageToken token) {
        if (token instanceof MessageToken.Emote) {
            return List.of("");
        }

        MessageToken.Text text = (MessageToken.Text) token;
        if (text.link()) {
            return List.of(text.text());
        }

        List<String> words = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (char c : text.text().toCharArray()) {
            current.append(c);
            if (c == ' ') {
                words.add(current.toString());
                current.setLength(0);
            }
        }
        if (!current.isEmpty()) {
            words.add(current.toString());
        }
        return words;
    }

    private static List<String> splitToFit(
            MessageToken token,
            String piece,
            int maxWidth,
            int emoteSize,
            Platform platform,
            String fontId
    ) {
        if (token instanceof MessageToken.Emote
                || piece.isEmpty()
                || pieceWidth(
                        token,
                        piece,
                        emoteSize,
                        platform,
                        fontId
                ) <= maxWidth) {
            return List.of(piece);
        }

        List<String> chunks = new ArrayList<>();
        int start = 0;

        while (start < piece.length()) {
            int cursor = start;
            int bestEnd = start;

            while (cursor < piece.length()) {
                int codePoint = piece.codePointAt(cursor);
                int next = cursor + Character.charCount(codePoint);
                String candidate = piece.substring(start, next);
                int width = pieceWidth(
                        token,
                        candidate,
                        emoteSize,
                        platform,
                        fontId
                );

                if (width > maxWidth) {
                    if (bestEnd == start) {
                        bestEnd = next;
                    }
                    break;
                }

                bestEnd = next;
                cursor = next;
            }

            chunks.add(piece.substring(start, bestEnd));
            start = bestEnd;
        }

        return chunks;
    }

    private static int pieceWidth(
            MessageToken token,
            String piece,
            int emoteSize,
            Platform platform,
            String fontId
    ) {
        if (token instanceof MessageToken.Emote) {
            return emoteSize;
        }
        return "off".equals(fontId)
                ? platform.measureTextWidth(piece)
                : platform.measureFontTextWidth(piece, fontId);
    }

    private static MessageToken tokenFor(
            MessageToken original,
            String piece
    ) {
        if (original instanceof MessageToken.Emote) {
            return original;
        }
        MessageToken.Text text = (MessageToken.Text) original;
        return new MessageToken.Text(
                piece,
                text.link(),
                text.url()
        );
    }
}
