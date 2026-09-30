# 調査ノート

時系列で追記する。結論が覆った場合も古い項目は消さず、「否定」「訂正」を追記する。
各項目は **観察** (ソースや実行で確認した事実) と **仮説** (推論) を分けて書く。

参照ソース: yarn 1.16.1+build.21 でデコンパイルしたもの (loom キャッシュの `minecraft-merged-...-sources.jar`)。

---

## 2026-09-29 要件 (ユーザーとの合意)

- 対象: Minecraft 1.16.1、Fabric、**Fabric API は使わない**
- 方式: 最初はディスク方式 (ワールドを保存 → フォルダを複製 → 再読込) で作る。最終的にはインメモリ方式へ移行する。インメモリ方式でも AI 状態・scheduled tick・POI などはすべて保持する
- RNG の復元: 保持したい。まず実現可能性を調査し、無理そうなら復元しない方向を検討する
- UI: 複数スロット + キー操作
- ビルド構成: loom 1.18.2 / Gradle 9.8.0 / loader 0.19.5 / yarn 1.16.1+build.21 / `--release 8`。この構成でビルドが通ることを確認済み

---

## 2026-09-29 RNG の調査

### 観察: サーバー側で状態を持つ Random

`new Random()` (シードなし)、`Math.random()`、`ThreadLocalRandom` の使用箇所を grep した (`net/minecraft/client/` 以下は除外)。

長く生きるインスタンス (保存・復元が必要):

| 場所 | 種類 | 主な用途 |
|---|---|---|
| `World.random` (ワールドごと) | `public final Random` | random tick、mob スポーン、天候、**ブロックのドロップ** (`Block.java:248,259`)、**ピグリン交換** (`PiglinBrain.java:396`)、爆発の威力・ドロップ・ブロック処理順 |
| `World.lcgBlockSeed` | `int` (LCG) | `getRandomPosInChunk`、random tick の位置 |
| `Entity.random` (エンティティごと) | `protected final Random` | AI、**モブのドロップ** (`LivingEntity.java:1314` の loot context)、エンダーアイの消失判定 (`EyeOfEnderEntity.java:94`) など |
| `Entity.MAX_ENTITY_ID` | `static AtomicInteger` | エンティティ ID の採番 |
| `MinecraftServer.random` | `Random` | サーバーリストのプレイヤーサンプル程度 (ゲームプレイへの影響は無さそう) |
| `Raid.random`、`WanderingTraderManager.random` | `Random` | レイド、行商人のスポーン |
| static: `Sensor.RANDOM`、`Item.RANDOM`、`MathHelper.RANDOM`、`ItemScatterer.RANDOM`、`DispenserBlockEntity.RANDOM`、`EnchantingTableBlockEntity.RANDOM` | `static final Random` | センサーの tick オフセット、UUID 採番 (`MathHelper.randomUuid()`)、コンテナ破壊時のドロップ散布、ディスペンサーのスロット選択など |
| `WeightedList.random` | `Random` (インスタンスごと) | Brain 系の重み付き選択 |

生成のたびにシードなしで作られるもの (生成時のシードが `seedUniquifier ^ System.nanoTime()` で決まるため再現しない):

- `Explosion.random` (`Explosion.java:49`、301 行目で火の設置判定に使う)。ネザーのベッド爆発に関係する
- `FishingBobberEntity.velocityRandom`、`EnchantmentScreenHandler.random`
  - `EnchantmentScreenHandler.random` は開くたびにプレイヤーの `enchantmentTableSeed` で `setSeed` される。このシードはプレイヤーの NBT に `XpSeed` として保存されるので、これは元から復元できる
- `LootContext` のフォールバック (`LootContext.java:199`)
- `ServerPlayerEntity.java:196` のリスポーン位置のばらつき
- `EnderDragonFight` / `EnderDragonSpawnState` / `EndGatewayBlockEntity` の構造物生成用

`Math.random()` を使う箇所 (JDK 内部の static Random):

- `LivingEntity.java:1067-1074` のノックバック方向 (攻撃元の位置が被弾側とほぼ同じ場合) とノックバックの向き
- `AbstractFurnaceBlockEntity.java:477` のかまどの経験値の端数
- `LivingEntity` のコンストラクタにある `randomSmallSeed` / `randomLargeSeed` / 初期 `yaw`
- その他はクライアント側の見た目用

`ThreadLocalRandom`: `EntityAttributeModifier` の UUID 採番のみ。

### 観察: `java.util.Random` の内部状態

- 状態は `private final AtomicLong seed`、`nextNextGaussian`、`haveNextNextGaussian`
- Java 16 以降は `java.util` が開かれていないので、`--add-opens` を付けない限り `setAccessible` で `seed` を読めない。1.16.1 を Java 17 以降で起動する環境は多いので、リフレクションには頼れない
- JDK 17 / 25 の `Random.java` を確認した。`Random(long)` は `getClass() == Random.class` でない場合に `setSeed(seed)` を呼ぶ。`nextInt` / `nextLong` / `nextFloat` / `nextDouble` / `nextBoolean` はすべて `protected int next(int bits)` を経由する。`nextGaussian` だけは `synchronized` で独自のキャッシュを持つ
- したがって、`Random` を継承して `next(int)`・`setSeed(long)`・`nextGaussian()` を上書きし、状態を自前のフィールドで持つクラスを作れば、状態の読み書きが可能になる。このクラスのインスタンスは Mixin で差し込む (final フィールドは `@Mutable` を付けてコンストラクタの末尾で差し替えるか、`@Redirect` の `NEW` を使う)

### 観察: world.random は毎 tick 大量に消費される

- `ServerWorld.tickChunk` (448 行目以降) では、tick 対象のチャンクごとに毎 tick `this.random.nextInt(16)` を呼ぶ (476 行目)。雷雨中は `nextInt(100000)` も呼ぶ。さらに `randomTickSpeed` 回の random tick で、選ばれたブロックの `randomTick(..., this.random)` が乱数を消費する
- ピグリン交換とブロックのドロップ (砂利から火打石など) はこの `world.random` を使う

### 仮説: RNG 復元の実用上の効果

- 技術的には、以下の組み合わせで「状態を保存した時点の乱数状態」を復元できる可能性が高い
  1. 長く生きる Random (world / entity / static / Raid など) を状態を読み書きできるサブクラスに差し替え、save 時に保存して load 時に書き戻す
  2. シードなしの `new Random()` と `Math.random()` を、MOD が持つ親 RNG から派生させる。親 RNG の状態も保存する
  3. `lcgBlockSeed` と `Entity.MAX_ENTITY_ID` も保存・復元する
- ただし `world.random` はチャンク数に比例して毎 tick 消費される。そのため、ピグリン交換などの結果は「load から何 tick 目に行動したか」で変わると思われる。人間が tick 単位で同じタイミングを再現するのは難しいので、**実戦での体感は「復元しない」場合とあまり変わらない**可能性が高い
- 再現性をさらに崩す要因 (未検証):
  - 非同期のチャンク読み込み: load 後に周囲のチャンクが揃うタイミングはスレッドの状況で変わる。tick 対象のチャンクの集合が変われば `world.random` の消費量も変わる。ディスク方式では避けにくい。インメモリ方式でチャンクを読み込んだまま復元すれば、この影響は抑えられる可能性がある
  - 統合サーバーの tick とクライアント入力の到着タイミングのずれ
  - エンティティの反復順序 (ID や UUID をキーとするハッシュ構造)
- 逆に、`Entity.random` を使うもの (ブレイズロッドなどモブのドロップ、エンダーアイの消失) はそのエンティティ自身の Random なので、world.random ほど他の要因に左右されない。ただし AI も同じ Random を毎 tick 消費するので、こちらもタイミングに依存すると思われる

### 観察 (2026-09-30 追記): tickChunk が呼ばれる条件とチャンクの処理順

