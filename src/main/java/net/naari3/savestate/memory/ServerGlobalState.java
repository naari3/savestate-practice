package net.naari3.savestate.memory;

import com.google.common.primitives.UnsignedLong;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ScoreboardPlayerScore;
import net.minecraft.scoreboard.ScoreboardState;
import net.minecraft.scoreboard.ServerScoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;
import net.minecraft.world.timer.Timer;
import net.naari3.savestate.mixin.accessor.PersistentStateManagerAccessor;
import net.naari3.savestate.mixin.accessor.TimerAccessor;

/**
 * ワールドごとではない、サーバー全体の状態: スコアボードと /schedule の予約一覧。
 * どちらも生きているオブジェクトはサーバーに 1 つで、他から参照されているので、NBT で取得し、復元では中身を入れ替える。
 *
 * データパックはこの 2 つに状態を持たせることが多い。/schedule の予約はワールドの時刻で発火するので、
 * 時刻だけ戻して予約を戻さないと、「1 tick 後に自分を予約し直す」ループが時刻が追いつくまで止まる。
 */
public final class ServerGlobalState {
	private final CompoundTag scoreboard;
	private final ListTag scheduledEvents;
	private final UnsignedLong eventCounter;

	private ServerGlobalState(CompoundTag scoreboard, ListTag scheduledEvents, UnsignedLong eventCounter) {
		this.scoreboard = scoreboard;
		this.scheduledEvents = scheduledEvents;
		this.eventCounter = eventCounter;
	}

	static ServerGlobalState capture(MinecraftServer server) {
		ScoreboardState state = scoreboardState(server);
		Timer<MinecraftServer> timer = timer(server);
		return new ServerGlobalState(
			state != null ? state.toTag(new CompoundTag()) : null,
			timer.toTag(),
			((TimerAccessor) timer).savestate$getEventCounter());
	}

	void restore(MinecraftServer server) {
		ScoreboardState state = scoreboardState(server);
		if (this.scoreboard != null && state != null) {
			// 今のスコアボードを空にしてから読み戻す (fromTag は既存のものに足すだけなので)。
			// ServerScoreboard の各操作はクライアントへの同期も行う
			ServerScoreboard sb = server.getScoreboard();
			for (ScoreboardObjective objective : new ArrayList<>(sb.getObjectives())) {
				sb.removeObjective(objective);
			}
			for (Team team : new ArrayList<>(sb.getTeams())) {
				sb.removeTeam(team);
			}
			state.fromTag(this.scoreboard.copy());
			state.markDirty();
		}

		Timer<MinecraftServer> timer = timer(server);
		TimerAccessor acc = (TimerAccessor) timer;
		acc.savestate$getEvents().clear();
		acc.savestate$getEventsByName().clear();
		acc.savestate$setEventCounter(UnsignedLong.ZERO);
		// toTag は発火の順に並んでいるので、この順に入れ直せば同じ時刻の予約どうしの順序も保たれる
		for (int i = 0; i < this.scheduledEvents.size(); i++) {
			acc.savestate$addEvent(this.scheduledEvents.getCompound(i).copy());
		}
		// 入れ直しで振られる通し番号は取得時より小さいので、以後の予約が後ろに並ぶように通し番号を取得時の値に戻す
		acc.savestate$setEventCounter(this.eventCounter);
	}

	/** 決定論の検査用。スコアボードの中身 (順序に依存しない形) と予約一覧。 */
	public static String describeScoreboard(MinecraftServer server) {
		ServerScoreboard sb = server.getScoreboard();
		StringBuilder out = new StringBuilder();
		List<ScoreboardObjective> objectives = new ArrayList<>(sb.getObjectives());
		objectives.sort(Comparator.comparing(ScoreboardObjective::getName));
		for (ScoreboardObjective objective : objectives) {
			out.append(objective.getName()).append('{');
			List<ScoreboardPlayerScore> scores = new ArrayList<>(sb.getAllPlayerScores(objective));
			scores.sort(Comparator.comparing(ScoreboardPlayerScore::getPlayerName));
			for (ScoreboardPlayerScore score : scores) {
				out.append(score.getPlayerName()).append('=').append(score.getScore()).append(',');
			}
			out.append('}');
		}
		List<Team> teams = new ArrayList<>(sb.getTeams());
		teams.sort(Comparator.comparing(Team::getName));
		for (Team team : teams) {
			List<String> members = new ArrayList<>(team.getPlayerList());
			members.sort(null);
			out.append(team.getName()).append(members);
		}
		return out.toString();
	}

	public static String describeScheduledEvents(MinecraftServer server) {
		return timer(server).toTag().toString();
	}

	private static Timer<MinecraftServer> timer(MinecraftServer server) {
		return server.getSaveProperties().getMainWorldProperties().getScheduledEvents();
	}

	private static ScoreboardState scoreboardState(MinecraftServer server) {
		PersistentState state = ((PersistentStateManagerAccessor) server.getOverworld().getPersistentStateManager()).savestate$getLoadedStates().get("scoreboard");
		return state instanceof ScoreboardState ? (ScoreboardState) state : null;
	}
}
