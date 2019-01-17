package buttondevteam.lib.architecture;

import buttondevteam.lib.TBMCCoreAPI;
import buttondevteam.lib.chat.TBMCChatAPI;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.experimental.var;
import org.bukkit.plugin.java.JavaPlugin;

public abstract class ButtonPlugin extends JavaPlugin {
	@Getter(AccessLevel.PROTECTED)
	private IHaveConfig iConfig;

	protected abstract void pluginEnable();

	protected abstract void pluginDisable();

	@Override
	public final void onEnable() {
		var section = super.getConfig().getConfigurationSection("global");
		if (section == null) section = super.getConfig().createSection("global");
		iConfig = new IHaveConfig(section);
		try {
			pluginEnable();
		} catch (Exception e) {
			TBMCCoreAPI.SendException("Error while enabling plugin " + getName() + "!", e);
		}
	}

	@Override
	public final void onDisable() {
		try {
			pluginDisable();
			saveConfig();
			iConfig = null; //Clearing the hashmap is not enough, we need to update the section as well
			TBMCChatAPI.RemoveCommands(this);
		} catch (Exception e) {
			TBMCCoreAPI.SendException("Error while disabling plugin " + getName() + "!", e);
		}
	}
}
