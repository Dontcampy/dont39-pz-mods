package com.dont39.pz.fasterloading.textures;

import com.dont39.pz.fasterloading.textures.bc7.Bc7Encoder;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import java.nio.ByteBuffer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The session's encoder, chosen once at the first level the fix takes: every level of a texture must have one format,
 * or the texture is incomplete.
 */
public final class Encoders {
    private static Encoder session;
    private static int driverFormat;

    private Encoders() {
    }

    public enum Kind {
        BC7,
        DXT5
    }

    /** An encoder and why it was chosen. */
    public record Choice(Kind kind, String reason) {
    }

    /** BC7 on the GPU when its switch is on and the GL has compute shaders (OpenGL 4.3), else DXT5 on the CPU. */
    public static Choice choose(boolean bc7Switch, boolean openGl43) {
        if (!bc7Switch) {
            return new Choice(Kind.DXT5, "textureBc7 off");
        }
        if (!openGl43) {
            return new Choice(Kind.DXT5, "textureBc7 on, OpenGL 4.3 missing");
        }
        return new Choice(Kind.BC7, "textureBc7 on");
    }

    /**
     * The session's encoder, built at the first call on the GL thread, which also probes {@link #driverFormat} and
     * sends {@code log} one line naming the encoder and why. Throws when the GL lacks what the chosen encoder needs or
     * the encoder cannot be built.
     */
    public static synchronized Encoder session(BooleanSupplier bc7Switch, Consumer<String> log) {
        if (session != null) {
            return session;
        }
        Choice choice = choose(bc7Switch.getAsBoolean(), GL.getCapabilities().OpenGL43);
        String gl = GL11.glGetString(GL11.GL_RENDERER) + ", " + GL11.glGetString(GL11.GL_VERSION);
        driverFormat = probeDriverFormat();
        String where;
        if (choice.kind() == Kind.BC7) {
            session = new Bc7Encoder();
            where = "on the GPU";
        } else {
            if (!GL.getCapabilities().GL_EXT_texture_compression_s3tc) {
                throw new IllegalStateException("the GL (" + gl + ") has no GL_EXT_texture_compression_s3tc for DXT5 ("
                        + choice.reason() + ")");
            }
            // its pool starts at the first level it splits
            Dxt5Encoder dxt5 = new Dxt5Encoder(Runtime.getRuntime().availableProcessors());
            session = dxt5;
            where = "on " + dxt5.threads() + " threads";
        }
        log.accept(session.id() + " " + where + " (" + choice.reason() + "; " + gl
                + "; the driver's GL_COMPRESSED_RGBA: 0x" + Integer.toHexString(driverFormat) + ")");
        return session;
    }

    /**
     * The internal format the driver gives a level asked for as {@code GL_COMPRESSED_RGBA}, probed once with the
     * session's encoder: what a level left to the driver gets.
     */
    public static synchronized int driverFormat() {
        if (session == null) {
            throw new IllegalStateException("the driver's format is probed with the session's encoder");
        }
        return driverFormat;
    }

    // on a scratch texture, without pixels: the hook leaves that call to the driver
    static int probeDriverFormat() {
        int bound = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        Upload.clearErrors();
        int texture = GL11.glGenTextures();
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL13.GL_COMPRESSED_RGBA, 4, 4, 0, GL11.GL_RGBA,
                    GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            int format = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
            Upload.checkErrors(() -> "probing the driver's format for GL_COMPRESSED_RGBA");
            return format;
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, bound);
            GL11.glDeleteTextures(texture);
        }
    }
}
