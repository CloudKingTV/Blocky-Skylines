# Setup and Toolchain (26.x)

Versions below were checked on **2026-10-18** against the official templates. Re-check them
when starting a project: NeoForge versions are listed at `projects.neoforged.net/neoforged/neoforge`
and Fabric's at `fabricmc.net/develop`, or read the current template's `gradle.properties`.

## Current versions

| Thing | Value |
|---|---|
| Latest Minecraft release | **26.3** "Wilderness Bound" (2026-09-15). 26.4 snapshots in progress |
| Version scheme | `YY.drop[.hotfix]`: 26.1, 26.1.2, 26.2, 26.3 … |
| Java | **25** for both toolchain and runtime (JEP 447: statements before `super(...)` are legal) |
| IDE | IntelliJ IDEA 2025.3+ (mixin support for 26.x), Eclipse 2025-12+ |
| NeoForge | `26.3.0.90` (4-part: MC `26.3.0` + build `90`; `-beta` suffix means breaking changes possible) |
| ModDevGradle | `net.neoforged.moddev` **2.0.148**; MDK wrapper Gradle 9.2.1 |
| Fabric Loader | **0.19.5** |
| Fabric Loom | `net.fabricmc.fabric-loom` **1.18-SNAPSHOT** (the non-remapping variant; 26.x has no obfuscation) |
| Fabric API | **0.163.0+26.3**; Fabric example mod wrapper Gradle 9.7.1 |
| Mappings | Mojang's official names, built into the game. **Yarn is discontinued** for 26.x. Parchment is optional and only adds parameter names/javadoc, which 26.x mostly ships already |
| GeckoLib | **5.5.7** for 26.3 (package `com.geckolib`, formerly `software.bernie.geckolib`) |
| Player Animation Library (ZigyTheBird) | 1.2.8 for 26.3 / 26.1 |

## Scaffolding

Use the official templates; don't hand-write build scripts from memory.

```bash
# NeoForge (one repo per MC version under the NeoForgeMDKs org)
git clone --depth 1 https://github.com/NeoForgeMDKs/MDK-26.3-ModDevGradle.git mymod
# Fabric (default branch tracks latest; branches exist per version, e.g. 26.2)
git clone --depth 1 https://github.com/FabricMC/fabric-example-mod.git mymod
```

Or run `scripts/scaffold.sh <neoforge|fabric> <modid> <com.your.pkg> [mc-version] [dir]`, which
clones the template and renames the mod id, package and display name.

### NeoForge project shape (MDK 26.3)

```
build.gradle            # plugins: java-library, maven-publish, net.neoforged.moddev 2.0.148, idea
gradle.properties       # minecraft_version=26.3, minecraft_version_range=[26.3], neo_version=26.3.0.90,
                        # mod_id, mod_name, mod_license, mod_version, mod_group_id
settings.gradle         # foojay toolchain resolver
src/main/java/<pkg>/ExampleMod.java        # @Mod(MODID) - common entry
src/main/java/<pkg>/ExampleModClient.java  # @Mod(value=MODID, dist=Dist.CLIENT) - client entry
src/main/java/<pkg>/Config.java            # ModConfigSpec
src/main/templates/META-INF/neoforge.mods.toml  # expanded by generateModMetadata task
src/main/resources/assets/<modid>/...
src/generated/resources/                   # datagen output (included as a resource dir)
```

Key `build.gradle` parts:

```groovy
java.toolchain.languageVersion = JavaLanguageVersion.of(25)

neoForge {
    version = project.neo_version
    // accessTransformers auto-detected at src/main/resources/META-INF/accesstransformer.cfg
    runs {
        client { client(); systemProperty 'neoforge.enabledGameTestNamespaces', project.mod_id }
        server { server(); programArgument '--nogui' }
        gameTestServer { type = "gameTestServer" }
        data {
            clientData()
            programArguments.addAll '--mod', project.mod_id, '--all',
                '--output', file('src/generated/resources/').getAbsolutePath(),
                '--existing', file('src/main/resources/').getAbsolutePath()
        }
        configureEach { logLevel = org.slf4j.event.Level.DEBUG }
    }
    mods { "${mod_id}" { sourceSet(sourceSets.main) } }
}

configurations { runtimeClasspath.extendsFrom localRuntime }  // runtime-only deps that aren't published
```

Entry points:

```java
@Mod(ExampleMod.MODID)
public class ExampleMod {
    public static final String MODID = "examplemod";
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);

    public ExampleMod(IEventBus modEventBus, ModContainer modContainer) {
        ITEMS.register(modEventBus);
        modEventBus.addListener(this::commonSetup);
        // 26.3 MDK uses LOCAL (loaded on both sides, not synced). Docs written for 26.1 call it COMMON.
        // Others: CLIENT (client only), SERVER (per-world, synced), STARTUP (avoid for content toggles)
        modContainer.registerConfig(ModConfig.Type.LOCAL, Config.SPEC);
    }
    private void commonSetup(FMLCommonSetupEvent event) { }
}

// Never loaded on a dedicated server: safe place for client-only references.
@Mod(value = ExampleMod.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = ExampleMod.MODID, value = Dist.CLIENT)
public class ExampleModClient {
    public ExampleModClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
    @SubscribeEvent static void onClientSetup(FMLClientSetupEvent event) { }
}
```