- ユーザーから「Ranked が RNG を標準化しているのと混同していないか」と質問があったため、バニラのソースを見直した
- `ServerChunkManager.java:383-389`: `tickChunk` が呼ばれるのは `isTooFarFromPlayersToSpawnMobs(chunkPos)` が false のチャンク、つまりプレイヤーから 128 ブロック以内のチャンクだけ。ロード済みのチャンクすべてではない
- `ServerWorld.java:476`: この条件を満たす各チャンクで、毎 tick `this.random.nextInt(16)` が必ず 1 回呼ばれる。random tick で選ばれた位置 (`getRandomPosInChunk`、こちらは `lcgBlockSeed` を使う) のブロックや流体が random tick を持っていれば、`this.random` がさらに消費される。溶岩は random tick を持つので、ネザーではこの消費が多いと思われる
- `ServerChunkManager.java:372`: チャンクの処理順を `Collections.shuffle(list)` で毎 tick 混ぜている。引数に Random を渡していないので、`Collections` 内部の static Random が使われる。**RNG の発生源の一覧に追加する**。処理順が変われば mob スポーンなどの結果も変わる
- ピグリン交換が `piglin.world.random` を使うこと (`PiglinBrain.java:396`) は、バニラのコードで確認済み。Ranked の変更とは関係ない
- MCSR Ranked が交換やドロップの RNG を標準化している方法は確認していない

## 2026-09-30 MCSR Ranked (mcsrranked-5.8.26.jar) の RNG 標準化

ユーザーの Prism インスタンス `1.16.1 RTA Ranked` にある jar を展開し、`javap` で bytecode を読んだ。クラス名は難読化されていない (MC 側は intermediary 名)。

### 観察

- `mcsrranked.standardrng.mixins.json` に約 60 個の Mixin がある (パッケージ `com.mcsrranked.client.standardrng.mixin`)
- `AccessibleRandom extends java.util.Random`: `public final AtomicLong seed` を自前で持ち、`next(int)` を上書きして標準の LCG を実装している。`getSeed()` で状態を読める。前回の「Random を継承して状態を自前で持つ」方針と同じやり方
- `WorldRNGState extends PersistentState` (ID は `"rng"`): overworld の `PersistentStateManager` に置かれる (`fromServer` が `method_30002` = overworld を取っている)。つまりワールドの `data/rng.dat` に保存される。NBT のキーは `seeds` (long 配列)、`eyeThrows`、`blockSections`、`entitySections`、`lootSections`
  - 用途ごと (`WorldRNGState$Type`) に別々の `AccessibleRandom` を持つ。種類は約 60 個: `BARTER`、`BLAZE`、`FLINT`、`EYE`、`ENDERMAN`、`DRAGON_PERCH` / `DRAGON_PATH` / `DRAGON_HEIGHT` など、`EXPLOSION`、`SPAWN`、`FORTRESS_SPAWN`、`BLAZE_SPAWN_*`、`ITEM_DROP_VELOCITY` / `ITEM_DROP_OFFSET`、`PORTAL_ORIENTATION` など
  - さらに `(Type, EntityType)` ごと、`(Type, x, y, z, dimension)` ごと、loot table の ID ごとに分けた Random のマップを持つ
  - 初期シードは `mixSeed(long, int...)` で作っている (定数 `4262064045L` が出てくる)。元になる値はワールドシードだと思われるが、未確認
- `MixinPiglinBrain` (対象は `method_24776` = `getBarteredItem`):
  - `LootContext.Builder.build` の呼び出しをフックし、`.random(...)` を `WorldRNGState.fromServer(server).getRandom(Type.BARTER)` に置き換えている
  - 別の注入では、交換結果を `WorldPiglinBarterState.guaranteeItem(piglin, stack, getRandom(BARTER))` で差し替えている。`WorldPiglinBarterState` は PersistentState (ID は `"piglin_barters"`) で、フィールドは `MAX_GUARANTEE = 72`、`MAX_PEARL_COUNT = 3`、`MAX_OBSIDIAN_COUNT = 6`、`pearlTradeIndexes`、`obsidianTradeIndexes`、`currentTrades`
    - 仮説: 72 回の交換のうち何回目にパールと黒曜石を出すかを、あらかじめ決めておく仕組みと思われる。中身の処理はまだ読んでいない

### 仮説・前回の推論への補足

- 2026-09-29 の「RNG を復元しても実戦での体感はあまり変わらない」という推論は、**バニラ前提**の話。バニラのコードについての観察 (交換が `world.random` を使う、その `world.random` は毎 tick 消費される) は今回も否定されていない
- Ranked の環境では、交換・ブレイズ・火打石・エンダーアイなどが専用の乱数列に分かれている。そのため、結果は「何回目の交換か」だけで決まり、tick のタイミングには依存しないと思われる。この環境なら RNG の復元は意味を持つ
- ディスク方式で savestate を取る場合、`data/rng.dat` と `data/piglin_barters.dat` はワールドフォルダごと複製される。したがって Ranked の MOD が入っていれば、これらの状態は自動的に復元されると思われる。ただし Ranked に anticheat と非互換チェック (`CheckIncompatibleVersions`) があるため、この MOD と同時に読み込めるかは未確認
- Ranked の標準化の仕組み (用途ごとに Random を分けて PersistentState に保存する) を自作で持つことは可能。ただし、Ranked の分け方やシードの作り方を再現して配布するかどうかは、別途判断が要る

### 未確認

- `World.random` を複数スレッドから触る箇所があるか。あるなら `AtomicLong` 相当の同期が必要
- ドラゴンの行動 (perch の判定など) がどの Random を使うか
- 1.16.1 のワーカースレッドで走るチャンク生成が `world.random` を使うか (`ChunkRegion` は独自の Random を持つように見えるが未確認)

## 2026-09-30 方針の変更: Ranked の話はいったん保留、練習用インスタンスで SS/SL を優先する

- ユーザーの判断: Ranked との共存や Ranked 式の RNG 標準化はいったん考えない。バニラ + この MOD の練習用インスタンスで savestate / loadstate ができればよい
- RNG の復元は当面やらない (バニラの挙動のまま)。調査結果は上に残しておき、インメモリ方式に移るときに改めて判断する

## 2026-09-30 フェーズ 1 (ディスク方式) の設計に使ったバニラの観察

- `MinecraftServer.save(suppressLogs, flush, force)` (564 行目): 全ワールドで `ServerWorld.save` → `saveLevel()` (PersistentState と、ドラゴン戦の状態を SaveProperties に書く) → `ServerChunkManager.save(flush)`。最後に `session.method_27426(..., getPlayerManager().getUserData())` で level.dat を書く。ホストのプレイヤー情報は level.dat の `Player` タグに入る
- `ThreadedAnvilChunkStorage.save(true)` (334 行目): ticking なチャンクを保存し、`unloadChunks` を実行したあと `completeAll()` (チャンク用の `StorageIoWorker` の完了待ち + region ファイルの同期) を呼ぶ
- **POI は別の `StorageIoWorker` が書く** (`SerializingRegionBasedStorage.worker`、TACS 652 行目の `method_20436` → `saveChunk` → `worker.setResult` は非同期)。`save(true)` はこの worker を待たない。したがって、複製の前に POI 側の `worker.completeAll().join()` も呼ぶ必要がある
- `MinecraftClient.disconnect(Screen)` (1848 行目): `while (!integratedServer.isStopping()) render(false);` で待つ。`isStopping()` は `!serverThread.isAlive()`。`shutdown()` の中で `session.close()` (session.lock の解放) が呼ばれるのはスレッド終了より前なので、disconnect から戻った時点ならワールドのファイルを差し替えてよい
- 再読込は `MinecraftClient.startIntegratedServer(String worldName)` (1602 行目)
- `MinecraftServer.session` は protected (Accessor が必要)。1.16.1 の `MinecraftServer` には `getSavePath` がない
- キー登録: `GameOptions.keysAll` は `public final` 配列で、コンストラクタの最後で `this.load()` が呼ばれる (202 行目)。その直前に要素を足す必要がある。`KeyBinding.categoryOrderMap` は private static で、未登録のカテゴリだとキー設定画面のソートで NPE になる
- Fabric API がないため、MOD の jar 内の lang ファイルは読み込まれない (resource loader は Fabric API 側の機能)。キー名やカテゴリ名は翻訳キーを使わず、そのまま表示する文字列にする

### フェーズ 1 の設計

