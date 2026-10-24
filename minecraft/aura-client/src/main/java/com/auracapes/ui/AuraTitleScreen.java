package com.auracapes.ui;

import com.auracapes.Accounts;
import com.auracapes.CapeLibraryScreen;
import com.auracapes.CapeRegistry;
import com.auracapes.StreamerName;
import com.auracapes.compat.Compat;

import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.PlainTextButton;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.CreditsAndAttributionScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.SafetyScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

/**
 * The Aura main menu - what you see instead of vanilla's title screen.
 *
 * <p><b>It extends TitleScreen on purpose.</b> Vanilla and other mods ask
 * "is the title screen showing?" with {@code instanceof TitleScreen} - the
 * disconnect flow, Realms, the music manager - and a menu that answered no
 * would quietly break all of them. So it IS a TitleScreen, and overrides
 * every method that would draw or add the vanilla parts. The fields TitleScreen
 * keeps for those parts (splash, Realms notifications, the friends button) stay
 * null, and every place TitleScreen reads them already checks for null.
 *
 * <p>It is swapped in by {@code GuiMixin} rather than by replacing calls to
 * {@code new TitleScreen()}, because there are a dozen of those and one
 * {@code setScreen} they all go through.
 */
public class AuraTitleScreen extends TitleScreen {

    private static final Identifier LOGO = Identifier.fromNamespaceAndPath(
            CapeRegistry.MOD_ID, "textures/gui/logo.png");
    private static final int LOGO_TEX_W = 640;
    private static final int LOGO_TEX_H = 170;

    /** The very first menu of a session gets the long, staged fade. */
    private static boolean firstEver = true;

    private final long openedAt = Util.getMillis();
    private final float fadeSeconds;

    private int logoX;
    private int logoY;
    private int logoW;
    private int logoH;

    public AuraTitleScreen() {
        super(false);
        this.fadeSeconds = firstEver ? 1.4F : 0.25F;
        firstEver = false;
    }

    /**
     * What GuiMixin hands to setScreen: our menu wherever vanilla's would
     * have appeared, and anything else untouched. The exact-class check is
     * what stops a mod's own TitleScreen subclass from being replaced.
     */
    public static Screen substitute(Screen screen) {
        if (screen != null && screen.getClass() == TitleScreen.class) {
            return new AuraTitleScreen();
        }
        // setScreen(null) with no world means "go to the title screen" -
        // vanilla builds a TitleScreen inside setScreen, after HEAD, so the
        // null case has to be answered here or it would slip past.
        if (screen == null && Minecraft.getInstance().level == null) {
            return new AuraTitleScreen();
        }
        return screen;
    }

    @Override
    protected void init() {
        float guiScale = (float) this.minecraft.getWindow().getGuiScale();
        // Drawn at the texture's own pixel size, so nearest-neighbour sampling
        // has nothing to round - capped so it never crowds a small window.
        logoW = Math.min(Math.round(LOGO_TEX_W / guiScale), (int) (this.width * 0.62F));
        logoH = logoW * LOGO_TEX_H / LOGO_TEX_W;

        int buttonsH = 22 + 5 + 22 + 9 + 20;
        int tagH = 14;
        int total = logoH + tagH + 16 + buttonsH;
        int top = Math.max(26, (this.height - total) / 2 - 6);

        logoX = (this.width - logoW) / 2;
        logoY = top;

        int cx = this.width / 2;
        int y = top + logoH + tagH + 16;
        int bw = 200;

        addRenderableWidget(new AuraButton(cx - bw / 2, y, bw, 22,
                Component.translatable("menu.singleplayer"), AuraButton.Style.PRIMARY,
                () -> Compat.setScreen(new SelectWorldScreen(this))));
        y += 27;

        AuraButton multiplayer = addRenderableWidget(new AuraButton(cx - bw / 2, y, bw, 22,
                Component.translatable("menu.multiplayer"), AuraButton.Style.PRIMARY, () -> {
                    Screen next = this.minecraft.options.skipMultiplayerWarning
                            ? new JoinMultiplayerScreen(this)
                            : new SafetyScreen(this);
                    Compat.setScreen(next);
                }));
        multiplayer.active = this.minecraft.allowsMultiplayer();
        y += 31;

        int small = (bw - 8) / 3;
        addRenderableWidget(new AuraButton(cx - bw / 2, y, small, 20,
                Component.literal("Cosmetics"), AuraButton.Style.SECONDARY,
                () -> Compat.setScreen(new CapeLibraryScreen(this))));
        addRenderableWidget(new AuraButton(cx - bw / 2 + small + 4, y, small, 20,
                Component.translatable("menu.options"), AuraButton.Style.SECONDARY,
                () -> Compat.setScreen(
                        new OptionsScreen(this, this.minecraft.options, false))));
        addRenderableWidget(new AuraButton(cx + bw / 2 - small, y, small, 20,
                Component.translatable("menu.quit"), AuraButton.Style.DANGER,
                () -> this.minecraft.stop()));

        AccountChip chip = addRenderableWidget(new AccountChip(this));
        addRenderableWidget(new NicknamePill(this, chip));

        Component credits = Component.translatable("title.credits");
        int cw = this.font.width(credits);
        addRenderableWidget(new PlainTextButton(this.width - cw - 4, this.height - 12, cw, 10,
                credits, b -> Compat.setScreen(new CreditsAndAttributionScreen(this)),
                this.font));
    }