`@EventBusSubscriber` routes each `@SubscribeEvent` static method to the right bus
automatically. Events implementing `IModBusEvent` (registration events, `RegisterKeyMappingsEvent`,
`RegisterGuiLayersEvent`, `AddClientReloadListenersEvent`, `EntityRenderersEvent.*`) go to the
mod bus. Everything else (`ClientTickEvent.Post`, `ViewportEvent.*`, `PlayerEvent.*`,
`AddServerReloadListenersEvent`) goes to the game bus `NeoForge.EVENT_BUS`.

### Fabric project shape (example mod 26.3)

```
build.gradle      # id 'net.fabricmc.fabric-loom' version "${loom_version}"; loom { splitEnvironmentSourceSets() }
gradle.properties # minecraft_version=26.3, loader_version=0.19.5, loom_version=1.18-SNAPSHOT,
                  # fabric_api_version=0.163.0+26.3
src/main/java/...           # common code (ModInitializer)
src/client/java/...         # client-only code (ClientModInitializer); can't be referenced from main
src/main/resources/fabric.mod.json
src/main/resources/<modid>.mixins.json
src/client/resources/<modid>.client.mixins.json
```

```groovy
dependencies {
    minecraft "com.mojang:minecraft:${project.minecraft_version}"
    implementation "net.fabricmc:fabric-loader:${project.loader_version}"
    implementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_api_version}"
}
tasks.withType(JavaCompile).configureEach { it.options.release = 25 }
```

Note: dependencies use plain `implementation`, not `modImplementation`, because there's no
remapping on 26.x.

`fabric.mod.json` essentials:

```json
{
  "schemaVersion": 1, "id": "modid", "version": "${version}",
  "environment": "*",
  "entrypoints": { "main": ["com.example.ExampleMod"], "client": ["com.example.client.ExampleModClient"] },
  "mixins": ["modid.mixins.json", { "config": "modid.client.mixins.json", "environment": "client" }],
  "depends": { "fabricloader": ">=0.19.5", "minecraft": "~26.3", "java": ">=25", "fabric-api": "*" }
}
```

## Adding library dependencies

```groovy
repositories {
    maven { url = "https://dl.cloudsmith.io/public/geckolib3/geckolib/maven/" } // GeckoLib
    maven { url = "https://api.modrinth.com/maven" }                           // any Modrinth project
}
dependencies {
    // GeckoLib 5: artifact is geckolib-<loader>-<mc>; group com.geckolib (verify on the maven index)
    implementation "com.geckolib:geckolib-neoforge-26.3:5.5.7"   // or geckolib-fabric-26.3
    // Modrinth maven: maven.modrinth:<slug>:<version-id>
}
```

Then declare the dependency in `neoforge.mods.toml` (`[[dependencies.<modid>]] modId="geckolib" type="required"`)
or in `fabric.mod.json` `depends`.

## Mixins, access transformers and class tweakers

Prefer an event or API hook. Use a mixin only when none exists, and keep it surgical:
`@Inject` at `HEAD`/`RETURN`/`TAIL`, MixinExtras `@WrapOperation`/`@ModifyExpressionValue` rather
than `@Redirect`/`@Overwrite`, and an `@Unique` prefix on added members.

- **NeoForge**: mixins are supported (`[[mixins]] config="modid.mixins.json"` in
  `neoforge.mods.toml`). To widen access, use `META-INF/accesstransformer.cfg`
  (`public net.minecraft.client.Camera setPosition(Lnet/minecraft/world/phys/Vec3;)V`).
- **Fabric**: class tweakers (formerly access wideners, Loader 0.18+ / Loom 1.12+). The file is
  `src/main/resources/<modid>.classtweaker` with header `classTweaker  v1  official`. Set
  `loom { accessWidenerPath = file(...) }` and `"accessWidener": "<modid>.classtweaker"` in
  `fabric.mod.json`. Directives: `accessible`, `extendable`, `mutable`, `inject-interface`,
  `extend-enum` (and `transitive-` variants). Run `./gradlew validateAccessWidener`.
- Accessor/invoker mixins (`@Accessor`, `@Invoker`) work on both loaders for private members.

## Everyday commands

```bash
./gradlew build                 # compile + jar in build/libs
./gradlew runClient             # dev client (NeoForge MDG and Loom both)
./gradlew runServer             # dedicated server: catches client-class leaks
./gradlew runData               # NeoForge datagen (MDG run named 'data'); Fabric: runDatagen
./gradlew runGameTestServer     # NeoForge gametests; Fabric: runGametest / client gametest tasks
./gradlew --refresh-dependencies
```

Decompiled sources: IntelliJ attaches them after Gradle sync (MDG and Loom both produce a
sources jar). **Use them to check every vanilla signature.** Search with Ctrl+N and
"Find Usages" on vanilla code, which shows how Mojang uses an API.

## Publishing checklist

- Version bump, changelog, `mod_license` set, `displayURL`/`issueTrackerURL`, an icon.
- The `minecraft_version_range` / `"minecraft": "~26.3"` range is correct; don't claim versions
  you haven't tested.
- Test on a dedicated server and in a fresh instance with only the required deps.
- Modrinth/CurseForge: upload the per-loader jars and mark dependencies (GeckoLib, Fabric API).
  The Modrinth `minotaur` and CurseForge `CurseGradle`/`mod-publish-plugin` Gradle plugins can
  automate this.
