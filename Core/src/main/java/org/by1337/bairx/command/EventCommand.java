package org.by1337.bairx.command;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.by1337.bairx.BAirDropX;
import org.by1337.bairx.airdrop.AirDrop;
import org.by1337.bairx.timer.Timer;
import org.by1337.blib.command.CommandException;
import org.by1337.blib.util.Pair;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class EventCommand implements TabExecutor {
    private final org.by1337.blib.command.Command<CommandSender> command;

    public EventCommand() {
        this.command = createEventCommand();
    }

    private org.by1337.blib.command.Command<CommandSender> createEventCommand() {
        org.by1337.blib.command.Command<CommandSender> eventCommand =
                new org.by1337.blib.command.Command<>("event");

        eventCommand.executor(((sender, args) -> {
            for (String line : BAirDropX.getCfg().getList("event.command.messages.help", String.class)) {
                BAirDropX.getMessage().sendMsg(sender, line);
            }
        }));

        eventCommand.addSubCommand(
                new org.by1337.blib.command.Command<CommandSender>("delay")
                        .executor(((sender, args) -> {
                            AirDrop activeAirDrop = getActiveAirDrop();

                            if (activeAirDrop != null) {
                                List<String> activeMessages = BAirDropX.getCfg().getList("event.command.messages.delay_active", String.class);
                                if (!activeMessages.isEmpty()) {
                                    String message = activeMessages.get(0)
                                            .replace("%name%", activeAirDrop.getAirName());
                                    BAirDropX.getMessage().sendMsg(sender, message);
                                }
                                if (activeMessages.size() > 1) {
                                    String message = activeMessages.get(1)
                                            .replace("%name%", activeAirDrop.getAirName());
                                    BAirDropX.getMessage().sendMsg(sender, message);
                                }
                            } else {
                                Pair<AirDrop, Long> nearestPair = getNearestAirDrop();
                                if (nearestPair == null) {
                                    BAirDropX.getMessage().sendMsg(sender,
                                            BAirDropX.getCfg().getAsString("event.command.messages.delay_no_airdrops"));
                                } else {
                                    List<String> messages = BAirDropX.getCfg().getList("event.command.messages.delay", String.class);
                                    String formattedTime = formatTime(nearestPair.getRight());

                                    if (!messages.isEmpty()) {
                                        String message = messages.get(0)
                                                .replace("%time%", formattedTime)
                                                .replace("%name%", nearestPair.getLeft() != null ? nearestPair.getLeft().getAirName() : "?");
                                        BAirDropX.getMessage().sendMsg(sender, message);
                                    }
                                    if (nearestPair.getLeft() != null && messages.size() > 1) {
                                        String message = messages.get(1)
                                                .replace("%time%", formattedTime)
                                                .replace("%name%", nearestPair.getLeft().getAirName());
                                        BAirDropX.getMessage().sendMsg(sender, message);
                                    }
                                }
                            }
                        }))
        );

        eventCommand.addSubCommand(
                new org.by1337.blib.command.Command<CommandSender>("gps")
                        .executor(((sender, args) -> {
                            if (!(sender instanceof Player player)) {
                                BAirDropX.getMessage().sendMsg(sender,
                                        BAirDropX.getCfg().getAsString("event.command.messages.gps_player_only"));
                                return;
                            }

                            AirDrop nearestAirDrop = getNearestActiveAirDrop(player);

                            if (nearestAirDrop == null || nearestAirDrop.getLocation() == null) {
                                BAirDropX.getMessage().sendMsg(player,
                                        BAirDropX.getCfg().getAsString("event.command.messages.gps_no_active"));
                                return;
                            }

                            if (!nearestAirDrop.isStarted()) {
                                BAirDropX.getMessage().sendMsg(player,
                                        BAirDropX.getCfg().getAsString("event.command.messages.gps_not_started"));
                                return;
                            }

                            Location airDropLocation = nearestAirDrop.getLocation();
                            Location playerLocation = player.getLocation();

                            double distance = -1;
                            if (playerLocation.getWorld().equals(airDropLocation.getWorld())) {
                                distance = playerLocation.distance(airDropLocation);
                            }

                            List<String> gpsMessages = BAirDropX.getCfg().getList("event.command.messages.gps", String.class);
                            for (String line : gpsMessages) {
                                String message = line
                                        .replace("%name%", nearestAirDrop.getAirName())
                                        .replace("%x%", String.valueOf(airDropLocation.getBlockX()))
                                        .replace("%y%", String.valueOf(airDropLocation.getBlockY()))
                                        .replace("%z%", String.valueOf(airDropLocation.getBlockZ()))
                                        .replace("%world%", getWorldDisplayName(airDropLocation.getWorld()))
                                        .replace("%distance%", formatDistance(distance));
                                BAirDropX.getMessage().sendMsg(player, message);
                            }
                        }))
        );

        return eventCommand;
    }

    private AirDrop getActiveAirDrop() {
        for (AirDrop airDrop : BAirDropX.getAirDrops()) {
            if (airDrop.isStarted()) {
                return airDrop;
            }
        }
        return null;
    }

    private Pair<AirDrop, Long> getNearestAirDrop() {
        Pair<AirDrop, Long> nearestPair = null;

        for (Timer timer : BAirDropX.getInstance().getTimerManager().getTimers()) {
            var pair = timer.getNearest();
            if (pair != null && pair.getRight() > 0) {
                if (nearestPair == null || pair.getRight() < nearestPair.getRight()) {
                    nearestPair = pair;
                }
            }
        }

        for (AirDrop airDrop : BAirDropX.getAirDrops()) {
            if (airDrop.isUseDefaultTimer() && airDrop.getToSpawn() > 0) {
                long timeToSpawn = airDrop.getToSpawn() * 50L;
                if (nearestPair == null || timeToSpawn < nearestPair.getRight()) {
                    nearestPair = Pair.of(airDrop, timeToSpawn);
                }
            }
        }

        return nearestPair;
    }

    private AirDrop getNearestActiveAirDrop(Player player) {
        AirDrop nearestAirDrop = null;
        double nearestDistance = Double.MAX_VALUE;
        Location playerLoc = player.getLocation();

        for (AirDrop airDrop : BAirDropX.getAirDrops()) {
            if (airDrop.isStarted() && airDrop.getLocation() != null) {
                Location airDropLoc = airDrop.getLocation();

                if (airDropLoc.getWorld().equals(playerLoc.getWorld())) {
                    double distance = airDropLoc.distance(playerLoc);
                    if (distance < nearestDistance) {
                        nearestDistance = distance;
                        nearestAirDrop = airDrop;
                    }
                } else {
                    if (nearestAirDrop == null) {
                        nearestAirDrop = airDrop;
                    }
                }
            }
        }
        return nearestAirDrop;
    }

    private String formatDistance(double distance) {
        if (distance < 0) {
            return "?";
        } else {
            return String.format("%.0f", distance);
        }
    }

    private String formatTime(long milliseconds) {
        long seconds = milliseconds / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;

        if (hours > 0) {
            return String.format(
                    BAirDropX.getCfg().getAsString("event.command.settings.time_format.hours"),
                    hours, minutes % 60, seconds % 60
            );
        } else if (minutes > 0) {
            return String.format(
                    BAirDropX.getCfg().getAsString("event.command.settings.time_format.minutes"),
                    minutes, seconds % 60
            );
        } else {
            return String.format(
                    BAirDropX.getCfg().getAsString("event.command.settings.time_format.seconds"),
                    seconds
            );
        }
    }

    private String getWorldDisplayName(World world) {
        String worldName = world.getName();
        String customName = BAirDropX.getCfg().getAsString("event.command.settings.world_names." + worldName);

        if (customName != null) {
            return customName;
        }

        return BAirDropX.getCfg().getAsString("event.command.settings.default_world_name")
                .replace("%world%", worldName);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        try {
            this.command.process(sender, args);
        } catch (CommandException e) {
            BAirDropX.getMessage().sendMsg(sender, e.getMessage());
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        return this.command.getTabCompleter(sender, args);
    }
}
