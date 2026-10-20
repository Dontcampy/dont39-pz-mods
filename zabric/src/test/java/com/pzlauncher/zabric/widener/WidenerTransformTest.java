package com.pzlauncher.zabric.widener;

import com.pzlauncher.zabric.classtweaker.ClassTweaker;
import com.pzlauncher.zabric.widener.WidenerScan.Listed;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.description.modifier.Ownership;
import net.bytebuddy.description.modifier.TypeManifestation;
import net.bytebuddy.description.modifier.Visibility;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WidenerTransformTest {
    @Test
    void aPackagePrivateNestedClassAndItsFieldBecomePublicAsTheyLoad() throws Exception {
        // generated, not compiled: the test runner loads every compiled test class before a test runs
        String name = WidenerTransformTest.class.getName() + "$Item";
        byte[] bytes = new ByteBuddy().subclass(Object.class).name(name)
                .modifiers(Visibility.PACKAGE_PRIVATE, TypeManifestation.FINAL, Ownership.STATIC)
                .innerTypeOf(WidenerTransformTest.class).asMemberType()
                .defineField("id", long.class, Visibility.PACKAGE_PRIVATE)
                .make().getBytes();

        // the expanded list the build writes for an "accessible field": the field and its class
        String owner = name.replace('.', '/');
        List<String> lines = List.of(ClassTweaker.HEADER, "accessible class " + owner,
                "accessible field " + owner + " id J");
        Listed listed = new Listed("m", "test", ClassTweaker.parse("test", lines), null);
        assertEquals(2, WidenerTransform.install(ByteBuddyAgent.install(), Map.of("m", Map.of("h", listed))));

        Class<?> item = MethodHandles.lookup().defineClass(bytes);
        assertEquals(Map.of(), WidenerTransform.failedTypes());
        // reflection reads the InnerClasses entry; the public lookup checks the class header, as linking does
        assertTrue(Modifier.isPublic(item.getModifiers()));
        MethodHandles.publicLookup().accessClass(item);
        MethodHandles.publicLookup().findGetter(item, "id", long.class);
    }
}
