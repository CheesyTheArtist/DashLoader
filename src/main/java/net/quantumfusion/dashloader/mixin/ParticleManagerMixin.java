package net.quantumfusion.dashloader.mixin;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.client.particle.ParticleTextureSheet;
import net.minecraft.client.texture.MissingSprite;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceReloadListener;
import net.minecraft.util.Identifier;
import net.minecraft.util.profiler.Profiler;
import net.minecraft.util.registry.Registry;
import net.quantumfusion.dashloader.DashLoader;
import org.apache.logging.log4j.LogManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

@Mixin(ParticleManager.class)
public abstract class ParticleManagerMixin {

    @Shadow
    @Final
    private Map<Identifier, ParticleManager.SimpleSpriteProvider> spriteAwareFactories;

    @Shadow
    protected abstract void loadTextureList(ResourceManager resourceManager, Identifier id, Map<Identifier, List<Identifier>> result);

    @Shadow
    @Final
    private SpriteAtlasTexture particleAtlasTexture;

    @Shadow
    @Final
    private Map<ParticleTextureSheet, Queue<Particle>> particles;

    @Inject(method = "reload(Lnet/minecraft/resource/ResourceReloadListener$Synchronizer;Lnet/minecraft/resource/ResourceManager;Lnet/minecraft/util/profiler/Profiler;Lnet/minecraft/util/profiler/Profiler;Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;",
            at = @At(value = "HEAD"), cancellable = true)
    private void reloadParticlesFast(ResourceReloadListener.Synchronizer synchronizer, ResourceManager manager, Profiler prepareProfiler, Profiler applyProfiler, Executor prepareExecutor, Executor applyExecutor, CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        if (DashLoader.getInstance().getParticlesOut() != null) {
            LogManager.getLogger().info("Particles loading");
            cir.setReturnValue(CompletableFuture.runAsync(() -> DashLoader.getInstance().getParticlesOut().forEach((identifier, sprites) -> spriteAwareFactories.get(identifier).setSprites(sprites))).thenCompose(synchronizer::whenPrepared));
        } else {
            Map<Identifier, List<Identifier>> map = Maps.newConcurrentMap();
            CompletableFuture<?>[] completableFutures = Registry.PARTICLE_TYPE.getIds().stream().map((identifier)
                    -> CompletableFuture.runAsync(()
                    -> this.loadTextureList(manager, identifier, map), prepareExecutor)).toArray(CompletableFuture<?>[]::new);
            CompletableFuture<?> var10000 = CompletableFuture.allOf(completableFutures).thenApplyAsync((void_)
                    -> {
                prepareProfiler.startTick();
                prepareProfiler.push("stitching");
                SpriteAtlasTexture.Data data = this.particleAtlasTexture.stitch(manager, map.values().stream().flatMap(Collection::stream), prepareProfiler, 0);
                prepareProfiler.pop();
                prepareProfiler.endTick();
                return data;
            }, prepareExecutor);
            cir.setReturnValue(var10000.thenCompose(synchronizer::whenPrepared).thenAcceptAsync((data) -> {
                LogManager.getLogger().info("Particle Apply");
                this.particles.clear();
                applyProfiler.startTick();
                applyProfiler.push("upload");
                this.particleAtlasTexture.upload((SpriteAtlasTexture.Data) data);
                applyProfiler.swap("bindSpriteSets");
                Sprite sprite = this.particleAtlasTexture.getSprite(MissingSprite.getMissingSpriteId());
                map.forEach((identifier, spritesAssets) -> {
                    ImmutableList<Sprite> spriteList;
                    if (spritesAssets.isEmpty()) {
                        spriteList = ImmutableList.of(sprite);
                    } else {
                        Stream<Identifier> spriteStream = spritesAssets.stream();
                        SpriteAtlasTexture spriteAtlasTexture = this.particleAtlasTexture;
                        spriteList = spriteStream.map(spriteAtlasTexture::getSprite).collect(ImmutableList.toImmutableList());
                    }
                    ImmutableList<Sprite> immutableList = spriteList;
                    ((ParticleManagerSimpleSpriteProviderAccessor) this.spriteAwareFactories.get(identifier)).setSprites(immutableList);
                });


                DashLoader.getInstance().addParticleManagerAssets(spriteAwareFactories, particleAtlasTexture);

                applyProfiler.pop();
                applyProfiler.endTick();
            }, applyExecutor));
        }
        cir.cancel();
    }
}