- save (キー、クライアントスレッド) → `server.submit` でサーバースレッド上で次を行う:
  1. `saveAllPlayerData()` (統計・進捗も含む)
  2. `server.save(true, true, true)`
  3. 各ワールドの POI worker で `completeAll().join()`
  4. ワールドフォルダを `<gameDir>/savestates/<ワールドのフォルダ名>/slot<N>.tmp` に複製し (`session.lock` は除く)、既存のスロットと入れ替える
  - サーバースレッドを止めたまま複製するので、複製中にワールドは進まない
- load (キー) → `client.execute` で tick の外に出してから、`world.disconnect()`、`client.disconnect(SaveLevelScreen)` (サーバー停止まで待つ) を行う。そのあとワールドフォルダを退避 (`.ssold` に rename)、スロットを `.sstmp` に複製してから rename、退避したフォルダを削除し、`startIntegratedServer(name)` で開き直す
- 既知の制約: ディスク方式なので、NBT に保存されない実行時の状態 (Goal の進行状況、経路探索、攻撃のクールダウンなど) は「セーブして抜けて入り直した」のと同じ扱いになる

## 2026-09-30 フェーズ 1 の動作確認 (開発用クライアント `./gradlew runClient`)

### 観察
- 新規ワールドで F6 (save) を押すと、3 ディメンションとも `All chunks are saved` が出た。`run/savestates/New World/slot1` (4.9 MB) に DIM-1 / DIM1 / region / level.dat / playerdata / advancements / stats / data が複製された。`session.lock` は除外されていた
  - `poi` フォルダは元のワールドにもなかった (POI がまだ 1 つもないため)。POI があるワールドで複製されるかは未確認
- F7 (load) を 02:02:24 から 02:03:25 の間に 8 回ほど連続で実行した。毎回「Stopping server → 約 1 秒後に Preparing start region」で再起動し、ログに Exception は 1 件もなかった。作業用のフォルダ (`.sstmp` / `.ssold` / `.tmp`) は残らなかった
- ユーザーの目視による確認: 「うまく動いてるっぽい」

### 未確認
- POI (ベッド、職業ブロック、ネザーポータル) があるワールドでの save/load
- ネザーやエンドにいる状態での save/load。エンダードラゴン戦の途中での save/load
- 本番の練習用インスタンス (Prism、Java 17 以降、ほかの MCSR 系 MOD と一緒に入れた状態) での動作
- 訂正: 上の「Exception は 1 件もなかった」は save/load の期間 (02:01:45 以降) の話。ログ全体では、起動直後 02:01:05 に Realms の認証失敗 (`Failed to parse into SignedJWT`) が 1 件あった。開発環境ではオフライン認証になるため毎回出るもので、この MOD とは関係ない

## 2026-09-30 村人が load のたびに違う方向へ歩く件

### 観察
- ユーザーの報告: 職業ブロックを置いて save した後、load するたびに村人がランダムな方向へ歩いていく
- スロット (`slot4`) の `poi/r.0.-1.mca` は 12 KB で、職業ブロックの POI は保存されていた (`slot1` の時点では 0 バイト)
- 自作の NBT リーダー (scratchpad の `villager.py`) で、スロットと現在のワールドの村人を読んだ。UUID が同じ村人の `Brain.memories` は、どちらも `minecraft:job_site` のみ (pos [82, 68, -141])
- `MemoryModuleType.java`: `JOB_SITE` は `register("job_site", GlobalPos.CODEC)` で codec 付き (保存される)。`WALK_TARGET` (37 行目) と `LOOK_TARGET` (38 行目) は codec なしで登録されているため、NBT に保存されない
- 村人の徘徊: `VillagerTaskListProvider` の `FindWalkTargetTask` → `TargetFinder` (123 行目) で `mob.getRandom()`、つまり `Entity.random` を使って行き先を決める。`Entity.random` は `new Random()` なので、load のたびにシードが変わる

### 仮説
- 村人が毎回違う方向へ歩くのは、(1) 歩いている途中の行き先 (`WALK_TARGET`) と経路が保存されないこと、(2) load 後に新しい行き先を選ぶ乱数 (`Entity.random`) が毎回違うこと、の 2 つが重なった結果と思われる。どちらもディスク方式 (フェーズ 1) の既知の制約の範囲内
- 同じ動きを再現するには、フェーズ 2 で Brain の保存されない記憶と実行中のタスクを保持し、**さらに `Entity.random` の状態も復元する**必要がある。AI の状態だけを保持しても、次に行き先を選ぶ時点で乱数が変わるので、そこから先の動きは再現しないと思われる。つまり「AI の状態をすべて保持する」という要件は、エンティティについては RNG の復元なしには意味を持ちにくい

## 2026-09-30 RNG の復元を実装 (ディスク方式の上に載せる)

ユーザーの判断: static な Random も含めて、復元できるものはすべて復元する (前回の選択肢の B)。

### 逸脱
- 当初の計画では、RNG の復元はフェーズ 2 (インメモリ方式) と同時に入れる予定だった。実際には、先にディスク方式 (フェーズ 1) の上に実装した
  - 理由: RNG の保存・復元の仕組みは、ディスク方式でもインメモリ方式でもそのまま使える。先に入れれば、村人の件をすぐ検証に使える (save 時点で歩いていた途中の行き先は失われても、load 後に選び直す行き先が毎回同じになるはず)

### 設計
- `StatefulRandom extends Random`: `next(int)` / `setSeed` / `nextGaussian` を上書きし、状態を `AtomicLong` で自前に持つ。アルゴリズムは java.util.Random と同一 (Ranked の `AccessibleRandom` と同じ考え方)
  - フィールドに初期化子を付けない。super のコンストラクタが `setSeed` を呼ぶ時点ではまだフィールド初期化子が走っておらず、初期化子があると後から上書きされるため
  - 引数なしで作ったものは**遅延シード**にする。最初に使われた時点で、サーバースレッドなら親 RNG (MASTER) から、それ以外なら時刻由来でシードを取る
    - 狙い: チャンクから読み込まれたエンティティのように、作った直後に NBT から状態を戻されるものが親 RNG を消費しないようにする。消費してしまうと、チャンクの読み込み順 (非同期) で親 RNG の進み方が変わる
- 置き換え (シードなしの `new Random()` を StatefulRandom に。`@Redirect` の `NEW` で descriptor `()Ljava/util/Random;` を指定し、引数なしのコンストラクタだけを対象にする):
  - インスタンス: Entity、World (random と lcgBlockSeed の初期値)、Explosion、Raid、WanderingTraderManager、WeightedList、FishingBobberEntity、EnchantmentScreenHandler、LootContext.Builder.build、ServerPlayerEntity.moveToSpawn、MinecraftServer
  - static (`<clinit>`): Sensor、Item、MathHelper、ItemScatterer、DispenserBlockEntity、EnchantingTableBlockEntity。`RngState.registerStatic(name, ...)` で名前を付けて登録する
  - `Math.random()`: LivingEntity の `<init>` と `damage`、AbstractFurnaceBlockEntity.dropExperience。サーバースレッドのときだけ保存対象の MATH を使う
  - `Collections.shuffle(list)`: ServerChunkManager.tickChunks。サーバースレッドのときだけ保存対象の SHUFFLE を使う
- 保存先:
  - エンティティ: エンティティの NBT のキー `mcsr-savestate:Random` (`Entity.toTag` / `fromTag`)。Raid も raids.dat の中に同じキーで保存する
  - それ以外: スロットの中の `mcsr-savestate-rng.dat`。中身は MASTER / MATH / SHUFFLE、static、MinecraftServer.random、ワールドごとの random / lcgBlockSeed / 行商人の random
- 復元のタイミング: エンティティはチャンクの読み込み時。それ以外は `PlayerManager.onPlayerConnect` の末尾。ワールドを開いてからプレイヤーが入るまでに消費される分はタイミング次第で変わるので、なるべく遅く適用する

### 仮説 (未検証)・分かっている限界
- エンティティ自身の Random だけで決まる動き (村人の徘徊の行き先、モブのドロップ) は、load のたびに同じになると思われる
- 次のものは再現しない可能性がある:
  - `world.random` の消費は tick 対象のチャンクの集合に依存する。この集合は load 後の非同期のチャンク読み込みで変わる
  - エンティティの tick の順序 (`entitiesById` への挿入順 = チャンクの読み込み順)。Brain のタスクの WeightedList は遅延シードで MASTER から取るので、この順序に左右される
  - ワーカースレッドで作られる Random (ワールド生成中のエンティティなど) は時刻由来のシードになる
  - `Entity.MAX_ENTITY_ID` (エンティティ ID の採番) は復元しない
