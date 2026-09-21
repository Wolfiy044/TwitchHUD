package com.wolfiy.twitchhud.fabric;

import com.wolfiy.twitchhud.core.BadgeStyle;
import com.wolfiy.twitchhud.core.ChatMessage;
import com.wolfiy.twitchhud.core.TwitchHudConfig;
import com.wolfiy.twitchhud.core.TwitchHudSession;
import com.wolfiy.twitchhud.render.ChatLayoutEngine;
import com.wolfiy.twitchhud.render.MessageToken;
import com.wolfiy.twitchhud.render.OverlayWidgetModel;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class ChatOverlayRenderer {
    private static final int EMOTE_SIZE = 16;
    private static final int LINE_HEIGHT = 18;
    private static final int PADDING = 4;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    private static final List<ChatMessage> PREVIEW_MESSAGES = List.of(
            new ChatMessage("preview-1", "twitchhud", "TwitchHUD", "This is a preview message - connect to a channel to see real chat!",
                    0x9147FF, false, false, false, List.of(), List.of(), System.currentTimeMillis(), ChatMessage.Kind.CHAT, null)
    );

    private static Text styled(String text, String fontId) {
        return "off".equals(fontId) ? Text.literal(text) : Text.literal(text).setStyle(PlatformImpl.styleFor(fontId));
    }

    private final TwitchHudSession session;
    private final EmoteTextureManager emoteTextures;
    private final BadgeTextureManager badgeTextures;
    private final List<LinkHit> linkHits = new ArrayList<>();
    private final List<MessageHit> messageHits = new ArrayList<>();
    private final List<WidgetHit> widgetHits = new ArrayList<>();

    public record LinkHit(int x, int y, int width, int height, String url) {
    }

    public record MessageHit(int x, int y, int width, int height, ChatMessage message) {
    }

    public record WidgetHit(String key, int x, int y, int width, int height) {
    }

    public ChatOverlayRenderer(TwitchHudSession session) {
        this.session = session;
        this.emoteTextures = new EmoteTextureManager(session.emoteCache());
        this.badgeTextures = new BadgeTextureManager(session.badgeIconService());
    }

    public void render(DrawContext context) {
        render(context, false, false);
    }

    public void render(DrawContext context, boolean preview, boolean interactiveMode) {
        linkHits.clear();
        messageHits.clear();
        widgetHits.clear();

        TwitchHudConfig config = session.config();
        MinecraftClient client = MinecraftClient.getInstance();
        int screenW = PlatformImpl.INSTANCE.screenWidth();
        int screenH = PlatformImpl.INSTANCE.screenHeight();

        int x = (int) (config.x() * screenW);
        int y = (int) (config.y() * screenH);
        int width = (int) config.width();
        int height = (int) config.height();

        int bgAlpha = (int) (config.backgroundOpacity() * 255);
        context.fill(x, y, x + width, y + height, bgAlpha << 24);
        context.enableScissor(x, y, x + width, y + height);

        List<ChatMessage> all = session.visibleMessages(interactiveMode);
        if (preview && all.isEmpty()) {
            all = PREVIEW_MESSAGES;
        }
        int hideCount = preview ? 0 : session.scrollOffset();
        int endExclusive = Math.max(0, all.size() - hideCount);
        List<ChatMessage> messages = all.subList(0, endExclusive);

        int lineHeight = (int) (LINE_HEIGHT * config.textScale());
        int cursorY = y + height - 4;

        int messageAlpha = (int) (config.messageOpacity() * 255);
        int messageColor = (messageAlpha << 24) | (config.messageColor() & 0xFFFFFF);
        int linkColor = (messageAlpha << 24) | 0x6DB4FF;

        for (int i = messages.size() - 1; i >= 0 && cursorY > y; i--) {
            ChatMessage message = messages.get(i);
            int blockTop = renderMessage(context, client, message, x, y, width, cursorY, lineHeight, messageColor, linkColor, config);
            int hitTop = Math.max(y, blockTop);
            int hitBottom = Math.min(y + height, cursorY);
            if (hitBottom > hitTop) {
                messageHits.add(new MessageHit(
                        x,
                        hitTop,
                        width,
                        hitBottom - hitTop,
                        message
                ));
            }
            cursorY = blockTop - (config.compactMode() ? 0 : 2);
        }

        context.disableScissor();
        renderWidgets(context, client, config, preview);
    }

    private int renderMessage(DrawContext context, MinecraftClient client, ChatMessage message,
                               int x, int y, int width, int cursorY, int lineHeight,
                               int messageColor, int linkColor, TwitchHudConfig config) {
        int padding = PADDING;
        String fontId = config.fontId();
        int contentWidth = Math.max(1, width - padding * 2);
        int maxHeight = Math.max(LINE_HEIGHT, (int) config.height());
        int baseLineHeight = config.compactMode() ? (int) (LINE_HEIGHT * 0.8f) : LINE_HEIGHT;

        if (message.kind() != ChatMessage.Kind.CHAT) {
            String banner = message.systemText() != null ? message.systemText() : message.text();
            List<MessageToken> tokens = List.of(new MessageToken.Text(banner, false, null));
            ChatLayoutEngine.ScaledLayout layout = ChatLayoutEngine.wrapScaled(
                    tokens, contentWidth, maxHeight, 0, EMOTE_SIZE, baseLineHeight,
                    (float) config.textScale(), PlatformImpl.INSTANCE, fontId);
            int blockTop = cursorY - layout.blockHeight();
            context.fill(x, blockTop, x + width, cursorY, 0x552ECC71);

            float scale = layout.scale();
            boolean scaled = scale != 1.0f;
            if (scaled) {
                context.getMatrices().pushMatrix();
                context.getMatrices().scale(scale, scale);
            }

            int drawX = Math.round((x + padding) / scale);
            int drawY = Math.round(blockTop / scale);
            drawLines(context, client, layout.lines(), drawX, drawY,
                    baseLineHeight, 0xFFB6FFCE, fontId);

            if (scaled) {
                context.getMatrices().popMatrix();
            }
            return blockTop;
        }

        List<ChatMessage.Badge> badges = config.showBadges()
                ? message.badges()
                : List.of();
        int badgeHeight = 12;
        int badgeRowWidth = 0;
        for (ChatMessage.Badge badge : badges) {
            badgeRowWidth += badgeWidth(client, badge, badgeHeight);
        }

        String timePrefix = config.showTimestamps()
                ? "[" + TIME_FORMAT.format(Instant.ofEpochMilli(message.timestamp())) + "] "
                : "";
        String prefix = timePrefix + message.displayName() + ": ";
        List<MessageToken> tokens = new ArrayList<>();
        tokens.add(new MessageToken.Text(prefix, false, null));
        tokens.addAll(ChatLayoutEngine.tokenize(message, config.showEmotes(), session.thirdPartyEmotes()));

        ChatLayoutEngine.ScaledLayout layout = ChatLayoutEngine.wrapScaled(
                tokens, contentWidth, maxHeight, badgeRowWidth, EMOTE_SIZE,
                baseLineHeight, (float) config.textScale(), PlatformImpl.INSTANCE, fontId);
        float scale = layout.scale();
        int blockTop = cursorY - layout.blockHeight();

        if (isMentioned(message, config.channel())) {
            context.fill(x, blockTop, x + width, cursorY, 0x33FFD24D);
        }

        boolean scaled = scale != 1.0f;
        if (scaled) {
            context.getMatrices().pushMatrix();
            context.getMatrices().scale(scale, scale);
        }

        int defaultLineX = Math.round((x + padding) / scale);
        int firstLineX = defaultLineX;
        if (!badges.isEmpty() && blockTop >= y) {
            int badgeY = Math.round(blockTop / scale);
            firstLineX = drawBadges(context, client, badges,
                    defaultLineX, badgeY, badgeHeight, fontId);
        }

        int charOffset = 0;
        for (int lineIdx = 0; lineIdx < layout.lines().size(); lineIdx++) {
            int physicalY = blockTop + lineIdx * layout.lineHeight();
            if (physicalY < y) {
                charOffset += lineCharCount(layout.lines().get(lineIdx));
                continue;
            }

            int baseX = lineIdx == 0 ? firstLineX : defaultLineX;
            int drawY = Math.round(physicalY / scale);
            charOffset = drawLine(context, client, layout.lines().get(lineIdx),
                    baseX, drawY, message, prefix.length(), charOffset,
                    messageColor, linkColor, fontId, scale);
        }

        if (scaled) {
            context.getMatrices().popMatrix();
        }
        return blockTop;
    }

    private static boolean isMentioned(ChatMessage message, String channel) {
        if (channel == null || channel.isBlank()) {
            return false;
        }
        String text = message.text();
        if (text == null || text.isEmpty()) {
            return false;
        }
        return text.toLowerCase(Locale.ROOT).contains("@" + channel.toLowerCase(Locale.ROOT));
    }

    private int drawBadges(DrawContext context, MinecraftClient client, List<ChatMessage.Badge> badges, int x, int y, int badgeHeight, String fontId) {
        int cursor = x;
        for (ChatMessage.Badge badge : badges) {
            Optional<BadgeTextureManager.BadgeTexture> icon = badgeTextures.get(badge.setId(), badge.version());
            if (icon.isPresent()) {
                BadgeTextureManager.BadgeTexture tex = icon.get();
                context.drawTexture(RenderPipelines.GUI_TEXTURED, tex.id(), cursor, y, 0f, 0f, badgeHeight, badgeHeight, tex.width(), tex.height(), tex.width(), tex.height());
                cursor += badgeHeight + 3;
                continue;
            }

            BadgeStyle.Style style = BadgeStyle.forSetId(badge.setId());
            int textWidth = client.textRenderer.getWidth(style.abbreviation());
            int chipWidth = textWidth + 6;
            context.fill(cursor, y, cursor + chipWidth, y + badgeHeight, 0xFF000000 | style.color());
            context.drawText(client.textRenderer, styled(style.abbreviation(), fontId), cursor + 3, y + (badgeHeight - 8) / 2, 0xFFFFFFFF, false);
            cursor += chipWidth + 3;
        }
        return cursor;
    }

    private int badgeWidth(MinecraftClient client, ChatMessage.Badge badge, int badgeHeight) {
        if (badgeTextures.get(badge.setId(), badge.version()).isPresent()) {
            return badgeHeight + 3;
        }
        BadgeStyle.Style style = BadgeStyle.forSetId(badge.setId());
        return client.textRenderer.getWidth(style.abbreviation()) + 9;
    }

    private static int lineCharCount(ChatLayoutEngine.LaidOutLine line) {
        int count = 0;
        for (ChatLayoutEngine.PlacedToken placed : line.tokens()) {
            if (placed.token() instanceof MessageToken.Text text) {
                count += text.text().length();
            }
        }
        return count;
    }

    private int drawLine(DrawContext context, MinecraftClient client, ChatLayoutEngine.LaidOutLine line,
                          int baseX, int drawY, ChatMessage message, int prefixLength, int runningChars, int messageColor, int linkColor, String fontId, float autoScale) {
        for (ChatLayoutEngine.PlacedToken placed : line.tokens()) {
            int drawX = baseX + placed.x();
            if (placed.token() instanceof MessageToken.Emote emote) {
                Optional<EmoteTextureManager.EmoteTexture> tex = emoteTextures.get(emote.emoteId(), emote.imageUrl());
                if (tex.isPresent()) {
                    EmoteTextureManager.EmoteTexture t = tex.get();
                    int drawW = fitEmoteWidth(t.width(), t.height());
                    int drawH = fitEmoteHeight(t.width(), t.height());
                    int emoteX = drawX + (EMOTE_SIZE - drawW) / 2;
                    int emoteY = drawY - 2 + (EMOTE_SIZE - drawH) / 2;
                    context.drawTexture(RenderPipelines.GUI_TEXTURED, t.id(), emoteX, emoteY, 0f, 0f, drawW, drawH, t.width(), t.height(), t.width(), t.height());
                }
            } else if (placed.token() instanceof MessageToken.Text text) {
                boolean isUsername = runningChars < prefixLength;
                int color = isUsername ? ((messageColor & 0xFF000000) | (message.color() & 0xFFFFFF)) : (text.link() ? linkColor : messageColor);
                context.drawText(client.textRenderer, styled(text.text(), fontId), drawX, drawY, color, true);
                if (text.link()) {
                    linkHits.add(new LinkHit(
                        Math.round(drawX * autoScale), Math.round((drawY - 1) * autoScale),
                        Math.round(placed.width() * autoScale),
                        Math.max(8, Math.round(10 * autoScale)), text.url()));
                }
                runningChars += text.text().length();
            }
        }
        return runningChars;
    }

    private void drawLines(DrawContext context, MinecraftClient client, List<ChatLayoutEngine.LaidOutLine> lines,
                            int baseX, int startY, int lineHeight, int color, String fontId) {
        for (int i = 0; i < lines.size(); i++) {
            int y = startY + i * lineHeight;
            for (ChatLayoutEngine.PlacedToken placed : lines.get(i).tokens()) {
                if (placed.token() instanceof MessageToken.Text text) {
                    context.drawText(client.textRenderer, styled(text.text(), fontId), baseX + placed.x(), y, color, true);
                }
            }
        }
    }

    private void renderWidgets(
            DrawContext context,
            MinecraftClient client,
            TwitchHudConfig config,
            boolean preview
    ) {
        int screenW = PlatformImpl.INSTANCE.screenWidth();
        int screenH = PlatformImpl.INSTANCE.screenHeight();
        for (OverlayWidgetModel.Card card : OverlayWidgetModel.cards(
                config,
                session.liveSnapshot(),
                preview
        )) {
            TwitchHudConfig.WidgetPosition position =
                    config.widgetPosition(card.key());
            int cardX = Math.max(
                    0,
                    Math.min(
                            screenW - card.width(),
                            (int) Math.round(position.x() * screenW)
                    )
            );
            int cardY = Math.max(
                    0,
                    Math.min(
                            screenH - card.height(),
                            (int) Math.round(position.y() * screenH)
                    )
            );
            drawWidget(
                    context,
                    client,
                    card,
                    cardX,
                    cardY,
                    config.widgetStyle(card.key())
            );
            if (preview) {
                int handle = 6;
                context.fill(
                        cardX + card.width() - handle,
                        cardY + card.height() - handle,
                        cardX + card.width(),
                        cardY + card.height(),
                        0xFFFFA500
                );
            }
            widgetHits.add(new WidgetHit(
                    card.key(),
                    cardX,
                    cardY,
                    card.width(),
                    card.height()
            ));
        }
    }

    private void drawWidget(
            DrawContext context,
            MinecraftClient client,
            OverlayWidgetModel.Card card,
            int x,
            int y,
            TwitchHudConfig.WidgetStyle style
    ) {
        int right = x + card.width();
        int bottom = y + card.height();
        int contentWidth = Math.max(12, card.width() - 12);

        context.fill(
                x,
                y,
                right,
                bottom,
                withAlpha(
                        0x000000,
                        style.backgroundOpacity()
                )
        );

        int textColor = withAlpha(
                style.textColor(),
                style.textOpacity()
        );
        float scale = effectiveWidgetScale(card, style);
        boolean scaled = Math.abs(scale - 1.0f) > 0.001f;

        if (scaled) {
            context.getMatrices().pushMatrix();
            context.getMatrices().scale(scale, scale);
        }

        int drawX = Math.round(x / scale);
        int drawY = Math.round(y / scale);
        int logicalWidth = Math.max(
                12,
                Math.round(contentWidth / scale)
        );
        String fontId = style.fontId();

        context.drawText(
                client.textRenderer,
                styled(
                        fitText(
                                client,
                                card.heading(),
                                logicalWidth,
                                fontId
                        ),
                        fontId
                ),
                drawX + 6,
                drawY + 4,
                textColor,
                true
        );

        if (!card.primary().isBlank()
                && card.height() >= Math.round(25 * scale)) {
            context.drawText(
                    client.textRenderer,
                    styled(
                            fitText(
                                    client,
                                    card.primary(),
                                    logicalWidth,
                                    fontId
                            ),
                            fontId
                    ),
                    drawX + 6,
                    drawY + 16,
                    textColor,
                    true
            );
        }

        if (!card.secondary().isBlank()
                && card.height() >= Math.round(37 * scale)) {
            context.drawText(
                    client.textRenderer,
                    styled(
                            fitText(
                                    client,
                                    card.secondary(),
                                    logicalWidth,
                                    fontId
                            ),
                            fontId
                    ),
                    drawX + 6,
                    drawY + 28,
                    textColor,
                    true
            );
        }

        if (scaled) {
            context.getMatrices().popMatrix();
        }
    }

    private static float effectiveWidgetScale(
            OverlayWidgetModel.Card card,
            TwitchHudConfig.WidgetStyle style
    ) {
        float requested = (float) style.textScale();
        int lines = 1;
        if (!card.primary().isBlank()) {
            lines++;
        }
        if (!card.secondary().isBlank()) {
            lines++;
        }

        int requiredHeight = 6 + lines * 12;
        float fit = Math.max(
                0.5f,
                (card.height() - 2.0f) / requiredHeight
        );
        return Math.max(
                0.5f,
                Math.min(requested, fit)
        );
    }

    private static int withAlpha(
            int rgb,
            double opacity
    ) {
        int alpha = (int) Math.round(
                Math.max(0.0, Math.min(1.0, opacity))
                        * 255.0
        );
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }

    private static String fitText(
            MinecraftClient client,
            String value,
            int maxWidth,
            String fontId
    ) {
        String text = value == null ? "" : value;
        if (client.textRenderer.getWidth(
                styled(text, fontId)
        ) <= maxWidth) {
            return text;
        }

        String suffix = "...";
        int codePoints = text.codePointCount(
                0,
                text.length()
        );
        while (codePoints > 0) {
            int end = text.offsetByCodePoints(
                    0,
                    codePoints
            );
            String candidate =
                    text.substring(0, end) + suffix;
            if (client.textRenderer.getWidth(
                    styled(candidate, fontId)
            ) <= maxWidth) {
                return candidate;
            }
            codePoints--;
        }
        return suffix;
    }

    private static int fitEmoteWidth(
            int width,
            int height
    ) {
        if (width <= 0 || height <= 0) {
            return EMOTE_SIZE;
        }
        if (width >= height) {
            return EMOTE_SIZE;
        }
        return Math.max(
                1,
                Math.round(
                        EMOTE_SIZE
                                * (float) width
                                / height
                )
        );
    }

    private static int fitEmoteHeight(
            int width,
            int height
    ) {
        if (width <= 0 || height <= 0) {
            return EMOTE_SIZE;
        }
        if (height >= width) {
            return EMOTE_SIZE;
        }
        return Math.max(
                1,
                Math.round(
                        EMOTE_SIZE
                                * (float) height
                                / width
                )
        );
    }

    public List<LinkHit> linkHits() {
        return linkHits;
    }

    public Optional<LinkHit> linkAt(double mouseX, double mouseY) {
        for (LinkHit hit : linkHits) {
            if (mouseX >= hit.x() && mouseX <= hit.x() + hit.width() && mouseY >= hit.y() && mouseY <= hit.y() + hit.height()) {
                return Optional.of(hit);
            }
        }
        return Optional.empty();
    }

    public Optional<WidgetHit> widgetAt(
            double mouseX,
            double mouseY
    ) {
        for (WidgetHit hit : widgetHits) {
            if (mouseX >= hit.x()
                    && mouseX <= hit.x() + hit.width()
                    && mouseY >= hit.y()
                    && mouseY <= hit.y() + hit.height()) {
                return Optional.of(hit);
            }
        }
        return Optional.empty();
    }

    public Optional<MessageHit> messageAt(double mouseX, double mouseY) {
        for (MessageHit hit : messageHits) {
            if (mouseX >= hit.x() && mouseX <= hit.x() + hit.width() && mouseY >= hit.y() && mouseY <= hit.y() + hit.height()) {
                return Optional.of(hit);
            }
        }
        return Optional.empty();
    }
}
