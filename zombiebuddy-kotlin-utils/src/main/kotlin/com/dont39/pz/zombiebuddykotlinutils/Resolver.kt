package com.dont39.pz.zombiebuddykotlinutils

import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.Constructor
import java.lang.reflect.Field

/**
 * Looks up another mod's classes and members by name, once, in a helper object's initializer. Never throws: the first
 * failure is kept in [problem] and later calls return null.
 */
class Resolver {
  var problem: String? = null
    private set

  fun type(name: String): Class<*>? = attempt { Class.forName(name) }

  fun field(owner: Class<*>?, name: String): Field? {
    if (owner == null) return missing(name)
    return attempt {
      val field = owner.getDeclaredField(name)
      field.isAccessible = true
      field
    }
  }

  /** A null value counts as a failure. */
  fun <T> staticValue(owner: Class<*>?, name: String, type: Class<T>): T? {
    val field = field(owner, name) ?: return null
    return attempt {
      val value = field.get(null)
      if (value == null) {
        problem = field.declaringClass.name + "." + name + " is null"
        return null
      }
      type.cast(value)
    }
  }

  fun constructor(owner: Class<*>?, vararg params: Class<*>): Constructor<*>? {
    if (owner == null) return missing("constructor")
    return attempt {
      val constructor = owner.getDeclaredConstructor(*params)
      constructor.isAccessible = true
      constructor
    }
  }

  fun methodType(returnType: Class<*>?, vararg params: Class<*>?): MethodType? {
    if (returnType == null || params.any { it == null }) return missing("method type")
    return attempt { MethodType.methodType(returnType, params) }
  }

  /** [asType] lets callers `invokeExact` with types they can name. */
  fun static(owner: Class<*>?, name: String, type: MethodType?, asType: MethodType? = null): MethodHandle? {
    if (owner == null || type == null) return missing(name)
    return attempt { adapt(MethodHandles.publicLookup().findStatic(owner, name, type), asType) }
  }

  fun virtual(owner: Class<*>?, name: String, type: MethodType?, asType: MethodType? = null): MethodHandle? {
    if (owner == null || type == null) return missing(name)
    return attempt { adapt(MethodHandles.publicLookup().findVirtual(owner, name, type), asType) }
  }

  private fun adapt(handle: MethodHandle, asType: MethodType?): MethodHandle =
    if (asType == null) handle else handle.asType(asType)

  private fun <T> missing(what: String): T? {
    if (problem == null) {
      problem = "nothing to look up $what in"
    }
    return null
  }

  private inline fun <T> attempt(block: () -> T): T? {
    if (problem != null) {
      return null
    }
    return try {
      block()
    } catch (e: Exception) {
      problem = e.toString()
      null
    } catch (e: LinkageError) {
      problem = e.toString()
      null
    }
  }
}