- 副作用: エンティティと Raid の NBT に独自のキーが増える。バニラは知らないキーを無視するので、MOD を外しても読み込める

## 2026-09-30 RNG を復元しても村人が違う方向へ歩く件の調査

### 観察
- ユーザーの報告: RNG の復元を入れたあとも、load するたびに村人が違う方向へ歩く
- 調査用のログを入れた (`SavestateDebug`、`-Dmcsr-savestate.debug=true`)。出す内容: `fromTag` で戻した村人の Random、load 後の村人の各 tick の Random・位置・WALK_TARGET、`FindWalkTargetTask` の前後、RNG を適用した時点の状態
- 同じ `slot1` から 4 回 load したログを村人ごとに並べた (scratchpad の runclient3.log):
  - `fromTag` で戻した値と t=0 (time=3263) の値は、4 回とも同じ
  - 村人 `108e1727`: t=1 までは 4 回とも同じ。t=2 で分かれる。ただし値は**同じ乱数列の上でずれているだけ**。例: 1 回目の t=3 `deddc90eec67` は、2 回目の t=2 と t=3 の間の値。1 回目の t=23 `ec6e28ef3301` は、2 回目の t=6 と一致する
  - 村人 `ea832d59`: 4 回目だけ t=1 の時点で、他の回の t=17 相当まで進んでいた
  - RNG の適用 (`applied`) は、overworld の時刻 3264 の回と 3265 の回があった。村人はそれより前に 1〜2 tick 動いていた。適用時の MASTER / MATH / SHUFFLE / overworld random の値は 4 回とも同じ
  - static として登録されていたのは `item`、`mathHelper`、`sensor` の 3 つだけ
- → 村人ごとの Random の復元は正しく動いている。load ごとに違うのは **1 tick あたりに村人の Random を引く回数**

### 仮説
- プレイヤーが入って RNG を適用するまでの間も、スポーン周辺のエンティティは tick している。その間は Sensor.RANDOM (センサーの実行タイミング) や MASTER (WeightedList の遅延シード) が未復元のまま使われるので、Brain の動きが変わり、村人の Random を引く回数がずれると思われる
- プレイヤーが入る tick が load ごとに違う (適用時刻 3264 と 3265) ことも、村人がプレイヤーを感知し始めるタイミングをずらすと思われる

### 対策 (実装した)
- load で開き直したワールドは、RNG を予約している間 (`RngState.isHoldingWorldTicks()`) は `MinecraftServer.tickWorlds` の中の `ServerWorld.tick` を飛ばす。ログインのネットワーク処理は止めない
- プレイヤーが入った時点 (`PlayerManager.onPlayerConnect` の末尾) で RNG を適用し、tick を再開する。これで「load 後の最初の tick」が、毎回同じ状態・同じ時刻・プレイヤーがいる状態から始まるはず
- サーバー停止時 (`MinecraftServer.shutdown` の先頭) に予約を消す。予約が残ったまま別のワールドを開いて、tick が止まり続けるのを防ぐため
- save の時点で未登録だった static な Random は、load 時に未シードに戻す (前回の修正。この版から効く)

## 2026-09-30 tick を止める対処の後も、最初の tick でずれる

### 観察
- tick を止める対処の後は、`applied` (時刻 3658) → 村人の t=0 (時刻 3659) の順になった。RNG を戻す前に村人が tick することはなくなった
- 同じスロットから load した最後の 2 回 (runclient4.log) を比べた。両方の村人とも、t=0 の Random は一致し、**t=1 (最初の 1 tick の後) で既にずれる**。村人 `108e1727` は、一方で WALK_TARGET = 82,68,-141 (職業ブロック)、もう一方で 82,68,-147 を選んだ
- `Sensor.java`: コンストラクタで `this.lastSenseTime = RANDOM.nextInt(senseInterval)` (static な `Sensor.RANDOM`)。`tick` では `--lastSenseTime <= 0` のときだけ `sense` を実行する。`lastSenseTime` は NBT に保存されない
- Sensor は Brain を作るとき、つまりエンティティを作ったとき (チャンクの読み込み中) に生成される。これは RNG を戻す前なので、未復元の `Sensor.RANDOM` が使われる

### 仮説
- センサーが何 tick 目に動くか (`lastSenseTime` の位相) が load ごとに違い、そのせいで最初の tick から Brain の判断が変わると思われる
- これは「NBT に保存されない実行時の状態」の典型例。ディスク方式では、この種の状態 (センサーの位相、タスクの開始・終了時刻、Goal のクールダウン、経路、codec のない記憶など) を**それぞれ個別に**保存して戻さないと、エンティティの動きは揃わない。対象は AI 全般に広く散らばっていて、数が多い
- インメモリ方式で、エンティティなどのオブジェクトを丸ごと (実行時の状態も含めて) 複製して戻すようにすれば、この類型はまとめて解決できる見込み

## 2026-09-30 決定論の自動検査を実装し、ディスク方式を測った

### 実装
- `DetCheck` (サーバー側): loadstate で tick を再開した時点 (tick 0) から、毎 tick の終わりにワールドの状態を要約して記録する。要約の中身は、RNG (master/math/shuffle/static)、ワールドの時刻・random・lcg、全エンティティの位置・速度・向き・Random・体力。複数回の記録を比べ、最初にずれた tick と、種類ごとに最初にずれた tick を出す
- `DetCheckDriver` (クライアント側): `./gradlew runClient -PdetcheckWorld=<フォルダ名> [-PdetcheckTicks=100] [-PdetcheckRuns=3]` で、ワールドを開く → 40 tick 待つ → スロット 9 に save → 3 回 load して各 100 tick 記録 → `run/savestates/detcheck-report.txt` にレポートを書く → ゲームを終了する。人の操作は要らない (`pauseOnLostFocus` を切っているので、ウィンドウが非アクティブでも止まらない)。debug 時は F9 で、今のスロットに対して手動でも実行できる
- 1 回の実行は約 50 秒

### 観察 (ワールド `detcheck` = `New World` の複製、100 tick × 3 回、detcheck2.log)
- tick 0 (RNG を戻した直後) は全項目一致。→ 保存・復元そのものは正しい
- 種類ごとに最初にずれた tick (run 1 vs run 0):
  - tick 1: 地面のアイテムの速度。`ItemEntity.tick` の `(age + entityId) % 4` による移動判定がエンティティ ID に依存しており、ID はチャンクを読み込んだ順に採番される
  - tick 2: `world overworld random`、`lcg`、`rng shuffle`、豚の Random
  - tick 3: `rng master`、`rng math`、狐、鶏。`entityCount` が 230 → 232 になり、**一方の回にだけ存在するエンティティ**が出る (`null -> 値`)
  - tick 4〜11: チェスト付きトロッコ、アイテム、豚、羊などが、回によって違う tick に現れる
  - tick 20 以降: イカ、タラ
- → 支配的な原因は**非同期のチャンク読み込み**。load 後、プレイヤーの周囲のチャンクが何 tick 目に読み込まれるかが回ごとに違う。その結果、エンティティが現れる tick、tick 対象のチャンクの集合 (→ world.random と shuffle の消費)、エンティティ ID がずれる

### 仮説
- ディスク方式 (ワールドを開き直す) を続ける限り、チャンクの読み込みを完全に同期化し、エンティティ ID も復元しない限り、この類型は消えないと思われる
- インメモリ方式でチャンクを読み込んだまま (サーバーを止めずに) 状態だけを戻せば、読み込みのタイミングの問題そのものが起きない

## 2026-09-30 フェーズ 2 (インメモリ方式) の設計と段階計画

### 基本方針
- サーバーは止めず、読み込み済みのチャンクもそのまま残す。状態だけを戻す (→ 非同期のチャンク読み込みによるずれを避ける)
- エンティティなどは NBT を経由せず、**リフレクションでオブジェクトの中身を丸ごと複製**する (→ センサーの位相やタスクの時刻など、NBT に保存されない実行時の状態もまとめて保持する)
- 合格の基準は、決定論の自動検査で「100 tick の間、全項目一致」になること

