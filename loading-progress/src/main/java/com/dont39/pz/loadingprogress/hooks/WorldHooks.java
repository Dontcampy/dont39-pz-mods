package com.dont39.pz.loadingprogress.hooks;

import com.pzlauncher.zabric.hook.At;
import com.pzlauncher.zabric.hook.CallbackInfo;
import com.pzlauncher.zabric.hook.CallbackInfoReturnable;
import com.pzlauncher.zabric.hook.Hooks;
import com.pzlauncher.zabric.hook.Inject;
import zombie.ZomboidFileSystem;
import zombie.asset.Asset;
import zombie.core.skinnedmodel.ModelManager;
import zombie.core.skinnedmodel.model.AnimationAssetManager;
import zombie.core.skinnedmodel.population.OutfitManager;
import zombie.iso.IsoChunk;
import zombie.iso.IsoChunkMap;
import zombie.iso.IsoLot;
import zombie.iso.WorldStreamer;

import static com.dont39.pz.loadingprogress.hooks.Loading.PROGRESS;

/**
 * The world load's items, from its loading thread and the threads it starts, and the asset loads it waits for. The
 * per-item methods take game types, so these hook callees and polls with JDK-only descriptors.
 */
public final class WorldHooks {
    private WorldHooks() {
    }

    // polled in every frame while the loading thread waits, before it builds the world
    @Hooks(OutfitManager.class)
    public static final class Clothing {
        private Clothing() {
        }

        @Inject(method = "isLoadingClothingItems()Z", at = @At("HEAD"))
        public static void waiting(CallbackInfoReturnable<Boolean> cir) {
            if (PROGRESS.loadsWorld()) {
                int loaded = 0;
                int total = 0;
                // one pass, so loaded never exceeds total while the table changes
                for (OutfitManager.ClothingItemEntry entry : OutfitManager.instance.cachedClothingItems.values()) {
                    total++;
                    if (!entry.item.isEmpty()) {
                        loaded++;
                    }
                }
                PROGRESS.clothingItems(loaded, total);
            }
        }
    }

    // polled in every frame while the loading thread waits, after it built the world; the asset manager's table, as
    // ModelManager loads before the access widener could open its own map
    @Hooks(ModelManager.class)
    public static final class Animations {
        private Animations() {
        }

        @Inject(method = "isLoadingAnimations()Z", at = @At("HEAD"))
        public static void waiting(CallbackInfoReturnable<Boolean> cir) {
            if (PROGRESS.loadsWorld()) {
                int loaded = 0;
                int total = 0;
                // one pass, so loaded never exceeds total while the table changes
                for (Asset asset : AnimationAssetManager.instance.getAssetTable().values()) {
                    total++;
                    if (!asset.isEmpty()) {
                        loaded++;
                    }
                }
                PROGRESS.animations(loaded, total);
            }
        }
    }

    // per position of the map's grid, on the loader threads; most positions have no cell
    @Hooks(targets = "zombie.iso.IsoMetaGrid$MetaGridLoaderThread")
    public static final class MapCells {
        private MapCells() {
        }

        @Inject(method = "loadCell(II)V", at = @At("HEAD"))
        public static void cell(int x, int y, CallbackInfo ci) {
            if (PROGRESS.loadsWorld() && IsoLot.MapFiles.stream().anyMatch(files -> files.hasCell(x, y))) {
                PROGRESS.mapCell(x + "_" + y, IsoLot.InfoHeaders.size());
            }
        }
    }

    // the game's files by media path; the mods' as one item per pass: their per-file call, getRelativeFile, is
    // skipped whole by Viewpoint's patch, HEAD hooks included
    @Hooks(ZomboidFileSystem.class)
    public static final class TileDefinitions {
        private TileDefinitions() {
        }

        @Inject(method = "getMediaPath(Ljava/lang/String;)Ljava/lang/String;", at = @At("HEAD"))
        public static void game(String path, CallbackInfoReturnable<String> cir) {
            if (PROGRESS.loadsWorld() && path != null && path.endsWith(".tiles")) {
                PROGRESS.tileDefinitions(path);
            }
        }

        @Inject(method = "loadModTileDefPropertyStrings()V", at = @At("HEAD"))
        public static void modProperties(CallbackInfo ci) {
            PROGRESS.modTileDefinitions();
        }

        @Inject(method = "loadModTileDefs()V", at = @At("HEAD"))
        public static void mods(CallbackInfo ci) {
            PROGRESS.modTileDefinitions();
        }
    }

    // per chunk of the player's grid in single player; a join requests its chunks through LoadChunkForLater, whose
    // descriptor names a game type
    @Hooks(IsoChunkMap.class)
    public static final class ChunksQueued {
        private ChunksQueued() {
        }

        @Inject(method = "LoadChunk(IIII)V", at = @At("HEAD"))
        public static void queued(CallbackInfo ci) {
            if (PROGRESS.loadsWorld()) {
                PROGRESS.chunkQueued(IsoChunk.loadGridSquare.size());
            }
        }
    }

    // polled every 100 ms by the loading thread until the queued chunks are loaded
    @Hooks(WorldStreamer.class)
    public static final class Chunks {
        private Chunks() {
        }

        @Inject(method = "isBusy()Z", at = @At("HEAD"))
        public static void polled(CallbackInfoReturnable<Boolean> cir) {
            if (PROGRESS.loadsWorld()) {
                PROGRESS.chunks(IsoChunk.loadGridSquare.size());
            }
        }
    }
}
