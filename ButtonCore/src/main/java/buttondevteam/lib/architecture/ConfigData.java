package buttondevteam.lib.architecture;

import buttondevteam.core.MainPlugin;
import buttondevteam.lib.ThorpeUtils;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Array;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Use the getter/setter constructor if {@link T} isn't a primitive type or String.<br>
 *     Use {@link Component#getConfig()} or {@link ButtonPlugin#getIConfig()} then {@link IHaveConfig#getData(String, Object)} to get an instance.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
//@AllArgsConstructor(access = AccessLevel.PACKAGE)
public class ConfigData<T> {
	private static final HashMap<Configuration, SaveTask> saveTasks = new HashMap<>();
	/**
	 * May be null for testing
	 */
	private final ConfigurationSection config;
	@Getter
	private final String path;
	private final T def;
	private final Object primitiveDef;
	private final Runnable saveAction;
	/**
	 * The parameter is of a primitive type as returned by {@link YamlConfiguration#get(String)}
	 */
	private Function<Object, T> getter;
	/**
	 * The result should be a primitive type or string that can be retrieved correctly later
	 */
	private Function<T, Object> setter;

	/**
	 * The config value should not change outside this instance
	 */
	private T value;
	/**
	 * Whether the default value is saved in the yaml
	 */
	private boolean saved = false;

	//This constructor is needed because it sets the getter and setter
	public ConfigData(ConfigurationSection config, String path, T def, Object primitiveDef, Function<Object, T> getter, Function<T, Object> setter, Runnable saveAction) {
		this.config = config;
		this.path = path;
		this.def = def;
		this.primitiveDef = primitiveDef;
		this.getter = getter;
		this.setter = setter;
		this.saveAction=saveAction;
	}

	@SuppressWarnings("unchecked")
	public T get() {
		if (value != null) return value; //Speed things up
		Object val = config == null ? null : config.get(path); //config==null: testing
		if (val == null) {
			val = primitiveDef;
		}
		if (!saved && Objects.equals(val, primitiveDef)) { //String needs .equals()
			if (def == null && config != null) //In Discord's case def may be null
				config.set(path, primitiveDef);
			else
				set(def); //Save default value - def is always set
			saved = true;
		}
		if (getter != null) {
			T hmm = getter.apply(val);
			if (hmm == null) hmm = def; //Set if the getter returned null
			return hmm;
		}
		if (val instanceof Number && def != null)
			val = ThorpeUtils.convertNumber((Number) val,
				(Class<? extends Number>) def.getClass());
		if (val instanceof List && def != null && def.getClass().isArray())
			val = ((List<T>) val).toArray((T[]) Array.newInstance(def.getClass().getComponentType(), 0));
		return value = (T) val; //Always cache, if not cached yet
	}

	public void set(T value) {
		Object val;
		if (setter != null && value != null)
			val = setter.apply(value);
		else val = value;
		if (config != null) {
			config.set(path, val);
			if(!saveTasks.containsKey(config.getRoot())) {
				synchronized (saveTasks) {
					saveTasks.put(config.getRoot(), new SaveTask(Bukkit.getScheduler().runTaskLaterAsynchronously(MainPlugin.Instance, () -> {
						synchronized (saveTasks) {
							saveTasks.remove(config.getRoot());
							saveAction.run();
						}
					}, 100), saveAction));
				}
			}
		}
		this.value = value;
	}

	@AllArgsConstructor
	private static class SaveTask {
		BukkitTask task;
		Runnable saveAction;
	}

	public static boolean saveNow(Configuration config) {
		SaveTask st = saveTasks.get(config);
		if (st != null) {
			st.task.cancel();
			saveTasks.remove(config);
			st.saveAction.run();
			return true;
		}
		return false;
	}
}