### 汎用の複製器 (DeepCloner) の方針
- 生成は `sun.misc.Unsafe.allocateInstance` (コンストラクタを走らせない)。フィールドは `Field.set` で写す。final の非 static フィールドも setAccessible すれば書ける。MC のクラスは Knot の unnamed module にあるので、リフレクションが通る
- 循環や共有参照に対応するため、IdentityHashMap で「元 → 複製」を管理する。深い再帰を避けるため、作業キューで幅優先に処理する
- ハッシュ系・ソート系のコレクション (HashMap/HashSet/TreeMap、fastutil、guava の Immutable*) は、中身の要素の複製がすべて埋まってから (第 2 段階で) 入れ直す。キーの hashCode が要素のフィールドに依存しうるため
- JDK のクラス (java.base) の内部はリフレクションで触れない (Java 16 以降)。そのため、主要なコレクション、Optional、Atomic*、ロック類は公開 API で作り直す。それ以外の JDK のクラスは共有し、警告を 1 回だけ出す
- ラムダ (`$$Lambda`) は Java 15 以降 hidden class で、フィールドを書き換えられない。そこで、捕捉した値を複製したうえで、ラムダのクラスのコンストラクタを呼んで作り直す
- **共有 (複製しない) の判定**:
  - 不変の値: String、ボックス型、enum、UUID、BlockPos (Mutable は除く)、Vec3d、Box、Identifier、BlockState など
  - レジストリに登録されたもの: 全レジストリの全要素を起動時に集めて判定する
  - 自分のクラス階層の static フィールドに入っている定数 (例: `DamageSource.FALL`。`==` で比較するコードがあるため)
  - ワールド側の単一インスタンス: World、MinecraftServer、ChunkManager、Chunk、PlayerManager、ネットワーク、Scoreboard、BossBar、Raid、PersistentState など
  - ライブラリ: netty、log4j、mojang (authlib / datafixer / brigadier)

### 段階計画
1. **エンティティ + RNG + 時刻・天候** (今回): プレイヤー以外の全エンティティを複製で入れ替え、`entitiesById` の順序も保存時と同じにする。プレイヤーは共有のまま、まだ戻さない。決定論の自動検査に「メモリのスロット」モードを足し、プレイヤーは動かない前提で測る
2. **チャンク**: ブロックの状態 (ChunkSection)、ブロックエンティティ、スケジュール済みの tick、光源。クライアントにチャンクを送り直す
3. **プレイヤー**と、保存後にディスクへ書かれたチャンク (保存時に読み込まれていなかったチャンク、読み込みが外れて保存されたチャンク) の扱い。書き込み前の内容を記録しておく方式を想定
4. ワールド全体の状態: PersistentState (raids など)、POI、ドラゴン戦、行商人、スコアボードなど
5. メモリのスロットをディスクにも残すか (ゲームを再起動しても残るようにするか) の検討

## 2026-09-30 インメモリ方式 段階 1 の実装と測定

### 実装
- `memory/DeepCloner`、`SharePolicy`、`ClassInfo`: 設計どおり。`MemorySnapshot`: プレイヤー以外の全エンティティを複製して保持し、復元時はそのまた複製で入れ替える。entitiesById の並び、RNG、時刻・天候、エンティティ ID の採番カウンタも戻す
- F6/F7 の既定をインメモリ方式にした (`-Dmcsr-savestate.mode=disk` / `-PsavestateMode=disk` でフェーズ 1 の方式)。メモリのスロットは取得したサーバーに結び付け、別のワールドでは使わない
- 取得は約 300〜500 ms (初回はレジストリを集める分を含む)、復元は 60〜130 ms (エンティティ約 250 体)

### 観察 1 (mem1.log): クライアントスレッドが static な Random を消費していた
- run 1 だけ、tick 0 で `rng static sensor` がずれた。復元と tick 0 の記録は同じサーバーのタスク内なので、サーバー側で乱数を引く余地はない
- 仮説: 統合サーバーでは static な Random をクライアントとサーバーで共有している。復元でエンティティが入れ替わると、クライアント側でもエンティティが作り直され、センサーの初期化で `Sensor.RANDOM` を引く
- 対策: static な Random (`registerStatic`) は `serverOnly` にし、サーバースレッド以外からの使用は保存対象の状態を動かさず、ThreadLocalRandom で答える
- あわせて、検査のドライバーは、読み込み済みのチャンク数が 100 tick 変わらなくなるまで待ってから save するようにした (40 tick では周囲のチャンクの読み込みが続いていて、回ごとに tick 対象のチャンクが増えていた)

### 観察 2 (mem2.log): 共有 RNG のずれは消えた
- 共有 RNG (master/math/shuffle/static) は一致するようになった。ワールドの Random がずれ始めるのは tick 28 / tick 5、村人は tick 75 以降
- tick 1 のタラ (cod) は run 1 と run 2 が同じ値で、run 0 だけが違った → 戻していないワールド側の状態の影響を疑った

### 観察 3 (mem3.log、4 回): ブロックの状態を検査に追加
- 検査の要約に、読み込み済みチャンクごとのブロックのハッシュ (ChunkSection をクライアント送信用の形式に書き出したもののハッシュ)、スケジュール済みの tick の件数を足した。run 1 を基準にした比較も出すようにした
- **tick 0 で既に、チャンク (9,-11) のブロックと `scheduledBlockTicks` (5389 → 5390 → 5391) が回ごとに違う**。段階 1 ではブロックもスケジュール済みの tick も戻していないので、前の回で進んだ分がそのまま残っている
- run 2 vs run 1 では、そのチャンクとスケジュール件数以外は tick 44 まで全項目一致した (その後、タラ、ワールドの Random、村人の順にずれる)
- tick 0 でプレイヤーの位置も run 0 と他の回で違った (プレイヤーはまだ戻していない。save から最初の復元までの間に動いた分)。この回の実行中にユーザーがウィンドウのフォーカスを取った (`pauseOnLostFocus` は切っているので、検査は止まらずに最後まで回った)

### 仮説
- 残っているずれの主因は、戻していないブロックの状態、スケジュール済みの tick、プレイヤーと思われる。エンティティの複製・復元そのものは、少なくとも 40 tick 程度は正しく動いている
- 次は段階 2 (チャンク: ブロックの状態、ブロックエンティティ、スケジュール済みの tick、光源) と段階 3 (プレイヤー)

## 2026-09-30 インメモリ方式 段階 2 (チャンク) の実装と測定

### 実装
- `WorldChunksSnapshot`: 読み込み済みの全チャンクについて、ChunkSection、ハイトマップ、ブロックエンティティを複製し、inhabitedTime も保存する。ワールドごとに、ブロックエンティティのリスト 2 つの並び、スケジュール済みの tick (ブロック・流体)、ブロックイベントのキューを保存する。`ScheduledTick.idCounter` (同じ時刻の予約の順序を決める通し番号) も戻す
- 復元では `setBlockState` を使わない (置き換え時の処理、例えばコンテナの中身をばらまく処理が走るため)。ChunkSection の配列の要素を差し替える。中身が変わったセクションについてだけ、変わったブロックを 1 つずつ `checkBlock` (光) と `markForUpdate` (クライアント) に通す
- 共有判定に `shareChunkData` を追加した (エンティティの複製では ChunkSection とブロックエンティティを共有し、チャンクの複製では複製する)。`IdList` は常に共有する
- 検査の要約に、ブロックエンティティの NBT のハッシュを加えた

### 観察 (mem4.log、4 回)
- 取得: エンティティ 244 体 + 571 チャンクで 422 ms。復元: 106〜265 ms。変わったブロックは 0〜7 個
- **tick 0 で全チャンクのブロックとスケジュール件数が一致**するようになった (段階 1 では tick 0 からずれていた)
- run 1 vs run 0: tick 0 のプレイヤーの速度、tick 2 の狐の向きとプレイヤーの位置を除き、tick 97 まで全項目一致
- run 2・3 vs run 0: tick 0 でプレイヤーの位置・速度が違う → tick 1〜2 でワールドの Random と lcg、タラ、村人 (向きの上下 40 と 0) がずれる → tick 11〜31 でチャンクのブロック、tick 58 でクリーパー
- プレイヤーの速度が 0 でない回がある。隣にいる村人に押されていると思われる

