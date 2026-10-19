package com.dont39.pz.zbktutils.processor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class KspTypesTest {
  @Test
  fun noParameters() {
    assertEquals(listOf<String>() to "V", parseMethodDescriptor("()V"))
  }

  @Test
  fun everyKindOfParameter() {
    assertEquals(
      listOf("I", "[J", "Ljava/lang/String;", "[[La/B\$C;", "Z") to "[Ljava/lang/Object;",
      parseMethodDescriptor("(I[JLjava/lang/String;[[La/B\$C;Z)[Ljava/lang/Object;"),
    )
  }

  @Test
  fun aReferenceReturn() {
    assertEquals(listOf("D") to "Ljava/util/List;", parseMethodDescriptor("(D)Ljava/util/List;"))
  }

  @Test
  fun notAMethodDescriptor() {
    assertThrows<IllegalArgumentException> { parseMethodDescriptor("Ljava/lang/String;") }
  }
}
