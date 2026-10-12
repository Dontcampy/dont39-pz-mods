package com.dont39.pz.zombiebuddykotlinutils.processor

import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider

/** Registered in META-INF/services; KSP passes the options pz-feature sets (see [GlueProcessor]). */
class GlueProcessorProvider : SymbolProcessorProvider {
  override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
    GlueProcessor(environment.codeGenerator, environment.logger, environment.options)
}