### 仮説
- 残りのずれの主因はプレイヤー (まだ戻していない)。mob のスポーンはプレイヤーの周囲で場所を選ぶので、プレイヤーの位置が違うとワールドの Random の消費も変わる。村人の視線もプレイヤーに向く
- 次は段階 3 のプレイヤー

## 2026-09-30 インメモリ方式 段階 3 (プレイヤー) と、残りのずれの特定

### 実装
- プレイヤーも、エンティティと同じ複製器 (1 つの対応表) で複製する (`SharePolicy(sharePlayers=false)`)。乗り物、ターゲット、釣り針などの相互参照をそろえるため
- 復元では、プレイヤーは接続に結び付いているので差し替えず、`DeepCloner.copyInto` で生きているオブジェクトのフィールドに書き戻す
  - 保存時と違うディメンションにいる場合は、先に `teleport` で移す
  - `PlayerBefore`: チャンクの所属を直す。送信予定のエンティティ削除を捨てる。開いている画面を閉じる。`requestTeleport` と速度を送る。体力・満腹度・経験値の「前回送った値」を捨てる。インベントリ、持っているスロット、能力、ステータス効果 (外して付け直す)、DataTracker の全項目を送り直す
- PlayerAdvancementTracker、Advancement、StatHandler、RecipeBook は共有する (進捗・統計・レシピ本は戻さない)

### 観察と対策
- mem5.log: プレイヤーを戻したら、ずれるのはタラと牛だけになった。tick 1 でそのエンティティの Random は一致しているのに、位置・速度がずれる。どの回がずれるかも一定しない
- 仮説 1 (否定): 同一性ハッシュのキーを持つ HashSet/HashMap の列挙順が、複製のたびに変わるのが原因と考えた。こうしたコレクションは LinkedHashSet/LinkedHashMap で作り直すようにした (438 + 179 個が該当)。しかし結果は変わらなかった (mem6.log)。この変更は、決定論のためには正しい方向なので残している
- 調査用に `ObjectDumper` と `-PdetcheckDump=<uuid 先頭 | type:種類>` を追加した。対象エンティティの全フィールドを tick 0〜2 でファイルに書き出す
- mem9.log: ずれたタラは、tick 0 のダンプが全回一致した。tick 1 で、一方の回にだけ `navigation.currentPath` があった
- 原因: `EntityNavigation.checkTimeouts` / `SwimNavigation.checkTimeouts` が、ノードで詰まったかの判定に**実時間** (`Util.getMeasuringTimeMs()`) を使っている。復元すると `lastActiveTickMs` は保存時の実時刻のままなので、保存から復元までの経過時間 (数秒) が詰まった時間として数えられ、経路が打ち切られる (save 直後に戻した run 0 だけは打ち切られない)
  - サーバー側のゲーム処理で実時間を使っているのは、ほかに WorldBorder (縮小) とストラクチャーブロックだけ (grep で確認)
- 対策: `checkTimeouts` 内の `Util.getMeasuringTimeMs()` を「ワールドの時刻 × 50 ms」に置き換えた (`EntityNavigationClockMixin`)。20 TPS で動いているときの進み方はバニラと同じ
  - 最初の実装は、対象を 2 つ持つ Mixin で `@Shadow` を使ったため適用に失敗していた (`Found a remappable @Shadow annotation`)。このエラーは ERROR ログが出るだけで起動は止まらない。アクセサ経由に直した。以後、検査のたびに Mixin の ERROR もログで確認する
- **mem12.log: 4 回とも 200 tick の間、全項目 (全エンティティの位置・速度・向き・Random・体力、全チャンクのブロックとブロックエンティティ、スケジュール件数、RNG、時刻) が一致した**

### 未対応 (次にやること)
- JDK が作るラムダ (`Predicate.and` など) は中身を読めないので共有している (警告 1 件)。スナップショットより前のオブジェクトを指したままになる可能性がある
- 保存時に読み込まれていなかったチャンク、保存後にディスクへ書かれたチャンク
- PersistentState (raids、マップなど)、POI、ドラゴン戦、行商人、スコアボード
- 検査はプレイヤーが動かない場合だけ。プレイヤーが動いた後や、ディメンションをまたいだ後の復元はまだ試していない

## 2026-09-30 1200 tick × 3 回の検査 (mem13.log)

### 観察
- run 2 vs run 0: ずれたのは **tick 243 以降のプレイヤーの向きだけ**。他の全項目 (ワールドの Random、全エンティティ、全チャンク、RNG) は 1200 tick 一致した
- run 1 vs run 0 / run 2: tick 8 でプレイヤーの向きがわずかにずれ (上下 13.4437 と 13.4283)、tick 854 でワールドの Random、876 でブロック、899 以降で夜の mob のスポーンがずれた
- Mixin の ERROR はなし

### 仮説
- プレイヤーの向きはクライアントから送られてくる (マウスの入力、またはクライアント側の視点の処理)。原理的に揃えられない「人の入力」の類型に当たる。検査中にマウスが触れた可能性もある
- run 1 の tick 854 のずれは、プレイヤーの向きの違いが波及したものと思われる (エンダーマンの視線判定、村人や mob の視線など、プレイヤーの向きを使う処理は多い)。ただし未確認。向きを揃えた状態でも tick 854 付近でずれるなら、別の原因 (例: 変わったブロックの光を非同期で計算していること。夜の敵対 mob のスポーン判定は光の値に応じて world.random の消費回数が変わる) を疑う

## 2026-09-30 取得時に読み込まれていなかったチャンクと、ワールド全体の状態

### 実装
- `ChunkJournal` (スナップショットのワールドごと): 取得後の変化を記録する
  - `loadedAfter`: 取得時に読み込まれていなかったチャンクが、初めて読み込まれた瞬間の NBT (`WorldChunk.loadToWorld` の末尾)。読み込まれるまでは変化しないので、これが取得時点の状態に相当する。新しく生成されたチャンクは、生成直後の状態が「まだ生成されていない」状態の代わりになる
  - `written`: 取得後にディスクへ保存されたチャンク (`ThreadedAnvilChunkStorage.save(Chunk)`)
  - `poiBefore`: 取得後に初めて POI を書く直前の、ディスク上の内容 (`StorageIoWorker.setResult` の先頭で、POI の worker のときだけ)
  - 記録を止めるのは、復元中のスナップショットだけ。ほかのスロットのスナップショットにとっては、この復元による書き込みも変化なので記録する
- 復元 (`WorldChunksSnapshot.restoreOutside`):
  - 取得時に読み込まれていて今は外れているチャンクは、同期で読み込んでからメモリの内容を当てる
  - `loadedAfter` のチャンクは、今も読み込まれていれば NBT をその場で当てる (ブロック、ブロックエンティティ、ハイトマップ、スケジュール済みの tick、inhabitedTime。エンティティは入れ替えの後で NBT から作る)。外れていて `written` なら、ディスクに書き戻す
  - POI: メモリ上の中身 (`loadedElements`) を取得時の複製に入れ替え、保存待ちにする。`poiBefore` をディスクに書き戻す
- 読み込み済みのチャンクの集合を、取得時と同じにそろえる (`convergeLoadedSet`)。ワールドは進めず、次を繰り返す: 期限付きチケットの期限切れ処理 (purge)、チケットの反映、読み込みを外す処理、保留中のタスクの実行 (キューが空になるまで)。各回の間は 10 ms 待ち、最大 10 秒
  - その間、取得時のチャンクには自前の期限なしチケット (`mcsr_savestate_restore`、FULL レベル) を付けて保持する。外れると中のエンティティが NBT から作り直され、実行時の状態を失うため
  - 取得後に読み込まれたチャンクは、中身を取得時点相当に戻してあるので、外すときにその内容でディスクに保存される
- ワールド全体の状態は、エンティティと同じ複製器 (1 つの対応表) で複製し、生きているオブジェクトに書き戻す (`DeepCloner.forceClone` + `copyInto`)
  - 対象: スポーンの管理 (ファントム、行商人、猫、ゾンビの襲撃、略奪者)、PersistentState (raids、map_N、idcounts など。スコアボードは除く)、ドラゴン戦
  - 行商人のスポーンの待ち時間と確率 (level.dat 側)
  - `Raid` は共有をやめた (略奪者から参照されるので、エンティティと同じ対応表で複製する)

