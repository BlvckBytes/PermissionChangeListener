/*
 * MIT License
 *
 * Copyright (c) 2023 BlvckBytes
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package me.blvckbytes.permissionchangelistener;

import me.blvckbytes.bbreflect.IReflectionHelper;
import me.blvckbytes.bbreflect.RClass;
import me.blvckbytes.bbreflect.handle.ClassHandle;
import me.blvckbytes.bbreflect.handle.FieldHandle;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissibleBase;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

public class PermissionChangeListener implements Listener {

  // Ticks that need to elapse until the last modifying call is actually routed
  private static final long DEBOUNCE_TICKS = 10;

  private final Plugin plugin;

  // Vanilla references of the proxied field for every player
  private final Map<Player, Object> vanillaRefs;

  // The previous permission list (last permission change call) for every player
  private final Map<Player, List<String>> previousPermissions;

  private final FieldHandle F_CRAFT_PLAYER__PERMISSIBLE_BASE, F_PERMISSIBLE_BASE__PERMISSIONS;

  /**
   * Create a new listener for permission changes on the permissible base of all online
   * players. Do not forget to call {@link #cleanup} in {@link Plugin#onDisable}!
   * @param plugin Plugin reference
   * @param reflection Reflection helper reference
   * @throws Exception Errors when requiring necessary handles
   */
  public PermissionChangeListener(Plugin plugin, IReflectionHelper reflection) throws Exception {
    ClassHandle C_CRAFT_PLAYER = reflection.getClass(RClass.CRAFT_PLAYER);
    ClassHandle C_PERMISSIBLE_BASE = ClassHandle.of(PermissibleBase.class, reflection.getVersion());

    F_CRAFT_PLAYER__PERMISSIBLE_BASE = C_CRAFT_PLAYER.locateField()
      .withType(C_PERMISSIBLE_BASE)
      .withAllowSuperclass(true)
      .required();

    F_PERMISSIBLE_BASE__PERMISSIONS  = C_PERMISSIBLE_BASE.locateField()
      .withType(Map.class)
      .withGeneric(String.class)
      .withGeneric(PermissionAttachmentInfo.class)
      .required();

    this.plugin = plugin;
    this.vanillaRefs = new HashMap<>();
    this.previousPermissions = new HashMap<>();

    for (Player t : Bukkit.getOnlinePlayers())
      proxyPermissions(t);

    Bukkit.getServer().getPluginManager().registerEvents(this, plugin);
  }

  //=========================================================================//
  //                                   API                                   //
  //=========================================================================//

  public void cleanup() {
    for (Player t : Bukkit.getOnlinePlayers())
      unproxyPermissions(t);
  }

  //=========================================================================//
  //                                Listeners                                //
  //=========================================================================//

  @EventHandler(priority = EventPriority.LOWEST)
  public void onLogin(PlayerLoginEvent e) {
    proxyPermissions(e.getPlayer());
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void onQuit(PlayerQuitEvent e) {
    unproxyPermissions(e.getPlayer());
  }

  //=========================================================================//
  //                                Utilities                                //
  //=========================================================================//

  /**
   * Fire the {@link PlayerPermissionsChangedEvent} after diffing the player's permission list
   * @param p Target player
   * @param permissions List of currently active permissions
   */
  private void fireEvent(Player p, List<String> permissions) {
    List<String> added = new ArrayList<>();
    List<String> removed = new ArrayList<>();
    List<String> previous = previousPermissions.getOrDefault(p, new ArrayList<>());

    for (String prev : previous) {
      // This permission was owned previously but is missing now
      if (!permissions.contains(prev))
        removed.add(prev);
    }

    for (String curr : permissions) {
      // This permission wasn't owned previously and thus has been added
      if (!previous.contains(curr))
        added.add(curr);
    }

    Bukkit.getPluginManager().callEvent(
      new PlayerPermissionsChangedEvent(p, permissions, added, removed)
    );
  }

  /**
   * Permission change handler, called whenever a player's permissions change
   * @param p Target player
   * @param permissions List of currently active permissions
   */
  private void onPermissionChange(Player p, List<String> permissions) {
    // Handle firing the delta event
    fireEvent(p, permissions);

    // Save these permissions as the previous state
    previousPermissions.put(p, permissions);
  }

  /**
   * Unproxy the permissions field for a given player by reverting
   * back to the vanilla reference
   * @param p Target player
   */
  private void unproxyPermissions(Player p) {
    // Get the vanilla reference from the local map, skip non-proxied players
    Object vanillaRef = vanillaRefs.get(p);
    if (vanillaRef == null)
      return;

    // Restore the vanilla reference
    try {
      Object permissibleBase = F_CRAFT_PLAYER__PERMISSIBLE_BASE.get(p);

      // Set field to the proxy reference
      F_PERMISSIBLE_BASE__PERMISSIONS.set(permissibleBase, vanillaRef);

      vanillaRefs.remove(p);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  /**
   * Get a list of active permissions from a map of attachments
   * @param permissions Map of attachments
   * @return List of active permissions
   */
  private List<String> getPermissions(Map<String, PermissionAttachmentInfo> permissions) {
    return permissions.values()
      .stream()
      .filter(PermissionAttachmentInfo::getValue)
      .map(PermissionAttachmentInfo::getPermission)
      .collect(Collectors.toList());
  }

  /**
   * Create a new permission-field proxy for a given player
   * @param p Target player
   * @param permissions Vanilla map
   * @return New map that can replace the vanilla list value
   */
  private Object createPermissionProxy(Player p, Map<String, PermissionAttachmentInfo> permissions) {
    // Create a new proxied map
    return Proxy.newProxyInstance(
      permissions.getClass().getClassLoader(),
      new Class[]{ Map.class },

      // Create an anonymous implementation here, since it's pretty basic and too specific
      new InvocationHandler() {

        // Handle of the debounce task used to debounce map call bursts
        private BukkitTask debounceTask = null;
        private long debounceCreation = 0;

        // Lock to synchronize map calls (as I don't know all possible callers)
        private final ReentrantLock lock = new ReentrantLock();

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
          // Only intercept on adding items (done after clearing when recalculating recursively)
          if (!method.getName().equals("put"))
            return method.invoke(permissions, args);

          if (
            // No debounce task created yet
            debounceTask == null ||
            // Or the previously created task has reached more than half of it's lifespan
            System.currentTimeMillis() - debounceCreation > (DEBOUNCE_TICKS * (1000 / 20 / 2))
          ) {
            // Lock while operating on the local debounce task handle
            lock.lock();

            // Cancel the previous debounce task
            if (debounceTask != null) {
              debounceTask.cancel();
              debounceTask = null;
            }

            // Create a new debounce task
            debounceCreation = System.currentTimeMillis();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
              onPermissionChange(p, getPermissions(permissions));
              debounceTask = null;
            }, DEBOUNCE_TICKS);

            // Done with operations, unlock
            lock.unlock();
          }

          return method.invoke(permissions, args);
        }
      }
    );
  }

  /**
   * Proxy the permissions field of a given player by setting a
   * read-only (non-modifying) proxy on the permissions map
   * @param p Target player
   */
  @SuppressWarnings("unchecked")
  private void proxyPermissions(Player p) {
    try {
      Object permissibleBase = F_CRAFT_PLAYER__PERMISSIBLE_BASE.get(p);
      Map<String, PermissionAttachmentInfo> perms = (Map<String, PermissionAttachmentInfo>) F_PERMISSIBLE_BASE__PERMISSIONS.get(permissibleBase);

      // Set field to the proxy reference
      F_PERMISSIBLE_BASE__PERMISSIONS.set(permissibleBase, createPermissionProxy(p, perms));

      // Save the vanilla reference
      this.vanillaRefs.put(p, perms);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}
