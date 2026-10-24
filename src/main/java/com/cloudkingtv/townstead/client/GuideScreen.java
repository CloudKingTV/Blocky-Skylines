package com.cloudkingtv.townstead.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.gui.screens.inventory.PageButton;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

/**
 * The Townstead Guide, in the look of a vanilla book: a contents page whose chapter titles jump to the chapter, then
 * each chapter's text laid out over as many pages as it needs (each chapter starts on a new page). The text comes
 * from the language files ({@code guide.townstead.<chapter>.title} and {@code .text}), so it can be translated.
 * It opens where it was last left.
 */
public final class GuideScreen extends Screen {
    /** The chapters, in order. */
    public static final List<String> CHAPTERS = List.of("start", "planner", "roads", "zoning", "utilities", "services", "money",
            "materials", "info", "milestones", "districts", "traffic", "fires", "together");
    private static final int TEXT_LEFT = 36, TEXT_TOP = 30, TEXT_WIDTH = 114, LINES = 14, LINE = 9;
    private static final int INK = 0xFF000000, TITLE = 0xFF2D5A27, LINK = 0xFF2D5A27, LINK_HOT = 0xFF7A4E10, FAINT = 0xFF6A6A6A;

    private record Line(FormattedCharSequence text, int color, int target) {}

    private static int lastPage;
    private final List<List<Line>> pages = new ArrayList<>();
    private final List<Integer> chapterStart = new ArrayList<>();
    private int page;
    private @Nullable String openAt;
    private PageButton forward, back;

    public GuideScreen() {
        this(null);
    }

    /** Opens at a chapter (one of {@link #CHAPTERS}), or where it was left if null. */
    public GuideScreen(@Nullable String chapter) {
        super(Component.translatable("guide.townstead.title"));
        this.openAt = chapter;
    }