### 途中で見つけた問題 (観察と対策)
- 行商人の管理がワールドのプロパティを参照していて、ゲームルールまで複製しようとし、`GameRules.Key.hashCode` で NPE になった。ゲームルールのキーは `GameRules` クラスの static 定数で、「自分のクラス階層の static」の判定では拾えない → WorldProperties、SaveProperties、GameRules、GameRules.Key、WorldBorder を共有にした
- `PersistentStateManager.loadedStates` には値が null のものがある (バニラが「ファイルがなかった」ことを覚えておくため) → 扱わない。この NPE は、既存のエンティティを外した後、スナップショットのエンティティを入れる前に起きたため、そのワールドはエンティティが消えた状態になった。**復元の途中で例外が起きると、ワールドが中途半端な状態で残る**ことに注意 (未対策)
- プレイヤーの `cameraPosition` (チケットの管理側に登録されている位置) が書き戻しでスナップショットの値になり、チケットの管理側と食い違っていた。次の移動で `ChunkTicketManager.handleChunkLeave` が NPE → 書き戻し前の値に戻してから `updateCameraPosition` で移し直す
- `ServerChunkManager.executeQueuedTasks()` は 1 回で 1 タスクしか実行しない → キューが空になるまで回す
- **`ChunkHolder.getWorldChunk()` は tick 対象 (ticking、レベル 32 以下) のチャンクしか返さない**。これまでのスナップショット (段階 2) は、読み込まれているが tick されない境界のチャンク (レベル 33) を保存していなかった (571 個 → 境界を含めると 671 個)。境界のチャンクへの変化は戻らない状態だった → `getBorderFuture()` で FULL のチャンクをすべて保存するようにした。集合をそろえる処理と検査も、FULL の集合と ticking の集合の両方で比べる

### 観察 (mem24.log)
- かき乱しあり (`-PdetcheckDisturb`: 取得時のチャンクにガラスを置く → プレイヤーを 800 ブロック先の Y=200 へ飛ばす (飛行状態) → 400 tick 待つ → 飛んだ先の新しいチャンクに金ブロックを置く → 復元) で 4 回。**4 回とも 200 tick の間、全項目一致**
- かき乱し後の復元: 同期読み込み 142 チャンク、その場で当てたチャンク 625、集合をそろえる繰り返し約 185 回、合計約 2.7 秒

### 未確認
- 取得後に読み込まれ、その後ディスクに書かれたチャンクの書き戻し (今回の検査では 0 件)
- POI の書き戻し、レイド、ドラゴン戦、地図の復元は、検査で直接は確かめていない
- ネザー・エンドをまたいだ場合

## 2026-09-30 ネザー・エンドをまたぐ検査とドラゴン戦

### 検査の追加
- `-PdetcheckStart=overworld|nether|end`: ワールドを開いて落ち着いた後、プレイヤーをクリエイティブにしてそのディメンションへ移してから取得する (エンドは (0, 90, 60)、ドラゴンが出現するまで待つ)。クリエイティブにするのは、ドラゴンに狙われず、窒息や落下で死なないようにするため
- `-PdetcheckDisturb=far|dim|end`:
  - far: 取得時のチャンクにガラス → 同じディメンションで 800 ブロック先へ → 新しいチャンクに金ブロック
  - dim: 取得時のチャンクにガラス → 別のディメンションへ (オーバーワールド ⇔ ネザー) → そこに金ブロック
  - end: ドラゴンの体力を 60 減らし、エンドクリスタルを全部壊す → オーバーワールドへ → 金ブロック

### 観察と対策
- オーバーワールドで取得 → ネザーへ (dimA.log): 読み込み済みのチャンクは 0 個に戻ったのに、ネザーのエンティティが 36 体残った。`idleTimeout` (プレイヤーがいなくなってからの tick 数。300 を超えるとエンティティの tick が止まる) も戻していなかった
  - 対策 1: 集合をそろえる処理で、読み込みを外す処理 (`unloadedChunks`、外している途中の `field_18807`、外す処理のタスクキュー `field_19343`) が空になるまで待つ
  - 対策 2: `ServerWorld.idleTimeout` を保存・復元する
  - 検査側: 未シードの static な Random は比較しない (取得時にまだクラスが読み込まれていなかったものと、復元で未シードに戻したものは同じ扱い)
- dimB.log: run 1 は 200 tick 全項目一致。run 2 は tick 0〜2 だけ ticking のチャンク数が 529 と 527 で違った (そろえる処理が 3 秒で揃いきらなかった)。他は一致
- ネザーで取得 → オーバーワールドへ (dimC〜E.log): オーバーワールドに、どのチャンクにも属さないエンティティが 63 体残った。**記録の NBT からチャンクに入れたエンティティが、その直後の取り外しで外れずに残った**
  - 最初の対策 (効かなかった): 復元の最初に、途中まで進んでいる取り外しを終わらせる (`flushUnloads`)。これは残している
  - 対策: 取得後に読み込まれたチャンク (`loadedAfter`) は、復元中にその場で当てるのをやめた。集合をそろえると必ず外れるので、外れた後に記録した NBT をディスクへ書き戻す (`rewriteJournalChunks`)。外れずに残った場合だけ、その場で当てる
  - dimF.log: 3 回とも 200 tick 全項目一致
- エンドで取得 (ドラゴン戦の最中) → ドラゴンに 60 ダメージ・クリスタル 10 個を破壊 → オーバーワールドへ → 戻す:
  - endA.log (初回): エンド側 (ドラゴン、クリスタル、ブロック) は 300 tick 全項目一致。オーバーワールドの予約された tick の件数だけが、run 0 の 850 に対して run 1・2 は 726 で、最後までその差のまま。ブロックは全チャンク一致
  - 調査用に、集合をそろえる前後で「スナップショットの予約のうちスケジューラーにないもの」を出すログを入れた。endB.log (20 tick × 2)・endC.log (300 tick × 3) では再現せず、**3 回とも 300 tick 全項目一致**。欠けたログも出なかった
  - 仮説 (未確認): endA はコピーしたワールドで初めてエンドに入った回で、ドラゴン戦の初期化など初回だけの条件が絡んだ可能性がある。**未解決**
- far の回帰確認 (farR.log): 3 回とも 200 tick 全項目一致

## 2026-09-30 復元の途中で失敗してもワールドを壊さない

### 実装
- 復元を「準備」と「適用」に分けた (`MemorySnapshot.restore(server, makeUndo)`)
  - 準備: ワールドに手を入れずに、失敗しうる処理をすべて済ませる。チャンクと POI の複製 (`WorldChunksSnapshot.prepare`)、エンティティの複製、プレイヤーとワールド全体の状態に書き戻す値の計算 (`DeepCloner.copyIntoDeferred`: 値だけ計算して溜め、適用段階の `applyDeferredWrites` で書き込む)。ここで失敗したら何も変わらない
  - 準備が済んだら、取り消し用のスナップショットを取る (約 0.3 秒)
  - 適用で例外が起きたら、取り消し用のスナップショットでロールバックする
- 取り消し用のスナップショットは `lastUndo` として残し、キー「Undo last load」(初期状態では割り当てなし) で直前の load を取り消せる。取り消しの取り消しもできる
- 調査用: `-PfailApply=N` で、N 回目の復元の適用段階 (エンティティを外した直後) にわざと例外を投げる。そのとき、復元の直前とロールバック後の状態の要約を比べてログに出す。検査のドライバーは、load が失敗したら同じ回をやり直す

### 観察 (fault1.log)
- far のかき乱しあり、2 回目の復元で故障を注入: 例外 → ロールバック → **ロールバック後の状態は、復元の直前と全項目一致 (0 items differ)**。この時点の状態は、遠くへ移動してチャンクが 1154 個読み込まれていた
- やり直した回とその後の回は、200 tick 全項目一致

## 2026-09-30 初めてエンドに入った直後に取得すると、回によってエンティティの数が変わる

