# PermissionChangeListener

This little library hooks into the `PermissibleBase` of all online players in order to detect
permission changes and then broadcast them to all consumers using the `PlayerPermissionsChangedEvent`.

## Usage

```java
import me.blvckbytes.bbreflect.ReflectionHelper;
import me.blvckbytes.bbreflect.version.ServerVersion;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public class ExampleUsage extends JavaPlugin implements Listener {

  private PermissionChangeListener changeListener;

  @Override
  public void onEnable() {
    try {
      ReflectionHelper rh = new ReflectionHelper(ServerVersion.current());
      changeListener = new PermissionChangeListener(this, rh);

      getServer().getPluginManager().registerEvents(this, this);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void onDisable() {
    if (changeListener != null)
      changeListener.cleanup();
  }

  @EventHandler
  public void onPermissionChange(PlayerPermissionsChangedEvent e) {
    List<String> added = e.getAdded(), removed = e.getRemoved(), active = e.getActive();
    Player player = e.getPlayer();

    // TODO: React to the event
  }
}
```