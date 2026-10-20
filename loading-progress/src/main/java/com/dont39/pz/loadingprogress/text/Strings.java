package com.dont39.pz.loadingprogress.text;

import com.dont39.pz.loadingprogress.progress.Item;
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

    /** {@code "n / N: step"}. */
    public static String stepLine(int n, int count, Step step, boolean chinese) {
        return n + " / " + count + (chinese ? "：" : ": ") + step(step, chinese);
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

    /** The second line, or null when the item has nothing to show. */
    public static String itemLine(Item item, int index, int total, String name, boolean chinese) {
        return switch (item) {
            case NONE, WORLD -> null;
            case JAVA_MOD -> counted(chinese ? "Java 模组" : "Java mod", index, total, name, chinese);
            case MOD -> counted(chinese ? "模组" : "Mod", index, total, name, chinese);
            case SCRIPT -> counted(chinese ? "脚本文件" : "Script file", index, total, name, chinese);
            case LUA -> counted(chinese ? "Lua 文件" : "Lua file", index, total, name, chinese);
            case TEXTURE_PACK -> named(chinese ? "纹理包" : "Texture pack", name, chinese);
            case EVENT -> named(chinese ? "Lua 事件" : "Lua event", name, chinese);
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
