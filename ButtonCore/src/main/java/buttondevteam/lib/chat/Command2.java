package buttondevteam.lib.chat;

import buttondevteam.core.MainPlugin;
import buttondevteam.lib.ChromaUtils;
import buttondevteam.lib.TBMCCoreAPI;
import buttondevteam.lib.player.ChromaGamerBase;
import com.google.common.base.Defaults;
import com.google.common.primitives.Primitives;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.val;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.io.InputStreamReader;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.NumberFormat;
import java.text.ParseException;
import java.util.*;
import java.util.function.Function;

/**
 * The method name is the subcommand, use underlines (_) to add further subcommands.
 * The args may be null if the conversion failed and it's optional.
 */
public abstract class Command2<TC extends ICommand2, TP extends Command2Sender> {
	protected Command2() {
		commandHelp.add("§6---- Commands ----");
	}

	/**
	 * Parameters annotated with this receive all of the remaining arguments
	 */
	@Target(ElementType.PARAMETER)
	@Retention(RetentionPolicy.RUNTIME)
	public @interface TextArg {
	}

	/**
	 * Methods annotated with this will be recognised as subcommands
	 */
	@Target(ElementType.METHOD)
	@Retention(RetentionPolicy.RUNTIME)
	public @interface Subcommand {
		/**
		 * Allowed for OPs only by default
		 */
		String MOD_GROUP = "mod";

		/**
		 * Help text to show players. A usage message will be also shown below it.
		 */
		String[] helpText() default {};

		/**
		 * The main permission which allows using this command (individual access can be still granted with "chroma.command.X").
		 * Used to be "tbmc.admin". The {@link #MOD_GROUP} is provided to use with this.
		 */
		String permGroup() default ""; //TODO
	}

	@Target(ElementType.PARAMETER)
	@Retention(RetentionPolicy.RUNTIME)
	public @interface OptionalArg {
	}

	@AllArgsConstructor
	protected static class SubcommandData<T extends ICommand2> {
		public final Method method;
		public final T command;
		public String[] helpText;
	}

	protected static class SubcommandHelpData<T extends ICommand2> extends SubcommandData<T> {
		private final TreeSet<String> ht = new TreeSet<>();
		private BukkitTask task;

		public SubcommandHelpData(Method method, T command, String[] helpText) {
			super(method, command, helpText);
		}

		public void addSubcommand(String command) {
			ht.add(command);
			if (task == null)
				task = Bukkit.getScheduler().runTask(MainPlugin.Instance, () -> {
					helpText = new String[ht.size() + 1]; //This will only run after the server is started  List<E> list = new ArrayList<E>(size());
					helpText[0] = "§6---- Subcommands ----"; //TODO: There may be more to the help text
					int i = 1;
					for (Iterator<String> iterator = ht.iterator();
					     iterator.hasNext() && i < helpText.length; i++) {
						String e = iterator.next();
						helpText[i] = e;
					}
					task = null; //Run again, if needed
				});
		}
	}

	@RequiredArgsConstructor
	protected static class ParamConverter<T> {
		public final Function<String, T> converter;
		public final String errormsg;
	}

	protected HashMap<String, SubcommandData<TC>> subcommands = new HashMap<>();
	private HashMap<Class<?>, ParamConverter<?>> paramConverters = new HashMap<>();

	private ArrayList<String> commandHelp = new ArrayList<>(); //Mainly needed by Discord

	/**
	 * Adds a param converter that obtains a specific object from a string parameter.
	 * The converter may return null.
	 *
	 * @param cl        The class of the result object
	 * @param converter The converter to use
	 * @param <T>       The type of the result
	 */
	public <T> void addParamConverter(Class<T> cl, Function<String, T> converter, String errormsg) {
		paramConverters.put(cl, new ParamConverter<>(converter, errormsg));
	}

	public boolean handleCommand(TP sender, String commandline) {
		for (int i = commandline.length(); i != -1; i = commandline.lastIndexOf(' ', i - 1)) {
			String subcommand = commandline.substring(0, i).toLowerCase();
			SubcommandData<TC> sd = subcommands.get(subcommand); //O(1)
			if (sd == null) continue;
			boolean sync = Bukkit.isPrimaryThread();
			Bukkit.getScheduler().runTaskAsynchronously(MainPlugin.Instance, () -> {
				try {
					handleCommandAsync(sender, commandline, sd, subcommand, sync);
				} catch (Exception e) {
					TBMCCoreAPI.SendException("Command execution failed for sender " + sender + " and message " + commandline, e);
				}
			});
			return true; //We found a method
		}
		return false;
	}

