package net.naari3.savestate.mixin.rng;

import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.entity.Entity;
import net.naari3.savestate.rng.RngState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * エンティティ ID の採番 (static な MAX_ENTITY_ID) は、統合サーバーではクライアントやワーカースレッドとも共有される。
 * クライアントはサーバーから届いたエンティティを作るたびに (ID はすぐ上書きされるが) カウンタを進め、
 * ワーカースレッドもチャンク生成でエンティティを作るたびに進めるので、復元後にサーバーで作られるエンティティの ID が回ごとに変わる。
 * ID は動きにも使われる (例: 止まっているアイテムは (age + ID) % 4 == 0 の tick にだけ動く)。
 * サーバースレッド以外は別のカウンタから採番し、共有のカウンタ (取得・復元の対象) はサーバースレッドだけが進めるようにする。
 */
@Mixin(Entity.class)
public class EntityIdMixin {
	/** サーバーの ID と重ならないように、十分大きい値から始める。 */
	@Unique
	private static final AtomicInteger savestate$OTHER_THREAD_IDS = new AtomicInteger(1 << 30);

	@Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Ljava/util/concurrent/atomic/AtomicInteger;incrementAndGet()I"))
	private int savestate$nextEntityId(AtomicInteger counter) {
		return RngState.isServerThread() ? counter.incrementAndGet() : savestate$OTHER_THREAD_IDS.incrementAndGet();
	}
}
