package com.example.freezemod;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class FreezeMod implements ModInitializer {
    public static final Map<UUID, Vec3d> FROZEN_PLAYERS = new HashMap<>();
    public static final Set<UUID> BLACKLISTED_PLAYERS = new HashSet<>();

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            registerCommands(dispatcher);
        });

        // Keep frozen players locked at their frozen coordinates every tick
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);

        // Prevent frozen players from breaking, placing, or attacking
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) ->
            isFrozen(player.getUuid()) ? ActionResult.FAIL : ActionResult.PASS);
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) ->
            isFrozen(player.getUuid()) ? ActionResult.FAIL : ActionResult.PASS);
        UseItemCallback.EVENT.register((player, world, hand) ->
            isFrozen(player.getUuid()) ? ActionResult.FAIL : ActionResult.PASS);
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) ->
            isFrozen(player.getUuid()) ? ActionResult.FAIL : ActionResult.PASS);
    }

    public static boolean isFrozen(UUID uuid) {
        return FROZEN_PLAYERS.containsKey(uuid);
    }

    private void onServerTick(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            Vec3d lockedPos = FROZEN_PLAYERS.get(player.getUuid());
            if (lockedPos != null) {
                if (player.getX() != lockedPos.x || player.getY() != lockedPos.y || player.getZ() != lockedPos.z) {
                    player.networkHandler.requestTeleport(lockedPos.x, lockedPos.y, lockedPos.z, player.getYaw(), player.getPitch());
                }
            }
        }
    }

    private void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("freeze")
            .requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))
            .then(CommandManager.literal("all")
                .executes(ctx -> {
                    int count = 0;
                    for (ServerPlayerEntity player : ctx.getSource().getServer().getPlayerManager().getPlayerList()) {
                        boolean isAdmin = CommandManager.GAMEMASTERS_CHECK.allows(player.getCommandSource().getPermissions());
                        if (!isAdmin && !BLACKLISTED_PLAYERS.contains(player.getUuid())) {
                            FROZEN_PLAYERS.put(player.getUuid(), new Vec3d(player.getX(), player.getY(), player.getZ()));
                            player.sendMessage(Text.literal("You have been frozen by an admin."), false);
                            count++;
                        }
                    }
                    int frozenCount = count;
                    ctx.getSource().sendFeedback(() -> Text.literal("Froze " + frozenCount + " player(s)."), true);
                    return 1;
                }))
            .then(CommandManager.literal("blacklist")
                .then(CommandManager.literal("add")
                    .then(CommandManager.argument("player", EntityArgumentType.player())
                        .executes(ctx -> {
                            ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
                            BLACKLISTED_PLAYERS.add(target.getUuid());
                            FROZEN_PLAYERS.remove(target.getUuid());
                            ctx.getSource().sendFeedback(
                                () -> Text.literal("Added " + target.getName().getString() + " to the freeze blacklist."),
                                true
                            );
                            return 1;
                        })))
                .then(CommandManager.literal("remove")
                    .then(CommandManager.argument("player", EntityArgumentType.player())
                        .executes(ctx -> {
                            ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
                            if (BLACKLISTED_PLAYERS.remove(target.getUuid())) {
                                ctx.getSource().sendFeedback(
                                    () -> Text.literal("Removed " + target.getName().getString() + " from the freeze blacklist."),
                                    true
                                );
                            } else {
                                ctx.getSource().sendError(Text.literal(target.getName().getString() + " is not on the freeze blacklist."));
                            }
                            return 1;
                        }))))
            .then(CommandManager.argument("player", EntityArgumentType.player())
                .executes(ctx -> {
                    ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
                    if (BLACKLISTED_PLAYERS.contains(target.getUuid())) {
                        ctx.getSource().sendError(Text.literal(target.getName().getString() + " is on the freeze blacklist and cannot be frozen."));
                        return 0;
                    }
                    FROZEN_PLAYERS.put(target.getUuid(), new Vec3d(target.getX(), target.getY(), target.getZ()));
                    target.sendMessage(Text.literal("You have been frozen by an admin."), false);
                    ctx.getSource().sendFeedback(() -> Text.literal("Froze " + target.getName().getString() + "."), true);
                    return 1;
                }))
        );

        dispatcher.register(CommandManager.literal("unfreeze")
            .requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))
            .then(CommandManager.literal("all")
                .executes(ctx -> {
                    int count = FROZEN_PLAYERS.size();
                    for (UUID uuid : FROZEN_PLAYERS.keySet()) {
                        ServerPlayerEntity player = ctx.getSource().getServer().getPlayerManager().getPlayer(uuid);
                        if (player != null) {
                            player.sendMessage(Text.literal("You have been unfrozen."), false);
                        }
                    }
                    FROZEN_PLAYERS.clear();
                    ctx.getSource().sendFeedback(() -> Text.literal("Unfroze " + count + " player(s)."), true);
                    return 1;
                }))
            .then(CommandManager.argument("player", EntityArgumentType.player())
                .executes(ctx -> {
                    ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
                    if (FROZEN_PLAYERS.remove(target.getUuid()) != null) {
                        target.sendMessage(Text.literal("You have been unfrozen."), false);
                        ctx.getSource().sendFeedback(() -> Text.literal("Unfroze " + target.getName().getString() + "."), true);
                        return 1;
                    } else {
                        ctx.getSource().sendError(Text.literal(target.getName().getString() + " is not frozen."));
                        return 0;
                    }
                }))
        );
    }
}
