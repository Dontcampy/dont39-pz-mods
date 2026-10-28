package com.pzlauncher.zabric.entrypoint;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.krka.kahlua.converter.KahluaConverterManager;
import se.krka.kahlua.integration.annotations.LuaMethod;
import se.krka.kahlua.integration.expose.LuaJavaClassExposer;
import se.krka.kahlua.j2se.J2SEPlatform;
import se.krka.kahlua.vm.KahluaTable;
import zombie.HiddenFromLua;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which classes' global Lua functions a Lua state gets after the loader left them out, as Kahlua names them. */
class LuaGlobalsTest {
    private KahluaTable env;
    private LuaJavaClassExposer exposer;

    public static final class Bridge {
        static int made;

        private Bridge() {
            made++;
        }

        @LuaMethod(name = "LuaGlobalsTest_named", global = true)
        public String named() {
            return "named";
        }

        @LuaMethod(global = true)
        public String LuaGlobalsTest_unnamed() {
            return "unnamed";
        }

        @LuaMethod(name = "LuaGlobalsTest_notGlobal")
        public String notGlobal() {
            return "notGlobal";
        }
    }

    public static final class Broken {
        public Broken() {
            throw new IllegalStateException("broken on purpose");
        }

        @LuaMethod(name = "LuaGlobalsTest_broken", global = true)
        public void broken() {
        }
    }

    public static final class Other {
        @LuaMethod(name = "LuaGlobalsTest_other", global = true)
        public void other() {
        }
    }

    @HiddenFromLua
    public static final class Hidden {
        @LuaMethod(name = "LuaGlobalsTest_hidden", global = true)
        public void hidden() {
        }
    }

    public interface Api {
        @LuaMethod(name = "LuaGlobalsTest_inherited", global = true)
        String inherited();
    }

    public static final class Implementation implements Api {
        @Override
        public String inherited() {
            return "inherited";
        }
    }

    @BeforeEach
    void newLuaState() {
        J2SEPlatform platform = new J2SEPlatform();
        // newEnvironment loads the game's stdlib.lua from the working folder
        env = platform.newTable();
        exposer = new LuaJavaClassExposer(new KahluaConverterManager(), platform, env);
        Bridge.made = 0;
    }

    private LuaGlobals.Result expose(List<Class<?>> classes, Set<Class<?>> skip) {
        return LuaGlobals.expose(env, exposer::exposeGlobalFunctions, classes, skip);
    }

    @Test
    void missingFunctionsAreExposedUnderTheNamesKahluaGives() {
        LuaGlobals.Result result = expose(List.of(Bridge.class), Set.of());

        assertEquals(List.of(Bridge.class), result.exposed());
        assertTrue(result.failed().isEmpty());
        assertEquals(Set.of("LuaGlobalsTest_named", "LuaGlobalsTest_unnamed"), LuaGlobals.globalNames(Bridge.class));
        assertNotNull(env.rawget("LuaGlobalsTest_named"));
        assertNotNull(env.rawget("LuaGlobalsTest_unnamed"));
        assertNull(env.rawget("LuaGlobalsTest_notGlobal"));
    }

    @Test
    void aClassWhoseFunctionsAreThereIsLeftAlone() {
        expose(List.of(Bridge.class), Set.of());
        Object before = env.rawget("LuaGlobalsTest_named");

        LuaGlobals.Result again = expose(List.of(Bridge.class), Set.of());

        assertTrue(again.exposed().isEmpty());
        assertEquals(1, Bridge.made);
        assertSame(before, env.rawget("LuaGlobalsTest_named"));
    }

    @Test
    void oneMissingFunctionExposesTheClass() {
        expose(List.of(Bridge.class), Set.of());
        env.rawset("LuaGlobalsTest_unnamed", null);

        assertEquals(List.of(Bridge.class), expose(List.of(Bridge.class), Set.of()).exposed());
        assertNotNull(env.rawget("LuaGlobalsTest_unnamed"));
    }

    @Test
    void aClassThatCannotBeMadeFailsAloneAndIsSkippedOnceReported() {
        LuaGlobals.Result result = expose(List.of(Broken.class, Other.class), Set.of());

        assertEquals(List.of(Other.class), result.exposed());
        assertEquals(Set.of(Broken.class), result.failed().keySet());
        assertInstanceOf(IllegalStateException.class, result.failed().get(Broken.class).getCause());
        assertNull(env.rawget("LuaGlobalsTest_broken"));

        assertTrue(expose(List.of(Broken.class), Set.of(Broken.class)).failed().isEmpty());
    }

    @Test
    void aClassKahluaHidesIsAFailureNotAnExposure() {
        LuaGlobals.Result result = expose(List.of(Hidden.class), Set.of());

        assertTrue(result.exposed().isEmpty());
        assertEquals(Set.of(Hidden.class), result.failed().keySet());
    }

    @Test
    void anAnnotationOnTheInterfaceCountsAsKahluaReadsIt() {
        assertEquals(Set.of("LuaGlobalsTest_inherited"), LuaGlobals.globalNames(Implementation.class));
        assertEquals(List.of(Implementation.class), expose(List.of(Implementation.class), Set.of()).exposed());
        assertNotNull(env.rawget("LuaGlobalsTest_inherited"));
    }
}