	//Needed because permission checking may load the (perhaps offline) sender's file which is disallowed on the main thread
	public void handleCommandAsync(TP sender, String commandline, SubcommandData<TC> sd, String subcommand, boolean sync) throws Exception {
		if (sd.method == null || sd.command == null) { //Main command not registered, but we have subcommands
			sender.sendMessage(sd.helpText);
			return;
		}
		if (!hasPermission(sender, sd.command, sd.method)) {
			sender.sendMessage("§cYou don't have permission to use this command");
			return;
		}
		val params = new ArrayList<Object>(sd.method.getParameterCount());
		int j = subcommand.length(), pj;
		Class<?>[] parameterTypes = sd.method.getParameterTypes();
		if (parameterTypes.length == 0)
			throw new Exception("No sender parameter for method '" + sd.method + "'");
		val sendertype = parameterTypes[0];
		final ChromaGamerBase cg;
		if (sendertype.isAssignableFrom(sender.getClass()))
			params.add(sender); //The command either expects a CommandSender or it is a Player, or some other expected type
		else if (sender instanceof Command2MCSender
			&& sendertype.isAssignableFrom(((Command2MCSender) sender).getSender().getClass()))
			params.add(((Command2MCSender) sender).getSender());
		else if (ChromaGamerBase.class.isAssignableFrom(sendertype)
			&& sender instanceof Command2MCSender
			&& (cg = ChromaGamerBase.getFromSender(((Command2MCSender) sender).getSender())) != null
			&& cg.getClass() == sendertype) //The command expects a user of our system
			params.add(cg);
		else {
			sender.sendMessage("§cYou need to be a " + sendertype.getSimpleName() + " to use this command.");
			return;
		}
		val paramArr = sd.method.getParameters();
		for (int i1 = 1; i1 < parameterTypes.length; i1++) {
			Class<?> cl = parameterTypes[i1];
			pj = j + 1; //Start index
			if (pj == commandline.length() + 1) { //No param given
				if (paramArr[i1].isAnnotationPresent(OptionalArg.class)) {
					if (cl.isPrimitive())
						params.add(Defaults.defaultValue(cl));
					else if (Number.class.isAssignableFrom(cl)
						|| Number.class.isAssignableFrom(cl))
						params.add(Defaults.defaultValue(Primitives.unwrap(cl)));
					else
						params.add(null);
					continue; //Fill the remaining params with nulls
				} else {
					sender.sendMessage(sd.helpText); //Required param missing
					return;
				}
			}
			if (paramArr[i1].isVarArgs()) {
				params.add(commandline.substring(j + 1).split(" +"));
				continue;
			}
			j = commandline.indexOf(' ', j + 1); //End index
			if (j == -1 || paramArr[i1].isAnnotationPresent(TextArg.class)) //Last parameter
				j = commandline.length();
			String param = commandline.substring(pj, j);
			if (cl == String.class) {
				params.add(param);
				continue;
			} else if (Number.class.isAssignableFrom(cl) || cl.isPrimitive()) {
				try {
					//noinspection unchecked
					Number n = ChromaUtils.convertNumber(NumberFormat.getInstance().parse(param), (Class<? extends Number>) cl);
					params.add(n);
				} catch (ParseException e) {
					sender.sendMessage("§c'" + param + "' is not a number.");
					return;
				}
				continue;
			}
			val conv = paramConverters.get(cl);
			if (conv == null)
				throw new Exception("No suitable converter found for parameter type '" + cl.getCanonicalName() + "' for command '" + sd.method.toString() + "'");
			val cparam = conv.converter.apply(param);
			if (cparam == null) {
				sender.sendMessage(conv.errormsg); //Param conversion failed - ex. plugin not found
				return;
			}
			params.add(cparam);
		}
		Runnable lol = () -> {
			try {
				val ret = sd.method.invoke(sd.command, params.toArray()); //I FORGOT TO TURN IT INTO AN ARRAY (for a long time)
				if (ret instanceof Boolean) {
					if (!(boolean) ret) //Show usage
						sender.sendMessage(sd.helpText);
				} else if (ret != null)
					throw new Exception("Wrong return type! Must return a boolean or void. Return value: " + ret);
			} catch (InvocationTargetException e) {
				TBMCCoreAPI.SendException("An error occurred in a command handler!", e.getCause());
			} catch (Exception e) {
				TBMCCoreAPI.SendException("Command handling failed for sender " + sender + " and subcommand " + subcommand, e);
			}
		};
		if (sync)
			Bukkit.getScheduler().runTask(MainPlugin.Instance, lol);
		else
			lol.run();
	} //TODO: Add to the help

	public abstract void registerCommand(TC command);

