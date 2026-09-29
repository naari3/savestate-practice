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
