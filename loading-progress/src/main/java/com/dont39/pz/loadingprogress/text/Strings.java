package com.dont39.pz.loadingprogress.text;

import com.dont39.pz.loadingprogress.progress.Item;
import com.dont39.pz.loadingprogress.progress.Line;
import com.dont39.pz.loadingprogress.progress.Progress;
import com.dont39.pz.loadingprogress.progress.Step;

import java.util.concurrent.TimeUnit;

/**
 * The mod's own English and Chinese text: the game's translation files load after the bar first shows, and only for
 * mods in the current mod list.
 */
public final class Strings {
    private Strings() {
    }

    /** A line of the log under the bar, or null when the item has nothing to show. */
    public static String line(Line line, boolean chinese) {
        return line.isStep() ? line.index() + " / " + line.total() + "  " + step(line.step(), chinese)
                : itemLine(line.item(), line.index(), line.total(), line.name(), chinese);
    }

    public static String step(Step step, boolean chinese) {
        return switch (step) {
            case CONNECTING -> chinese ? "正在连接服务器" : "Connecting to the server";
            case MODS -> chinese ? "加载模组" : "Loading mods";
            case TRANSLATIONS -> chinese ? "加载翻译" : "Loading translations";
            case UI_TEXTURES -> chinese ? "加载界面纹理" : "Loading UI textures";
            case SCRIPTS -> chinese ? "加载脚本" : "Loading scripts";
            case CLOTHING -> chinese ? "加载服装" : "Loading clothing";
            case TILES -> chinese ? "加载图块和家具" : "Loading tiles and furniture";
            case CLOTHING_TILES -> chinese ? "加载服装、图块和家具" : "Loading clothing, tiles and furniture";
            case LUA -> chinese ? "加载 Lua" : "Loading Lua";
            case TEXTURES -> chinese ? "加载纹理" : "Loading textures";
            case MODELS -> chinese ? "加载模型和动画" : "Loading models and animations";
            case GAME_BOOT -> chinese ? "游戏启动中" : "Starting the game";
            case LUA_EVENTS -> chinese ? "运行模组启动代码" : "Running mods' start-up code";
            case LOGIN_QUEUE -> chinese ? "排队等待登录" : "Waiting in the login queue";
            case PREPARING_WORLD -> chinese ? "准备世界" : "Preparing the world";
            case LOADING_WORLD -> chinese ? "加载世界" : "Loading the world";
            case READY -> chinese ? "加载完成" : "Loaded";
            case ENTERING_WORLD -> chinese ? "进入世界" : "Entering the world";
        };
    }

    /** An item, or null when it has nothing to show. */
    public static String itemLine(Item item, int index, int total, String name, boolean chinese) {
        return switch (item) {
            case NONE -> null;
            // the game's own text, in its language
            case WORLD -> name;
            case JAVA_MOD -> counted(chinese ? "Java 模组" : "Java mod", index, total, name, chinese);
            case MOD -> counted(chinese ? "模组" : "Mod", index, total, name, chinese);
            case SCRIPT -> counted(chinese ? "脚本文件" : "Script file", index, total, name, chinese);
            case LUA -> counted(chinese ? "Lua 文件" : "Lua file", index, total, name, chinese);
            case TEXTURE_PACK -> named(chinese ? "纹理包" : "Texture pack", name, chinese);
            case EVENT, WORLD_EVENT -> named(chinese ? "Lua 事件" : "Lua event", name, chinese);
            case CLOTHING_ITEM -> counted(chinese ? "服装" : "Clothing item", index, total, name, chinese);
            case MAP_CELL -> counted(chinese ? "地图格" : "Map cell", index, total, name, chinese);
            // no name: the mods' files
            case TILE_DEFINITIONS -> named(chinese ? "图块定义" : "Tile definitions",
                    name != null ? name : chinese ? "模组的文件" : "mods' files", chinese);
            case CHUNK -> counted(chinese ? "区块" : "Chunk", index, total, name, chinese);
            case ANIMATION -> counted(chinese ? "动画" : "Animation", index, total, name, chinese);
        };
    }

    /** A frame gap for the log line of a flow's end, in English. */
    public static String gap(Progress.Gap gap) {
        Progress.Shown at = gap.item();
        String item = at == null ? null : itemLine(at.item(), at.index(), at.total(), at.name(), false);
        long knownMillis = TimeUnit.NANOSECONDS.toMillis(gap.knownNanos());
        return millis(gap.nanos()) + " in " + step(gap.step(), false) + (item == null ? "" : " (" + item + ")")
                + (gap.known() == null || knownMillis == 0 ? ""
                : ", " + knownMillis + " ms of it in known calls, mostly " + gap.known().label());
    }

    private static String millis(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(nanos) + " ms";
    }

    private static String counted(String what, int index, int total, String name, boolean chinese) {
        if (index == 0) {
            return null;
        }
        String count = what + " " + index + " / " + total;
        return name == null ? count : count + (chinese ? "：" : ": ") + name;
    }

    private static String named(String what, String name, boolean chinese) {
        return name == null ? null : what + (chinese ? "：" : ": ") + name;
    }
}
