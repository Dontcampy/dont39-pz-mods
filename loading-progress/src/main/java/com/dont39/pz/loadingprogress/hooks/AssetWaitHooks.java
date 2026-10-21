package com.dont39.pz.loadingprogress.hooks;

import com.dont39.pz.loadingprogress.progress.AssetWait;
import com.dont39.pz.loadingprogress.progress.FileWork;
import com.pzlauncher.zabric.hook.At;
import com.pzlauncher.zabric.hook.CallbackInfo;
import com.pzlauncher.zabric.hook.CallbackInfoReturnable;
import com.pzlauncher.zabric.hook.Hooks;
import com.pzlauncher.zabric.hook.Inject;
import com.pzlauncher.zabric.hook.Thrown;
import zombie.GameWindow;
import zombie.ZomboidFileSystem;
import zombie.asset.FileTask_Exists;
import zombie.asset.FileTask_LoadPackImage;
import zombie.core.skinnedmodel.model.FileTask_AbstractLoadModel;
import zombie.core.textures.TextureIDAssetManager;
import zombie.debug.DebugLogStream;
import zombie.fileSystem.FileSystemImpl;
import zombie.fileSystem.FileTask;
import zombie.fileSystem.TexturePackDevice;
import zombie.tileDepth.TileDepthTextures;
import zombie.worldMap.FileTask_LoadImagePyramidTexture;

import java.io.File;

import static com.dont39.pz.loadingprogress.hooks.Loading.PROGRESS;

/**
 * The world load's wait for the game's file tasks: its bounds, the queue the game pumps once per frame, and each task's
 * call on a worker. A task's call has no JDK-only way to name its task, so its time is matched to the task by the
 * result the game takes on the pumping thread.
 */
public final class AssetWaitHooks {
    private static final String MODEL = "FileTask_AbstractLoadModel";

    private AssetWaitHooks() {
    }

    // DebugType.println(String) has a varargs overload the loader would hook too
    @Hooks(DebugLogStream.class)
    public static final class Bounds {
        private Bounds() {
        }

        @Inject(method = "println(Ljava/lang/String;)V", at = @At("HEAD"))
        public static void line(String line, CallbackInfo ci) {
            if (!PROGRESS.loadsWorld()) {
                return;
            }
            if (AssetWait.BEGINS.equals(line)) {
                PROGRESS.assetWaitBegins(System.nanoTime(), System.currentTimeMillis());
            } else if (AssetWait.ENDS.equals(line)) {
                PROGRESS.assetWaitEnds(System.nanoTime(), System.currentTimeMillis());
            }
        }
    }

    // on the game thread, once per frame; its own thread is the only one that changes the lists
    @Hooks(FileSystemImpl.class)
    public static final class Queue {
        private Queue() {
        }

        @Inject(method = "updateAsyncTransactions()V", at = @At("RETURN"))
        public static void pumped(CallbackInfo ci) {
            FileWork files = PROGRESS.files();
            if (!files.tracking()) {
                return;
            }
            if (!(GameWindow.fileSystem instanceof FileSystemImpl queue)) {
                throw new IllegalStateException("the game's file system " + GameWindow.fileSystem
                        + " is not the FileSystemImpl whose queue was pumped");
            }
            files.pumped(System.nanoTime(), queue.inProgress, queue.pending.size(), AssetWaitHooks::describe);
        }
    }

    // a texture decoder sleeps here while the decoded images wait for the render thread to upload them
    @Hooks(TextureIDAssetManager.class)
    public static final class Throttle {
        private Throttle() {
        }

        @Inject(method = "waitFileTask()V", at = @At("HEAD"))
        public static void begins(CallbackInfo ci) {
            if (PROGRESS.files().waiting()) {
                PROGRESS.files().throttleBegins(System.nanoTime());
            }
        }

        @Inject(method = "waitFileTask()V", at = @At("FINALLY"))
        public static void ends(CallbackInfo ci, @Thrown Throwable thrown) {
            if (PROGRESS.files().waiting()) {
                PROGRESS.files().throttleEnds(System.nanoTime());
            }
        }
    }

    // a model task's call resolves its file on its worker just before the import; the task names only the asset
    @Hooks(ZomboidFileSystem.class)
    public static final class ModelFile {
        private ModelFile() {
        }

        @Inject(method = "validatePrefix(Ljava/lang/String;)V", at = @At("HEAD"))
        public static void resolved(String path, CallbackInfo ci) {
            if (PROGRESS.files().tracking()) {
                PROGRESS.files().callFile(path);
            }
        }
    }

    // the classes of the game that declare a file task's call, one hook each

    @Hooks(targets = "zombie.asset.FileTask_Exists")
    public static final class Exists {
        private Exists() {
        }

        @Inject(method = "call()Ljava/lang/Object;", at = @At("HEAD"))
        public static void begins(CallbackInfoReturnable<Object> cir) {
            callBegins();
        }

        @Inject(method = "call()Ljava/lang/Object;", at = @At("FINALLY"))
        public static void ends(CallbackInfoReturnable<Object> cir, @Thrown Throwable thrown) {
            callEnds("FileTask_Exists", cir, thrown);
        }
    }