    /** 0..1, how far into the opening fade we are, delayed by {@code delay}. */
    private float fade(float delay) {
        float s = (Util.getMillis() - openedAt) / 1000.0F;
        float span = fadeSeconds * 0.6F;
        return Aura.clamp01((s - delay * fadeSeconds) / Math.max(0.01F, span));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        this.extractPanorama(g, a);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        // The panorama went into the previous stratum (extractBackground), so
        // this blurs exactly the panorama and none of the menu.
        if (this.minecraft.options.getMenuBackgroundBlurriness() >= 1.0F) {
            g.blurBeforeThisStratum();
        }
        float bg = fade(0.0F);
        float logoA = fade(0.15F);
        float uiA = fade(0.4F);

        AuraBackdrop.draw(g, this.width, this.height, bg);

        // Logo, with a slow breathing drift so the screen is never quite still.
        int bob = Math.round((float) Math.sin(Aura.time() * 1.1F) * 1.5F);
        int drop = Math.round((1.0F - logoA) * 6.0F);
        g.blit(RenderPipelines.GUI_TEXTURED, LOGO, logoX, logoY + bob + drop, 0.0F, 0.0F,
                logoW, logoH, LOGO_TEX_W, LOGO_TEX_H, LOGO_TEX_W, LOGO_TEX_H,
                Aura.fade(0xFFFFFFFF, logoA));

        if (logoA > 0.04F) {
            String tag = "C L I E N T";
            int tagY = logoY + logoH + 2;
            g.centeredText(this.font, tag, this.width / 2, tagY, Aura.fade(Aura.MUTED, logoA));
            int lineW = 34;
            int cx = this.width / 2;
            int half = this.font.width(tag) / 2 + 8;
            Aura.accentBar(g, cx - half - lineW, tagY + 4, cx - half, tagY + 5, logoA * 0.8F);
            Aura.accentBar(g, cx + half, tagY + 4, cx + half + lineW, tagY + 5, logoA * 0.8F);
        }

        for (GuiEventListener child : this.children()) {
            if (child instanceof AbstractWidget w) {
                w.setAlpha(uiA);
            }
            if (child instanceof Renderable r) {
                r.extractRenderState(g, mouseX, mouseY, a);
            }
        }

        if (uiA > 0.04F) {
            String version = "Aura Client  ·  Minecraft "
                    + SharedConstants.getCurrentVersion().name();
            g.text(this.font, version, 4, this.height - 12, Aura.fade(Aura.FAINT, uiA));
        }
    }

    // ------------------------------------------------------------ nickname pill

    /**
     * Under the account chip: the /name nickname, on or off, before you join.
     * It sits with the account because both answer "who will I look like".
     */
    private static final class NicknamePill extends AbstractButton {

        private final AuraTitleScreen screen;
        private float hover;
        private long lastFrame;

        NicknamePill(AuraTitleScreen screen, AccountChip chip) {
            super(chip.getX(), chip.getBottom() + 4, chip.getWidth(), 16, Component.literal("Nickname"));
            this.screen = screen;
        }