	protected void registerCommand(TC command, char commandChar) {
		val path = command.getCommandPath();
		int x = path.indexOf(' ');
		val mainPath = commandChar + path.substring(0, x == -1 ? path.length() : x);
		//var scmdmap = subcommandStrings.computeIfAbsent(mainPath, k -> new HashSet<>()); //Used to display subcommands
		val scmdHelpList = new ArrayList<String>();
		Method mainMethod = null;
		boolean nosubs = true;
		boolean isSubcommand = x != -1;
		try { //Register the default handler first so it can be reliably overwritten
			mainMethod = command.getClass().getMethod("def", Command2Sender.class, String.class);
			val cc = command.getClass().getAnnotation(CommandClass.class);
			var ht = cc == null || isSubcommand ? new String[0] : cc.helpText(); //If it's not the main command, don't add it
			if (ht.length > 0)
				ht[0] = "§6---- " + ht[0] + " ----";
			scmdHelpList.addAll(Arrays.asList(ht));
			if (!isSubcommand)
				scmdHelpList.add("§6Subcommands:");
			if (!commandHelp.contains(mainPath))
				commandHelp.add(mainPath);
		} catch (Exception e) {
			TBMCCoreAPI.SendException("Could not register default handler for command /" + path, e);
		}
		for (val method : command.getClass().getMethods()) {
			val ann = method.getAnnotation(Subcommand.class);
			if (ann == null) continue; //Don't call the method on non-subcommands because they're not in the yaml
			var ht = command.getHelpText(method, ann);
			if (ht != null) {
				val subcommand = commandChar + path + //Add command path (class name by default)
					(method.getName().equals("def") ? "" : " " + method.getName().replace('_', ' ').toLowerCase()); //Add method name, unless it's 'def'
				ht = getHelpText(method, ht, subcommand);
				subcommands.put(subcommand, new SubcommandData<>(method, command, ht)); //Result of the above (def) is that it will show the help text
				scmdHelpList.add(subcommand);
				nosubs = false;
			}
		}
		if (nosubs && scmdHelpList.size() > 0)
			scmdHelpList.remove(scmdHelpList.size() - 1); //Remove Subcommands header
		if (mainMethod != null && !subcommands.containsKey(commandChar + path)) //Command specified by the class
			subcommands.put(commandChar + path, new SubcommandData<>(mainMethod, command, scmdHelpList.toArray(new String[0])));
		if (mainMethod != null && !mainPath.equals(commandChar + path)) { //Main command, typically the same as the above
			if (isSubcommand) { //The class itself is a subcommand
				val scmd = subcommands.computeIfAbsent(mainPath, p -> new SubcommandData<>(null, null, new String[]{"§6---- Subcommands ----"}));
				val scmdHelp = Arrays.copyOf(scmd.helpText, scmd.helpText.length + scmdHelpList.size());
				for (int i = 0; i < scmdHelpList.size(); i++)
					scmdHelp[scmd.helpText.length + i] = scmdHelpList.get(i);
				scmd.helpText = scmdHelp;
			} else if (!subcommands.containsKey(mainPath))
				subcommands.put(mainPath, new SubcommandData<>(null, null, scmdHelpList.toArray(new String[0])));
		}
	}

	private String[] getHelpText(Method method, String[] ht, String subcommand) {
		val str = method.getDeclaringClass().getResourceAsStream("/commands.yml");
		if (str == null)
			TBMCCoreAPI.SendException("Error while getting command data!", new Exception("Resource not found!"));
		else {
			if (ht.length > 0)
				ht[0] = "§6---- " + ht[0] + " ----";
			YamlConfiguration yc = YamlConfiguration.loadConfiguration(new InputStreamReader(str)); //Generated by ButtonProcessor
			val ccs = yc.getConfigurationSection(method.getDeclaringClass().getCanonicalName());
			if (ccs != null) {
				val cs = ccs.getConfigurationSection(method.getName());
				if (cs != null) {
					val mname = cs.getString("method");
					val params = cs.getString("params");
					//val goodname = method.getName() + "(" + Arrays.stream(method.getGenericParameterTypes()).map(cl -> cl.getTypeName()).collect(Collectors.joining(",")) + ")";
					int i = mname.indexOf('('); //Check only the name - the whole method is still stored for backwards compatibility and in case it may be useful
					if (i != -1 && method.getName().equals(mname.substring(0, i)) && params != null) {
						String[] both = Arrays.copyOf(ht, ht.length + 1);
						both[ht.length] = "§6Usage:§r " + subcommand + " " + params;
						ht = both;
					} else
						TBMCCoreAPI.SendException("Error while getting command data for " + method + "!", new Exception("Method '" + method.toString() + "' != " + mname + " or params is " + params));
				} else
					TBMCCoreAPI.SendException("Error while getting command data for " + method + "!", new Exception("cs is " + cs));
			} else
				TBMCCoreAPI.SendException("Error while getting command data for " + method + "!", new Exception("ccs is " + ccs + " - class: " + method.getDeclaringClass().getCanonicalName()));
		}
		return ht;
	}

	public abstract boolean hasPermission(TP sender, TC command, Method subcommand);

	public String[] getCommandsText() {
		return commandHelp.toArray(new String[0]);
	}

	public String[] getHelpText(String path) {
		val scmd = subcommands.get(path);
		if (scmd == null) return null;
		return scmd.helpText;
	}

	/*public Set<String> getAllSubcommands() {
		return Collections.unmodifiableSet(subcommands.keySet());
	}*/
}
