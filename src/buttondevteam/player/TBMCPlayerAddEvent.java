package buttondevteam.player;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class TBMCPlayerAddEvent extends Event {
	private static final HandlerList handlers = new HandlerList();

	private TBMCPlayer player;

	public TBMCPlayerAddEvent(TBMCPlayer player) {
		// TODO: Separate player configs, figure out how to make one TBMCPlayer
		// object have all the other plugin properties
	}

	public TBMCPlayer GetPlayer() {
		return player;
	}

	@Override
	public HandlerList getHandlers() {
		return handlers;
	}

	public static HandlerList getHandlerList() {
		return handlers;
	}
}
