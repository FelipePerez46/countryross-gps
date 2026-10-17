package com.countryross.gps.client;

import com.countryross.gps.CountryRossGps;
import com.countryross.gps.net.SyncPacket;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

/** Tela do GPS: mapa de curvas de nivel em verde, setinha preta do jogador, areas "sem sinal". */
public class GpsScreen extends Screen {
    private static final ResourceLocation ARROW =
            new ResourceLocation(CountryRossGps.MODID, "textures/gui/arrow.png");

    private static final int COL_BODY_OUTER = 0xFF1C1F18;
    private static final int COL_BODY = 0xFF2E3328;
    private static final int COL_BEZEL = 0xFF0A0D09;
    private static final int COL_SCREEN = 0xFF0F2A12;
    private static final int COL_TEXT = 0xFF9BD17A;
    private static final int COL_TEXT_DIM = 0xFF5F8A4A;
    private static final int COL_NOSIGNAL = 0xFF3FA83F;

    private int devX, devY, devW, devH;
    private int mapX, mapY, mapW, mapH;

    private double viewX, viewZ;
    private double zoom = ClientConfig.defaultZoom;
    private boolean follow = true;

    public GpsScreen() {
        super(Component.literal("CountryRoss GPS"));
    }

    @Override
    protected void init() {
        devH = Math.min(this.height - 12, 380);
        devW = Math.min(this.width - 12, (int) (devH * 0.78));
        devX = (this.width - devW) / 2;
        devY = (this.height - devH) / 2;
        mapX = devX + 12;
        mapY = devY + 26;
        mapW = devW - 24;
        mapH = devH - 26 - 56;
        LocalPlayer p = this.minecraft != null ? this.minecraft.player : null;
        if (p != null) {
            viewX = p.getX();
            viewZ = p.getZ();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ coordenadas

    private double sx(double wx) {
        return mapX + mapW / 2.0 + (wx - viewX) * zoom;
    }

    private double sz(double wz) {
        return mapY + mapH / 2.0 + (wz - viewZ) * zoom;
    }

    private SyncPacket.RegionInfo regionAt(double x, double z) {
        for (SyncPacket.RegionInfo r : ClientState.regions) {
            if (x >= r.minX() && x <= r.maxX() + 1 && z >= r.minZ() && z <= r.maxZ() + 1) return r;
        }
        return null;
    }

    // ------------------------------------------------------------------ desenho

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        LocalPlayer player = this.minecraft != null ? this.minecraft.player : null;
        if (player == null) return;

        double px = Mth.lerp(partialTick, player.xo, player.getX());
        double pz = Mth.lerp(partialTick, player.zo, player.getZ());
        if (follow) {
            viewX = px;
            viewZ = pz;
        }

        SyncPacket.RegionInfo here = regionAt(player.getX(), player.getZ());
        boolean jammed = ClientState.isJammed(player);
        boolean signal = !jammed && here != null && here.unlocked();

        drawBody(g, signal);

        g.enableScissor(mapX, mapY, mapX + mapW, mapY + mapH);
        g.fill(mapX, mapY, mapX + mapW, mapY + mapH, COL_SCREEN);
        if (jammed) {
            drawJam(g);
        } else {
            if (ClientConfig.showGrid) drawGrid(g);
            drawRegions(g);
            drawArrow(g, px, pz, Mth.lerp(partialTick, player.yRotO, player.getYRot()));
        }
        g.disableScissor();

        // linhas de varredura de tela analogica
        if (ClientConfig.scanlines) {
            for (int yy = mapY; yy < mapY + mapH; yy += 2) {
                g.fill(mapX, yy, mapX + mapW, yy + 1, 0x14000000);
            }
        }

        drawReadout(g, player, here, signal, jammed);
        super.render(g, mouseX, mouseY, partialTick);
    }

    private void drawBody(GuiGraphics g, boolean signal) {
        g.fill(devX, devY, devX + devW, devY + devH, COL_BODY_OUTER);
        g.fill(devX + 2, devY + 2, devX + devW - 2, devY + devH - 2, COL_BODY);
        g.fill(mapX - 4, mapY - 4, mapX + mapW + 4, mapY + mapH + 4, COL_BEZEL);

        g.drawString(this.font, "COUNTRYROSS GPS", devX + 12, devY + 9, COL_TEXT, false);

        // LED de sinal
        boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
        int led = signal ? 0xFF58E04F : (blink ? 0xFF9A3A22 : 0xFF3A1A12);
        g.fill(devX + devW - 20, devY + 9, devX + devW - 12, devY + 17, COL_BODY_OUTER);
        g.fill(devX + devW - 19, devY + 10, devX + devW - 13, devY + 16, led);

        // botoes decorativos
        int by = devY + devH - 16;
        for (int i = 0; i < 4; i++) {
            int bx = devX + devW / 2 - 44 + i * 24;
            g.fill(bx, by, bx + 16, by + 8, COL_BODY_OUTER);
            g.fill(bx + 1, by + 1, bx + 15, by + 7, 0xFF3D4435);
        }
    }

    private void drawGrid(GuiGraphics g) {
        int step = zoom >= 1.0 ? 100 : 500;
        double left = viewX - (mapW / 2.0) / zoom;
        double right = viewX + (mapW / 2.0) / zoom;
        double top = viewZ - (mapH / 2.0) / zoom;
        double bottom = viewZ + (mapH / 2.0) / zoom;
        for (long x = (long) Math.floor(left / step) * step; x <= right; x += step) {
            int s = (int) sx(x);
            g.fill(s, mapY, s + 1, mapY + mapH, 0x303F8F3F);
        }
        for (long z = (long) Math.floor(top / step) * step; z <= bottom; z += step) {
            int s = (int) sz(z);
            g.fill(mapX, s, mapX + mapW, s + 1, 0x303F8F3F);
        }
    }

    private void drawRegions(GuiGraphics g) {
        for (SyncPacket.RegionInfo r : ClientState.regions) {
            double x1 = sx(r.minX());
            double x2 = sx(r.maxX() + 1);
            double y1 = sz(r.minZ());
            double y2 = sz(r.maxZ() + 1);
            if (x2 < mapX || x1 > mapX + mapW || y2 < mapY || y1 > mapY + mapH) continue;

            ClientState.Loaded l = ClientState.get(r.id());
            if (r.unlocked() && l != null) {
                PoseStack pose = g.pose();
                pose.pushPose();
                pose.translate((float) x1, (float) y1, 0f);
                float s = (float) (zoom * l.scale());
                pose.scale(s, s, 1f);
                g.blit(l.tex(), 0, 0, l.cw(), l.ch(), 0f, 0f, l.cw(), l.ch(), l.cw(), l.ch());
                pose.popPose();
                drawBorder(g, x1, y1, x2, y2, 0xFF2E6B33);
            } else {
                int cx1 = (int) Math.max(x1, mapX);
                int cy1 = (int) Math.max(y1, mapY);
                int cx2 = (int) Math.min(x2, mapX + mapW);
                int cy2 = (int) Math.min(y2, mapY + mapH);
                if (cx2 <= cx1 || cy2 <= cy1) continue;
                g.fill(cx1, cy1, cx2, cy2, 0xFF0A180B);
                if (r.unlocked()) {
                    g.drawCenteredString(this.font, "CARREGANDO MAPA...", (cx1 + cx2) / 2, (cy1 + cy2) / 2 - 4,
                            COL_TEXT_DIM);
                } else {
                    for (int yy = cy1; yy < cy2; yy += 4) {
                        g.fill(cx1, yy, cx2, yy + 1, 0x403F8F3F);
                    }
                    if (cx2 - cx1 > 70 && cy2 - cy1 > 24) {
                        g.drawCenteredString(this.font, "SEM SINAL", (cx1 + cx2) / 2, (cy1 + cy2) / 2 - 8,
                                COL_NOSIGNAL);
                        g.drawCenteredString(this.font, r.name(), (cx1 + cx2) / 2, (cy1 + cy2) / 2 + 3,
                                COL_TEXT_DIM);
                    }
                }
                drawBorder(g, x1, y1, x2, y2, 0xFF1E4A22);
            }
        }
    }

    private void drawBorder(GuiGraphics g, double x1, double y1, double x2, double y2, int color) {
        int ax = (int) x1, ay = (int) y1, bx = (int) x2, by = (int) y2;
        g.fill(ax, ay, bx, ay + 1, color);
        g.fill(ax, by - 1, bx, by, color);
        g.fill(ax, ay, ax + 1, by, color);
        g.fill(bx - 1, ay, bx, by, color);
    }

    private void drawArrow(GuiGraphics g, double wx, double wz, float yaw) {
        double x = Mth.clamp(sx(wx), mapX + 8, mapX + mapW - 8);
        double y = Mth.clamp(sz(wz), mapY + 8, mapY + mapH - 8);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        PoseStack pose = g.pose();
        pose.pushPose();
        pose.translate(x, y, 0);
        pose.mulPose(Axis.ZP.rotationDegrees(yaw + 180f));
        g.blit(ARROW, -8, -8, 0, 0, 16, 16, 16, 16);
        pose.popPose();
        RenderSystem.disableBlend();
    }

    private void drawJam(GuiGraphics g) {
        java.util.Random rnd = new java.util.Random(System.currentTimeMillis() / 90);
        int cell = 6;
        for (int yy = mapY; yy < mapY + mapH; yy += cell) {
            for (int xx = mapX; xx < mapX + mapW; xx += cell) {
                if (rnd.nextInt(100) < 38) {
                    int a = 0x20 + rnd.nextInt(0x50);
                    g.fill(xx, yy, Math.min(xx + cell, mapX + mapW), Math.min(yy + cell, mapY + mapH),
                            (a << 24) | 0x3FA83F);
                }
            }
        }
        for (int i = 0; i < 3; i++) {
            int ty = mapY + rnd.nextInt(Math.max(1, mapH));
            g.fill(mapX, ty, mapX + mapW, ty + 1 + rnd.nextInt(3), 0x553FA83F);
        }
        boolean blink = (System.currentTimeMillis() / 400) % 2 == 0;
        int cy = mapY + mapH / 2;
        g.fill(mapX + 10, cy - 18, mapX + mapW - 10, cy + 18, 0xCC0A180B);
        g.drawCenteredString(this.font, "SINAL BLOQUEADO", mapX + mapW / 2, cy - 12,
                blink ? 0xFFD0603C : COL_NOSIGNAL);
        g.drawCenteredString(this.font, "INTERFERENCIA DETECTADA", mapX + mapW / 2, cy + 2, COL_TEXT_DIM);
    }

    private void drawReadout(GuiGraphics g, LocalPlayer p, SyncPacket.RegionInfo here, boolean signal,
                             boolean jammed) {
        int y0 = mapY + mapH + 8;
        boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
        if (jammed) {
            g.drawString(this.font, "X ???   Z ???   ALT ???", mapX, y0, COL_TEXT_DIM, false);
            if (blink) g.drawString(this.font, "SINAL BLOQUEADO", mapX, y0 + 12, 0xFFD0603C, false);
        } else {
            if (ClientConfig.showCoordinates) {
                g.drawString(this.font,
                        "X " + (int) Math.floor(p.getX()) + "   Z " + (int) Math.floor(p.getZ())
                                + "   ALT " + (int) Math.floor(p.getY()),
                        mapX, y0, COL_TEXT, false);
            }
            if (signal) {
                g.drawString(this.font, "SINAL OK - " + here.name(), mapX, y0 + 12, COL_NOSIGNAL, false);
            } else if (blink) {
                g.drawString(this.font, "SEM SINAL", mapX, y0 + 12, 0xFFD0603C, false);
            }
        }
        g.drawString(this.font, "[ARRASTAR] MOVER  [ROLAR] ZOOM  [R] CENTRAR", mapX, y0 + 24, COL_TEXT_DIM, false);
    }

    // ------------------------------------------------------------------ entrada

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0) {
            viewX -= dragX / zoom;
            viewZ -= dragY / zoom;
            follow = false;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        zoom = Mth.clamp(zoom * (delta > 0 ? 1.2 : 1.0 / 1.2), 0.1, 8.0);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_R) {
            follow = true;
            return true;
        }
        if (this.minecraft != null && this.minecraft.options.keyInventory.matches(keyCode, scanCode)) {
            this.onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