        @Override
        public void onPress(InputWithModifiers input) {
            Compat.setScreen(new NicknameScreen(screen));
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float pt) {
            long now = Util.getMillis();
            float dt = lastFrame == 0 ? 0 : Math.min(0.1F, (now - lastFrame) / 1000.0F);
            lastFrame = now;
            hover += ((this.isHovered() ? 1 : 0) - hover) * Math.min(1.0F, dt * 14.0F);

            float a = this.alpha;
            int x0 = getX();
            int y0 = getY();
            int x1 = getRight();
            int y1 = getBottom();
            boolean on = StreamerName.enabled();
            Aura.round(g, x0, y0, x1, y1, Aura.fade(Aura.lerp(Aura.PANEL, Aura.PANEL_HI, hover), a));
            Aura.outline(g, x0, y0, x1, y1, Aura.fade(
                    Aura.lerp(on ? 0x6086EFAC : Aura.EDGE, 0x70A78BFA, hover), a));
            if (a <= 0.04F) {
                return;
            }
            var font = Minecraft.getInstance().font;
            g.fill(x0 + 6, y0 + 6, x0 + 10, y0 + 10, Aura.fade(on ? Aura.GOOD : Aura.FAINT, a));
            String label = on ? "Nickname: " + StreamerName.alias() : "Nickname: Off";
            label = font.plainSubstrByWidth(label, x1 - x0 - 26);
            g.text(font, label, x0 + 14, y0 + 4, Aura.fade(on ? Aura.TEXT : Aura.MUTED, a), false);
            g.text(font, "›", x1 - 10, y0 + 4, Aura.fade(Aura.lerp(Aura.FAINT, Aura.TEXT, hover), a));
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            this.defaultButtonNarrationText(output);
        }
    }

    // ------------------------------------------------------------ account chip

    /**
     * Top-right: your face and name, click to switch. Lunar puts the account
     * here because it is the one thing you check before pressing Multiplayer.
     */
    private static final class AccountChip extends AbstractButton {

        private final AuraTitleScreen screen;
        private float hover;
        private long lastFrame;

        AccountChip(AuraTitleScreen screen) {
            super(0, 0, 10, 26, Component.literal("Account"));
            this.screen = screen;
            var font = Minecraft.getInstance().font;
            String name = Accounts.currentName();
            int w = 30 + Math.max(font.width(name), font.width("Switch account")) + 16;
            this.setWidth(w);
            this.setX(screen.width - w - 8);
            this.setY(8);
        }

        @Override
        public void onPress(InputWithModifiers input) {
            Compat.setScreen(new AccountScreen(screen));
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float pt) {
            long now = Util.getMillis();
            float dt = lastFrame == 0 ? 0 : Math.min(0.1F, (now - lastFrame) / 1000.0F);
            lastFrame = now;
            hover += ((this.isHovered() ? 1 : 0) - hover) * Math.min(1.0F, dt * 14.0F);

            float a = this.alpha;
            int x0 = getX();
            int y0 = getY();
            int x1 = getRight();
            int y1 = getBottom();
            Aura.round(g, x0, y0, x1, y1, Aura.fade(Aura.lerp(Aura.PANEL, Aura.PANEL_HI, hover), a));
            Aura.outline(g, x0, y0, x1, y1,
                    Aura.fade(Aura.lerp(Aura.EDGE, 0x70A78BFA, hover), a));

            if (a <= 0.04F) {
                return;
            }
            String name = Accounts.currentName();
            PlayerFaceExtractor.extractRenderState(g, Accounts.currentSkin(), x0 + 5, y0 + 5, 16);
            // Online dot: green for the launch account, violet for an offline alt.
            int dot = Accounts.onLaunchAccount() ? Aura.GOOD : Aura.VIOLET;
            g.fill(x0 + 18, y0 + 18, x0 + 22, y0 + 22, Aura.fade(0xFF0E0E18, a));
            g.fill(x0 + 19, y0 + 19, x0 + 21, y0 + 21, Aura.fade(dot, a));

            var font = Minecraft.getInstance().font;
            g.text(font, name, x0 + 27, y0 + 4, Aura.fade(Aura.TEXT, a));
            String caption = hover > 0.5F ? "Switch account"
                    : (Accounts.onLaunchAccount() ? "Launch account" : "Offline account");
            g.text(font, caption, x0 + 27, y0 + 14,
                    Aura.fade(Aura.lerp(Aura.FAINT, 0xFFC4B5FD, hover), a), false);
            g.text(font, "›", x1 - 10, y0 + 9, Aura.fade(Aura.lerp(Aura.FAINT, Aura.TEXT, hover), a));
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            this.defaultButtonNarrationText(output);
        }
    }
}
