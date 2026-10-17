package com.countryross.gps.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * /countrygps trigger <id>                dispara um gatilho (command block ou chat)
 * /countrygps unlock <regiao>             libera uma regiao
 * /countrygps lock <regiao>               bloqueia uma regiao
 * /countrygps jammer on|off|toggle <id>   liga/desliga um Bloqueador de Sinal
 * /countrygps list                        mostra regioes e bloqueadores
 * /countrygps rescan <regiao>             gera o mapa da regiao de novo
 * /countrygps reload                      recarrega a configuracao
 */
public final class GpsCommands {
    private static final SuggestionProvider<CommandSourceStack> REGIONS =
            (ctx, b) -> SharedSuggestionProvider.suggest(RegionManager.regionIds(), b);
    private static final SuggestionProvider<CommandSourceStack> JAMMERS =
            (ctx, b) -> SharedSuggestionProvider.suggest(RegionManager.jammerIds(), b);
    private static final SuggestionProvider<CommandSourceStack> TRIGGERS =
            (ctx, b) -> SharedSuggestionProvider.suggest(RegionManager.triggerIds(), b);

    private GpsCommands() {}

    private static boolean admin(CommandSourceStack s) {
        return s.hasPermission(RegionManager.adminLevel());
    }

    private static boolean trig(CommandSourceStack s) {
        return s.hasPermission(RegionManager.triggerLevel());
    }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("countrygps")
                .then(Commands.literal("trigger").requires(GpsCommands::trig)
                        .then(Commands.argument("id", StringArgumentType.word()).suggests(TRIGGERS)
                                .executes(GpsCommands::trigger)))
                .then(Commands.literal("unlock").requires(GpsCommands::admin)
                        .then(Commands.argument("regiao", StringArgumentType.word()).suggests(REGIONS)
                                .executes(GpsCommands::unlock)))
                .then(Commands.literal("lock").requires(GpsCommands::admin)
                        .then(Commands.argument("regiao", StringArgumentType.word()).suggests(REGIONS)
                                .executes(GpsCommands::lock)))
                .then(Commands.literal("jammer").requires(GpsCommands::admin)
                        .then(Commands.literal("on")
                                .then(Commands.argument("id", StringArgumentType.word()).suggests(JAMMERS)
                                        .executes(c -> jammer(c, 1))))
                        .then(Commands.literal("off")
                                .then(Commands.argument("id", StringArgumentType.word()).suggests(JAMMERS)
                                        .executes(c -> jammer(c, 0))))
                        .then(Commands.literal("toggle")
                                .then(Commands.argument("id", StringArgumentType.word()).suggests(JAMMERS)
                                        .executes(c -> jammer(c, 2)))))
                .then(Commands.literal("rescan").requires(GpsCommands::admin)
                        .then(Commands.argument("regiao", StringArgumentType.word()).suggests(REGIONS)
                                .executes(GpsCommands::rescan)))
                .then(Commands.literal("reload").requires(GpsCommands::admin).executes(GpsCommands::reload))
                .then(Commands.literal("list").requires(GpsCommands::admin).executes(GpsCommands::list)));
    }

    private static int trigger(CommandContext<CommandSourceStack> c) {
        String id = StringArgumentType.getString(c, "id");
        CommandSourceStack src = c.getSource();
        String who = src.getEntity() instanceof ServerPlayer sp ? sp.getName().getString() : "comando";
        int result = RegionManager.runCommandTrigger(src.getServer(), id, who);
        if (result == -2) {
            src.sendFailure(Component.literal("Gatilho inexistente: " + id));
            return 0;
        }
        if (result == -1) {
            src.sendFailure(Component.literal("Este gatilho nao aceita comando (useCommand = false): " + id));
            return 0;
        }
        String msg = result > 0
                ? "Gatilho '" + id + "' executado (" + result + " alteracao(oes))."
                : "Gatilho '" + id + "' executado, mas nada mudou (ja estava nesse estado).";
        src.sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    private static int unlock(CommandContext<CommandSourceStack> c) {
        String id = StringArgumentType.getString(c, "regiao");
        boolean ok = RegionManager.unlock(c.getSource().getServer(), id);
        if (ok) c.getSource().sendSuccess(() -> Component.literal("Regiao liberada: " + id), true);
        else c.getSource().sendFailure(Component.literal("Regiao inexistente ou ja liberada: " + id));
        return ok ? 1 : 0;
    }

    private static int lock(CommandContext<CommandSourceStack> c) {
        String id = StringArgumentType.getString(c, "regiao");
        boolean ok = RegionManager.lock(id);
        if (ok) c.getSource().sendSuccess(() -> Component.literal("Regiao bloqueada: " + id), true);
        else c.getSource().sendFailure(Component.literal("Regiao inexistente ou ja bloqueada: " + id));
        return ok ? 1 : 0;
    }

    /** mode: 1 = ligar, 0 = desligar, 2 = alternar. */
    private static int jammer(CommandContext<CommandSourceStack> c, int mode) {
        String id = StringArgumentType.getString(c, "id");
        if (RegionManager.findJammer(id) == null) {
            c.getSource().sendFailure(Component.literal("Bloqueador inexistente: " + id));
            return 0;
        }
        boolean active;
        if (mode == 2) {
            Boolean now = RegionManager.toggleJammer(id);
            active = now != null && now;
        } else {
            active = mode == 1;
            RegionManager.setJammer(id, active);
        }
        String msg = "Bloqueador '" + id + "' " + (active ? "ATIVADO" : "desativado");
        c.getSource().sendSuccess(() -> Component.literal(msg), true);
        return 1;
    }

    private static int rescan(CommandContext<CommandSourceStack> c) {
        String id = StringArgumentType.getString(c, "regiao");
        boolean ok = RegionManager.rescan(id);
        if (ok) c.getSource().sendSuccess(() -> Component.literal("Gerando o mapa de novo: " + id), true);
        else c.getSource().sendFailure(Component.literal("Regiao inexistente: " + id));
        return ok ? 1 : 0;
    }

    private static int reload(CommandContext<CommandSourceStack> c) {
        RegionManager.reload(c.getSource().getServer());
        c.getSource().sendSuccess(() -> Component.literal("Configuracao do GPS recarregada."), true);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> c) {
        for (String id : RegionManager.regionIds()) {
            GpsConfig.Region r = RegionManager.find(id);
            if (r == null) continue;
            String line = RegionManager.describe(r);
            c.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        for (String id : RegionManager.jammerIds()) {
            GpsConfig.Jammer j = RegionManager.findJammer(id);
            if (j == null) continue;
            String line = RegionManager.describe(j);
            c.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }
}