    @Override
    protected void init() {
        layOut();
        page = openAt != null && CHAPTERS.contains(openAt) ? chapterStart.get(CHAPTERS.indexOf(openAt)) : Math.min(lastPage, pages.size() - 1);
        openAt = null;
        int left = (width - 192) / 2, top = 2;
        forward = addRenderableWidget(new PageButton(left + 116, top + 157, true, b -> turn(1), true));
        back = addRenderableWidget(new PageButton(left + 43, top + 157, false, b -> turn(-1), true));
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).pos((width - 200) / 2, top + 194).width(200).build());
        buttons();
    }

    /** Contents first, then every chapter from the top of a page. */
    private void layOut() {
        pages.clear();
        chapterStart.clear();
        int contentsLines = 2;
        for (int i = 0; i < CHAPTERS.size(); i++) {
            contentsLines += wrap((i + 1) + ". " + Component.translatable("guide.townstead." + CHAPTERS.get(i) + ".title").getString(), TEXT_WIDTH, "    ").size();
        }
        int contentsPages = (contentsLines + LINES - 1) / LINES;
        List<List<Line>> chapters = new ArrayList<>();
        for (String ch : CHAPTERS) {
            List<Line> lines = new ArrayList<>();
            for (FormattedCharSequence s : font.split(Component.translatable("guide.townstead." + ch + ".title").withStyle(ChatFormatting.BOLD), TEXT_WIDTH)) {
                lines.add(new Line(s, TITLE, -1));
            }
            lines.add(new Line(FormattedCharSequence.EMPTY, INK, -1));
            for (FormattedCharSequence s : font.split(Component.translatable("guide.townstead." + ch + ".text"), TEXT_WIDTH)) lines.add(new Line(s, INK, -1));
            chapters.add(lines);
        }
        int at = contentsPages;
        for (List<Line> lines : chapters) {
            chapterStart.add(at);
            at += Math.max(1, (lines.size() + LINES - 1) / LINES);
        }
        List<Line> contents = new ArrayList<>();
        contents.add(new Line(Component.translatable("guide.townstead.contents").withStyle(ChatFormatting.BOLD).getVisualOrderText(), TITLE, -1));
        contents.add(new Line(FormattedCharSequence.EMPTY, INK, -1));
        for (int i = 0; i < CHAPTERS.size(); i++) {
            // A long title wraps, its second line indented under the first; both lines lead to the chapter.
            String title = (i + 1) + ". " + Component.translatable("guide.townstead." + CHAPTERS.get(i) + ".title").getString();
            for (String part : wrap(title, TEXT_WIDTH, "    ")) contents.add(new Line(Component.literal(part).getVisualOrderText(), LINK, chapterStart.get(i)));
        }
        paginate(contents);
        for (List<Line> lines : chapters) paginate(lines);
    }

    /** Word-wraps plain text to a width, continuation lines starting with {@code indent}. */
    private List<String> wrap(String text, int width, String indent) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String tried = line.isEmpty() ? (out.isEmpty() ? "" : indent) + word : line + " " + word;
            if (!line.isEmpty() && font.width(tried) > width) {
                out.add(line.toString());
                line = new StringBuilder(indent + word);
            } else line = new StringBuilder(tried);
        }
        if (!line.isEmpty()) out.add(line.toString());
        return out;
    }

    private void paginate(List<Line> lines) {
        for (int i = 0; i < lines.size(); i += LINES) pages.add(lines.subList(i, Math.min(lines.size(), i + LINES)));
    }

    private void turn(int by) {
        page = Math.max(0, Math.min(pages.size() - 1, page + by));
        lastPage = page;
        buttons();
    }

    private void go(int to) {
        page = Math.max(0, Math.min(pages.size() - 1, to));
        lastPage = page;
        buttons();
        if (minecraft != null) {
            minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                    net.minecraft.sounds.SoundEvents.BOOK_PAGE_TURN, 1.0f));
        }
    }

    private void buttons() {
        forward.visible = page < pages.size() - 1;
        back.visible = page > 0;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        g.blit(RenderPipelines.GUI_TEXTURED, BookViewScreen.BOOK_LOCATION, (width - 192) / 2, 2, 0f, 0f, 192, 192, 256, 256);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        int left = (width - 192) / 2, top = 2;
        Component indicator = Component.translatable("guide.townstead.page", page + 1, pages.size());
        g.text(font, indicator, left + 148 - font.width(indicator), top + 16, FAINT, false);
        if (page > 0) {
            Component home = Component.translatable("guide.townstead.to_contents");
            boolean hot = overContents(mouseX, mouseY);
            g.text(font, home.copy().withStyle(hot ? Style.EMPTY.withUnderlined(true) : Style.EMPTY), left + TEXT_LEFT, top + 16, hot ? LINK_HOT : FAINT, false);
        }
        List<Line> lines = pages.get(page);
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            int y = top + TEXT_TOP + i * LINE;
            boolean hot = l.target >= 0 && mouseX >= left + TEXT_LEFT && mouseX < left + TEXT_LEFT + TEXT_WIDTH && mouseY >= y && mouseY < y + LINE;
            g.text(font, l.text, left + TEXT_LEFT, y, hot ? LINK_HOT : l.color, false);
        }
    }

    private boolean overContents(double x, double y) {
        int left = (width - 192) / 2, top = 2;
        return page > 0 && x >= left + TEXT_LEFT && x < left + TEXT_LEFT + font.width(Component.translatable("guide.townstead.to_contents"))
                && y >= top + 15 && y < top + 25;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            if (overContents(event.x(), event.y())) { go(0); return true; }
            int left = (width - 192) / 2, top = 2;
            List<Line> lines = pages.get(page);
            for (int i = 0; i < lines.size(); i++) {
                int y = top + TEXT_TOP + i * LINE;
                if (lines.get(i).target >= 0 && event.x() >= left + TEXT_LEFT && event.x() < left + TEXT_LEFT + TEXT_WIDTH
                        && event.y() >= y && event.y() < y + LINE) {
                    go(lines.get(i).target);
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (scrollY != 0) turn(scrollY < 0 ? 1 : -1);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        switch (event.key()) {
            case InputConstants.KEY_PAGEDOWN, InputConstants.KEY_RIGHT -> { turn(1); return true; }
            case InputConstants.KEY_PAGEUP, InputConstants.KEY_LEFT -> { turn(-1); return true; }
            case InputConstants.KEY_HOME -> { go(0); return true; }
            default -> { return super.keyPressed(event); }
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Pages in the guide as laid out (for tests). */
    int pageCount() {
        return pages.size();
    }
}
