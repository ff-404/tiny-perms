package com.extclp.tinyperms;

import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.CommandNode;
import me.lucko.fabric.api.permissions.v0.PermissionCheckEvent;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static com.mojang.brigadier.arguments.BoolArgumentType.bool;
import static com.mojang.brigadier.arguments.BoolArgumentType.getBool;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.string;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class TinyPerms implements ModInitializer {

    static List<Group> groups = new ArrayList<>();

    @Override
    public void onInitialize() {
        loadGroups();
        PermissionCheckEvent.EVENT.register((s, permission) -> {
            var source = (ServerCommandSource) s;
            var player = source.getPlayer();
            if (player == null) {
                return TriState.DEFAULT;
            }
            for (Group group : groups) {
                boolean operator = source.getServer().getPlayerManager().isOperator(player.getPlayerConfigEntry());
                if (group.name.equals("default") && !operator || group.name.equals("admin") && operator || group.users.contains(player.getStringifiedName())) {
                    var tri = TriState.of(group.permissions.get(permission));
                    if (tri != TriState.DEFAULT) {
                        return tri;
                    }

                }
            }
            return TriState.DEFAULT;
        });

        SuggestionProvider<ServerCommandSource> suggestGroup = (context, builder) -> {
            String remaining = builder.getRemaining();
            for (Group group : groups) {
                if (group.name.startsWith(remaining)) {
                    builder.suggest(group.name);
                }
            }
            return builder.buildFuture();
        };
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("perms").requires(CommandManager.requirePermissionLevel(CommandManager.ADMINS_CHECK))
                .then(literal("group").then(argument("group", string()).suggests(suggestGroup)
                    .then(literal("create").executes(context -> {
                        String name = getString(context, "group");
                        Optional<Group> group = getGroup(name);
                        if (group.isPresent()) {
                            throw new SimpleCommandExceptionType(Text.of("已存在")).create();
                        }
                        groups.add(new Group(name));
                        saveGroups();
                        context.getSource().sendMessage(Text.of("已添加"));
                        return 1;
                    }))
                    .then(literal("delete").executes(context -> {
                        Optional<Group> group = getGroup(getString(context, "group"));
                        if (group.isEmpty()) {
                            throw new SimpleCommandExceptionType(Text.of("不存在")).create();
                        }
                        groups.remove(group.get());
                        saveGroups();
                        context.getSource().sendMessage(Text.of("已删除"));
                        return 1;
                    }))
                    .then(literal("user")
                        .then(literal("add").then(argument("user", string()).suggests((context, builder) -> {
                            ServerCommandSource source = context.getSource();
                            Optional<Group> group = getGroup(getString(context, "group"));
                            if (group.isPresent()) {
                                String remaining = builder.getRemaining();
                                for (ServerPlayerEntity player : source.getServer().getPlayerManager().getPlayerList()) {
                                    if (group.get().users.contains(player.getStringifiedName())) {
                                        continue;
                                    }
                                    if (player.getStringifiedName().startsWith(remaining)) {
                                        builder.suggest(player.getStringifiedName());
                                    }
                                }
                            }

                            return builder.buildFuture();
                        }).executes(context -> {
                            Optional<Group> group = getGroup(getString(context, "group"));
                            if (group.isEmpty()) {
                                throw new SimpleCommandExceptionType(Text.of("权限组不存在")).create();
                            }
                            String user = getString(context, "user");

                            group.get().users.add(user);
                            saveGroups();
                            context.getSource().sendMessage(Text.of("已添加"));
                            return 1;
                        })))
                        .then(literal("remove").then(argument("user", string()).suggests((context, builder) -> {
                            Optional<Group> group = getGroup(getString(context, "group"));
                            if (group.isPresent()) {
                                String remaining = builder.getRemaining();
                                for (var user : group.get().users) {
                                    if (user.startsWith(remaining)) {
                                        builder.suggest(user);
                                    }
                                }
                            }
                            return builder.buildFuture();
                        }).executes(context -> {

                            Optional<Group> group = getGroup(getString(context, "group"));
                            if (group.isEmpty()) {
                                throw new SimpleCommandExceptionType(Text.of("权限组不存在")).create();
                            }
                            String user = getString(context, "user");

                            group.get().users.remove(user);
                            saveGroups();
                            context.getSource().sendMessage(Text.of("已删除"));
                            return 1;
                        }))))
                    .then(literal("permission")
                        .then(literal("set").then(argument("permission", string()).suggests((context, builder) -> {
                            String remaining = builder.getRemaining();

                            var prefix = "minecraft.command.";
                            if (!remaining.startsWith(prefix)) {
                                List<String> suggests = new ArrayList<>();
                                for (CommandNode<ServerCommandSource> child : dispatcher.getRoot().getChildren()) {
                                    suggests.add(prefix + child.getName());
                                }
                                for (String suggest : suggests) {
                                    if (suggest.startsWith(remaining)) {
                                        builder.suggest(suggest);
                                    }
                                }
                                return builder.buildFuture();
                            }
                            String[] nodes = remaining.substring(prefix.length()).split("\\.", -1);

                            var currentNode = "";
                            CommandNode<?> parent = null;
                            CommandNode<?> current = dispatcher.getRoot();
                            for (String node : nodes) {
                                parent = current;
                                current = current.getChild(node);
                                currentNode = node;
                                if (current == null) {
                                    break;
                                }
                            }
                            if (nodes[nodes.length - 1].equals(currentNode)) {
                                var str = builder.getRemaining();
                                if (!str.endsWith(".") && str.contains(".")) {
                                    str = str.substring(0, str.lastIndexOf('.') + 1);
                                }
                                for (CommandNode<?> child : parent.getChildren()) {
                                    if (child.getName().startsWith(currentNode)) {
                                        builder.suggest(str + child.getName());
                                    }
                                }
                            }
                            return builder.buildFuture();
                        }).then(argument("value", bool()).executes(context -> {
                            var source = context.getSource();

                            String groupName = getString(context, "group");
                            String permission = getString(context, "permission");
                            var value = getBool(context, "value");

                            var group = getGroup(groupName);
                            if (group.isEmpty()) {
                                throw new SimpleCommandExceptionType(Text.of("不存在")).create();
                            }
                            group.get().permissions.put(permission, value);
                            saveGroups();
                            source.sendMessage(Text.of("设置权限成功"));

                            return 1;
                        }))))
                        .then(literal("unset").then(argument("permission", string()).suggests((context, builder) -> {
                            String groupName = getString(context, "group");
                            var group = getGroup(groupName);
                            if (group.isPresent()) {
                                for (String permission : group.get().permissions.keySet()) {
                                    builder.suggest(permission);
                                }
                            }

                            return builder.buildFuture();
                        }).executes(context -> {
                            var source = context.getSource();

                            String groupName = getString(context, "group");
                            String permission = getString(context, "permission");

                            var group = getGroup(groupName);
                            if (group.isEmpty()) {
                                throw new SimpleCommandExceptionType(Text.of("不存在")).create();
                            }
                            group.get().permissions.remove(permission);
                            saveGroups();
                            source.sendMessage(Text.of("移除权限成功"));
                            return 1;
                        })))
                    ))));
        });
    }

    static void loadGroups() {
        Path file = Paths.get("config/permission.json");
        if (!Files.exists(file)) {
            groups = new ArrayList<>();
            groups.add(new Group("default"));
            groups.add(new Group("admin"));
            saveGroups();
            return;
        }
        try {
            String jsonString = Files.readString(file);
            Gson gson = new Gson();
            groups = Lists.newArrayList(gson.fromJson(jsonString, Group[].class));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static void saveGroups() {
        try {
            Gson gson = new Gson();
            Files.writeString(Paths.get("config/permission.json"), gson.toJson(groups));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static Optional<Group> getGroup(String groupName) {
        return groups.stream()
            .filter(group -> group.name.equals(groupName))
            .findFirst();
    }

    record Group(String name, List<String> users, Map<String, Boolean> permissions) {

        public Group(String name) {
            this(name, new ArrayList<>(), new HashMap<>());
        }
    }
}