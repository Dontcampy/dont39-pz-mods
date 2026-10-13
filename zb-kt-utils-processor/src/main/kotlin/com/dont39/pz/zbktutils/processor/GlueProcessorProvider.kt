package com.dont39.pz.zbktutils.processor

import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider

/** Registered in META-INF/services. */
class GlueProcessorProvider : SymbolProcessorProvider {
  override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
    GlueProcessor(environment.codeGenerator, environment.logger, environment.options)
}