    @Hooks(targets = "zombie.asset.FileTask_LoadAiScene")
    public static final class AiScene {
        private AiScene() {
        }

        @Inject(method = "call()Ljava/lang/Object;", at = @At("HEAD"))
        public static void begins(CallbackInfoReturnable<Object> cir) {
            callBegins();
        }

        @Inject(method = "call()Ljava/lang/Object;", at = @At("FINALLY"))
        public static void ends(CallbackInfoReturnable<Object> cir, @Thrown Throwable thrown) {
            callEnds("FileTask_LoadAiScene", cir, thrown);
        }
    }

    @Hooks(targets = "zombie.asset.FileTask_LoadImageData")
    public static final class ImageData {
        private ImageData() {
        }

        @Inject(method = "call()Ljava/lang/Object;", at = @At("HEAD"))
        public static void begins(CallbackInfoReturnable<Object> cir) {
            callBegins();
        }

        @Inject(method = "call()Ljava/lang/Object;", at = @At("FINALLY"))
        public static void ends(CallbackInfoReturnable<Object> cir, @Thrown Throwable thrown) {
            callEnds("FileTask_LoadImageData", cir, thrown);
        }
    }

    @Hooks(targets = "zombie.asset.FileTask_LoadPackImage")
    public static final class PackImage {
        private PackImage() {
        }

        @Inject(method = "call()Ljava/lang/Object;", at = @At("HEAD"))
        public static void begins(CallbackInfoReturnable<Object> cir) {
            callBegins();
        }

        @Inject(method = "call()Ljava/lang/Object;", at = @At("FINALLY"))
        public static void ends(CallbackInfoReturnable<Object> cir, @Thrown Throwable thrown) {
            callEnds("FileTask_LoadPackImage", cir, thrown);
        }
    }

    @Hooks(targets = "zombie.asset.FileTask_ParseXML")
    public static final class Xml {
        private Xml() {
        }

        @Inject(method = "call()Ljava/lang/Object;", at = @At("HEAD"))
        public static void begins(CallbackInfoReturnable<Object> cir) {
            callBegins();
        }

        @Inject(method = "call()Ljava/lang/Object;", at = @At("FINALLY"))
        public static void ends(CallbackInfoReturnable<Object> cir, @Thrown Throwable thrown) {
            callEnds("FileTask_ParseXML", cir, thrown);
        }
    }

    @Hooks(targets = "zombie.core.skinnedmodel.model.AiSceneAssetManager$FileTask_LoadAiScene")
    public static final class ModelAiScene {
        private ModelAiScene() {
        }

        @Inject(method = "call()Ljava/lang/Object;", at = @At("HEAD"))
        public static void begins(CallbackInfoReturnable<Object> cir) {
            callBegins();
        }

        @Inject(method = "call()Ljava/lang/Object;", at = @At("FINALLY"))
        public static void ends(CallbackInfoReturnable<Object> cir, @Thrown Throwable thrown) {
            callEnds("AiSceneAssetManager$FileTask_LoadAiScene", cir, thrown);
        }
    }

    // meshes, animations and physics shapes
    @Hooks(targets = "zombie.core.skinnedmodel.model.FileTask_AbstractLoadModel")
    public static final class Model {
        private Model() {
        }

        @Inject(method = "call()Ljava/lang/Object;", at = @At("HEAD"))
        public static void begins(CallbackInfoReturnable<Object> cir) {
            callBegins();
        }

        @Inject(method = "call()Ljava/lang/Object;", at = @At("FINALLY"))
        public static void ends(CallbackInfoReturnable<Object> cir, @Thrown Throwable thrown) {
            callEnds(MODEL, cir, thrown);
        }
    }

    @Hooks(targets = "zombie.fileSystem.FileSystemImpl$OpenTask")
    public static final class Open {
        private Open() {
        }

        @Inject(method = "call()Ljava/lang/Object;", at = @At("HEAD"))
        public static void begins(CallbackInfoReturnable<Object> cir) {
            callBegins();
        }

        @Inject(method = "call()Ljava/lang/Object;", at = @At("FINALLY"))
        public static void ends(CallbackInfoReturnable<Object> cir, @Thrown Throwable thrown) {
            callEnds("FileSystemImpl$OpenTask", cir, thrown);
        }
    }

    @Hooks(targets = "zombie.fileSystem.FileSystemImpl$CloseTask")
    public static final class Close {
        private Close() {
        }

        @Inject(method = "call()Ljava/lang/Object;", at = @At("HEAD"))
        public static void begins(CallbackInfoReturnable<Object> cir) {
            callBegins();
        }

        @Inject(method = "call()Ljava/lang/Object;", at = @At("FINALLY"))
        public static void ends(CallbackInfoReturnable<Object> cir, @Thrown Throwable thrown) {
            callEnds("FileSystemImpl$CloseTask", cir, thrown);
        }
    }

    @Hooks(targets = "zombie.tileDepth.TileDepthTextures$LoadTask")
    public static final class DepthMap {
        private DepthMap() {
        }

