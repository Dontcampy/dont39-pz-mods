package com.dont39.pz.zombiebuddykotlinutils

import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.Constructor
import java.lang.reflect.Field

/** Looks up another mod's classes and members by name. Throws [IllegalStateException] naming what does not match. */
object Resolver {
  fun type(name: String): Class<*> = find("class $name") { Class.forName(name) }

  fun field(owner: Class<*>, name: String): Field = find("field ${owner.name}.$name") {
    val field = owner.getDeclaredField(name)
    field.isAccessible = true
    field
  }

  fun <T> staticValue(owner: Class<*>, name: String, type: Class<T>): T {
    val field = field(owner, name)
    val value = find("value of ${owner.name}.$name") { field.get(null) }
      ?: throw IllegalStateException("${owner.name}.$name is null")
    return find("${owner.name}.$name as ${type.name}") { type.cast(value) }
  }

  fun constructor(owner: Class<*>, vararg params: Class<*>): Constructor<*> =
    find("constructor ${owner.name}(${params.joinToString { it.name }})") {
      val constructor = owner.getDeclaredConstructor(*params)
      constructor.isAccessible = true
      constructor
    }

  /** [asType] lets callers `invokeExact` with types they can name. */
  fun static(owner: Class<*>, name: String, type: MethodType, asType: MethodType = type): MethodHandle =
    find("method ${owner.name}.$name$type") { MethodHandles.publicLookup().findStatic(owner, name, type).asType(asType) }

  /** The receiver becomes the first parameter. */
  fun virtual(
    owner: Class<*>,
    name: String,
    type: MethodType,
    asType: MethodType = type.insertParameterTypes(0, owner),
  ): MethodHandle =
    find("method ${owner.name}.$name$type") { MethodHandles.publicLookup().findVirtual(owner, name, type).asType(asType) }

  private inline fun <T> find(what: String, block: () -> T): T = try {
    block()
  } catch (e: Exception) {
    throw IllegalStateException("cannot get $what", e)
  } catch (e: LinkageError) {
    throw IllegalStateException("cannot get $what", e)
  }
}
