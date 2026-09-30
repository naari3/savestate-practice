package net.naari3.savestate.rng;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.nbt.CompoundTag;

/**
 * 内部状態を読み書きできる {@link Random}。
 *
 * Java 16 以降は java.util.Random の private フィールドをリフレクションで読めないので、
 * next(int) / setSeed / nextGaussian を上書きして状態を自前で持つ。アルゴリズムは java.util.Random と同一。
 *
 * 引数なしで作ったものは遅延シードになる。最初に使われた時点で {@link RngState#freshSeed()} からシードを取る
 * (サーバースレッドなら保存対象の親 RNG から、それ以外なら時刻由来)。NBT から状態を戻されたインスタンスは親 RNG を消費しない。
 */
public class StatefulRandom extends Random {
	private static final long MULTIPLIER = 0x5DEECE66DL;
	private static final long ADDEND = 0xBL;
	private static final long MASK = (1L << 48) - 1;

	// 初期化子を付けないこと。super のコンストラクタが setSeed を呼ぶ時点ではまだフィールド初期化子が走っておらず、
	// 初期化子があると setSeed で設定した値が後から上書きされてしまう
	private AtomicLong state;
	private double nextNextGaussian;
	private boolean haveNextNextGaussian;
	private volatile boolean seeded;
	/**
	 * true なら、サーバースレッド以外からの使用は保存対象の状態を動かさず、別の (保存しない) 乱数で答える。
	 * static な Random は統合サーバーではクライアントとサーバーが共有しているため
	 * (例: クライアント側のエンティティを作るときもセンサーの初期化で Sensor.RANDOM を引く)。
	 */
	private boolean serverOnly;

	public StatefulRandom() {
		super(0L);
		this.seeded = false;
	}

	public StatefulRandom serverOnly() {
		this.serverOnly = true;
		return this;
	}

	private boolean useFallback() {
		return this.serverOnly && !RngState.isServerThread();
	}

	public StatefulRandom(long seed) {
		super(seed);
	}

	@Override
	public synchronized void setSeed(long seed) {
		if (this.state == null) {
			this.state = new AtomicLong();
		}
		this.state.set((seed ^ MULTIPLIER) & MASK);
		this.haveNextNextGaussian = false;
		this.seeded = true;
	}

	private void ensureSeeded() {
		if (!this.seeded) {
			synchronized (this) {
				if (!this.seeded) {
					this.setSeed(RngState.freshSeed());
				}
			}
		}
	}

	@Override
	protected int next(int bits) {
		if (this.useFallback()) {
			return ThreadLocalRandom.current().nextInt() >>> (32 - bits);
		}
		this.ensureSeeded();
		AtomicLong s = this.state;
		long oldSeed;
		long nextSeed;
		do {
			oldSeed = s.get();
			nextSeed = (oldSeed * MULTIPLIER + ADDEND) & MASK;
		} while (!s.compareAndSet(oldSeed, nextSeed));
		return (int) (nextSeed >>> (48 - bits));
	}

	@Override
	public synchronized double nextGaussian() {
		if (this.useFallback()) {
			return ThreadLocalRandom.current().nextGaussian();
		}
		if (this.haveNextNextGaussian) {
			this.haveNextNextGaussian = false;
			return this.nextNextGaussian;
		}
		double v1;
		double v2;
		double s;
		do {
			v1 = 2 * this.nextDouble() - 1;
			v2 = 2 * this.nextDouble() - 1;
			s = v1 * v1 + v2 * v2;
		} while (s >= 1 || s == 0);
		double multiplier = StrictMath.sqrt(-2 * StrictMath.log(s) / s);
		this.nextNextGaussian = v2 * multiplier;
		this.haveNextNextGaussian = true;
		return v1 * multiplier;
	}

	/** デバッグ用。状態を消費せずに文字列で返す。 */
	public String describe() {
		return this.seeded ? Long.toHexString(this.state.get()) + (this.haveNextNextGaussian ? "+g" : "") : "unseeded";
	}

	public synchronized CompoundTag toTag() {
		CompoundTag tag = new CompoundTag();
		tag.putBoolean("seeded", this.seeded);
		if (this.seeded) {
			tag.putLong("state", this.state.get());
			tag.putBoolean("haveGaussian", this.haveNextNextGaussian);
			tag.putDouble("gaussian", this.nextNextGaussian);
		}
		return tag;
	}

	public synchronized void fromTag(CompoundTag tag) {
		if (this.state == null) {
			this.state = new AtomicLong();
		}
		if (tag.getBoolean("seeded")) {
			this.state.set(tag.getLong("state"));
			this.haveNextNextGaussian = tag.getBoolean("haveGaussian");
			this.nextNextGaussian = tag.getDouble("gaussian");
			this.seeded = true;
		} else {
			this.haveNextNextGaussian = false;
			this.seeded = false;
		}
	}
}