        @Inject(method = "call()Ljava/lang/Object;", at = @At("HEAD"))
        public static void begins(CallbackInfoReturnable<Object> cir) {
            callBegins();
        }

        @Inject(method = "call()Ljava/lang/Object;", at = @At("FINALLY"))
        public static void ends(CallbackInfoReturnable<Object> cir, @Thrown Throwable thrown) {
            callEnds("TileDepthTextures$LoadTask", cir, thrown);
        }
    }

    @Hooks(targets = "zombie.worldMap.FileTask_LoadImagePyramidTexture")
    public static final class MapImage {
        private MapImage() {
        }

        @Inject(method = "call()Ljava/lang/Object;", at = @At("HEAD"))
        public static void begins(CallbackInfoReturnable<Object> cir) {
            callBegins();
        }

        @Inject(method = "call()Ljava/lang/Object;", at = @At("FINALLY"))
        public static void ends(CallbackInfoReturnable<Object> cir, @Thrown Throwable thrown) {
            callEnds("FileTask_LoadImagePyramidTexture", cir, thrown);
        }
    }

    @Hooks(targets = "zombie.worldMap.FileTask_LoadWorldMapBinary")
    public static final class MapBinary {
        private MapBinary() {
        }

        @Inject(method = "call()Ljava/lang/Object;", at = @At("HEAD"))
        public static void begins(CallbackInfoReturnable<Object> cir) {
            callBegins();
        }

        @Inject(method = "call()Ljava/lang/Object;", at = @At("FINALLY"))
        public static void ends(CallbackInfoReturnable<Object> cir, @Thrown Throwable thrown) {
            callEnds("FileTask_LoadWorldMapBinary", cir, thrown);
        }
    }

    @Hooks(targets = "zombie.worldMap.FileTask_LoadWorldMapXML")
    public static final class MapXml {
        private MapXml() {
        }

        @Inject(method = "call()Ljava/lang/Object;", at = @At("HEAD"))
        public static void begins(CallbackInfoReturnable<Object> cir) {
            callBegins();
        }

        @Inject(method = "call()Ljava/lang/Object;", at = @At("FINALLY"))
        public static void ends(CallbackInfoReturnable<Object> cir, @Thrown Throwable thrown) {
            callEnds("FileTask_LoadWorldMapXML", cir, thrown);
        }
    }

    private static void callBegins() {
        if (PROGRESS.files().tracking()) {
            PROGRESS.files().callBegins(System.nanoTime());
        }
    }

    // worker: the class that declares the call, as describe names it
    private static void callEnds(String worker, CallbackInfoReturnable<Object> cir, Throwable thrown) {
        if (PROGRESS.files().tracking()) {
            PROGRESS.files().callEnds(worker, cir.getReturnValue(), thrown, System.nanoTime());
        }
    }

    private static FileWork.Task describe(FileSystemImpl.AsyncItem item) {
        FileTask task = item.task;
        if (task == null || item.future == null) {
            throw new IllegalStateException("a file task at the game's workers has no task or no future");
        }
        String type = task.getClass().getName();
        type = type.substring(type.lastIndexOf('.') + 1);
        return new FileWork.Task(type, task instanceof FileTask_AbstractLoadModel ? MODEL : type, name(task),
                item.future);
    }

    private static String name(FileTask task) {
        if (task instanceof FileTask_LoadPackImage pack) {
            return packFile(pack) + "/" + pack.imageName;
        }
        if (task instanceof TileDepthTextures.LoadTask depthMap) {
            return String.valueOf(depthMap.path);
        }
        if (task instanceof FileTask_LoadImagePyramidTexture mapImage) {
            return String.valueOf(mapImage.path);
        }
        // its file name is set and cleared by its call and the game's done()
        if (task instanceof FileTask_AbstractLoadModel model) {
            return model.getRawFileName();
        }
        if (task instanceof FileTask_Exists exists) {
            return exists.fileName;
        }
        if (task instanceof FileSystemImpl.OpenTask open) {
            return open.path;
        }
        // the others name their file for the game's error log; a closing file has no name
        return task.getErrorMessage();
    }

    // the file the task's call opens, as mounted for the pack's name in the mods' override order
    private static String packFile(FileTask_LoadPackImage pack) {
        if (!(pack.fileSystem instanceof FileSystemImpl files)) {
            throw new IllegalStateException("the pack image task " + pack.packName + "/" + pack.imageName
                    + " reads from the file system " + pack.fileSystem + ", not a FileSystemImpl");
        }
        TexturePackDevice device = files.texturepackDevices.get(pack.packName);
        if (device == null || device.filename == null) {
            throw new IllegalStateException("the game has no texture pack file mounted as " + pack.packName
                    + " for its pack image task " + pack.imageName);
        }
        // a relative name opens from the working folder: the game's folder, canonical like the game's own file paths
        File file = new File(device.filename);
        return file.isAbsolute() ? file.getPath()
                : new File(ZomboidFileSystem.instance.base.canonicalFile, device.filename).getPath();
    }
}