### 観察
- 新しくコピーしたワールド (detcheck_end2) で、オーバーワールドで落ち着かせてからエンドへ移し、取得 → 復元を繰り返した (40 tick × 2)
  - 取得: 202 体。run 0 (取得の直後に復元) は 178 体を入れ、「chunk ... is not loaded; entity skipped」が 34 件出た (オーバーワールドの羊・鶏・豚など。例: (-133, 71, -199))
  - run 1 (かき乱しでそのあたりのチャンクが読み込まれた後) は 192 体を入れた → tick 0 から違う
  - 復元後に、スナップショット由来でもプレイヤーでもないエンティティ (stray) は出なかった
- 取得時の `entitiesById` に、FULL で読み込まれていないチャンクのエンティティが入っていた

### 仮説
- プレイヤーをオーバーワールドからエンドへ移した直後は、オーバーワールドのチャンクの取り外しがタスクとして後から走るため、外れかけのチャンクのエンティティがしばらく `entitiesById` に残っている。それらを取り込むと、復元時にそのチャンクが読み込まれているかどうかで入る・入らないが変わる
- 2026-09-30 の endA.log (予約された tick の件数が 850 と 726 で違った) も同じ「初めてエンドに入った直後の取得」だったので、関係している可能性がある (未確認)

### 対策
- 取得時、FULL で読み込まれていないチャンクにいるエンティティ (プレイヤー以外) は取り込まない (`WorldChunksSnapshot.fullChunkKeys`)。それらはチャンクと一緒にディスクへ保存されるので、ディスク側の記録 (journal) で扱われる
- 調査用のログ: デバッグ時は復元後の stray エンティティを出す (不変条件の確認として残す)

### 観察 (対策後)
- endR3.log (新しいコピー detcheck_end3、start=end、disturb=end、300 tick × 3): 取得時に 24 体を除外。3 回とも 300 tick 全項目一致。「entity skipped」は 0 件
- farR2.log (far、200 tick × 3): 3 回とも全項目一致

## 2026-09-30 遠くへ移動した後の復元が遅い (約 4 秒)

### 観察 (計測)
- 訂正: 同日の farR2.log は `-PdetcheckDisturb` を付けておらず、かき乱しが入っていなかった (far の回帰確認になっていない)。以下は `-PdetcheckDisturb=far` を付けて測り直したもの
- 段階ごとの時間を出すログを入れた (デバッグ時のみ。`restore timing` と `apply timing`)。speed1.log (far): 準備 60〜90 ms、取り消し用の取得 60〜140 ms、適用のうち overworld のそろえる処理 (`convergeLoadedSet`) が 3.4〜4.3 秒で、ほぼ全部
- speed2.log: 読み込み済みの集合は約 0.4 秒でそろうが、その後 ticking でないチャンクが 88 個のまま約 3.5 秒続き、最後にまとめて解消する
- speed3.log: player ticket throttler のキューには常に 1 チャンクだけがあり、サンプルのチャンクには復元用のチケット (レベル 33) しか付いていなかった
- ソース (ChunkTicketManager): プレイヤーのチケットは throttler 経由で 1 チャンクずつ付く。1 チャンクごとに「ワーカー → メインスレッドのタスクで付ける → そのチャンクが entity ticking になる → メインスレッドのタスクでブロックを外す」の往復があり、それが済むまで次のチャンクへ進まない

### 仮説
- こちらのループは 1 回ごとに 10 ms sleep しており、throttler が 1 回に 1 チャンクしか進めないため、約 300 チャンク × 約 12 ms で約 3.5 秒かかっている

### 対策 1: 待ち方
- `Thread.sleep(10)` をやめ、`LockSupport.parkNanos` で最大 1 ms 待つ。メインスレッドの executor は、タスクが届くと `ThreadExecutor.send` でサーバースレッドを unpark するので、タスクが届けばすぐ起きる
- 上限 (10 秒) と「読み込み済みのチャンク数が変わらないままならあきらめる」(3 秒) は、回数ではなく時間で数える
- speed4.log (far、200 tick × 3): そろえる処理が 0.77〜0.82 秒。3 回とも全項目一致
- 検討してやめた案: throttler を通さず、プレイヤーのチケットを直接付ける。throttler のキューに残っている古い「チケットを外す」メッセージが後から実行されると、直接付けたチケットが外れる順序の問題があるため、vanilla の経路はそのままにした

### 観察 2: 移動していない復元でも、そろえる処理の 1 回目に約 200 ms かかる
- speed5.log: 1 回目の `unloadTick` (ThreadedAnvilChunkStorage.tick) が 190〜265 ms。その中の POI の保存 (`SerializingRegionBasedStorage.tick`) と思われる
- 原因と思われるもの: `restorePoi` が、取得時に読み込まれていたすべての POI セクションを未保存にしていた。そのため復元のたびに全セクションを書き直し、さらに書き込みごとに他の有効な journal (直前に取った取り消し用スナップショットなど) が、上書き前の内容をディスクから読んでいた

### 対策 2: POI の未保存の集合を取得時と同じにする
- 取得時の `unsavedElements` を記録し、復元ではそれだけを未保存にする。取得後にディスクへ書かれた列は `poiBefore` で取得時のディスクの内容に戻すので、メモリ・未保存の集合・ディスクの 3 つが取得時と同じになる
- speed6.log: 移動していない復元の適用が 281 ms → 76 ms (1 回目の `unloadTick` が 9 us)。far は 0.55〜1.0 秒。200 tick × 3 回とも全項目一致
- 注意: 決定論の検査は POI そのものを比べていない (村人などの振る舞いを通して間接的に見ているだけ)

### 回帰確認
- speedDim.log (dim): 3 回とも 200 tick 全項目一致。復元 0.5〜0.77 秒
- speed_end4.log (新しいコピー、start=end、disturb=end): 3 回とも 300 tick 全項目一致。復元 0.38〜0.72 秒 (以前は約 1.1 秒)
- speed_nether1.log (新しいコピー、start=nether、disturb=dim): 3 回とも 300 tick 全項目一致。復元 0.87〜0.92 秒

### 残り
- far の後の残り時間は、外れかけていたチャンクが FULL に戻る非同期処理 (約 250〜450 ms) と、throttler がプレイヤーのチケットを 1 チャンクずつ付ける待ち。後者をさらに縮めるには throttler を迂回する必要があり、上の順序の問題があるので未着手

## 2026-09-30 SpeedrunAPI に依存させる (ユーザーの指示)

### 経緯
- 当初の制約は「Fabric API を使わない」。ユーザーの指示で SpeedrunAPI (contariaa/SpeedrunAPI、MCSR の MOD が使う設定 API) に依存させた。SpeedrunAPI は Fabric API のうち key-binding-api (tildejustin によるポート) を同梱し、MOD の assets (lang) を読み込む仕組みも持つ
- バージョン: jitpack の `v2.2-1.16-1.16.1` (2026-09-30 時点の最新タグ)。`gradle.properties` の `speedrunapi_version`

### 変更
- 設定 (`SavestateConfig`、config/mcsr/mcsr-savestate.json): 方式 (memory / disk) とスロット数 (1〜9)
  - 検査用に `-Dmcsr-savestate.mode` があればそちらを優先する
  - 調査用のプロパティ (debug、detcheck、failApply、audit) は開発用なので、システムプロパティのまま
  - 取り消し用スナップショットを取らない設定は出さなかった。load の途中で失敗したときのロールバックにも使っているため
- キー: `KeyBindingHelper` で登録し、自前の `GameOptionsMixin` と `categoryOrderMap` の登録を削除した。表示名は翻訳キー (assets/mcsr-savestate/lang の en_us / ja_jp)
  - 翻訳キーに変えたので、以前の options.txt のキー割り当て (`key_Save state` など) は引き継がれない

### 確認 (sapi1〜3.log)
- 読み込まれる MOD に speedrunapi 2.2 と fabric-key-binding-api-v1 が入り、設定ファイルが既定値で作られた。Mixin の監査でエラーなし
- キーが `GameOptions.keysAll` に入っている。ワールドに入った後、翻訳キーが「Save state」に解決される (最初の tick はリソースの読み込み前で、未解決だった)
- far、100 tick × 2: 全項目一致
- 設定画面の見た目は確認していない
