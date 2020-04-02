package de.minebench.ianua;

/*
 * Ianua
 * Copyright (c) 2019 Max Lee aka Phoenix616 (mail@moep.tv)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class Ianua extends JavaPlugin implements Listener {

    private final static Set<Material> PORTAL_BLOCKS = EnumSet.of(Material.WATER, Material.LAVA, Material.SUGAR_CANE);
    private static final String NO_PORTAL = "IANUA:NO_PORTAL";
    private final Cache<Portal, String> portalCache = CacheBuilder.newBuilder().expireAfterAccess(10, TimeUnit.MINUTES).build();
    private final Map<UUID, Long> portalCooldowns = new HashMap<>();
    private int portalDistance = 3;
    private long portalCooldown = 1000;
    private boolean portalTurnPlayer;
    private boolean blockMessages;
    private String noPermission;
    private String noServerPermission;
    private String signIdentifier;

    @Override
    public void onEnable() {
        loadConfig();
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.AQUA + getName() + " v" + getDescription().getVersion());
            return true;
        } else if ("reload".equalsIgnoreCase(args[0])) {
            loadConfig();
            portalCache.invalidateAll();
            sender.sendMessage(ChatColor.YELLOW + getName() + " reloaded!");
            return true;
        } else if ("info".equalsIgnoreCase(args[0])) {
            sender.sendMessage(new String[]{
                    ChatColor.YELLOW + "Info:",
                    ChatColor.AQUA + " portalCache size: " + ChatColor.YELLOW + portalCache.size(),
                    ChatColor.YELLOW + "Config:",
                    ChatColor.AQUA + " portalTurnPlayer: " + ChatColor.YELLOW + portalTurnPlayer,
                    ChatColor.AQUA + " portalDistance: " + ChatColor.YELLOW + portalDistance,
                    ChatColor.AQUA + " blockMessages: " + ChatColor.YELLOW + blockMessages,
                    ChatColor.AQUA + " noPermission: " + ChatColor.YELLOW + noPermission,
                    ChatColor.AQUA + " noServerPermission: " + ChatColor.YELLOW + noServerPermission,
                    ChatColor.AQUA + " signIdentifier: " + ChatColor.YELLOW + signIdentifier
            });
            return true;
        }
        return false;
    }

    private void loadConfig() {
        saveDefaultConfig();
        reloadConfig();

        portalTurnPlayer = getConfig().getBoolean("portalTurnPlayer");
        portalDistance = getConfig().getInt("portalDistance");
        if (portalDistance < 0) portalDistance = 0;
        portalCooldown = getConfig().getLong("portalCooldown");
        if (portalCooldown < 0) portalCooldown = 0;
        blockMessages = getConfig().getBoolean("blockMessages");
        noPermission = ChatColor.translateAlternateColorCodes('&', getConfig().getString("lang.noPermission"));
        noServerPermission = ChatColor.translateAlternateColorCodes('&', getConfig().getString("lang.noServerPermission"));
        signIdentifier = getConfig().getString("signIdentifier").toLowerCase();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (blockMessages) {
            event.setJoinMessage(null);
        }
        if (findPortal(event.getPlayer().getLocation().getBlock()) != null) {
            teleportOutOfPortal(event.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void on(SignChangeEvent e) {
        if (e.getLine(0).equalsIgnoreCase("[" + signIdentifier + "]")) {
            if (!e.getPlayer().hasPermission("ianua.create")){
                e.setCancelled(true);
                e.getPlayer().sendMessage(ChatColor.RED + "You don't have the permission to create server portals!");
            } else {
                e.getPlayer().sendMessage(ChatColor.AQUA + "Created sign to server " + e.getLine(1));
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void on(PlayerMoveEvent e) {
        if (e.getTo() == null
                || e.getFrom().getBlockY() == e.getTo().getBlockY()
                && e.getFrom().getBlockX() == e.getTo().getBlockX()
                && e.getFrom().getBlockZ() == e.getTo().getBlockZ()) {
            return;
        }

        Block block = e.getTo().getBlock();
        if (!PORTAL_BLOCKS.contains(block.getType())) {
            return;
        } else if (PORTAL_BLOCKS.contains(e.getFrom().getBlock().getType())) {
            return;
        }

        handlePortal(e.getPlayer(), block, e);
    }

    @EventHandler(ignoreCancelled = true)
    public void on(PlayerPortalEvent e) {
        handlePortal(e.getPlayer(), e.getFrom().getBlock(), e);
    }

    @EventHandler
    public void on(PlayerQuitEvent e) {
        portalCooldowns.remove(e.getPlayer().getUniqueId());
    }

    private void handlePortal(Player player, Block block, Cancellable e) {
        String server = findPortal(block);
        if (server == null) {
            return;
        }
        e.setCancelled(true);

        if (portalCooldowns.getOrDefault(player.getUniqueId(), System.currentTimeMillis()) + portalCooldown > System.currentTimeMillis()) {
            return;
        }
        portalCooldowns.put(player.getUniqueId(), System.currentTimeMillis());

        teleportOutOfPortal(player);

        if (!player.hasPermission("ianua.use")) {
            player.sendMessage(noPermission);
            return;
        }

        if (!player.hasPermission("ianua.use." + server)) {
            player.sendMessage(noServerPermission.replace("%server%", server));
            return;
        }

        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(b);
        try {
            out.writeUTF("Connect");
            out.writeUTF(server);
            player.sendPluginMessage(this, "BungeeCord", b.toByteArray());
        } catch (IOException ex) {
            // Impossible
        }
    }

    private String findPortal(Block block) {
        Portal portal = new Portal(block);
        String server = portalCache.getIfPresent(portal);
        if (server != null && server.equals(NO_PORTAL)) {
            return null;
        } else if (server == null) {
            do {
                block = block.getRelative(BlockFace.DOWN);
            } while (PORTAL_BLOCKS.contains(block.getType()) && block.getY() > 1);
            block = block.getRelative(BlockFace.DOWN);
            BlockState state = block.getState();
            if (state instanceof Sign && ((Sign) state).getLine(0).equalsIgnoreCase("[" + signIdentifier + "]")) {
                server = ((Sign) state).getLine(1);
            }
        }

        if (server == null || server.isEmpty()) {
            portalCache.put(portal, NO_PORTAL);
            return null;
        }
        return server;
    }

    private void teleportOutOfPortal(Player player) {
        if (portalDistance == 0) {
            return;
        }
        Location location = player.getLocation();
        float originalPitch = location.getPitch();
        location.setPitch(0);
        Vector vec = location.getDirection().normalize().multiply(portalDistance);
        location = location.add(vec.multiply(-1));
        location.setPitch(originalPitch);
        if (portalTurnPlayer) {
            float yaw = location.getYaw();
            if ((yaw += 180) > 360) {
                yaw -= 360;
            }
            location.setYaw(yaw);
        }
        player.teleport(location, PlayerTeleportEvent.TeleportCause.PLUGIN);
    }

    private class Portal {
        private final UUID worldId;
        private final int x;
        private final int y;
        private final int z;

        private Portal(UUID worldId, int x, int y, int z) {
            this.worldId = worldId;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public Portal(Block block) {
            this(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }

        @Override
        public int hashCode() {
            int hash = worldId.hashCode();
            hash = hash * 31 + x;
            hash = hash * 31 + y;
            return hash * 31 + z;
        }
    }
}
