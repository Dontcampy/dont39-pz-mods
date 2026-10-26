package com.dont39.pz.serverreborn

import com.dont39.pz.zbktutils.ModGate

// a top-level constant: the object's own one is not readable in its superclass call
private const val ID = "Dont39ServerReborn"

/** The mod's id, its log lines and the gate of all its hooks, which the main entrypoint opens. */
object ServerReborn : ModGate(ID) {
  const val MOD_ID = ID
}
