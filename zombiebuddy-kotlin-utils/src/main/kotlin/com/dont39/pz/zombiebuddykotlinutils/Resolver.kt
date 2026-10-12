package com.dont39.pz.zombiebuddykotlinutils

import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.Constructor
import java.lang.reflect.Field

/**
 * Looks up another mod's classes and members by name. Use one per helper object, only in that object's initializer, and
 * keep the results in plain `val`s so the per-frame path reads fields.
 *
 * Never throws. The first failure is kept in [problem]; after it every call returns null without trying, because one
 * missing piece makes the rest useless. Null arguments (results of earlier failed calls) are accepted and give null.
 *
 * A patch can trigger a helper object's initializer before ZombieBuddyKotlin is ready, so this class uses only the JDK
 * and plain language features, no Kotlin library calls.
 */
class Resolver {
  /** The first failure (usually the exception's `toString()`), or null when everything resolved. */
  var problem: String? = null
    private set

  fun type(name: String): Class<*>? = attempt { Class.forName(name) }

  /** A declared field of [owner], made accessible. */
  fun field(owner: Class<*>?, name: String): Field? {
    if (owner == null) return missing(name)
    return attempt {
      val field = owner.getDeclaredField(name)
      field.isAccessible = true
      field
    }
  }

  /**
   * The current value of a static field, checked against [type]. A null value counts as a failure. Use object types for
   * [type] (`List::class.java`, `Array<Any?>::class.java`); a primitive class never matches.
   */
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

  /** A declared constructor of [owner], made accessible. Primitive parameters: `Double::class.java` etc. */
  fun constructor(owner: Class<*>?, vararg params: Class<*>): Constructor<*>? {
    if (owner == null) return missing("constructor")
    return attempt {
      val constructor = owner.getDeclaredConstructor(*params)
      constructor.isAccessible = true
      constructor
    }
  }

  /** A method type whose classes may be results of [type]; null if one of them is null. */
  fun methodType(returnType: Class<*>?, vararg params: Class<*>?): MethodType? {
    if (returnType == null) return missing("method type")
    for (param in params) {
      if (param == null) return missing("method type")
    }
    return attempt { MethodType.methodType(returnType, params) }
  }

  /**
   * A public static method of a public [owner]. [asType] adapts the handle (e.g. the target's own types to `Any`), so
   * callers can use `invokeExact` with types they can name.
   */
  fun static(owner: Class<*>?, name: String, type: MethodType?, asType: MethodType? = null): MethodHandle? {
    if (owner == null || type == null) return missing(name)
    return attempt { adapt(MethodHandles.publicLookup().findStatic(owner, name, type), asType) }
  }

  /** A public instance method of a public [owner]; the receiver becomes the first parameter. See [static] for [asType]. */
  fun virtual(owner: Class<*>?, name: String, type: MethodType?, asType: MethodType? = null): MethodHandle? {
    if (owner == null || type == null) return missing(name)
    return attempt { adapt(MethodHandles.publicLookup().findVirtual(owner, name, type), asType) }
  }

  private fun adapt(handle: MethodHandle, asType: MethodType?): MethodHandle =
    if (asType == null) handle else handle.asType(asType)

  /** Null input without a recorded failure means the caller passed null itself; record that too. */
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
