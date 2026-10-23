package com.dont39.pz.fasterloading.textures;

import com.dont39.pz.fasterloading.textures.Encoders.Choice;
import com.dont39.pz.fasterloading.textures.Encoders.Kind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EncodersTest {
    @Test
    void bc7WhenItsSwitchIsOnAndTheGlHasOpenGl43() {
        assertEquals(new Choice(Kind.BC7, "textureBc7 on"), Encoders.choose(true, true));
    }

    @Test
    void dxt5WhenTheSwitchIsOff() {
        assertEquals(new Choice(Kind.DXT5, "textureBc7 off"), Encoders.choose(false, true));
        assertEquals(new Choice(Kind.DXT5, "textureBc7 off"), Encoders.choose(false, false));
    }

    @Test
    void dxt5WithItsReasonWhenOpenGl43IsMissing() {
        assertEquals(new Choice(Kind.DXT5, "textureBc7 on, OpenGL 4.3 missing"), Encoders.choose(true, false));
    }
}
