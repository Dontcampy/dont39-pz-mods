// Shared helpers for All-in-One's features, bundled by dont39-all-in-one.
plugins {
  id("buildsrc.convention.pz-kotlin")
}

dependencies {
  // linked only after its presence is probed (Widening.kt)
  compileOnly(project(":zabric"))
}
