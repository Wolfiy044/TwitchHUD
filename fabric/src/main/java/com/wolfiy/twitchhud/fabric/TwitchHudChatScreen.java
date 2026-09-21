package com.wolfiy.twitchhud.fabric;

import com.wolfiy.twitchhud.core.ChatMessage;
import com.wolfiy.twitchhud.core.TwitchHudSession;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class TwitchHudChatScreen extends Screen {
    private static final int COPY_BUTTON_WIDTH = 16;
    private static final int COPY_BUTTON_GAP = 3;
    private static final int COPY_COLOR = 0xFF3A3A42;
    private static final int COPY_COLOR_HOVER = 0xFF9147FF;

    private final TwitchHudSession session;
    private final ChatOverlayRenderer renderer;
    private final List<ChatOverlayRenderer.LinkHit> copyButtons = new ArrayList<>();

    protected TwitchHudChatScreen(TwitchHudSession session, ChatOverlayRenderer renderer) {
        super(Text.literal("TwitchHUD Chat"));
        this.session = session;
        this.renderer = renderer;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderer.render(context, false, true);
        rebuildCopyButtons();

        Optional<ChatOverlayRenderer.MessageHit> hovered = renderer.messageAt(mouseX, mouseY);
        hovered.ifPresent(hit -> context.fill(hit.x(), hit.y(), hit.x() + hit.width(), hit.y() + hit.height(), 0x22FFFFFF));

        for (ChatOverlayRenderer.LinkHit button : copyButtons) {
            boolean hovering = isOver(button, mouseX, mouseY);
            context.fill(button.x(), button.y(), button.x() + button.width(), button.y() + button.height(),
                    hovering ? COPY_COLOR_HOVER : COPY_COLOR);
            int textWidth = this.textRenderer.getWidth("C");
            context.drawText(this.textRenderer, "C", button.x() + (button.width() - textWidth) / 2, button.y() + 1, 0xFFFFFFFF, false);
        }

        Optional<ChatOverlayRenderer.LinkHit> hoveredButton = copyButtonAt(mouseX, mouseY);
        if (hoveredButton.isPresent()) {
            context.drawText(this.textRenderer, "Click: copy link", mouseX + 12, mouseY, 0xFFFFFFFF, true);
        } else if (hovered.isPresent()) {
            context.drawText(this.textRenderer, "Click: copy - Right-click: hide user", mouseX + 12, mouseY, 0xFFAAAAAA, true);
        }

        super.render(context, mouseX, mouseY, delta);
    }

    private void rebuildCopyButtons() {
        copyButtons.clear();
        for (ChatOverlayRenderer.LinkHit link : renderer.linkHits()) {
            int bx = link.x() + link.width() + COPY_BUTTON_GAP;
            int by = link.y() - 1;
            copyButtons.add(new ChatOverlayRenderer.LinkHit(bx, by, COPY_BUTTON_WIDTH, link.height() + 2, link.url()));
        }
    }

    private static boolean isOver(ChatOverlayRenderer.LinkHit button, double mouseX, double mouseY) {
        return mouseX >= button.x() && mouseX <= button.x() + button.width()
                && mouseY >= button.y() && mouseY <= button.y() + button.height();
    }

    private Optional<ChatOverlayRenderer.LinkHit> copyButtonAt(double mouseX, double mouseY) {
        for (ChatOverlayRenderer.LinkHit button : copyButtons) {
            if (isOver(button, mouseX, mouseY)) {
                return Optional.of(button);
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        Optional<ChatOverlayRenderer.LinkHit> copyButton = copyButtonAt(mouseX, mouseY);
        if (copyButton.isPresent()) {
            PlatformImpl.INSTANCE.copyToClipboard(copyButton.get().url());
            return true;
        }

        Optional<ChatOverlayRenderer.LinkHit> link = renderer.linkAt(mouseX, mouseY);
        if (link.isPresent() && button == 0) {
            PlatformImpl.INSTANCE.openUrl(link.get().url());
            return true;
        }

        Optional<ChatOverlayRenderer.MessageHit> hit = renderer.messageAt(mouseX, mouseY);
        if (hit.isPresent()) {
            ChatMessage message = hit.get().message();
            if (button == 1) {
                session.hideUser(message.username());
            } else {
                PlatformImpl.INSTANCE.copyToClipboard(message.copyText());
            }
            return true;
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        session.scrollBy((int) Math.signum(verticalAmount), session.visibleMessages(true).size());
        return true;
    }

    @Override
    public void close() {
        copyButtons.clear();
        session.resetScroll();
        this.client.setScreen(null);
    }
}
